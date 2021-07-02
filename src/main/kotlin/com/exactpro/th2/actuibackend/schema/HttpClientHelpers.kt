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

package com.exactpro.th2.actuibackend.schema

import com.exactpro.th2.actuibackend.entities.exceptions.InfraSchemaDataException
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*

suspend fun getHttpClient(): HttpClient {
    return HttpClient {
        expectSuccess = false
        install(HttpTimeout)
        HttpResponseValidator {
            validateResponse { response: HttpResponse ->
                val statusCode = response.status.value

                when (statusCode) {
                    HttpStatusCode.NoContent.value -> throw InfraSchemaDataException("Schema incorrect data. Status: $statusCode. Request: ${response.request.url}")
                    in 300..399 -> throw RedirectResponseException(response, response.content.readUTF8Line() ?: "")
                    in 400..499 -> throw ClientRequestException(response, response.content.readUTF8Line() ?: "")
                    in 500..599 -> throw ServerResponseException(response, response.content.readUTF8Line() ?: "")
                }

                if (statusCode >= 600) {
                    throw ResponseException(response, response.content.readUTF8Line() ?: "")
                }
            }
        }
    }
}

open class ResponseObject<T>(open val data: T? = null, open val exception: Exception? = null) {
    fun getValueOrThrow(): T {
        return data ?: throw exception!!
    }
}