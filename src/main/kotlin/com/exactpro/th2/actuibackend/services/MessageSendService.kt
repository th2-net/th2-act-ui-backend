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

import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.requests.MethodCallRequest
import com.exactpro.th2.actuibackend.entities.responces.MethodCallResponse
import com.exactpro.th2.actuibackend.services.grpc.GrpcService
import com.exactpro.th2.actuibackend.services.rabbitmq.RabbitMqService
import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventID
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class MessageSendService(
    private val rabbitMqService: RabbitMqService,
    private val grpcService: GrpcService,
    private val jacksonMapper: ObjectMapper
) {

    private val actNameRabbit = "act-ui sendMessage"
    private val actNameGrpc = "act-ui grpc meghod call"
    private val description = "act-ui subroot event"

    private suspend fun sendSubrootEvent(actName: String, parentEventId: EventID): EventID {
        return coroutineScope {
            rabbitMqService.createAndStoreEvent(
                actName, parentEventId.id, description,
                Event.Status.PASSED, "act-ui", null
            )
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
                "act-ui message send request",
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
                        val data = jacksonMapper.writeValueAsString(request.message)
                    },
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
                "act-ui grpc method call request",
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
            val subrootEvent = sendSubrootEvent(actNameRabbit, parentEventId)
            val event = try {
                rabbitMqService.sendMessage(request, subrootEvent)
                saveMessageRequestEvent(subrootEvent.id, request, true)
            } catch (e: Exception) {
                saveMessageRequestEvent(subrootEvent.id, request, false, e.toString())
            }
            mapOf(
                "eventId" to event.id,
                "session" to request.session,
                "dictionary" to request.dictionary,
                "messageType" to request.messageType
            )
        }
    }


    suspend fun sendGrpcMessage(request: MethodCallRequest, parentEventId: EventID): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val subrootEvent = sendSubrootEvent(actNameGrpc, parentEventId)
            var responseInfo: String?
            val event = try {
                responseInfo = grpcService.sendMessage(request, subrootEvent).message
                methodCallRequestEvent(subrootEvent.id, request, true)
            } catch (e: Exception) {
                responseInfo = e.message
                methodCallRequestEvent(subrootEvent.id, request, false, e.toString())
            }
            mapOf(
                "eventId" to event.id,
                "methodName" to request.methodName,
                "fullServiceName" to request.fullServiceName.toString(),
                "responseMessage" to responseInfo.toString()
            )
        }
    }
}