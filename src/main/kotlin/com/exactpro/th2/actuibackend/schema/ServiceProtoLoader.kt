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
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.ehcache.Cache
import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.CacheManagerBuilder
import org.ehcache.config.builders.ExpiryPolicyBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import java.time.Duration

class ServiceProtoLoader(val configuration: Configuration, val objectMapper: ObjectMapper) {

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private data class ResponseInfo(
        override val data: String? = null,
        override val exception: java.lang.Exception? = null
    ) : ResponseObject<String>(data, exception)

    private val manager = CacheManagerBuilder.newCacheManagerBuilder().build(true)
    private val serviceDescriptorCache: Cache<String, ResponseInfo> = manager.createCache(
        "descriptors",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
            String::class.java,
            ResponseInfo::class.java,
            ResourcePoolsBuilder.heap(configuration.protoCacheSize.value.toLong())
        ).withExpiry(
            ExpiryPolicyBuilder
                .timeToLiveExpiration(Duration.ofSeconds(configuration.descriptorsCacheExpiry.value.toLong()))
        )
            .build()
    )

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
            val responseData: ResponseObject<String> = try {
                httpClient.request<HttpResponse> {
                    url(createUrl(serviceName))
                    method = HttpMethod.Get
                    timeout {
                        requestTimeoutMillis = 10000
                    }
                }.let { ResponseObject(data = it.receive()) }
            } catch (exception: Exception) {
                logger.error(exception.cause) {
                    "Can not get service proto. " +
                            "Error message: ${exception.message}."
                }
                ResponseObject(null, exception)
            }

            responseData.getValueOrThrow()
        }
    }

    @KtorExperimentalAPI
    suspend fun getServiceProto(serviceName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                objectMapper.readTree(loadServiceProtoBase64(serviceName)).let { jsonTree ->
                    jsonTree.get("content").textValue().also {
                        serviceDescriptorCache.put(serviceName, ResponseInfo(it))
                    }
                }
            } catch (e: Exception) {
                serviceDescriptorCache.put(serviceName, ResponseInfo(exception = e))
                throw e
            }
        }
    }

    suspend fun isServiceHasDescriptor(serviceName: String): Boolean {
        return try {
            if (serviceDescriptorCache.containsKey(serviceName)) {
                serviceDescriptorCache.get(serviceName).getValueOrThrow()
            } else {
                getServiceProto(serviceName)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}