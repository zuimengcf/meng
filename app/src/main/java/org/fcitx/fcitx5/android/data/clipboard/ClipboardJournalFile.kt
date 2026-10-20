/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** File publication is downstream of the Room outbox, so interrupted batches can be replayed. */
internal object ClipboardJournalFile {
    fun append(target: File, lines: Sequence<String>) {
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建历史日志目录" } }
        RandomAccessFile(target, "rw").use { file ->
            val originalLength = file.length()
            try {
                var length = originalLength
                if (length > 0) {
                    file.seek(length - 1)
                    if (file.read() != '\n'.code) { file.seek(length); file.write('\n'.code); length++ }
                }
                file.seek(length)
                lines.forEach { file.write((it + "\n").toByteArray(Charsets.UTF_8)) }
                // This also barriers an idempotent replay with no new lines.
                file.fd.sync()
            } catch (failure: Throwable) {
                try { file.setLength(originalLength); file.fd.sync() }
                catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            }
        }
    }

    /** Caller must have a committed outbox batch to replay. Save exact damaged bytes
     * before truncating an incomplete final append; never repair/skip middle rows. */
    fun recoverInterruptedTail(target: File, isValid: (String) -> Boolean): Boolean {
        if (!target.isFile || target.length() == 0L) return false
        RandomAccessFile(target, "rw").use { file ->
            val length = file.length()
            file.seek(length - 1)
            if (file.read() == '\n'.code) return false
            var start = length
            val buffer = ByteArray(8192)
            search@ while (start > 0) {
                val size = minOf(start, buffer.size.toLong()).toInt()
                val offset = start - size
                file.seek(offset); file.readFully(buffer, 0, size)
                for (i in size - 1 downTo 0) if (buffer[i] == '\n'.code.toByte()) {
                    start = offset + i + 1; break@search
                }
                start = offset
            }
            require(length - start <= Int.MAX_VALUE) { "历史日志尾行过大，已保留原文件" }
            val tail = ByteArray((length - start).toInt())
            file.seek(start); file.readFully(tail)
            val text = tail.toString(Charsets.UTF_8)
            if (text.isBlank() || isValid(text)) return false
            val saved = File(target.parentFile, "${target.name}.interrupted-${UUID.randomUUID()}")
            FileOutputStream(saved).use { it.write(tail); it.fd.sync() }
            file.setLength(start); file.fd.sync()
            return true
        }
    }
}
