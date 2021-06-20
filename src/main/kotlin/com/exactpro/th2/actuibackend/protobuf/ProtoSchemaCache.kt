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

package com.exactpro.th2.actuibackend.protobuf

import com.exactpro.th2.actuibackend.Context
import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.protobuf.ProtoService
import com.exactpro.th2.actuibackend.entities.requests.FullServiceName
import com.exactpro.th2.actuibackend.schema.SchemaParser
import com.exactpro.th2.actuibackend.schema.ServiceProtoLoader
import kotlinx.coroutines.runBlocking
import org.ehcache.Cache
import org.ehcache.config.builders.CacheConfigurationBuilder
import org.ehcache.config.builders.CacheManagerBuilder
import org.ehcache.config.builders.ExpiryPolicyBuilder
import org.ehcache.config.builders.ResourcePoolsBuilder
import java.time.Duration

class ProtoSchemaCache(
    private val context: Context,
    private val serviceProtoLoader: ServiceProtoLoader,
    private val schemaParser: SchemaParser
) {

    private val protobufParser = ProtobufParser(context)

    private val manager = CacheManagerBuilder.newCacheManagerBuilder().build(true)
    private val actNameToSchemaPackage: Cache<String, DependentSchemas> = manager.createCache(
        "proto",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
            String::class.java,
            DependentSchemas::class.java,
            ResourcePoolsBuilder.heap(context.configuration.protoCacheSize.value.toLong())
        ).withExpiry(
            ExpiryPolicyBuilder
                    .timeToLiveExpiration(Duration.ofSeconds(context.configuration.protoCacheExpiry.value.toLong()))
        ).build()
    )

    private suspend fun getDependentSchemaByActName(actName: String): DependentSchemas {
        return if (actNameToSchemaPackage.containsKey(actName)) {
            actNameToSchemaPackage.get(actName)
        } else {
            serviceProtoLoader.getServiceProto(actName).let {
                protobufParser.parseBase64ToJsonTree(it)
            }.let {
                protobufParser.parseJsonToProtoSchemas(actName, it)
            }.let {
                actNameToSchemaPackage.put(actName, it)
                it
            }
        }
    }

    private suspend fun getPackageByActName(actName: String): DependentSchemas {
        return getDependentSchemaByActName(actName)
    }

    suspend fun getServices(): List<FullServiceName> {
        return schemaParser.getActs()
                .map { act -> getDependentSchemaByActName(act) }
                .flatMap { it.getServices() }
    }

    suspend fun getServicesInAct(actName: String): List<FullServiceName> {
        return getPackageByActName(actName).getServices()
    }

    suspend fun getServiceDescription(name: FullServiceName): ProtoService {
        return getSchemaByServiceName(name).getServiceInfo(name)
    }

    suspend fun getJsonSchemaByServiceName(name: FullServiceName, methodName: String?): Map<String, String> {
        val schema = getPackageByActName(name.actName).getJsonSchemaByService(name)
        return if (methodName == null) {
            schema
        } else {
            val protoMethod = getServiceDescription(name).methods.firstOrNull { it.methodName == methodName }
                ?: throw InvalidRequestException("Unknown method name: '$methodName'")
            listOf(protoMethod.inputType, protoMethod.outputType).associateWith { schema[it]!! }
        }
    }

    suspend fun getSchemaByServiceName(name: FullServiceName): ProtoSchema {
        return getPackageByActName(name.actName).getService(name)
    }
}