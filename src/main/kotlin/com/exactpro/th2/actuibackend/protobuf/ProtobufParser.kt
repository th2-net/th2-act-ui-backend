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
import com.exactpro.th2.actuibackend.entities.exceptions.Base64StringToJsonTreeParseException
import com.exactpro.th2.actuibackend.entities.exceptions.JsonToProtoParseException
import com.exactpro.th2.actuibackend.entities.exceptions.ProtoParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.github.os72.protocjar.Protoc
import com.google.protobuf.DescriptorProtos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


class ProtobufParser(private val context: Context) {

    companion object {
        val logger = KotlinLogging.logger { }
    }

    private val defaultPath = "src/main/resources/protobuf"
    private val jsonSchemaGoPlugin: String = findJsonPlugin("protoc-gen-jsonschema")
    private val protoFiles = createDirectory("proto")
    private val temporaryFiles = createDirectory("temp")
    private val defaultPackage = "\n\npackage th2;\n\n"
    private val matchPackage = Regex("""[\W\s]+package[\W\s]+""")

    private data class JsonFileMetadata(val jsonPackage: String, val jsonName: String, val filePath: File)
    private data class ParsedProtoFile(
        val descriptor: List<DescriptorProtos.FileDescriptorProto>,
        val jsonSchemas: List<JsonSchema>
    )

    private fun insertPackageName(protoFile: String): String {
        return if (!protoFile.contains(matchPackage)) {
            StringBuilder(protoFile).apply {
                append(defaultPackage)
            }.toString()
        } else {
            protoFile
        }
    }

    private fun findJsonPlugin(name: String): String {
        val defaultPaths = listOf("src/main/resources", "src/test/resources", "/home")
        for (path in defaultPaths) {
            for (file in File(path).walk()) {
                if (file.name.contains(name)) return file.path
            }
        }
        logger.error { "Cannot find plugin '$name' from paths: $defaultPaths" }
        throw IOException("Cannot find plugin '$name' from paths: $defaultPaths")
    }


    private fun createDirectory(directoryName: String): File {
        return context.configuration.protoCompileDirectory.value.let {
            var directory = File(it + File.separator + directoryName)
            if (!createDirIfNotExist(directory)) {
                directory = File(defaultPath + File.separator + directoryName)
                if (!createDirIfNotExist(directory)) throw IOException("Failed to create directory: ${it + File.separator + directoryName} or directory: ${directory.path}")
            }
            directory
        }
    }

    private fun createDirIfNotExist(directory: File): Boolean {
        return directory.exists() || directory.mkdirs()
    }

    private suspend fun compileSchema(protoPaths: List<String>, protoFile: String, args: List<String>) {
        withContext(Dispatchers.IO) {
            val exitCode = Protoc.runProtoc((protoPaths.map { "--proto_path=$it" } + args + listOf(
                protoFile
            )).toTypedArray())

            if (exitCode != 0) {
                throw ProtoParseException("Failed to generate schema for: $protoFile")
            }
        }
    }

    private suspend fun lookupProtos(
        protoPaths: List<String>, protoFile: String, tempDir: Path, resolved: MutableSet<String>
    ): List<DescriptorProtos.FileDescriptorProto> {
        return withContext(Dispatchers.IO) {
            val schema = generateSchema(protoPaths, protoFile, tempDir)
            schema.fileList.filter { resolved.add(it.name) }.flatMap { fd ->
                fd.dependencyList.filterNot(resolved::contains)
                    .flatMap { lookupProtos(protoPaths, it, tempDir, resolved) } + fd
            }
        }
    }

    private suspend fun generateSchema(
        protoPaths: List<String>, protoFile: String, tempDir: Path
    ): DescriptorProtos.FileDescriptorSet {
        return withContext(Dispatchers.IO) {
            var outFile: File? = null
            try {
                outFile = File.createTempFile(tempDir.toString(), null, null)
                compileSchema(
                    protoPaths, protoFile, listOf(
                        "--include_std_types", "--descriptor_set_out=$outFile"
                    )
                )
                Files.newInputStream(outFile.toPath()).use { DescriptorProtos.FileDescriptorSet.parseFrom(it) }
            } finally {
                outFile?.delete()
            }
        }
    }


    private suspend fun generateJsonSchema(protoPaths: List<String>, protoFile: String): List<JsonSchema> {
        return withContext(Dispatchers.IO) {
            var tempSchemasFolder: Path? = null
            try {
                tempSchemasFolder = Files.createTempDirectory(temporaryFiles.toPath(), File(protoFile).name)
                compileSchema(
                    protoPaths, protoFile, listOf(
                        "--include_std_types",
                        "--plugin=${jsonSchemaGoPlugin}",
                        "--jsonschema_out=disallow_additional_properties,json_fieldnames:$tempSchemasFolder"
                    )
                )
                File(tempSchemasFolder.toUri()).walk().filter { it.isFile }.toList().map { file ->
                    async {
                        JsonSchema.createJsonSchema(file.name, Files.newBufferedReader(file.toPath()).use {
                            context.jacksonMapper.readTree(it.readText())
                        })
                    }
                }.awaitAll()
            } finally {
                tempSchemasFolder?.toFile()?.deleteRecursively()
            }
        }
    }

    private suspend fun parseProtoFile(
        protoFile: File, searchPath: String
    ): ParsedProtoFile {
        val fileDescription = lookupProtos(
            listOf(searchPath), protoFile.path, temporaryFiles.toPath(), mutableSetOf()
        )
        val jsonSchemas = generateJsonSchema(listOf(searchPath), protoFile.path)
        return ParsedProtoFile(fileDescription, jsonSchemas)
    }

    private fun saveProtoFile(protoFileName: String, data: String): File {
        try {
            return File(protoFileName).let {
                createDirIfNotExist(it.parentFile)
                it.bufferedWriter().use { out ->
                    out.write(data)
                }
                it
            }
        } catch (e: IOException) {
            throw ProtoParseException("Failed to save proto file: $data to directory: $temporaryFiles")
        }
    }

    suspend fun parseBase64ToJsonTree(jsonBase64String: String): JsonNode {
        return withContext(Dispatchers.IO) {
            try {
                val jsonString = String(Base64.getDecoder().decode(jsonBase64String))
                context.jacksonMapper.readTree(jsonString)
            } catch (e: Exception) {
                val message = when (e) {
                    is JsonProcessingException -> {
                        "Unable to parse json string jsonTree.".also {
                            logger.error(e) { "$it Base64 string: $jsonBase64String" }
                        }
                    }
                    else -> {
                        "Unable to parse base64 to json string.".also {
                            logger.error(e) { "$it Base64 string: $jsonBase64String" }
                        }
                    }
                }
                throw Base64StringToJsonTreeParseException("$message ${e.message}")
            }
        }
    }


    private suspend fun jsonToProtoFiles(jsonTree: JsonNode, tempFolder: String): List<JsonFileMetadata> {
        return withContext(Dispatchers.IO) {
            jsonTree.fields().asSequence().flatMap { jarPackage ->
                jarPackage.value.fields().asSequence().map {
                    val textValue = insertPackageName(it.value.textValue())
                    JsonFileMetadata(
                        jarPackage.key, it.key, saveProtoFile(tempFolder + File.separator + it.key, textValue)
                    )
                }
            }.toList()
        }
    }

    suspend fun parseJsonToProtoSchemas(actName: String, jsonTree: JsonNode): DependentSchemas {
        return withContext(Dispatchers.IO) {
            var tempDirectoryProto: File? = null
            try {
                tempDirectoryProto = Files.createTempDirectory(protoFiles.toPath(), "").toFile()
                val pathsToProtoFiles = jsonToProtoFiles(jsonTree, tempDirectoryProto.path)
                val protoSchemas = pathsToProtoFiles.map { jsonMetadata ->
                    parseProtoFile(jsonMetadata.filePath, tempDirectoryProto.path).let {
                        ProtoSchema(
                            actName,
                            jsonMetadata.jsonName,
                            jsonMetadata.jsonPackage,
                            it.descriptor,
                            it.jsonSchemas
                        )
                    }
                }
                DependentSchemas(actName, protoSchemas)
            } catch (e: Exception) {
                val message = "Unable to parse json to .proto file.".also {
                    logger.error(e) {
                        "$it File path: $tempDirectoryProto Json tree: ${jsonTree.toPrettyString()}"
                    }
                }
                throw JsonToProtoParseException("$message ${e.message}")
            } finally {
                tempDirectoryProto?.deleteRecursively()
            }
        }
    }
}
