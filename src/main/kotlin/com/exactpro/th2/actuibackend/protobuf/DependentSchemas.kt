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

import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.requests.FullServiceName

class DependentSchemas(private val actName: String, protoSchemas: List<ProtoSchema>) {
    private val protoNameToProtoSchema = protoSchemas
        .associateBy { it.protoFileName }
    private val serviceNameToSchema = protoNameToProtoSchema.values.flatMap { protoSchema ->
        protoSchema.getServices().map { it to protoSchema }
    }.associate { it }

    fun getServices(): List<FullServiceName> {
        return serviceNameToSchema.keys.toList()
    }

    fun getService(name: FullServiceName): ProtoSchema {
        return serviceNameToSchema[name] ?: throw InvalidRequestException("Service: '$name' not found")
    }

    fun getJsonSchemaByService(name: FullServiceName): Map<String, String> {
        val schema = getService(name)
        return schema.protoSchema.filter { it.name != schema.mainSchema.name }.mapNotNull {
            protoNameToProtoSchema[it.name]?.jsonSchemaMap?.entries
        }.flatten().associate { it.toPair() } + schema.jsonSchemaMap
    }

}