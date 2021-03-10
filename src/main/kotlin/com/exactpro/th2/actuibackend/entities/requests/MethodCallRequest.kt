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


data class FullServiceName(val actName: String, val serviceName: String) {
    companion object {
        const val divider = ":"
    }

    constructor(name: String) : this(
        actName = name.split(divider).getOrNull(0)
            ?: throw InvalidRequestException("Invalid service name: '$name'. Service name must contains (boxName:serviceName)'"),
        serviceName = name.split(divider).getOrNull(1)
            ?: throw InvalidRequestException("Invalid service name: '$name'. Service name must contains (boxName:serviceName)'")
    )

    override fun toString(): String {
        return "$actName$divider$serviceName"
    }

}

data class MethodCallRequest(
    val fullServiceName: FullServiceName,
    val methodName: String,
    var message: String
) {
    companion object {
        private fun getRequiredParameter(parameter: String, parameters: Map<String, List<String>>): String {
            return parameters[parameter]?.firstOrNull()
                ?: throw InvalidRequestException("Required parameter '$parameter' not specified")
        }
    }

    constructor(parameters: Map<String, List<String>>, methodCallMessage: String) : this(
        fullServiceName = FullServiceName(getRequiredParameter("fullServiceName", parameters)),
        methodName = getRequiredParameter("methodName", parameters),
        message = methodCallMessage
    )
}

