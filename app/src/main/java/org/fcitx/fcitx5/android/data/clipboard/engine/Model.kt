/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync core (io.localsync.core.Model), Gson -> kotlinx.serialization.json.
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** 一条记录的合并单元：unit 名 -> 值（JsonNull 表示清空）。按 unit 做 LWW。 */
typealias Units = Map<String, JsonElement>

enum class OpType { UPSERT, DELETE }

enum class ConflictPolicy {
    /** 删除之后只有 create 能复活 */
    DELETE_WINS,
    /** 删除之后任何更晚的修改都会复活 */
    UPDATE_REVIVES,
}

/** 操作日志里的一条操作；opId = (origin, seq)，origin 内 seq 单调递增。 */
data class SyncOp(
    val origin: String,
    val seq: Long,
    val collection: String,
    val key: String,
    val hlc: String,
    val type: OpType,
    val create: Boolean = false,
    val units: Map<String, JsonElement>? = null,
)

/** 引擎为每条记录维护的合并状态（与业务表分开存储）。 */
data class RecordState(
    val collection: String,
    val key: String,
    val values: MutableMap<String, JsonElement> = LinkedHashMap(),
    val clocks: MutableMap<String, String> = LinkedHashMap(),
    var born: String? = null,
    var deleted: String? = null,
    /** 影子记录：引擎认为存在，但业务层尚未落地（被拒绝、缺图片、集合停用）。 */
    var shadow: Boolean = false,
) {
    fun maxUnitClock(): String? = clocks.values.maxOrNull()

    /** 该记录上出现过的最大时钟（unit / born / deleted）。 */
    fun maxClock(): String? = Hlc.max(Hlc.max(maxUnitClock(), born), deleted)

    fun isAlive(policy: ConflictPolicy): Boolean {
        val b = born ?: return false
        val lastWrite = if (policy == ConflictPolicy.UPDATE_REVIVES) Hlc.max(b, maxUnitClock()) else b
        return Hlc.cmp(lastWrite, deleted) > 0
    }

    fun hash(): String = EngineJson.hash(values)

    fun copyDeep(): RecordState = RecordState(collection, key, LinkedHashMap(values), LinkedHashMap(clocks), born, deleted, shadow)
}

typealias VersionVector = Map<String, Long>

object EngineJson {
    fun canonical(e: JsonElement?): String = StringBuilder().also { write(e ?: JsonNull, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    sb.append(JsonPrimitive(k).toString()).append(':')
                    write(e.getValue(k), sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }
                sb.append(']')
            }
            JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) sb.append(e.toString()) else {
                // 统一数字表示，避免 1 / 1.0 造成哈希抖动
                val l = e.longOrNull
                val d = e.content.toDoubleOrNull()
                when {
                    l != null -> sb.append(l)
                    d != null && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 9.0E15 -> sb.append(d.toLong())
                    else -> sb.append(e.content)
                }
            }
        }
    }

    fun hash(units: Map<String, JsonElement>): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(canonical(JsonObject(units)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun same(a: JsonElement?, b: JsonElement?): Boolean {
        val x = a ?: JsonNull
        val y = b ?: JsonNull
        return x == y || canonical(x) == canonical(y)
    }

    fun unitsToJson(units: Map<String, JsonElement>?): String? = units?.let { JsonObject(it).toString() }

    fun unitsFromJson(s: String?): MutableMap<String, JsonElement> =
        if (s.isNullOrEmpty()) LinkedHashMap() else LinkedHashMap(kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject)

    fun clocksToJson(c: Map<String, String>): String = JsonObject(c.mapValues { JsonPrimitive(it.value) }).toString()

    fun clocksFromJson(s: String?): MutableMap<String, String> =
        if (s.isNullOrEmpty()) LinkedHashMap()
        else LinkedHashMap(kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject.mapValues { it.value.jsonPrimitive.content })

    fun vvToJson(vv: Map<String, Long>): String = JsonObject(vv.mapValues { JsonPrimitive(it.value) }).toString()

    fun vvFromJson(s: String?): Map<String, Long> =
        if (s.isNullOrEmpty()) emptyMap()
        else kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
}
