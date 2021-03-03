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

package com.exactpro.th2.actuibackend.message

import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType
import com.exactpro.sf.common.impl.messages.xml.configuration.JavaType.*
import com.exactpro.th2.actuibackend.entities.exceptions.SchemaValidateException
import org.apache.commons.lang3.math.NumberUtils
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.*

class ValueChecker {
    companion object {
        private const val falseString = false.toString()
        private const val trueString = true.toString()
        private val dateFormat: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SSS").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val checkerMap: Map<JavaType, (Any) -> Boolean> = mapOf(
            JAVA_LANG_SHORT to ::checkShort,
            JAVA_LANG_INTEGER to ::checkInteger,
            JAVA_LANG_LONG to ::checkLong,
            JAVA_LANG_BYTE to ::checkByte,
            JAVA_LANG_FLOAT to ::checkFloat,
            JAVA_LANG_DOUBLE to ::checkDouble,
            JAVA_MATH_BIG_DECIMAL to ::checkBigDecimal,
            JAVA_LANG_STRING to ::checkString,
            JAVA_TIME_LOCAL_DATE_TIME to ::checkDateTime,
            JAVA_LANG_CHARACTER to ::checkCharacter,
            JAVA_LANG_BOOLEAN to ::checkBoolean
        )

        fun checkValue(type: JavaType, any: Any): Boolean {
            return checkerMap[type]?.invoke(any) ?: throw SchemaValidateException("Unknown data type: $type")
        }

        private fun checkBoolean(any: Any): Boolean {
            if (any is Boolean) return true
            return any is String && (trueString.equals(any, ignoreCase = true) || falseString.equals(
                any, ignoreCase = true
            ))
        }

        private fun checkCharacter(any: Any): Boolean {
            if (any is Char) return true
            return any is String && any.length == 1
        }

        private fun checkString(any: Any): Boolean {
            return any is String
        }

        private fun checkBigDecimal(any: Any): Boolean {
            if (any is Number) return true
            return any is String && NumberUtils.isCreatable(any)
        }

        private fun checkDouble(any: Any): Boolean {
            if (any is Double) return true
            return any is String && any.toDoubleOrNull()?.let { true } ?: false
        }

        private fun checkInteger(any: Any): Boolean {
            if (any is Int) return true
            return any is String && any.toIntOrNull()?.let { true } ?: false
        }

        private fun checkFloat(any: Any): Boolean {
            if (any is Float) return true
            return any is String && any.toFloatOrNull()?.let { true } ?: false
        }

        private fun checkShort(any: Any): Boolean {
            if (any is Short) return true
            return any is String && any.toShortOrNull()?.let { true } ?: false
        }

        private fun checkLong(any: Any): Boolean {
            if (any is Long) return true
            return any is String && any.toLongOrNull()?.let { true } ?: false
        }

        private fun checkByte(any: Any): Boolean {
            if (any is Byte) return true
            return any is String && any.toByteOrNull()?.let { true } ?: false
        }

        private fun checkDateTime(any: Any): Boolean {
            if (any is LocalDateTime) return true
            return try {
                any is String && dateFormat.parse(any).let { true }
            } catch (e: ParseException) {
                false
            }
        }
    }
}
