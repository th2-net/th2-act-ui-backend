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

package com.exactpro.th2.actuibackend.entities.responces

import com.fasterxml.jackson.annotation.JsonInclude
import com.google.protobuf.DynamicMessage
import java.lang.Exception

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MethodCallResponse(
    val message: String? = null,
    val rawMessage: DynamicMessage? = null,
    val exception: Exception? = null
)