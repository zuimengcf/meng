/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Small JSON-RPC 2.0 builder used by the MCP transport. */
object McpJsonRpc {
    fun success(id: JsonElement?, result: JsonElement): JsonObject = buildJson {
        "jsonrpc" to "2.0"
        if (id != null && id !is JsonNull) "id" to id
        "result" to result
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject = buildJson {
        "jsonrpc" to "2.0"
        if (id != null && id !is JsonNull) "id" to id
        "error" to buildJson {
            "code" to code
            "message" to message
            if (data != null) "data" to data
        }
    }

    fun notification(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject = buildJson {
        "jsonrpc" to "2.0"
        "method" to method
        "params" to params
    }

    fun request(id: JsonElement, method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject = buildJson {
        "jsonrpc" to "2.0"
        "id" to id
        "method" to method
        "params" to params
    }

    fun buildJson(build: JsonObjectBuilder.() -> Unit): JsonObject {
        val b = JsonObjectBuilder()
        b.build()
        return b.result
    }

    fun jstr(s: String): JsonPrimitive = JsonPrimitive(s)
    fun jbool(b: Boolean): JsonPrimitive = JsonPrimitive(b)
    fun jnum(n: Number): JsonPrimitive = JsonPrimitive(n)
    fun jnull(): JsonElement = JsonNull
}

class JsonObjectBuilder {
    private val m = LinkedHashMap<String, JsonElement>()
    val result: JsonObject get() = JsonObject(m)
    infix fun String.to(value: String?) { if (value != null) m[this] = JsonPrimitive(value) }
    infix fun String.to(value: JsonElement?) { if (value != null) m[this] = value }
    infix fun String.to(value: Number?) { if (value != null) m[this] = JsonPrimitive(value) }
    infix fun String.to(value: Boolean?) { if (value != null) m[this] = JsonPrimitive(value) }
}
