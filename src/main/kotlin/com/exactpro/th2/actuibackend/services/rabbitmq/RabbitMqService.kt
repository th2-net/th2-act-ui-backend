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


package com.exactpro.th2.actuibackend.services.rabbitmq

import com.exactpro.th2.actuibackend.Context
import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.services.grpc.createMessageFromRequest
import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.Event.Status.FAILED
import com.exactpro.th2.common.event.Event.Status.PASSED
import com.exactpro.th2.common.event.Event.start
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageBatch
import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.IOException


class RabbitMqService(private val context: Context) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    private val actName = "act-ui messages"
    private val description = "act-ui root event"

    private val configuration = context.configuration

    val parentEventId = runBlocking {
        createAndStoreEvent(actName, null, description, PASSED, "act-ui", null)
    }

    private fun sendMessage(message: Message, sessionAlias: String?, parentEventId: EventID) {
        try {
            configuration.messageRouterParsedBatch.sendAll(
                MessageBatch.newBuilder().addMessages(
                    Message.newBuilder(message).setParentEventId(parentEventId).build()
                ).build(), sessionAlias
            )
        } catch (e: Exception) {
            logger.error(e) { }
            throw InvalidRequestException("Can not send message. Session alias: '$sessionAlias' message: $message")
        }
    }

    @Throws(JsonProcessingException::class, InvalidRequestException::class)
    suspend fun createAndStoreEvent(
        name: String,
        parentEventId: String?,
        description: String,
        status: Event.Status,
        type: String,
        data: List<IBodyData>?
    ): EventID {
        return withContext(Dispatchers.IO) {
            val event = start()
                .name(name)
                .description(description)
                .type(type)
                .status(status)
                .also { event ->
                    data?.let {
                        for (item in it) {
                            event.bodyData(item)
                        }
                    }
                }
                .endTimestamp()

            val protoEvent = event.toProtoEvent(parentEventId)
            try {
                configuration.eventRouter.send(
                    EventBatch.newBuilder().addEvents(protoEvent).build(), "publish", "event"
                )
                protoEvent.id
            } catch (e: IOException) {
                logger.error(e) { }
                throw InvalidRequestException("Can not send event:  '${protoEvent.id.id}'")
            }
        }
    }

    @Throws(IOException::class)
    suspend fun sendMessage(request: MessageSendRequest, parentEventId: EventID) {
        withContext(Dispatchers.IO) {
            val message = createMessageFromRequest(request)
            sendMessage(message, request.session, parentEventId)
        }
    }
}
