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


package com.exactpro.th2.actuibackend.entities.requests

import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException


data class MessageSendRequest(
    val session: String,
    val dictionary: String,
    val messageType: String,
    val isValidated: Boolean,
    val message: Map<String, Any>
) {

    companion object {
        private fun getRequiredParameter(parameter: String, parameters: Map<String, List<String>>): String {
            return parameters[parameter]?.firstOrNull()
                ?: throw InvalidRequestException("Required parameter '$parameter' not specified")
        }
    }

    constructor(parameters: Map<String, List<String>>, methodCallMessage: Map<String, Any>) : this(
        session = getRequiredParameter("session", parameters),
        dictionary = getRequiredParameter("dictionary", parameters),
        messageType = getRequiredParameter("messageType", parameters),
        isValidated =  parameters["isValidated"]?.first()?.toBoolean() ?: true,
        message = methodCallMessage
    )
}