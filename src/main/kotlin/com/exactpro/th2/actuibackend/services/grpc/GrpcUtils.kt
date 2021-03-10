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

import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.common.grpc.ListValue
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.message.setMetadata

private fun anyToValue(value: Any): Value {
    return when (value) {
        is List<*> -> {
            Value.newBuilder().setListValue(
                ListValue.newBuilder().addAllValues(value.map { anyToValue(it!!) }).build()
            ).build()
        }
        is Map<*, *> -> {
            Value.newBuilder().setMessageValue(
                Message.newBuilder()
                    .putAllFields(value.entries.associate { it.key as String to anyToValue(it.value!!) }).build()
            ).build()
        }
        else -> {
            Value.newBuilder().setSimpleValue(value.toString()).build()
        }
    }
}

fun convertMessageMapToValueMap(rawMessage: Map<String, Any>): Map<String, Value> {
    return rawMessage.entries.associate {
        it.key to anyToValue(it.value)
    }
}

fun createMessageFromRequest(messageSendRequest: MessageSendRequest): Message {
    return Message.newBuilder().setMetadata(
        messageType = messageSendRequest.messageType, sessionAlias = messageSendRequest.session
    ).putAllFields(convertMessageMapToValueMap(messageSendRequest.message)).build()
}

