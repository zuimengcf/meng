/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.util.HashSet
import java.util.zip.ZipInputStream

/** Safe extraction limits shared by user-data import and JVM tests. */
object ZipStreamLimits {
    const val MAX_ENTRIES = 4096
    const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
}

data class ZipExtractionLimits(
    val maxEntries: Int = ZipStreamLimits.MAX_ENTRIES,
    val maxEntryBytes: Long = ZipStreamLimits.MAX_ENTRY_BYTES,
    val maxTotalBytes: Long = ZipStreamLimits.MAX_TOTAL_BYTES,
)

/**
 * Extract a ZIP into [destDir]. Entry names are treated as untrusted paths.
 * Directories are validated too, duplicate names are rejected, and bounded
 * streaming prevents zip bombs from exhausting storage or memory.
 */
fun ZipInputStream.extract(destDir: File, limits: ZipExtractionLimits = ZipExtractionLimits()): List<File> {
    require(limits.maxEntries > 0 && limits.maxEntryBytes >= 0 && limits.maxTotalBytes >= 0)
    require(!destDir.exists() || destDir.isDirectory) { "ZIP destination is not a directory" }
    if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Cannot create ZIP destination")
    val root = destDir.canonicalFile
    val rootPath = root.path
    val seen = HashSet<String>()
    var count = 0
    var total = 0L
    var entry = nextEntry
    while (entry != null) {
        if (++count > limits.maxEntries) throw SecurityException("too many ZIP entries")
        val normalized = validateZipEntryName(entry.name)
        if (!seen.add(normalized)) throw SecurityException("duplicate ZIP entry: $normalized")
        val out = File(root, normalized).canonicalFile
        if (out.path != rootPath && !out.path.startsWith(rootPath + File.separator)) {
            throw SecurityException("ZIP entry escapes destination: ${entry.name}")
        }
        if (entry.isDirectory) {
            if (!out.exists() && !out.mkdirs()) throw IOException("Cannot create ZIP directory: $normalized")
        } else {
            out.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create ZIP parent") }
            var written = 0L
            out.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = read(buffer)
                    if (read < 0) break
                    written += read
                    total += read
                    if (written > limits.maxEntryBytes || total > limits.maxTotalBytes) {
                        throw SecurityException("ZIP extraction size limit exceeded")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        closeEntry()
        entry = nextEntry
    }
    return root.listFiles()?.toList() ?: emptyList()
}

/** Pure helper intentionally public so JVM tests can exercise path policy. */
fun validateZipEntryName(name: String): String {
    if (name.isEmpty() || name.indexOf('\u0000') >= 0) throw SecurityException("invalid ZIP entry")
    if (name.startsWith('/') || name.startsWith('\\') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(name))
        throw SecurityException("absolute ZIP entry: $name")
    val normalized = name.replace('\\', '/').removeSuffix("/")
    if (normalized.isEmpty()) throw SecurityException("invalid ZIP entry: $name")
    val parts = normalized.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." })
        throw SecurityException("unsafe ZIP entry: $name")
    return parts.joinToString("/")
}

/** Pure duplicate policy used by tests and by callers preflighting ZIP manifests. */
fun validateUniqueZipEntryNames(names: Iterable<String>): List<String> {
    val seen = HashSet<String>()
    return names.map { validateZipEntryName(it).also { normalized ->
        if (!seen.add(normalized)) throw SecurityException("duplicate ZIP entry: $normalized")
    } }
}
