/*******************************************************************************
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.exactpro.th2.actuibackend.services.grpc

import com.exactpro.th2.actuibackend.Context
import com.exactpro.th2.actuibackend.entities.exceptions.CustomException
import com.exactpro.th2.actuibackend.entities.exceptions.SendProtoMessageException
import com.exactpro.th2.actuibackend.entities.requests.MethodCallRequest
import com.exactpro.th2.actuibackend.entities.responces.MethodCallResponse
import com.exactpro.th2.actuibackend.protobuf.ProtoSchemaCache
import com.exactpro.th2.actuibackend.schema.SchemaParser
import com.exactpro.th2.common.grpc.EventID
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging

class GrpcService(
    private val context: Context,
    private val protoSchemaCache: ProtoSchemaCache,
    private val schemaParser: SchemaParser
) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    private val responseTimeout = context.configuration.responseTimeout.value.toLong()
    private val PARENT_EVENT_ID_FIELD = "parent_event_id"
    private val namespace = context.configuration.namespace.value
    private val statusField = "status"
    private val errorStatus = "ERROR"

    private fun validateMessage(message: DynamicMessage, stringMessage: String) {
        val statusField = message.allFields.entries.firstOrNull { it.key.name.toLowerCase().contains(statusField) }
        if (statusField?.value?.toString()?.toUpperCase() == errorStatus)
            throw SendProtoMessageException(stringMessage)

        message.allFields.forEach {
            if (it.value is DynamicMessage) {
                validateMessage(it.value as DynamicMessage, stringMessage)
            }
        }
    }

    private fun setParentEvent(
        message: DynamicMessage,
        messageDescriptor: Descriptors.Descriptor,
        parentEvent: EventID
    ): DynamicMessage {
        val parentEventFieldDescriptor = messageDescriptor.findFieldByName(PARENT_EVENT_ID_FIELD)
        return if (parentEventFieldDescriptor != null) {
            DynamicMessage.newBuilder(message)
                .setField(parentEventFieldDescriptor, parentEvent).build()
        } else {
            message
        }
    }

    private suspend fun getPort(boxName: String): Int {
        val boxNameToPort = schemaParser.getActs().let {
            schemaParser.getServicePorts(it.toSet())
        }

        return boxNameToPort[boxName]
            ?: throw SendProtoMessageException("Unable to determine box: '$boxName' port")
    }


    suspend fun sendMessage(callRequest: MethodCallRequest, parentEvent: EventID): MethodCallResponse {
        var channel: ManagedChannel? = null
        return try {
            channel = ManagedChannelBuilder.forAddress(
                namespace, getPort(callRequest.fullServiceName.actName)
            ).usePlaintext().build()
            val protoSchema = protoSchemaCache.getSchemaByServiceName(callRequest.fullServiceName)
            val originMethod = protoSchema.getServiceMethod(
                callRequest.fullServiceName.serviceName,
                callRequest.methodName
            )
            val dynamicMessage =
                protoSchema.jsonToDynamicMessage(callRequest.message, originMethod.inputType).let {
                    setParentEvent(it, originMethod.inputType, parentEvent)
                }
            val methodDescriptor = generateMethodDescriptor(originMethod, callRequest.fullServiceName.serviceName)

            executeCall(channel, methodDescriptor, dynamicMessage, protoSchema::protoMessageToJson)
        } catch (e: CustomException) {
            logger.error(e) { }
            throw e
        } catch (e: Exception) {
            "Can not call grpc service: $callRequest. ${e.message}".let {
                logger.error(e) { it }
                throw SendProtoMessageException(it, e)
            }
        } finally {
            channel?.shutdown()
        }
    }

    private suspend fun executeCall(
        channel: ManagedChannel,
        methodDescriptor: MethodDescriptor<DynamicMessage, DynamicMessage>,
        requestMessage: DynamicMessage,
        protoMessageToJson: (DynamicMessage) -> String
    ): MethodCallResponse {
        val callOptions = CallOptions.DEFAULT
        val clientCall = channel.newCall(methodDescriptor, callOptions)
        val responseChannel = Channel<MethodCallResponse>(0)
        ClientCalls.asyncUnaryCall(clientCall, requestMessage, object : StreamObserver<DynamicMessage> {
            override fun onNext(value: DynamicMessage?) {
                value?.allFields?.entries?.firstOrNull { it.key.name.toLowerCase().contains("status") }
                runBlocking {
                    responseChannel.send(
                        MethodCallResponse(
                            message = value?.let { message -> protoMessageToJson(message) },
                            rawMessage = value
                        )
                    )
                }
            }

            override fun onError(t: Throwable?) {
                runBlocking {
                    responseChannel.send(
                        MethodCallResponse(
                            null,
                            exception =
                            SendProtoMessageException(
                                "Unable to send gRPC message: $requestMessage. ${t?.message ?: ""}",
                                if (t is Exception) t else null
                            )
                        )
                    )
                }
            }

            override fun onCompleted() {
            }

        })
        return try {
            withTimeout(responseTimeout) {
                responseChannel.receive().also { message ->
                    message.exception?.let { throw it }
                    message.message?.let { validateMessage(message.rawMessage!!, it) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            try {
                responseChannel.cancel()
            } catch (e: CancellationException) {
                logger.error(e) { "gRPC cancel channel from dynamic message: '${requestMessage}'. ${e.message}" }
            }
            throw SendProtoMessageException(
                "gRPC response timed out after $responseTimeout milliseconds. ${e.message}",
                e
            )
        }
    }

    private fun generateMethodDescriptor(
        originMethodDescriptor: Descriptors.MethodDescriptor,
        serviceName: String
    ): MethodDescriptor<DynamicMessage, DynamicMessage> {
        val fullMethodName = MethodDescriptor.generateFullMethodName(
            serviceName, originMethodDescriptor.name
        )
        val inputTypeMarshaller = ProtoUtils.marshaller(
            DynamicMessage.newBuilder(originMethodDescriptor.inputType).buildPartial()
        )
        val outputTypeMarshaller = ProtoUtils.marshaller(
            DynamicMessage.newBuilder(originMethodDescriptor.outputType).buildPartial()
        )
        return MethodDescriptor.newBuilder<DynamicMessage, DynamicMessage>().setFullMethodName(fullMethodName)
            .setRequestMarshaller(inputTypeMarshaller).setResponseMarshaller(outputTypeMarshaller)
            .setType(MethodDescriptor.MethodType.UNKNOWN).build()
    }
}
