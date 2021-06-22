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

import Configuration
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging

class ServiceProtoLoader(val configuration: Configuration, val objectMapper: ObjectMapper) {

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private val getSchemaRetryCount = configuration.getSchemaRetryCount.value.toLong()
    private val getSchemaRetryDelay = configuration.getSchemaRetryDelay.value.toLong()

    private val serviceType = "Th2Box"

    private fun createUrl(serviceName: String): String {
        return String.format(
            "%s/%s/%s",
            configuration.schemaProtoLink.value,
            serviceType,
            serviceName
        )
    }

    @KtorExperimentalAPI
    private suspend fun loadServiceProtoBase64(serviceName: String): String {
        return withContext(Dispatchers.IO) {
            val httpClient = getHttpClient()
            var retries = 0
            var responseData: ResponseObject<String>
            do {
                var needRetry = false
                responseData = try {
                    httpClient.request<HttpResponse> {
                        url(createUrl(serviceName))
                        method = HttpMethod.Get
                    }.let { ResponseObject(data = it.receive()) }
                } catch (exception: Exception) {
                    logger.error(exception.cause) {
                        "Can not get service proto.  Retry: $retries. " +
                                "Error message: ${exception.message}."
                    }
                    needRetry = true
                    ResponseObject(null, exception)
                }
                if (needRetry) delay(getSchemaRetryDelay)
            } while (needRetry && retries++ < getSchemaRetryCount)

            responseData.getValueOrThrow()
        }
    }

    @KtorExperimentalAPI
    suspend fun getServiceProto(serviceName: String): String {
        return withContext(Dispatchers.IO) {
            objectMapper.readTree(loadServiceProtoBase64(serviceName)).let {
                it.get("content").textValue()
            }
        }
    }
}