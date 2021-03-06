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


package com.exactpro.th2.actuibackend

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun ObjectMapper.asStringSuspend(data: Any?): String {
    val mapper = this

    return withContext(Dispatchers.IO) {
        mapper.writeValueAsString(data)
    }
}


fun Exception.getCauseEscapeCoroutineException(): Throwable? {
    var rootCause: Throwable? = this
    while (rootCause?.cause != null && rootCause is CancellationException) {
        rootCause = rootCause.cause
    }
    return rootCause
}

suspend fun Exception.getMessagesFromStackTrace(): String {
    val stringBuilder = StringBuilder()
    var rootCause: Throwable? = this
    while (rootCause?.cause != null) {
        stringBuilder.append(rootCause.message ?: "", "\n")
        rootCause = rootCause.cause
    }
    stringBuilder.append(rootCause?.message ?: "")
    return String(stringBuilder)
}