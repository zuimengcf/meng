/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import androidx.sqlite.db.SupportSQLiteDatabase
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 单条剪贴板文本的大小上限。
 *
 * Android 读数据库时整行必须装进一个 CursorWindow（约 2 MB）。一条 2 MB 以上的文本写得进库，
 * 却再也读不出来（SQLiteBlobTooBigException）：所有「读全表」的同步、清理、记录新复制都会失败，
 * 表现为「一条卡死，之后同步都不成功」。所以：
 * - 新复制的超长文本只保存开头一段并明确标注（系统剪贴板里的原文不受影响）；
 * - 对端发来的超长文本同样换成带标注的开头一段（确定性变换，各设备结果一致，不会来回覆盖）；
 * - 已经在库里的超长行：完整内容另存为文件，库里换成带标注的开头一段，让同步恢复。
 */
object ClipboardSizeLimits {
    /** 每字符 UTF-8 最多 4 字节：512K 字符 ≤ 2 MB 仍偏紧，这里取 384K 字符（≤ 1.5 MB）。 */
    const val MAX_TEXT_CHARS = 384 * 1024
    /** 超长时保留的开头长度。 */
    const val KEEP_CHARS = 64 * 1024
    private const val REPAIR_BYTES = 1_500_000
    private const val SLICE_CHARS = 256 * 1024

    fun isOversized(text: String): Boolean = text.length > MAX_TEXT_CHARS

    /** 确定性截取：同一段原文在任何设备上得到同样的结果。 */
    fun shorten(text: String): String {
        if (!isOversized(text)) return text
        var end = KEEP_CHARS
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "\n\n[内容过大：原文共 ${text.length} 字，此处只保留开头 ${end} 字]"
    }

    /** 把已经在库里的超长行修复成可读状态；完整原文写到 files/clipboard_oversize/ 下。返回修复行数。 */
    fun repairOversizedRows(db: SupportSQLiteDatabase): Int {
        val rows = ArrayList<Pair<Long, Long>>()
        db.query("SELECT id, length(text) FROM clipboard WHERE length(CAST(text AS BLOB)) > $REPAIR_BYTES").use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getLong(1)
        }
        if (rows.isEmpty()) return 0
        val dir = File(appContext.filesDir, "clipboard_oversize").apply { mkdirs() }
        rows.forEach { (id, chars) ->
            val file = File(dir, "clipboard-$id-${System.currentTimeMillis()}.txt")
            val head = StringBuilder()
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                var start = 1L
                while (start <= chars) {
                    val piece = db.query("SELECT substr(text, ?, ?) FROM clipboard WHERE id = ?", arrayOf<Any>(start, SLICE_CHARS, id))
                        .use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: break
                    if (piece.isEmpty()) break
                    out.write(piece)
                    if (head.length < KEEP_CHARS) head.append(piece, 0, minOf(piece.length, KEEP_CHARS - head.length))
                    start += piece.codePointCount(0, piece.length)
                }
            }
            if (head.isNotEmpty() && Character.isHighSurrogate(head[head.length - 1])) head.setLength(head.length - 1)
            val replaced = head.toString() + "\n\n[内容过大：原文共 $chars 字，完整内容已另存为 ${file.name}]"
            db.execSQL("UPDATE clipboard SET text = ? WHERE id = ?", arrayOf<Any>(replaced, id))
            Timber.w("clipboard oversize row repaired: id=%d chars=%d", id, chars)
        }
        return rows.size
    }
}
