/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

/** Metadata-only diagnostic log; never a synchronization/recovery data source. */
object ClipboardSyncLog {
    @Serializable enum class Kind {
        SESSION, UPLOAD, RECEIVE, MERGE_SKIP, MERGE_APPLY, DELETE, TOMBSTONE_RECYCLE, WATERMARK,
    }
    // Keep the legacy decode/API shape; redact all untrusted strings at the disk boundary.
    @Serializable data class Line(
        val ts: Long, val kind: Kind, val dir: String = "", val syncId: String = "",
        val deleted: Boolean = false, val textPreview: String = "", val localUpdatedAt: Long = 0,
        val remoteUpdatedAt: Long = 0, val detail: String = "",
    )
    private const val MAX_LINES = 1000
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val file by lazy { AtomicFile(File(appContext.filesDir, "clipboard_sync_log.jsonl")) }
    private fun safe(line: Line) = line.copy(
        dir = line.dir.takeIf { it in setOf("CLIENT_UP", "SERVER", "SERVER_HOST", "CLIENT_DOWN") } ?: "",
        syncId = "", textPreview = "", detail = "",
    )
    private fun rows(): List<Line> = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { runCatching { json.decodeFromString<Line>(it) }.getOrNull() }
                .map(::safe).toList()
        }
    }.getOrDefault(emptyList())
    private fun save(rows: List<Line>) {
        val now = System.currentTimeMillis()
        val safeRows = rows.filter { it.ts > 0 && it.ts <= now && now - it.ts < RETENTION_MS }.takeLast(MAX_LINES)
        val stream = file.startWrite()
        try {
            safeRows.forEach { stream.write((json.encodeToString(safe(it)) + "\n").toByteArray(Charsets.UTF_8)) }
            file.finishWrite(stream)
        } catch (t: Throwable) { file.failWrite(stream); throw t }
    }
    /** Rewrites legacy previews/details on first startup/read, not merely when displayed. */
    @Synchronized fun sanitizeLegacy() { runCatching { save(rows()) } }
    @Synchronized fun readAll(): List<Line> = runCatching {
        val rows = rows(); save(rows); rows.filter { System.currentTimeMillis() - it.ts < RETENTION_MS }
            .takeLast(MAX_LINES).asReversed()
    }.getOrDefault(emptyList())
    fun readRecent(n: Int): List<Line> = readAll().take(n.coerceAtLeast(0))
    @Synchronized fun clear() { file.delete() }
    @Synchronized fun log(line: Line) { runCatching { save(rows() + safe(line)) } }
    fun session(dir: String, detail: String) { log(Line(System.currentTimeMillis(), Kind.SESSION, dir)) }
    fun transfer(dir: String, kind: Kind, syncId: String, deleted: Boolean, text: String, updatedAt: Long, detail: String = "") {
        log(Line(System.currentTimeMillis(), kind, dir, deleted = deleted, remoteUpdatedAt = updatedAt))
    }
    fun merge(dir: String, kind: Kind, syncId: String, deleted: Boolean, text: String,
              localUpdatedAt: Long, remoteUpdatedAt: Long, detail: String = "") {
        log(Line(System.currentTimeMillis(), kind, dir, deleted = deleted,
            localUpdatedAt = localUpdatedAt, remoteUpdatedAt = remoteUpdatedAt))
    }
    fun system(kind: Kind, detail: String) { log(Line(System.currentTimeMillis(), kind)) }
}
