/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.fcitx.fcitx5.android.data.clipboard.engine.OpType
import org.fcitx.fcitx5.android.data.clipboard.engine.SyncOp
import org.fcitx.fcitx5.android.data.clipboard.engine.Units

/**
 * 剪贴板条目/分组 <-> localsync 引擎 unit 的纯映射（不依赖 Android，可在 JVM 上测）。
 *
 * - 每条记录一个原子 unit [UNIT]：整条 LWW，与旧快照协议「较新者胜」的粒度一致，
 *   保证引擎设备与旧版设备之间不会出现「同版本号、不同内容」的分叉。
 * - 已删除条目只同步元数据（正文、置顶、图片一律清空）：删除后的内容不离开本机。
 * - 图片只带 SHA-256，字节走 blob_get / blob_put。
 */
object ClipboardEngineMapping {
    const val ENTRIES = "clipboard.entries"
    const val GROUPS = "clipboard.groups"
    const val UNIT = "v"
    const val MAX_UNIT_CHARS = 8 * 1024 * 1024

    fun entryUnits(e: SyncEntry): Units {
        val m = LinkedHashMap<String, JsonElement>()
        m["text"] = JsonPrimitive(if (e.deleted) "" else e.text)
        m["pinned"] = JsonPrimitive(!e.deleted && e.pinned)
        m["timestamp"] = JsonPrimitive(e.timestamp)
        m["type"] = JsonPrimitive(e.type)
        m["sensitive"] = JsonPrimitive(e.sensitive)
        m["deleted"] = JsonPrimitive(e.deleted)
        m["groupSyncId"] = JsonPrimitive(e.groupSyncId)
        m["sortOrder"] = JsonPrimitive(e.sortOrder)
        m["updatedAt"] = JsonPrimitive(e.updatedAt)
        m["deviceId"] = JsonPrimitive(e.deviceId)
        if (!e.deleted) e.imageSha256?.let { m["image"] = JsonPrimitive(it) }
        return mapOf(UNIT to JsonObject(m))
    }

    fun entryFromUnits(key: String, units: Units): SyncEntry {
        val o = units.getValue(UNIT).jsonObject
        fun s(n: String) = o.getValue(n).jsonPrimitive.content
        val deleted = o.getValue("deleted").jsonPrimitive.boolean
        return SyncEntry(syncId = key, text = if (deleted) "" else s("text"), pinned = !deleted && o.getValue("pinned").jsonPrimitive.boolean,
            timestamp = o.getValue("timestamp").jsonPrimitive.long, type = s("type"), sensitive = o.getValue("sensitive").jsonPrimitive.boolean,
            deleted = deleted, updatedAt = o.getValue("updatedAt").jsonPrimitive.long, deviceId = s("deviceId"),
            groupSyncId = s("groupSyncId"), sortOrder = o.getValue("sortOrder").jsonPrimitive.long,
            imageSha256 = if (deleted) null else o["image"]?.jsonPrimitive?.content)
    }

    fun groupUnits(g: SyncGroup): Units = mapOf(UNIT to JsonObject(mapOf(
        "name" to JsonPrimitive(g.name), "sortOrder" to JsonPrimitive(g.sortOrder), "updatedAt" to JsonPrimitive(g.updatedAt),
        "deleted" to JsonPrimitive(g.deleted), "deviceId" to JsonPrimitive(g.deviceId))))

    fun groupFromUnits(key: String, units: Units): SyncGroup {
        val o = units.getValue(UNIT).jsonObject
        return SyncGroup(syncId = key, name = o.getValue("name").jsonPrimitive.content, sortOrder = o.getValue("sortOrder").jsonPrimitive.int,
            updatedAt = o.getValue("updatedAt").jsonPrimitive.long, deleted = o.getValue("deleted").jsonPrimitive.boolean,
            deviceId = o.getValue("deviceId").jsonPrimitive.content)
    }

    /** 记录的业务版本时间：本地操作的 HLC wall。 */
    fun versionWall(units: Units): Long? = (units[UNIT] as? JsonObject)?.get("updatedAt")?.jsonPrimitive?.longOrNull

    fun imageHash(units: Units): String? = (units[UNIT] as? JsonObject)?.get("image")?.jsonPrimitive?.content

    /** 远端 unit 必须能完整还原成条目/分组，否则整批拒收（不让半条数据进引擎状态）。 */
    fun validate(op: ClipboardEngineWireOp) {
        require(op.collection == ENTRIES || op.collection == GROUPS) { "未知同步集合" }
        require(op.key.isNotBlank() && op.key.length <= 256) { "无效同步标识" }
        if (op.type == OpType.DELETE.name) { require(op.units == null) { "删除操作不得携带内容" }; return }
        require(op.type == OpType.UPSERT.name) { "未知操作类型" }
        val units = requireNotNull(op.units) { "缺少操作内容" }
        require(units.keys == setOf(UNIT)) { "操作内容格式无效" }
        require(units.toString().length <= MAX_UNIT_CHARS) { "单条同步内容过大" }
        if (op.collection == ENTRIES) entryFromUnits(op.key, units).also { e ->
            e.imageSha256?.let { require(ClipboardImageBlobs.isSha256(it)) { "无效图片哈希" } }
        } else groupFromUnits(op.key, units)
    }

    fun toWire(op: SyncOp) = ClipboardEngineWireOp(op.origin, op.seq, op.collection, op.key, op.hlc, op.type.name, op.create,
        op.units?.let { JsonObject(it) })

    fun fromWire(op: ClipboardEngineWireOp): SyncOp {
        validate(op)
        return SyncOp(op.origin, op.seq, op.collection, op.key, op.hlc, OpType.valueOf(op.type), op.create, op.units)
    }
}

/** 线上的一条引擎操作（只在双方协商 [ClipboardSyncCapabilities.ENGINE_V1] 后出现）。 */
@Serializable
data class ClipboardEngineWireOp(
    val origin: String,
    val seq: Long,
    val collection: String,
    val key: String,
    val hlc: String,
    val type: String,
    val create: Boolean = false,
    val units: JsonObject? = null,
)
