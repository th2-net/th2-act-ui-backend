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
import com.exactpro.th2.actuibackend.asStringSuspend
import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.responces.MessageSendResponse
import com.exactpro.th2.actuibackend.services.grpc.createMessageFromRequest
import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.Event.Status.PASSED
import com.exactpro.th2.common.event.Event.start
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.event.bean.builder.MessageBuilder
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageBatch
import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.IOException
import java.time.Instant


class RabbitMqService(private val context: Context) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    private val actName = "sendMessage"
    private val description = "Message sender backend root event"

    val parentEventId = createParentEvent(actName, null, description, PASSED)

    private fun sendMessage(message: Message, sessionAlias: String?, parentEventId: EventID) {
        try {
            context.configuration.messageRouterParsedBatch.sendAll(
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
    private fun createParentEvent(
        actName: String, parentEventId: EventID?, description: String, status: Event.Status, bodyData: IBodyData? = null
    ): EventID {
        val event = start().name(actName).description(description).type(actName).status(status).endTimestamp()
        bodyData?.let { event.bodyData(bodyData) }
        val protoEvent = event.toProtoEvent(parentEventId?.id)
        return try {
            context.configuration.eventRouter.send(
                EventBatch.newBuilder().apply {
                    addEvents(protoEvent)
                }.build(),
                "publish",
                "event"
            )
            protoEvent.id
        } catch (e: IOException) {
            logger.error(e) { }
            throw InvalidRequestException("Can not send event:  '${protoEvent.id.id}'")
        }
    }

    @Throws(IOException::class)
    suspend fun sendMessage(messageSendRequest: MessageSendRequest, parentEventId: EventID): MessageSendResponse {
        return withContext(Dispatchers.IO) {
            val message = createMessageFromRequest(messageSendRequest)
            val eventName = "send_message:${messageSendRequest.session}"
            val description =
                """Send message session: '${messageSendRequest.session}' dictionary: '${messageSendRequest.dictionary}' messageType: '${messageSendRequest.messageType}'"""
            val bodyData: IBodyData = MessageBuilder().text(
                context.jacksonMapper.asStringSuspend(messageSendRequest.message)
            ).build()

            val sendEventId = createParentEvent(eventName, parentEventId, description, PASSED, bodyData)
            val sendEventTimestamp = Instant.now()

            sendMessage(message, messageSendRequest.session, sendEventId)

            MessageSendResponse(messageSendRequest.session, sendEventTimestamp, sendEventId.id)
        }
    }
}