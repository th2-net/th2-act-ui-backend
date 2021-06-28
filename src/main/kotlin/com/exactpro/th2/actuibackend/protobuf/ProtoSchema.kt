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
import com.exactpro.th2.actuibackend.entities.exceptions.JsonToProtoParseException
import com.exactpro.th2.actuibackend.entities.exceptions.ProtoParseException
import com.exactpro.th2.actuibackend.entities.protobuf.ProtoMethod
import com.exactpro.th2.actuibackend.entities.protobuf.ProtoService
import com.exactpro.th2.actuibackend.entities.requests.FullServiceName
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

data class JsonSchema private constructor(val schemaName: String, val schema: JsonNode) {
    companion object {
        private const val parentEventIdField = "parentEventId"
        private const val refValues = "\$ref"
        private const val definitions = "#/definitions/"

        private fun deleteParentEventId(schema: JsonNode) {
            schema.findParents(parentEventIdField)?.let { parents ->
                parents.forEach { node ->
                    if (node is ObjectNode) {
                        node.findValue(parentEventIdField)?.let {
                            if (it is ObjectNode) it.removeAll()
                        }
                        node.remove(parentEventIdField)
                    }
                }
            }
        }

        private fun setDefinitionsPrefix(schema: JsonNode) {
            schema.findParents(refValues).forEach {
                if (it is ObjectNode)
                    it.put(refValues, "${definitions}${it.findValue(refValues).textValue()}")
            }
        }

        suspend fun createJsonSchema(schemaName: String, schema: JsonNode): JsonSchema {
            deleteParentEventId(schema)
            setDefinitionsPrefix(schema)
            return JsonSchema(schemaName, schema)
        }
    }
}

data class ProtoSchema(
    val actName: String,
    val protoFileName: String,
    val protoFileJar: String,
    val protoSchema: List<DescriptorProtos.FileDescriptorProto>,
    val jsonSchema: List<JsonSchema>
) {

    companion object {
        fun getMainSchema(
            protoFileName: String,
            protoSchema: List<DescriptorProtos.FileDescriptorProto>
        ): DescriptorProtos.FileDescriptorProto {
            return protoSchema.find { it.name == protoFileName }
                ?: throw ProtoParseException("Incorrect proto descriptor name: '$protoFileName'")
        }

        val logger = KotlinLogging.logger { }
    }

    private val fileFormat = ".jsonschema"

    val mainSchema = getMainSchema(protoFileName, protoSchema)

    private val protoSchemaMap = protoSchema.associateBy { it.name }

    val jsonSchemaMap = jsonSchema.associate {
        "${mainSchema.`package`}.${it.schemaName.replace(fileFormat, "")}" to it.schema
    }

    private val mainDescriptor = createMainDescriptor()

    private val serviceNameToService = mainSchema.serviceList.associate { service ->
        FullServiceName(actName, service.name).let { name ->
            name to ProtoService(name, service.methodList.map {
                ProtoMethod(
                    it.name, it.inputType.replace(".${mainSchema.`package`}", mainSchema.`package`),
                    it.outputType.replace(".${mainSchema.`package`}", mainSchema.`package`)
                )
            })
        }
    }


    private val typeRegistry = JsonFormat.TypeRegistry.newBuilder().add(mainDescriptor.messageTypes).build()
    private val jsonParser = JsonFormat.parser().usingTypeRegistry(typeRegistry)

    private fun createFileDescriptor(
        protoDescriptor: DescriptorProtos.FileDescriptorProto,
        builtDependencies: MutableMap<String, Descriptors.FileDescriptor>
    ): Descriptors.FileDescriptor {
        val descriptor = Descriptors.FileDescriptor.buildFrom(
            protoDescriptor, if (protoDescriptor.dependencyCount == 0) {
                emptyArray()
            } else {
                protoDescriptor.dependencyList.mapNotNull { dependency ->
                    builtDependencies[dependency] ?: protoSchemaMap[dependency]?.let {
                        createFileDescriptor(it, builtDependencies)
                    }
                }.toTypedArray()
            }
        )
        builtDependencies[protoDescriptor.name] = descriptor
        return descriptor
    }

    private fun createMainDescriptor(): Descriptors.FileDescriptor {
        return createFileDescriptor(
            mainSchema, mutableMapOf()
        )
    }

    suspend fun jsonToDynamicMessage(jsonMessage: String, messageType: Descriptors.Descriptor): DynamicMessage {
        return withContext(Dispatchers.IO) {
            try {
                val dmBuilder = DynamicMessage.newBuilder(messageType)
                jsonParser.merge(jsonMessage, dmBuilder)
                dmBuilder.build()
            } catch (e: InvalidProtocolBufferException) {
                "Cannot parse json message to protobuf dynamic message. Incorrect json format. ${e.message}".let {
                    logger.error(e) { it }
                    throw JsonToProtoParseException(it, e)
                }
            } catch (e: Exception) {
                "Cannot parse json message to protobuf dynamic message. ${e.message}".let {
                    logger.error(e) { it }
                    throw JsonToProtoParseException(it, e)
                }
            }
        }
    }

    fun protoMessageToJson(protoMessage: DynamicMessage): String {
        val printer = JsonFormat.printer().usingTypeRegistry(typeRegistry).includingDefaultValueFields()
        return printer.print(protoMessage)
    }

    fun getServices(): List<FullServiceName> {
        return serviceNameToService.keys.toList()
    }

    fun getServiceInfo(serviceName: FullServiceName): ProtoService {
        return serviceNameToService[serviceName]
            ?: throw InvalidRequestException("Unknown service name: '$serviceName'")
    }

    private fun getServiceProto(serviceName: String): Descriptors.ServiceDescriptor {
        return mainDescriptor.file.findServiceByName(serviceName)
            ?: throw InvalidRequestException("Unknown service name: '$serviceName'")
    }

    fun getServiceMethod(serviceName: String, methodName: String): Descriptors.MethodDescriptor {
        val serviceDescriptor = getServiceProto(serviceName)
        return serviceDescriptor.findMethodByName(methodName)
    }

}
