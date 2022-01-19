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

import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType
import com.exactpro.sf.common.messages.structures.IDictionaryStructure
import com.exactpro.sf.common.messages.structures.IFieldStructure
import com.exactpro.sf.common.messages.structures.impl.MessageStructure
import com.exactpro.sf.common.messages.structures.loaders.XmlDictionaryStructureLoader
import com.exactpro.th2.actuibackend.Context
import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.exceptions.SchemaValidateException
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.schema.*
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.features.*
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
import java.time.Duration.ofSeconds


class SchemaParser(private val context: Context) {

    companion object {
        private val TH2_CONN = setOf("th2-conn")
        private val logger = KotlinLogging.logger { }
        private const val JSON_TREE = "jsonTree"
        private const val SCHEMA_NAME = "http://json-schema.org/draft-07/schema#"
    }

    private val getSchemaRetryCount = context.configuration.getSchemaRetryCount.value.toLong()
    private val getSchemaRetryDelay = context.configuration.getSchemaRetryDelay.value.toLong()

    private val manager = CacheManagerBuilder.newCacheManagerBuilder().build(true)
    private var jsonTree: JsonNode? = null
    private var jsonTreeCommitRef: String = ""

    private val jsonTreeCache: Cache<String, JsonNode> = manager.createCache(
        "schema",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
            String::class.java,
            JsonNode::class.java,
            ResourcePoolsBuilder.heap(1)
        ).withExpiry(
            ExpiryPolicyBuilder
                .timeToLiveExpiration(ofSeconds(context.configuration.schemaCacheExpiry.value.toLong()))
        )
            .build()
    )

    private val TH2_ACT = context.configuration.actTypes


    @KtorExperimentalAPI
    private suspend fun getSchemaXml(): ByteArray {
        return withContext(Dispatchers.IO) {
            val httpClient = getHttpClient()
            var retries = 0
            var response: ResponseObject<ByteArray>
            do {
                var needRetry = false
                response = try {
                    ResponseObject(
                        data = httpClient.request<HttpResponse> {
                            url(context.configuration.schemaDefinitionLink.value)
                            timeout {
                                requestTimeoutMillis = 10000
                            }
                            method = HttpMethod.Get
                        }.content.toByteArray()
                    )
                } catch (exception: Exception) {
                    logger.error(exception.cause) {
                        "Can not get schema. Retry: $retries. " +
                                "Error message: ${exception.message}."
                    }
                    needRetry = true
                    ResponseObject(null, exception)
                }
                if (needRetry) delay(getSchemaRetryDelay)
            } while (needRetry && retries++ < getSchemaRetryCount)

            response.getValueOrThrow()
        }
    }

    /**
     * Get the hash of the last commit using a regular expression.
     */
    private fun getCommitRef(schema: String): String {
        return Regex("""commitRef":"\w{40}",""").find(schema)?.value?.substring(12, 52) ?: ""
    }

    private suspend fun getJsonTree(): JsonNode {
        return withContext(Dispatchers.IO) {
            if (jsonTreeCache.containsKey(JSON_TREE) && jsonTree != null) {
                return@withContext jsonTree
            }

            val schemaXml = getSchemaXml()
            val commitRef = getCommitRef(String(schemaXml))
            if (commitRef == jsonTreeCommitRef && jsonTree != null) {
                return@withContext jsonTree
            }

            jsonTreeCommitRef = commitRef
            context.jacksonMapper.readTree(schemaXml).let {
                jsonTreeCache.put(JSON_TREE, it)
                jsonTree = it
                it
            }
        } ?: throw SchemaValidateException("JsonTree must be initialized")
    }

    private suspend fun getXmlDictionary(dictionaryName: String): IDictionaryStructure {
        return withContext(Dispatchers.IO) {
            val jsonTree = getJsonTree()
            parseByteArrayToXmlDictionary(jsonTree, dictionaryName)
        }
    }

    private suspend fun parseByteArrayToXmlDictionary(
        jsonTree: JsonNode,
        dictionaryName: String
    ): IDictionaryStructure {
        return withContext(Dispatchers.IO) {
            val preparedXmlString = getDictionaryFromJsonTree(jsonTree).firstOrNull {
                it.get("name")?.textValue() == dictionaryName
            }?.findValue("data")?.textValue()

            preparedXmlString?.let {
                XmlDictionaryStructureLoader().load(it.byteInputStream())
            } ?: throw InvalidRequestException("Dictionary: '$dictionaryName' not found")
        }
    }

    private suspend fun getConfigsByType(typesSet: Set<String>): List<JsonNode> {
        val jsonTree = getJsonTree()
        return jsonTree.get("resources").elements().asSequence().filter {
            typesSet.contains(it.get("spec")?.get("type")?.textValue())
        }.toList()
    }

    private suspend fun getDictionaryFromJsonTree(jsonTree: JsonNode): List<JsonNode> {
        return jsonTree.findParents("kind").filter {
            it.get("kind")?.textValue() == "Th2Dictionary"
        }
    }

    private fun convertXmlDictToPattern(xmlDictionary: IDictionaryStructure): Map<String, DataMap> {
        val rootElements = xmlDictionary.messages.map { it.key }.toMutableSet()
        return xmlDictionary.messages.entries.associate { mess ->
            mess.key to createDataMap(mess.value, rootElements, SCHEMA_NAME)
        }.filter { rootElements.contains(it.key) }
    }

    private fun createDataMap(
        xmlField: IFieldStructure,
        rootElements: MutableSet<String>,
        schemaName: String? = null
    ): DataMap {
        return xmlField.fields.entries.let { entries ->
            val properties = entries.associate {
                it.key to recursiveTraversal(it.value, rootElements)
            }
            val required = entries.filter { it.value.isRequired }.map { it.key }
            DataMap(required, properties, schemaName)
        }
    }

    private fun renameType(javaType: JavaType): ValueType {
        return ValueType.values().first { it.valuesSet.contains(javaType) }
    }

    private fun getFormat(javaType: JavaType): String? {
        return StringFormat.values().firstOrNull { it.type == javaType }?.toString()
    }

    private fun recursiveTraversal(
        xmlField: IFieldStructure, rootElements: MutableSet<String>
    ): DataObject {

        return if (xmlField is MessageStructure) {
            rootElements.remove(xmlField.name)

            val dataMap = createDataMap(xmlField, rootElements)

            if (xmlField.isCollection)
                Array(dataMap)
            else
                dataMap

        } else {
            val type = renameType(xmlField.javaType)
            if (xmlField.values.isNotEmpty()) {
                Enumerate(type, xmlField.values.values.associate { it.name to it.getCastValue<Any>() })
            } else {
                Simple(
                    type,
                    xmlField.getDefaultValue<Any>(),
                    getFormat(xmlField.javaType)
                )
            }
        }
    }

    suspend fun getMessageXmlSchema(messageSendRequest: MessageSendRequest): IFieldStructure {
        val xmlDictionary = getXmlDictionary(messageSendRequest.dictionary)

        return xmlDictionary.messages.entries.firstOrNull { it.key == messageSendRequest.messageType }?.value
            ?: throw SchemaValidateException(
                "Message type: '${messageSendRequest.messageType}' " + "does not exist in dictionary: '${messageSendRequest.dictionary}'"
            )
    }


    suspend fun getActs(): List<String> {
        val configs = getConfigsByType(TH2_ACT)
        return configs.mapNotNull {
            it.get("name")?.textValue()
        }
    }

    suspend fun getServicePorts(serviceNames: Set<String>): Map<String, Int> {
        val jsonTree = getJsonTree()
        return jsonTree.get("resources").elements().asSequence().mapNotNull { resource ->
            resource.get("name")?.textValue()?.let { it to resource }
        }.filter { serviceNames.contains(it.first) }
            .mapNotNull { service ->
                service.second.findValue("extended-settings")
                    ?.findValue("service")
                    ?.findValue("endpoints")
                    ?.findValue("nodePort")
                    ?.intValue()?.let {
                        service.first to it
                    }
            }.associate { it }
    }

    private suspend fun getDictionaries(): List<String> {
        return getDictionaryFromJsonTree(getJsonTree()).map { it.get("name").textValue() }
    }

    private fun checkSessionsAliasContains(jsonTree: JsonNode, session: String): Boolean {
        return jsonTree.findParents("field-name")?.asSequence()
            ?.filter { it?.get("field-name")?.textValue() == "session_alias" }
            ?.any { it?.get("expected-value")?.textValue() == session } ?: false
    }

    private fun getConfigsByKind(jsonTree: JsonNode, kindsSet: Set<String>): List<JsonNode> {
        return jsonTree.findParents("kind").asSequence().filter {
            kindsSet.contains(it?.get("kind")?.textValue())
        }.toList()
    }

    private fun getLinkedBoxes(jsonTree: JsonNode, boxes: Collection<String>): Set<String> {
        return getConfigsByKind(jsonTree, setOf("Th2Link"))
            .asSequence()
            .mapNotNull { it.get("spec")?.get("boxes-relation")?.get("router-mq") }
            .flatten()
            .mapNotNull { it?.get("from")?.get("box")?.textValue() to it?.get("to")?.get("box")?.textValue() }
            .mapNotNull {
                when {
                    boxes.contains(it.first) -> it.second
                    boxes.contains(it.second) -> it.first
                    else -> null
                }
            }
            .toSet()
    }

    private fun getLinkedDictionaries(jsonTree: JsonNode, boxes: Collection<String>): Set<String> {
        return getConfigsByKind(jsonTree, setOf("Th2Link"))
            .asSequence()
            .mapNotNull { it.get("spec")?.get("dictionaries-relation") }
            .flatten()
            .mapNotNull {
                it?.get("box")?.textValue() to it?.get("dictionary")?.get("name")?.textValue()
            }
            .filter { boxes.contains(it.first) }
            .mapNotNull { it.second }
            .toSet()
    }

    suspend fun getDictionariesBySession(session: String): Collection<String> {
        val jsonTree = getJsonTree()
        val boxesBySession = getConfigsByType(TH2_CONN)
            .mapNotNull { resource ->
                resource.get("name")?.textValue()?.let { it to resource }
            }.filter {
                checkSessionsAliasContains(it.second, session)
            }
            .map { it.first }
            .toList()

        val linkedBoxes = getLinkedBoxes(jsonTree, boxesBySession)

        if (linkedBoxes.isEmpty()) {
            return getDictionaries()
        }

        val linkedDictionaries = getLinkedDictionaries(jsonTree, linkedBoxes)

        return linkedDictionaries.ifEmpty { getDictionaries() }
    }

    suspend fun getDictionarySchema(dictionaryName: String): Map<String, DataMap> {
        return withContext(Dispatchers.IO) {
            convertXmlDictToPattern(getXmlDictionary(dictionaryName))
        }
    }
}
