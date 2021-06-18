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
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private val getSchemaRetryCount = configuration.getSchemaRetryCount.value.toLong()
    private val getSchemaRetryDelay = configuration.getSchemaRetryDelay.value.toLong()

    private val manager = CacheManagerBuilder.newCacheManagerBuilder().build(true)
    private val serviceProtoCache: Cache<String, String> = manager.createCache(
        "schemaProto",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
            String::class.java,
            String::class.java,
            ResourcePoolsBuilder.heap(1)
        ).withExpiry(
            ExpiryPolicyBuilder
                    .timeToLiveExpiration(Duration.ofSeconds(configuration.schemaProtoCacheExpiry.value.toLong()))
        ).build()
    )

    private fun createUrl(serviceName: String): String {
        return String.format(
            "%s/%s/%s/%s",
            configuration.schemaProtoLink.value,
            configuration.namespace.value,
            configuration.serviceType.value,
            serviceName
        )
    }

    @KtorExperimentalAPI
    private suspend fun loadServiceProtoBase64(serviceName: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val httpClient = HttpClient()
            var retries = 0
            var byteArray: ByteArray? = null
            do {
                var needRetry = false
                try {
                    val response = httpClient.request<HttpResponse> {
                        url(createUrl(serviceName))
                        method = HttpMethod.Get
                    }
                    logger.debug("get service proto response status: ${response.status}")
                    if (response.status != HttpStatusCode.OK) {
                        logger.error { "Bad response. Status: '${response.status.value}' description: ${response.status.description}" }
                        needRetry = true
                    } else {
                        byteArray = response.content.toByteArray()
                    }
                } catch (cause: Throwable) {
                    logger.error(cause) { "Can not get service proto. Retry: $retries" }
                    needRetry = true
                }
                if (needRetry) delay(getSchemaRetryDelay)
            } while (needRetry && retries++ < getSchemaRetryCount)

            byteArray ?: throw Exception("Can not get service proto")
        }
    }


    suspend fun getServiceProto(serviceName: String): String? {
        return withContext(Dispatchers.IO) {
            if (serviceProtoCache.containsKey(serviceName)) {
                serviceProtoCache.get(serviceName)
            } else {
                try {

                    objectMapper.readTree(loadServiceProtoBase64(serviceName)).let {
                        serviceProtoCache.put(serviceName, it.toString())
                        it.toString()
                    }
                } catch (e: Exception) {
                    println(e)
                    null
                }
            }
        }
    }
}