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

package com.exactpro.th2.actuibackend.entities.schema

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DataMap(
    @JsonProperty("\$schema")
    val schema: String?,
    val type: String,
    val required: List<String>?,
    val properties: Map<String, DataObject>,
    val additionalProperties: Boolean
) : DataObject {
    constructor(
        required: List<String>,
        properties: Map<String, DataObject>,
        schema: String? = null,
        additionalProperties: Boolean = false
    ) : this(
        type = "object",
        required = if (required.isNotEmpty()) required else null,
        properties = properties,
        additionalProperties = additionalProperties,
        schema = schema
    )
}