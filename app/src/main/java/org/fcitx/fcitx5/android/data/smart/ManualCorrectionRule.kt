/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

@Serializable
data class ManualCorrectionRule(
    val id: String,
    val input: String,
    val output: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val createdAt: Long,
    val updatedAt: Long
)

object ManualCorrectionRuleContract {
    const val FORMAT = "meng-manual-corrections"
    const val VERSION = 1
    private const val MAX_RULES = 10_000
    internal const val MAX_BYTES = 4 * 1024 * 1024
    private val idAscii = Regex("[\\x21-\\x7E]{1,128}")
    private val pinyinAscii = Regex("[a-z]{1,64}")
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }

    fun validate(rules: List<ManualCorrectionRule>): List<ManualCorrectionRule> {
        require(rules.size <= MAX_RULES) { "too many manual correction rules" }
        val ids = HashSet<String>(); val pairs = HashSet<String>()
        rules.forEach { r ->
            require(r.id.isNotEmpty() && idAscii.matches(r.id)) { "invalid rule id" }
            require(pinyinAscii.matches(r.input)) { "input must be lowercase ASCII pinyin" }
            require(r.output.isNotEmpty()) { "output must not be empty" }
            require(!r.output.any { it == '\n' || it == '\r' || it.isISOControl() || it == '\u2028' || it == '\u2029' }) { "output contains a control character" }
            require(StandardCharsets.UTF_8.newEncoder().canEncode(r.output)) { "output contains invalid Unicode" }
            require(r.output.toByteArray(StandardCharsets.UTF_8).size <= 512) { "output is too large" }
            require(r.priority in 0..1_000_000) { "invalid priority" }
            require(r.createdAt >= 0L && r.updatedAt >= r.createdAt) { "invalid timestamps" }
            require(ids.add(r.id)) { "duplicate rule id" }
            require(pairs.add(r.input + "\u0000" + r.output)) { "duplicate input/output" }
        }
        val encoded = encodeUnchecked(rules)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "manual corrections exceed 4 MiB" }
        return rules.toList()
    }

    fun encode(rules: List<ManualCorrectionRule>): String {
        val checked = validate(rules)
        return encodeUnchecked(checked)
    }

    private fun encodeUnchecked(rules: List<ManualCorrectionRule>): String = buildJsonObject {
        put("format", FORMAT); put("version", VERSION)
        put("rules", JsonArray(rules.map { r -> buildJsonObject {
            put("id", r.id); put("input", r.input); put("output", r.output)
            put("enabled", r.enabled); put("priority", r.priority)
            put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
        } }))
    }.toString()

    fun decode(text: String): List<ManualCorrectionRule> {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "manual corrections exceed 4 MiB" }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { error("invalid manual correction JSON") }
        require(root.keys == setOf("format", "version", "rules")) { "unknown or missing manual correction fields" }
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "invalid manual correction format" }
        require(root["version"]?.jsonPrimitive?.isString == false && root["version"]?.jsonPrimitive?.int == VERSION) { "unsupported manual correction version" }
        val array = root["rules"] as? JsonArray ?: error("rules must be an array")
        val rules = array.map { element ->
            val obj = element as? JsonObject ?: error("rule must be an object")
            require(obj.keys == setOf("id", "input", "output", "enabled", "priority", "createdAt", "updatedAt")) { "unknown or missing rule fields" }
            require(obj["id"] is JsonPrimitive && (obj["id"] as JsonPrimitive).isString)
            require(obj["input"] is JsonPrimitive && (obj["input"] as JsonPrimitive).isString)
            require(obj["output"] is JsonPrimitive && (obj["output"] as JsonPrimitive).isString)
            require(obj["enabled"] is JsonPrimitive && !(obj["enabled"] as JsonPrimitive).isString &&
                ((obj["enabled"] as JsonPrimitive).content == "true" || (obj["enabled"] as JsonPrimitive).content == "false"))
            require(obj["priority"] is JsonPrimitive && !(obj["priority"] as JsonPrimitive).isString)
            require(obj["createdAt"] is JsonPrimitive && !(obj["createdAt"] as JsonPrimitive).isString)
            require(obj["updatedAt"] is JsonPrimitive && !(obj["updatedAt"] as JsonPrimitive).isString)
            ManualCorrectionRule(
                obj["id"]!!.jsonPrimitive.content, obj["input"]!!.jsonPrimitive.content,
                obj["output"]!!.jsonPrimitive.content, obj["enabled"]!!.jsonPrimitive.boolean,
                obj["priority"]!!.jsonPrimitive.int, obj["createdAt"]!!.jsonPrimitive.long,
                obj["updatedAt"]!!.jsonPrimitive.long
            )
        }
        return validate(rules)
    }

    fun toNativeTsv(rules: List<ManualCorrectionRule>): String {
        val checked = validate(rules)
        return checked.asSequence().filter { it.enabled }
            .sortedWith(compareByDescending<ManualCorrectionRule> { it.priority }.thenByDescending { it.updatedAt }.thenBy { it.id })
            .joinToString("\n", postfix = if (checked.any { it.enabled }) "\n" else "") { "${it.input}\t${it.output}\t${it.priority + 1}" }
    }

    internal fun newId(): String = UUID.randomUUID().toString()
}
