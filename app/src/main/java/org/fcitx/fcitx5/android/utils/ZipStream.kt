/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Write one backup entry without size or count limits; owns and closes [source]. */
fun ZipOutputStream.writeBackupEntry(name: String, source: InputStream? = null) {
    source.use { input ->
        putNextEntry(ZipEntry(name))
        input?.copyTo(this)
        closeEntry()
    }
}

/**
 * Extract a ZIP into [destDir]. Entry names are treated as untrusted paths.
 * Directories are validated too and duplicate names are rejected. Contents are
 * streamed without application-imposed file size, archive size or entry limits;
 * callers must allow for the storage required by the uncompressed archive.
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    require(!destDir.exists() || destDir.isDirectory) { "ZIP destination is not a directory" }
    if (!destDir.exists() && !destDir.mkdirs()) throw IOException("Cannot create ZIP destination")
    val root = destDir.canonicalFile
    val rootPath = root.path
    val seen = HashSet<String>()
    var entry = nextEntry
    while (entry != null) {
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
            out.outputStream().buffered().use { output ->
                copyTo(output)
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
