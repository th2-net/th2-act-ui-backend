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

import com.exactpro.th2.actuibackend.configuration.Variable


class CustomConfigurationClass {
    var hostname: String = "localhost"
    var port: Int = 8081
    var responseTimeout: Int = 6000
    var clientCacheTimeout: Int = 60
    var ioDispatcherThreadPoolSize: Int = 10
    var schemaDefinitionLink: String = ""
    val tempFileRootDirectory: String = "/tmp"
    val namespace: String = "th2-qa"
    val actTypes: Set<String> = setOf("th2-act")
    val schemaCacheExpiry = 24 * 60 * 60
    val protoCacheExpiry = 60 * 60
    val protoCacheSize = 100
    val getSchemaRetryCount = 10
    val getSchemaRetryDelay = 1
    val schemaDescriptorsLink: String = ""
    val descriptorsCacheExpiry = 10
    val sessions = emptySet<String>()

    override fun toString(): String {
        return "CustomConfigurationClass(hostname='$hostname', port=$port, responseTimeout=$responseTimeout, clientCacheTimeout=$clientCacheTimeout, ioDispatcherThreadPoolSize=$ioDispatcherThreadPoolSize, schemaDefinitionLink='$schemaDefinitionLink', protoCompileDirectory='$tempFileRootDirectory', namespace='$namespace', actTypes=$actTypes, schemaCacheExpiry=$schemaCacheExpiry, protoCacheExpiry=$protoCacheExpiry, protoCacheSize=$protoCacheSize, getSchemaRetryCount=$getSchemaRetryCount, getSchemaRetryDelay=$getSchemaRetryDelay, schemaDescriptorsLink='$schemaDescriptorsLink', descriptorsCacheExpiry=$descriptorsCacheExpiry, sessions=$sessions)"
    }
}


class Configuration(customConfiguration: CustomConfigurationClass) {

    val hostname: Variable = Variable("hostname", customConfiguration.hostname, "localhost")

    val port: Variable = Variable("port", customConfiguration.port.toString(), "8081")

    val responseTimeout: Variable = Variable("responseTimeout", customConfiguration.responseTimeout.toString(), "60000")

    val ioDispatcherThreadPoolSize: Variable = Variable(
        "ioDispatcherThreadPoolSize", customConfiguration.ioDispatcherThreadPoolSize.toString(), "1"
    )

    val clientCacheTimeout: Variable = Variable(
        "clientCacheTimeout", customConfiguration.clientCacheTimeout.toString(), "3600"
    )

    val schemaDefinitionLink: Variable = Variable(
        "schemaXMLLink", customConfiguration.schemaDefinitionLink, ""
    )

    val schemaDescriptorsLink: Variable = Variable(
        "schemaProtoLink", customConfiguration.schemaDescriptorsLink, ""
    )

    val tempFileRootDirectory: Variable = Variable(
        "tempFileRootDirectory", customConfiguration.tempFileRootDirectory, "/tmp"
    )

    val namespace: Variable = Variable("namespace", customConfiguration.namespace, "th2-qa")

    val actTypes: Set<String> = customConfiguration.actTypes.also {
        Variable(
            "actTypes",
            it.toString(),
            setOf("th2-act").toString()
        )
    }

    val schemaCacheExpiry: Variable =
        Variable("schemaCacheExpiry", customConfiguration.schemaCacheExpiry.toString(), "180")

    val protoCacheExpiry: Variable =
        Variable("protoCacheExpiry", customConfiguration.protoCacheExpiry.toString(), "3600")

    val protoCacheSize: Variable =
        Variable("protoCacheSize", customConfiguration.protoCacheSize.toString(), "100")

    val getSchemaRetryCount: Variable =
        Variable("getSchemaRetryCount", customConfiguration.getSchemaRetryCount.toString(), "10")

    val getSchemaRetryDelay: Variable =
        Variable("getSchemaRetryDelay", customConfiguration.getSchemaRetryDelay.toString(), "1")

    val descriptorsCacheExpiry: Variable =
        Variable("descriptorsCacheExpiry", customConfiguration.descriptorsCacheExpiry.toString(), "10")

    val sessions: Set<String> = customConfiguration.sessions.also {
        Variable("sessions", customConfiguration.sessions.toString(), emptySet<String>().toString())
    }
}




