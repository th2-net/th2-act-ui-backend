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

import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.requests.FullServiceName
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.requests.MethodCallRequest
import com.exactpro.th2.actuibackend.message.MessageValidator
import com.exactpro.th2.actuibackend.protobuf.ProtoSchemaCache
import com.exactpro.th2.actuibackend.schema.SchemaParser
import com.exactpro.th2.actuibackend.schema.ServiceProtoLoader
import com.exactpro.th2.actuibackend.services.grpc.GrpcService
import com.exactpro.th2.actuibackend.services.rabbitmq.RabbitMqService
import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.IBodyData
import com.exactpro.th2.common.grpc.EventID
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.*
import kotlin.system.measureTimeMillis


class Main(args: Array<String>) {
    private val logger = KotlinLogging.logger {}
    private val context = Context(args)
    private val configuration = context.configuration
    private val jacksonMapper = context.jacksonMapper
    private val timeout = context.timeout
    private val cacheControl = context.cacheControl
    private val serviceProtoLoader = ServiceProtoLoader(configuration, jacksonMapper)
    private val schemaParser = SchemaParser(configuration, jacksonMapper)
    private val rabbitMqService = RabbitMqService(configuration)
    private val messageValidator = MessageValidator(configuration, schemaParser)
    private val protoSchemaCache = ProtoSchemaCache(context, serviceProtoLoader, schemaParser)
    private val actGrpcService = GrpcService(context, protoSchemaCache, schemaParser, rabbitMqService.parentEventId)

    private val actName = "act-ui sendMessage"
    private val description = "act-ui subroot event"

    @InternalAPI
    private suspend fun sendErrorCode(call: ApplicationCall, e: Exception, code: HttpStatusCode) {
        withContext(NonCancellable) {
            call.respondText(e.rootCause?.message ?: e.toString(), ContentType.Text.Plain, code)
        }
    }

    @InternalAPI
    private suspend fun handleRequest(
        call: ApplicationCall,
        requestName: String,
        cacheControl: CacheControl?,
        vararg parameters: Any?,
        calledFun: suspend () -> Any
    ) {
        val stringParameters = parameters.contentDeepToString()
        coroutineScope {
            measureTimeMillis {
                logger.debug { "handling '$requestName' request with parameters '$stringParameters'" }

                try {
                    try {
                        launch {
                            withTimeout(timeout) {
                                cacheControl?.let { call.response.cacheControl(it) }
                                call.respondText(
                                    jacksonMapper.asStringSuspend(calledFun.invoke()), ContentType.Application.Json
                                )
                            }
                        }.join()
                    } catch (e: Exception) {
                        throw e.cause ?: e
                    }
                } catch (e: NoSuchElementException) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - not found" }
                    sendErrorCode(call, e, HttpStatusCode.NotFound)

                } catch (e: InvalidRequestException) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - bad request" }
                    sendErrorCode(call, e, HttpStatusCode.BadRequest)
                } catch (e: Exception) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - unexpected exception" }
                    sendErrorCode(call, e, HttpStatusCode.InternalServerError)
                }
            }.let { logger.debug { "request '$requestName' with parameters '$stringParameters' handled - time=${it}ms" } }
        }
    }

    @InternalAPI
    fun run() {

        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, configuration.ioDispatcherThreadPoolSize.value)

        embeddedServer(Netty, configuration.port.value.toInt()) {

            install(Compression)
            install(ContentNegotiation) {
                jackson {
                    enable(SerializationFeature.INDENT_OUTPUT)
                }
            }

            routing {

                get("/dictionaries") {
                    handleRequest(call, "dictionaries", cacheControl) {
                        schemaParser.getDictionaries()
                    }
                }

                get("/{dictionary}/{message}") {
                    val message = call.parameters["message"]
                    val dictionary = call.parameters["dictionary"]
                    handleRequest(call, "dictionary message", cacheControl, message, dictionary) {
                        schemaParser.getDictionarySchema(dictionary!!).entries.first { it.key == message }
                    }
                }

                get("/{dictionary}") {
                    val dictionary = call.parameters["dictionary"]
                    handleRequest(call, "dictionary", cacheControl, dictionary) {
                        schemaParser.getDictionarySchema(dictionary!!).map { it.key }
                    }
                }

                get("/sessions") {
                    handleRequest(call, "sessions", cacheControl) {
                        schemaParser.getSessions()
                    }
                }

                get("/acts") {
                    handleRequest(call, "acts", cacheControl) {
                        schemaParser.getActs()
                            .filter { serviceProtoLoader.isServiceHasDescriptor(it) }
                    }
                }

                post("/message") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    val rawMessage = call.receiveText()
                    handleRequest(call, "message", cacheControl, queryParametersMap) {

                        val request = MessageSendRequest(queryParametersMap, jacksonMapper.readValue(rawMessage))
                        messageValidator.validate(request)

                        val subrootEvent = rabbitMqService.createAndStoreEvent(
                            actName,
                            rabbitMqService.parentEventId.id,
                            description,
                            Event.Status.PASSED,
                            "act-ui",
                            null
                        )

                        val event = try {
                            rabbitMqService.sendMessage(request, rabbitMqService.parentEventId)
                            saveMessageRequestEvent(rabbitMqService, subrootEvent.id, request, true, jacksonMapper)
                        } catch (e: Exception) {
                            saveMessageRequestEvent(
                                rabbitMqService,
                                subrootEvent.id,
                                request,
                                false,
                                jacksonMapper,
                                e.toString()
                            )
                        }



                        mapOf(
                            "eventId" to event.id,
                            "session" to request.session,
                            "dictionary" to request.dictionary,
                            "messageType" to request.messageType

                        ).also {
                            call.response.cacheControl(CacheControl.NoCache(null))
                        }
                    }
                }

                get("/services") {
                    handleRequest(call, "services", cacheControl) {
                        protoSchemaCache.getServices().map { it.toString() }
                    }
                }

                get("/services/{box}") {
                    val boxName = call.parameters["box"]
                    handleRequest(call, "services", cacheControl, boxName) {
                        protoSchemaCache.getServicesInAct(boxName!!).map { it.toString() }
                    }
                }

                get("/service/{name}") {
                    val serviceName = call.parameters["name"]
                    handleRequest(call, "service", cacheControl, serviceName) {
                        val fullName = FullServiceName(serviceName!!)
                        protoSchemaCache.getServiceDescription(fullName)
                    }
                }

                post("/method") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    val methodCallMessage = call.receiveText()
                    handleRequest(call, "method", cacheControl, queryParametersMap) {
                        val methodCallRequest = MethodCallRequest(queryParametersMap, methodCallMessage)
                        actGrpcService.sendMessage(methodCallRequest).also {
                            call.response.cacheControl(CacheControl.NoCache(null))
                        }
                    }
                }

                get("/json_schema/{name}") {
                    val serviceName = call.parameters["name"]
                    val byMethod = call.request.queryParameters["method"]
                    handleRequest(call, "json_schema", cacheControl, serviceName) {
                        val fullName = FullServiceName(serviceName!!)
                        protoSchemaCache.getJsonSchemaByServiceName(fullName, byMethod)
                    }
                }
            }
        }.start(false)

        logger.info { "serving on: http://${configuration.hostname.value}:${configuration.port.value}" }
    }
}

fun saveMessageRequestEvent(
    rabbitMqService: RabbitMqService,
    parentEventId: String,
    request: MessageSendRequest,
    success: Boolean,
    jacksonMapper: ObjectMapper,
    errorData: String? = ""
): EventID {
    return rabbitMqService.createAndStoreEvent(
        "act-ui message send request",
        parentEventId,
        "",
        if (success) Event.Status.PASSED else Event.Status.FAILED,
        "act-ui",
        listOf(
            object : IBodyData {
                val type = "message"
                val data = request.run { "session=$session dictionary=$dictionary messageType=$messageType" }
            },
            object : IBodyData {
                val type = "message"
                val data = jacksonMapper.writeValueAsString(request.message)
            },
            object : IBodyData {
                val type = "message"
                val data = errorData ?: "message sent successfully"
            }
        )
    )
}


@InternalAPI
fun main(args: Array<String>) {
    Main(args).run()
}
