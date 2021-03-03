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

import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType
import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType.*
import com.google.protobuf.Value


enum class StringFormat(val type: JavaType) {
    DATA(JAVA_TIME_LOCAL_DATE),
    TIME(JAVA_TIME_LOCAL_TIME),
    DATA_TIME(JAVA_TIME_LOCAL_DATE_TIME);

    override fun toString(): String {
        return super.toString().toLowerCase().replace("_", "-")
    }
}

enum class ValueType(val valuesSet: Set<JavaType>) {
    INTEGER(setOf(
        JAVA_LANG_SHORT,
        JAVA_LANG_INTEGER,
        JAVA_LANG_LONG,
        JAVA_LANG_BYTE
    )),
    NUMBER(setOf(
        JAVA_LANG_FLOAT,
        JAVA_LANG_DOUBLE,
        JAVA_MATH_BIG_DECIMAL
    )),
    STRING(setOf(
        JAVA_LANG_STRING,
        JAVA_TIME_LOCAL_DATE_TIME,
        JAVA_TIME_LOCAL_DATE,
        JAVA_TIME_LOCAL_TIME,
        JAVA_LANG_CHARACTER
    )),
    BOOLEAN(setOf(JAVA_LANG_BOOLEAN));

    override fun toString(): String {
        return super.toString().toLowerCase()
    }
}
