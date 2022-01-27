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

package com.exactpro.th2.actuibackend.services

import com.exactpro.th2.actuibackend.Context
import com.exactpro.th2.actuibackend.entities.exceptions.MethodRequestException
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.requests.MethodCallRequest
import com.exactpro.th2.actuibackend.getMessagesFromStackTrace
import com.exactpro.th2.actuibackend.services.grpc.GrpcService
import com.exactpro.th2.actuibackend.services.rabbitmq.RabbitMqService
import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventID
import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class MessageSendService(
    private val rabbitMqService: RabbitMqService,
    private val grpcService: GrpcService,
    private val context: Context
) {

    private suspend fun tryToCreateJson(text: String?): Any? {
        return text?.let {
            try {
                context.jacksonMapper.readTree(it)
            } catch (e: JsonProcessingException) {
                it
            }
        }
    }

    private suspend fun saveMessageRequestEvent(
        parentEventId: String,
        request: MessageSendRequest,
        success: Boolean,
        errorData: String? = ""
    ): EventID {
        return coroutineScope {
            rabbitMqService.createAndStoreEvent(
                "subroot act-ui ${request.messageType} send request",
                parentEventId,
                "",
                if (success) Event.Status.PASSED else Event.Status.FAILED,
                "act-ui",
                listOf(
                    object : IBodyData {
                        val type = "message"
                        val data = request.run { "session=$session dictionary=$dictionary messageType=$messageType" }
                    },
                    object : IBodyData {
                        val type = "message"
                        val data = context.jacksonMapper.writeValueAsString(request.message)
                    },
                    object : IBodyData {
                        val type = "message"
                        val data = errorData ?: "message sent successfully"
                    }
                )
            )
        }
    }

    private suspend fun createSaveError(
        parentEventId: String,
        eventName: String,
        errorData: String? = ""
    ): EventID {
        return coroutineScope {
            rabbitMqService.createAndStoreEvent(
                eventName,
                parentEventId,
                "",
                Event.Status.FAILED,
                "act-ui",
                listOf(
                    object : IBodyData {
                        val type = "message"
                        val data = errorData ?: "message sent successfully"
                    }
                )
            )
        }
    }


    private suspend fun methodCallRequestEvent(
        parentEventId: String,
        request: MethodCallRequest,
        success: Boolean,
        errorData: String? = ""
    ): EventID {
        return coroutineScope {

            rabbitMqService.createAndStoreEvent(
                "act ${request.fullServiceName} method ${request.methodName} call request",
                parentEventId,
                "",
                if (success) Event.Status.PASSED else Event.Status.FAILED,
                "act-ui",
                listOf(
                    object : IBodyData {
                        val type = "message"
                        val data = request.run { "fullServiceName=$fullServiceName methodName=$methodName" }
                    },
                    object : IBodyData {
                        val type = "message"
                        val data = request.message
                    },
                    object : IBodyData {
                        val type = "message"
                        val data = errorData ?: "grpc method call successfully"
                    }
                )
            )
        }
    }


    suspend fun sendRabbitMessage(request: MessageSendRequest, parentEventId: EventID): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val subrootEvent = saveMessageRequestEvent(parentEventId.id, request, true)
            try {
                rabbitMqService.sendMessage(request, subrootEvent)
            } catch (e: Exception) {
                createSaveError(subrootEvent.id, "Send request failed", e.getMessagesFromStackTrace())
            }
            mapOf(
                "eventId" to subrootEvent.id,
                "session" to request.session,
                "dictionary" to request.dictionary,
                "messageType" to request.messageType
            )
        }
    }

    suspend fun sendGrpcMessage(request: MethodCallRequest, parentEventId: EventID): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val subrootEvent = methodCallRequestEvent(parentEventId.id, request, true)

            var result: Map<String, Any?> = mapOf(
                "eventId" to subrootEvent.id,
                "methodName" to request.methodName,
                "fullServiceName" to request.fullServiceName.toString()
            )

            try {
                with(result) {
                    plus(
                        "responseMessage" to tryToCreateJson(
                            grpcService.sendMessage(
                                request,
                                subrootEvent
                            ).message
                        )
                    )
                }.also { result = it }
            } catch (e: Exception) {
                result.plus("responseMessage" to tryToCreateJson(e.message)).also { result = it }
                createSaveError(subrootEvent.id, "Method call failed", e.getMessagesFromStackTrace())
                throw MethodRequestException(e.getMessagesFromStackTrace(), result, e)
            }

            result
        }
    }
}