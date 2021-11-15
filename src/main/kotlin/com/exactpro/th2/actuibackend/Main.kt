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

import CustomConfigurationClass
import com.exactpro.th2.actuibackend.entities.exceptions.InvalidRequestException
import com.exactpro.th2.actuibackend.entities.requests.FullServiceName
import com.exactpro.th2.actuibackend.entities.requests.MessageSendRequest
import com.exactpro.th2.actuibackend.entities.requests.MethodCallRequest
import com.exactpro.th2.actuibackend.message.MessageValidator
import com.exactpro.th2.actuibackend.protobuf.ProtoSchemaCache
import com.exactpro.th2.actuibackend.schema.SchemaParser
import com.exactpro.th2.actuibackend.schema.ServiceProtoLoader
import com.exactpro.th2.actuibackend.services.MessageSendService
import com.exactpro.th2.actuibackend.services.grpc.GrpcService
import com.exactpro.th2.actuibackend.services.rabbitmq.RabbitMqService
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageBatch
import com.exactpro.th2.common.metrics.liveness
import com.exactpro.th2.common.metrics.readiness
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageRouter
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
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

class Main {

    private val configurationFactory: CommonFactory
    private val messageRouterParsedBatch: MessageRouter<MessageBatch>
    private val eventRouter: MessageRouter<EventBatch>
    private val applicationContext: Context
    private val serviceProtoLoader: ServiceProtoLoader
    private val schemaParser: SchemaParser
    private val protoSchemaCache: ProtoSchemaCache
    private val messageValidator: MessageValidator
    private val rabbitMqService: RabbitMqService
    private val actGrpcService: GrpcService
    private val messageService: MessageSendService
    private val cacheControl: CacheControl
    private val jacksonMapper: ObjectMapper

    private val resources: Deque<AutoCloseable> = ConcurrentLinkedDeque()
    private val lock = ReentrantLock()
    private val condition: Condition = lock.newCondition()

    constructor(args: Array<String>) {

        configureShutdownHook(resources, lock, condition)

        configurationFactory = CommonFactory.createFromArguments(*args)
        resources += configurationFactory

        messageRouterParsedBatch = configurationFactory.messageRouterParsedBatch
        resources += messageRouterParsedBatch

        eventRouter = configurationFactory.eventBatchRouter
        resources += eventRouter

        applicationContext = Context(configurationFactory.getCustomConfiguration(CustomConfigurationClass::class.java))

        serviceProtoLoader = ServiceProtoLoader(applicationContext)
        schemaParser = SchemaParser(applicationContext)
        protoSchemaCache = ProtoSchemaCache(applicationContext, serviceProtoLoader, schemaParser)
        messageValidator = MessageValidator(schemaParser)

        rabbitMqService = RabbitMqService(applicationContext, messageRouterParsedBatch, eventRouter)
        actGrpcService = GrpcService(applicationContext, protoSchemaCache, schemaParser)
        messageService = MessageSendService(rabbitMqService, actGrpcService, applicationContext)
        cacheControl = applicationContext.cacheControl
        jacksonMapper = applicationContext.jacksonMapper
    }

    @InternalAPI
    private suspend fun sendErrorCode(call: ApplicationCall, e: Exception, code: HttpStatusCode) {
        withContext(NonCancellable) {
            call.respondText(e.getMessagesFromStackTrace(), ContentType.Text.Plain, code)
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
                            withTimeout(applicationContext.timeout) {
                                cacheControl?.let { call.response.cacheControl(it) }
                                call.respondText(
                                    jacksonMapper.asStringSuspend(calledFun.invoke()),
                                    ContentType.Application.Json
                                )
                            }
                        }.join()
                    } catch (e: Exception) {
                        throw e.getCauseEscapeCoroutineException() ?: e
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
        logger.info { "Starting the box" }

        liveness = true

        startServer()

        readiness = true

        awaitShutdown(lock, condition)
    }

    @InternalAPI
    private fun startServer() {

        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, applicationContext.configuration.ioDispatcherThreadPoolSize.value)

        embeddedServer(Netty, applicationContext.configuration.port.value.toInt()) {

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
                        applicationContext.configuration.sessions
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
                        val request =
                            MessageSendRequest(queryParametersMap, jacksonMapper.readValue(rawMessage))
                        messageValidator.validate(request)
                        messageService.sendRabbitMessage(request, rabbitMqService.parentEventId).also {
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
                        messageService.sendGrpcMessage(methodCallRequest, rabbitMqService.parentEventId).also {
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

        logger.info { "serving on: http://${applicationContext.configuration.hostname.value}:${applicationContext.configuration.port.value}" }
    }

    private fun configureShutdownHook(resources: Deque<AutoCloseable>, lock: ReentrantLock, condition: Condition) {
        Runtime.getRuntime().addShutdownHook(thread(
            start = false,
            name = "Shutdown hook"
        ) {
            logger.info { "Shutdown start" }
            readiness = false
            try {
                lock.lock()
                condition.signalAll()
            } finally {
                lock.unlock()
            }
            resources.descendingIterator().forEachRemaining { resource ->
                try {
                    resource.close()
                } catch (e: Exception) {
                    logger.error(e) { "Cannot close resource ${resource::class}" }
                }
            }
            liveness = false
            logger.info { "Shutdown end" }
        })
    }

    @Throws(InterruptedException::class)
    private fun awaitShutdown(lock: ReentrantLock, condition: Condition) {
        try {
            lock.lock()
            logger.info { "Wait shutdown" }
            condition.await()
            logger.info { "App shutdown" }
        } finally {
            lock.unlock()
        }
    }
}


@InternalAPI
fun main(args: Array<String>) {
    try {
        Main(args).run()
    } catch (ex: Exception) {
        logger.error(ex) { "Cannot start the box" }
        exitProcess(1)
    }
}
