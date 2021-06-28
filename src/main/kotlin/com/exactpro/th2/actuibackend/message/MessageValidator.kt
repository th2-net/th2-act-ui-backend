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

package com.exactpro.th2.actuibackend.message

import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.impl.MessageStructure
import com.exactpro.th2.actuibackend.entities.exceptions.SchemaValidateException
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.schema.SchemaParser
import mu.KotlinLogging
import kotlin.collections.set

class MessageValidator(private val schemaParser: SchemaParser) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    @Throws(SchemaValidateException::class)
    private fun recursiveTraversal(xmlField: IFieldStructure, messageField: Any, notUseParent: Boolean = false) {
        if (xmlField is MessageStructure) {
            if (!notUseParent && xmlField.isCollection) {
                val messageList = messageField as List<*>
                messageList.forEach { recursiveTraversal(xmlField, it!!, true) }
            } else {
                val messageMap = messageField as Map<*, *>
                val currentLevelFields = messageMap.keys.associate { it as String to false } as MutableMap<String, Boolean>
                for (field in xmlField.fields) {
                    messageMap[field.key]?.let {
                        currentLevelFields[field.key] = true
                        recursiveTraversal(field.value, it)
                    } ?: if (field.value.isRequired) logger.warn("Required field: '${field.key}' " + "does not exist in message: $messageField")
                }
                val incorrectFields = currentLevelFields.entries.filter { !it.value }.map { it.key }
                if (incorrectFields.isNotEmpty()) throw SchemaValidateException("Unknown fields in message: $incorrectFields")
            }
        } else {
            if (!ValueChecker.checkValue(xmlField.javaType, messageField)) throw SchemaValidateException(
                "Incorrect field type. Schema field: " + "'${xmlField.name}' schema type: ${xmlField.javaType} " + "message value: '${messageField}'"
            )

            val allowedValues = xmlField.values.values.map { it.value }.toSet()
            if (allowedValues.isNotEmpty() && !allowedValues.contains(messageField.toString())) throw SchemaValidateException(
                "Incorrect field value. Schema field: " + "'${xmlField.name}' schema allowed values: $allowedValues " + "message value: '${messageField}'"
            )
        }
    }

    @Throws(SchemaValidateException::class)
    suspend fun validate(messageSendRequest: MessageSendRequest) {
        try {
            val schema = schemaParser.getMessageXmlSchema(messageSendRequest)
            recursiveTraversal(schema, messageSendRequest.message, true)
        } catch (e: Exception) {
            when (e) {
                is SchemaValidateException -> throw e
                else -> throw SchemaValidateException("Structure validate exception: ${e.message}")
            }
        }
    }
}
