/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/** Only fixed action codes, time and count cross this persistence boundary. */
object ClipboardAuditPolicy {
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_ROWS = 1000
    private val labels = mapOf(
        "INSERT" to "新增条目", "DELETE" to "删除条目", "UPDATE_TEXT" to "编辑条目",
        "PIN" to "置顶变更", "SET_GROUP" to "移动分组或排序",
        "GROUP_ADD" to "新增分组", "GROUP_RENAME" to "编辑分组",
        "GROUP_DELETE" to "删除分组", "HISTORY_ENTRY" to "接收条目历史",
        "HISTORY_GROUP" to "接收分组历史", "PURGE" to "到期清除正文与图片",
    )
    fun label(action: String): String = labels[action] ?: "其他操作"
    fun allowed(action: String): Boolean = action in labels
    fun retain(rows: List<ClipboardAuditLog.Row>, now: Long): List<ClipboardAuditLog.Row> =
        rows.filter { allowed(it.action) && it.count > 0 && it.timestamp > 0 &&
            it.timestamp <= now && now - it.timestamp < RETENTION_MS }.takeLast(MAX_ROWS)
}

/** Best-effort audit trail, independent of synchronization and its recovery payloads. */
object ClipboardAuditLog {
    @Serializable data class Row(val timestamp: Long, val action: String, val count: Int)
    private val json = Json { ignoreUnknownKeys = true }
    private val file by lazy { AtomicFile(File(appContext.filesDir, "clipboard_audit.json")) }

    private fun readRows(): List<Row> = runCatching {
        json.decodeFromString<List<Row>>(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrDefault(emptyList())

    private fun save(rows: List<Row>) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(rows).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
    }

    @Synchronized fun record(action: String, count: Int = 1) {
        if (!ClipboardAuditPolicy.allowed(action) || count <= 0) return
        runCatching {
            val now = System.currentTimeMillis()
            save(ClipboardAuditPolicy.retain(readRows() + Row(now, action, count), now))
        }
    }

    @Synchronized fun recent(): List<Row> = runCatching {
        val rows = ClipboardAuditPolicy.retain(readRows(), System.currentTimeMillis())
        save(rows)
        rows.asReversed()
    }.getOrDefault(emptyList())

    @Synchronized fun clear() { file.delete() }
}
