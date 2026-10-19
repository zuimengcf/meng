/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网同步**完整日志**（可查证）。
 *
 * 以 append-only 的本地 JSONL 文件记录每次同步的完整轨迹，便于事后排查
 * 「对端内容缺失 / 删除不同步 / 条目被覆盖」等问题：
 * - 每次同步会话开始时记一条 [SyncLogSession]（方向：CLIENT_UP / SERVER_HOST / CLIENT_DOWN）；
 * - 上报 / 回传 / 合并 / 跳过 / 覆盖等关键动作各记一条 [SyncLogEntry]，含 syncId、
 *   deleted 状态、updatedAt、本机 vs 远程时间戳比较结果，一眼可查谁覆盖了谁。
 *
 * 独立于剪贴板 Room 库与 oplog，只记录同步诊断信息，不参与任何同步逻辑。
 */
object ClipboardSyncLog {

    /** 单条日志类型。 */
    @Serializable
    enum class Kind {
        SESSION,       // 同步会话开始
        UPLOAD,        // 客户端上报条目
        RECEIVE,       // 客户端/主机接收对端条目
        MERGE_SKIP,    // 合并时跳过（本机较新）
        MERGE_APPLY,   // 合并时落地（远程较新，含覆盖/删除）
        DELETE,        // 收到删除墓碑
        TOMBSTONE_RECYCLE, // 墓碑回收
        WATERMARK,     // 水位线推进
    }

    /** 日志正文（序列化写入一行）。 */
    @Serializable
    data class Line(
        val ts: Long,
        val kind: Kind,
        /** 方向：CLIENT_UP=客户端→主机；SERVER=主机处理；CLIENT_DOWN=客户端接收 */
        val dir: String = "",
        val syncId: String = "",
        val deleted: Boolean = false,
        val textPreview: String = "",
        val localUpdatedAt: Long = 0,
        val remoteUpdatedAt: Long = 0,
        val detail: String = "",
    )

    private const val LOG_FILE = "clipboard_sync_log.jsonl"
    private const val MAX_LINES = 5000

    private val json = Json { ignoreUnknownKeys = true }
    private val logFile: java.io.File by lazy { java.io.File(appContext.filesDir, LOG_FILE) }

    private val lineCount = AtomicLong(0)

    private fun rawLines(): List<String> =
        runCatching { if (logFile.exists()) logFile.readLines(Charsets.UTF_8) else emptyList() }
            .getOrElse { emptyList() }

    /** 读取全部日志行（倒序，最新在前）。 */
    fun readAll(): List<Line> = rawLines().mapNotNull { runCatching { json.decodeFromString<Line>(it) }.getOrNull() }
        .map { it.copy(textPreview = "") }
        .reversed()

    /** 读取最近 N 条。 */
    fun readRecent(n: Int): List<Line> = readAll().take(n)

    /** 清空同步日志。 */
    fun clear() {
        runCatching { logFile.delete() }
    }

    /** 追加一条日志（自动裁剪超上限）。 */
    @Synchronized
    fun log(line: Line) {
        runCatching {
            logFile.parentFile?.mkdirs()
            // 日志超上限时先裁剪（保留最近一半），避免无限膨胀
            if (lineCount.incrementAndGet() > MAX_LINES.toLong()) {
                trimToHalf()
                lineCount.set((MAX_LINES / 2).toLong())
            }
            RandomAccessFile(logFile, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write((json.encodeToString(line) + "\n").toByteArray(Charsets.UTF_8))
            }
        }.onFailure { Timber.w(it, "Failed to append clipboard sync log") }
    }

    /** 裁剪：仅保留最近一半行。 */
    private fun trimToHalf() {
        val all = rawLines()
        if (all.size <= MAX_LINES / 2) return
        runCatching {
            val keep = all.takeLast(MAX_LINES / 2)
            logFile.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    // ===== 便捷记录 =====

    /** 记录一条「会话开始」。 */
    fun session(dir: String, detail: String) {
        log(Line(ts = System.currentTimeMillis(), kind = Kind.SESSION, dir = dir, detail = detail))
    }

    /** 记录一条「上报/回传条目」（含 deleted 状态与文本预览）。 */
    fun transfer(dir: String, kind: Kind, syncId: String, deleted: Boolean, text: String, updatedAt: Long, detail: String = "") {
        log(
            Line(
                ts = System.currentTimeMillis(), kind = kind, dir = dir, syncId = syncId,
                deleted = deleted, textPreview = preview(text), remoteUpdatedAt = updatedAt, detail = detail
            )
        )
    }

    /** 记录一条「合并动作」（跳过/落地/删除）。 */
    fun merge(dir: String, kind: Kind, syncId: String, deleted: Boolean, text: String,
              localUpdatedAt: Long, remoteUpdatedAt: Long, detail: String = "") {
        log(
            Line(
                ts = System.currentTimeMillis(), kind = kind, dir = dir, syncId = syncId,
                deleted = deleted, textPreview = preview(text), localUpdatedAt = localUpdatedAt,
                remoteUpdatedAt = remoteUpdatedAt, detail = detail
            )
        )
    }

    /** 记录墓碑回收 / 水位线推进。 */
    fun system(kind: Kind, detail: String) {
        log(Line(ts = System.currentTimeMillis(), kind = kind, detail = detail))
    }

    /** 诊断只记录长度；密码及敏感剪切板内容不进入额外日志。 */
    private fun preview(text: String): String =
        "[内容已隐藏，${text.length} 字符]"
}
