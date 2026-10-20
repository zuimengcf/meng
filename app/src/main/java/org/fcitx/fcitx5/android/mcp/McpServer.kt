/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.mcp

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncSettings
import org.fcitx.fcitx5.android.data.smart.SmartInputControl
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

/** JSON-RPC MCP endpoint. The transport must remain the encrypted wire.kind=mcp endpoint. */
object McpServer {
    private val json = Json { ignoreUnknownKeys = false }
    private const val PREFS_NAME = "org.fcitx.fcitx5.android_preferences"
    const val PROTOCOL_VERSION = "2025-06-18"

    private class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val required: Set<String>,
        val types: Map<String, String>,
        val exec: suspend (JsonObject) -> JsonElement,
    )

    private val tools = LinkedHashMap<String, Tool>()
    @Volatile var initialized = false; private set
    @Volatile var enabled = true

    // Explicit allow-list: no room passwords, keystore ciphertext, sync session, or arbitrary prefs.
    private val safeSettingKeys = setOf(
        "inline_suggestions", "toolbar_num_row_on_password", "popup_on_key_press",
        "keep_letters_uppercase", "show_voice_input_button", "expand_toolbar_by_default",
        "space_swipe_move_cursor", "show_lang_switch_key", "reset_keyboard_on_focus_change",
        "ignore_system_cursor", "hide_key_config", "disable_animation", "verbose_log",
        "sound_on_keypress", "sound_on_keypress_volume", "haptic_on_keypress", "haptic_on_keyup",
        "haptic_on_repeat", "button_sound_volume", "keyboard_height_percent",
        "keyboard_height_percent_landscape", "keyboard_side_padding", "keyboard_side_padding_landscape",
        "keyboard_bottom_padding", "keyboard_bottom_padding_landscape", "one_hand_width_percent",
        "one_hand_edge_margin", "one_hand_bottom_margin", "floating_width_percent",
        "one_hand_corner_radius", "floating_corner_radius",
        "floating_x_percent", "floating_y_percent", "floating_x_percent_landscape",
        "floating_y_percent_landscape", "long_press_delay", "show_key_popup", "editor_info_inspector"
    )

    init { registerTools() }

    /** Room passwords are never accepted as MCP credentials. */
    fun isValidToken(token: String?): Boolean = token != null && ClipboardSyncSettings.getMcpToken()?.let {
        java.security.MessageDigest.isEqual(it.toByteArray(), token.toByteArray())
    } == true

    /** Returns one response, or the empty string for a valid notification. */
    fun handle(raw: String): String {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return encode(McpJsonRpc.error(null, -32700, "Parse error"))
        if (element !is JsonObject) return encode(McpJsonRpc.error(null, -32600, "Invalid Request: expected object"))
        return handleObject(element)
    }

    private fun handleObject(raw: JsonObject): String {
        val id = raw["id"]
        val hasId = raw.containsKey("id")
        // JSON-RPC IDs are strings or numbers; never traverse arbitrary JSON as a primitive.
        val validId = !hasId || (id is JsonPrimitive && (id.isString || (id.booleanOrNull == null && id.contentOrNull != null)))
        val version = (raw["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!validId || version != "2.0") {
            return encode(McpJsonRpc.error(if (hasId && validId) id else null, -32600, "Invalid Request: jsonrpc must be 2.0 and id must be string or number"))
        }
        val methodElement = raw["method"]
        val method = (methodElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (method.isNullOrBlank()) return encode(McpJsonRpc.error(if (hasId) id else null, -32600, "Invalid Request: missing string method"))
        val notification = !hasId
        val paramsElement = raw["params"]
        if (paramsElement != null && paramsElement !is JsonObject) {
            return encode(McpJsonRpc.error(if (hasId) id else null, -32602, "Invalid params: params must be an object"))
        }
        val params = paramsElement as? JsonObject ?: JsonObject(emptyMap())
        val response = when (method) {
            "ping" -> McpJsonRpc.success(id, JsonObject(emptyMap()))
            "initialize" -> onInitialize(id, params)
            "notifications/initialized" -> { initialized = true; null }
            "tools/list" -> onToolsList(id)
            "tools/call" -> onToolsCall(id, params)
            else -> McpJsonRpc.error(id, -32601, "Method not found: $method")
        }
        return if (notification) "" else response?.let(::encode) ?: ""
    }

    private fun onInitialize(id: JsonElement?, params: JsonObject): JsonObject {
        val versionElement = params["protocolVersion"]
        val version = (versionElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (versionElement != null && version == null)
            return McpJsonRpc.error(id, -32602, "Invalid params: protocolVersion must be a string")
        if (version != null && version != PROTOCOL_VERSION && version != "2024-11-05")
            return McpJsonRpc.error(id, -32602, "Unsupported protocolVersion")
        initialized = true
        return McpJsonRpc.success(id, McpJsonRpc.buildJson {
            "protocolVersion" to PROTOCOL_VERSION
            "capabilities" to McpJsonRpc.buildJson { "tools" to McpJsonRpc.buildJson { } }
            "serverInfo" to McpJsonRpc.buildJson { "name" to "meng-ime-mcp"; "version" to "0.1.1" }
            "instructions" to "仅通过加密 wire.kind=mcp 使用；房间凭据不会通过 MCP 返回。"
        })
    }

    private fun onToolsList(id: JsonElement?): JsonObject = McpJsonRpc.success(id, McpJsonRpc.buildJson {
        "tools" to buildJsonArray { tools.values.forEach { add(McpJsonRpc.buildJson {
            "name" to it.name; "description" to it.description; "inputSchema" to it.inputSchema
        }) } }
    })

    private fun onToolsCall(id: JsonElement?, params: JsonObject): JsonObject {
        val name = params["name"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?: return McpJsonRpc.error(id, -32602, "Invalid params: name must be a string")
        val tool = tools[name] ?: return McpJsonRpc.error(id, -32602, "Unknown tool: $name")
        val args = params["arguments"]?.let { it as? JsonObject }
            ?: if (params.containsKey("arguments")) return McpJsonRpc.error(id, -32602, "Invalid params: arguments must be an object") else JsonObject(emptyMap())
        val validation = validate(tool, args)
        if (validation != null) return McpJsonRpc.error(id, -32602, validation)
        return try {
            // The encrypted HTTP endpoint calls this boundary on its worker thread.
            val result = runBlocking { tool.exec(args) }
            toolResult(id, result, false)
        } catch (e: Exception) {
            Timber.w(e, "MCP tool '$name' failed")
            toolResult(id, JsonPrimitive("工具执行失败: ${e.message ?: "unknown error"}"), true)
        }
    }

    private fun validate(tool: Tool, args: JsonObject): String? {
        val declared = tool.inputSchema["properties"]?.jsonObject?.keys.orEmpty()
        val unknown = args.keys.firstOrNull { it !in declared }
        if (unknown != null) return "Invalid params: unknown '$unknown'"
        val missing = tool.required.firstOrNull { !args.containsKey(it) }
        if (missing != null) return "Invalid params: missing '$missing'"
        for ((key, type) in tool.types) args[key]?.let { value ->
            val ok = when (type) {
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "object" -> value is JsonObject
                else -> true
            }
            if (!ok) return "Invalid params: '$key' must be $type"
        }
        return null
    }

    private fun toolResult(id: JsonElement?, value: JsonElement, failed: Boolean) = McpJsonRpc.success(id, McpJsonRpc.buildJson {
        "content" to buildJsonArray { add(McpJsonRpc.buildJson { "type" to "text"; "text" to json.encodeToString(value) }) }
        "isError" to failed
    })

    private fun registerTools() {
        register("ime_list", "列出可用输入法方案。", schema(), emptyMap()) {
            val names = fcitx { availableIme().map { it.uniqueName } }
            McpJsonRpc.buildJson { "imes" to buildJsonArray { names.forEach { add(McpJsonRpc.jstr(it)) } } }
        }
        register("ime_current", "查询当前输入法方案。", schema(), emptyMap()) { McpJsonRpc.jstr(fcitx { currentIme().uniqueName }) }
        register("ime_activate", "切换输入法方案。", schema(Prop("ime", "string", "方案名")), mapOf("ime" to "string")) { args -> val v = args["ime"]!!.jsonPrimitive.content; fcitx { require(availableIme().any { it.uniqueName == v }) { "目标输入法不可用" }; activateIme(v) }; McpJsonRpc.jstr("已切换到 $v") }
        register("ime_toggle", "切换上/下一个输入法方案。", schema(Prop("forward", "boolean", "是否向前", McpJsonRpc.jbool(true))), mapOf("forward" to "boolean")) { args -> fcitx { enumerateIme(args["forward"]?.jsonPrimitive?.booleanOrNull ?: true) }; McpJsonRpc.jstr(fcitx { currentIme().uniqueName }) }
        register("ime_reset", "重置输入上下文。", schema(), emptyMap()) { fcitx { reset() }; McpJsonRpc.jstr("已重置") }
        register("commit_text", "向当前输入框提交文本。", schema(Prop("text", "string", "文本")), mapOf("text" to "string")) { args ->
            withContext(Dispatchers.Main.immediate) {
                val svc = FcitxInputMethodService.instance ?: throw IllegalStateException("输入法服务未运行")
                check(svc.currentInputConnection != null) { "当前没有聚焦的输入框" }
                svc.commitText(args["text"]!!.jsonPrimitive.content)
            }
            McpJsonRpc.jstr("已提交")
        }
        register("settings_list", "列出允许远程读取的输入法设置（不含房间凭据）。", schema(), emptyMap()) { _ -> settingsList() }
        register("settings_get", "读取一个允许的输入法设置。", schema(Prop("key", "string", "设置键")), mapOf("key" to "string")) { args -> settingGet(args["key"]!!.jsonPrimitive.content) }
        register("settings_set", "按现有值的原生类型写入一个允许的设置。", schema(Prop("key", "string", "设置键"), Prop("value", "any", "与现有值相同 JSON 类型的值")), mapOf("key" to "string")) { args -> settingSet(args["key"]!!.jsonPrimitive.content, args["value"] ?: error("缺少参数 value")) }
        register("clipboard_list", "读取剪贴板条目；新建/编辑/分组/删除仍由现有同步一致性 API 管理。", schema(Prop("limit", "integer", "最多 100 条", McpJsonRpc.jnum(20))), mapOf("limit" to "integer")) { args ->
            val n = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(0, 100); val entries = ClipboardManager.getAllByTimeDesc().take(n)
            McpJsonRpc.buildJson { "count" to entries.size; "entries" to buildJsonArray { entries.forEach { e -> add(McpJsonRpc.buildJson { "id" to e.id; "text" to e.text; "deleted" to e.deleted; "syncId" to e.syncId }) } } }
        }
        register("stats_overview", "读取输入统计概览。", schema(), emptyMap()) { _ ->
            val today = InputStatsManager.todayStats()
            val weekTotal = InputStatsManager.weekStats().sumOf { it.totalChars }
            val monthTotal = InputStatsManager.monthStats().sumOf { it.totalChars }
            val top = InputStatsManager.topWords().map { it.word to it.count }
            val associationCount = InputStatsManager.assocCount()
            McpJsonRpc.buildJson {
                "today" to McpJsonRpc.buildJson { "totalChars" to (today?.totalChars ?: 0L); "chineseChars" to (today?.chineseChars ?: 0L); "commitCount" to (today?.commitCount ?: 0L) }
                "weekTotalChars" to weekTotal; "monthTotalChars" to monthTotal
                "topWords" to buildJsonArray { top.forEach { (word, count) -> add(McpJsonRpc.buildJson { "word" to word; "count" to count }) } }
                "assocCount" to associationCount
            }
        }
        register("room_status", "读取安全房间状态；仅返回模式、地址和端口，不返回密码或密文。", schema(), emptyMap()) { _ ->
            val s = runCatching { ClipboardSyncSettings.readSession() }.getOrNull()
            McpJsonRpc.buildJson { "mcpEnabled" to enabled; "active" to (s != null); if (s != null) { "mode" to s.mode; "host" to s.host; "port" to s.port } }
        }
        register("smart_input_status", "读取现有智能输入 API 配置。", schema(), emptyMap()) { _ -> mapJson(SmartInputControl.getConfig()) }
        register("smart_input_configure", "按现有 SmartInputContract 校验并更新智能输入配置。", schema(Prop("patch", "object", "SmartInputApi 支持的配置字段")), mapOf("patch" to "object")) { args ->
            val patch = args["patch"]!!.jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: error("patch values must be strings") }
            mapJson(SmartInputControl.configure(patch))
        }
    }

    private data class Prop(val name: String, val type: String, val description: String, val default: JsonElement? = null)
    private fun schema(vararg p: Prop): JsonObject = McpJsonRpc.buildJson {
        "type" to "object"; "properties" to McpJsonRpc.buildJson { p.forEach { x -> x.name to McpJsonRpc.buildJson { if (x.type != "any") "type" to x.type; "description" to x.description; if (x.default != null) "default" to x.default } } }
        val required = p.filter { it.default == null }.map { it.name }; if (required.isNotEmpty()) "required" to buildJsonArray { required.forEach { add(it) } }
        "additionalProperties" to false
    }
    private fun register(name: String, desc: String, schema: JsonObject, types: Map<String, String>, exec: suspend (JsonObject) -> JsonElement) { tools[name] = Tool(name, desc, schema, schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(), types, exec) }
    private fun prefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private fun keyAllowed(key: String) = key in safeSettingKeys && !key.contains("password", true) && !key.contains("secret", true) && !key.contains("room", true) && !key.contains("sync", true)
    private fun settingValue(v: Any?): JsonElement = when (v) { null -> JsonNull; is Boolean -> JsonPrimitive(v); is Int -> JsonPrimitive(v); is Long -> JsonPrimitive(v); is Float -> JsonPrimitive(v); is Double -> JsonPrimitive(v); is String -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    private fun settingsList(): JsonObject = McpJsonRpc.buildJson { safeSettingKeys.sorted().forEach { k -> if (prefs().contains(k)) k to settingValue(prefs().all[k]) } }
    private fun settingGet(key: String): JsonObject { require(keyAllowed(key)) { "设置键不允许远程访问" }; require(prefs().contains(key)) { "设置不存在" }; return McpJsonRpc.buildJson { "key" to key; "value" to settingValue(prefs().all[key]) } }
    internal fun parseSettingValue(old: Any?, value: JsonElement): Any? = when (old) {
        is Boolean -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error("值必须是 boolean")
        is Int -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt() ?: error("值必须是 Int 范围内 integer")
        is Long -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("值必须是 integer")
        is Float -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat() ?: error("值必须是 number")
        is Double -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: error("值必须是 number")
        is String -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("值必须是 string")
        else -> error("设置类型不支持")
    }
    private fun settingSet(key: String, value: JsonElement): JsonPrimitive { require(keyAllowed(key)) { "设置键不允许远程写入" }; val p = prefs(); require(p.contains(key)) { "设置不存在" }; val e = p.edit(); when (val parsed = parseSettingValue(p.all[key], value)) { is Boolean -> e.putBoolean(key, parsed); is Int -> e.putInt(key, parsed); is Long -> e.putLong(key, parsed); is Float -> e.putFloat(key, parsed); is Double -> e.putFloat(key, parsed.toFloat()); is String -> e.putString(key, parsed); else -> error("设置类型不支持") }; require(e.commit()) { "设置保存失败" }; return McpJsonRpc.jstr("已写入 $key") }
    private fun mapJson(map: Map<String, String>) = McpJsonRpc.buildJson { map.forEach { (k, v) -> k to v } }
    private fun encode(e: JsonElement) = json.encodeToString(e)
    private suspend fun <T> fcitx(block: suspend org.fcitx.fcitx5.android.core.FcitxAPI.() -> T): T { val c = FcitxDaemon.getFirstConnectionOrNull() ?: error("fcitx 未运行"); return withContext(Dispatchers.IO) { c.runOnReady(block) } }
}
