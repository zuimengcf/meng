/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap

/**
 * 内容寻址的图片块（借自 localsync files 的「按内容哈希拉块」）。
 *
 * 同步快照里图片只以 SHA-256 出现；字节由 blob_get 单张传输，不再整库 base64 进一个 JSON。
 * 本对象不依赖 Android API：目录由调用方传入，便于 JVM 单测。
 */
object ClipboardImageBlobs {
    private const val CACHE_LIMIT = 4096
    private val EXTENSIONS = listOf("png", "jpg", "gif", "webp")

    /** 一张图片字节的来源：本地文件，或日志里已有的内联 base64（只持有引用，不复制）。 */
    sealed class Source {
        data class FromFile(val file: File) : Source()
        class Inline(val base64: String) : Source()
    }

    private data class FileKey(val path: String, val length: Long, val modified: Long)
    private val fileHashes = object : LinkedHashMap<FileKey, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FileKey, String>?) = size > CACHE_LIMIT
    }
    private val inlineHashes = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_LIMIT
    }

    fun isSha256(value: String?): Boolean =
        value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 文件哈希：按 (路径, 长度, 修改时间) 缓存，内容不变不重算。 */
    fun sha256OfFile(file: File): String? {
        if (!file.isFile) return null
        val key = FileKey(file.absolutePath, file.length(), file.lastModified())
        synchronized(fileHashes) { fileHashes[key]?.let { return it } }
        val md = MessageDigest.getInstance("SHA-256")
        val result = runCatching {
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            hex(md.digest())
        }.getOrNull() ?: return null
        synchronized(fileHashes) { fileHashes[key] = result }
        return result
    }

    /**
     * 内联 base64 的哈希。[cacheKey] 必须唯一对应这份内容（例如历史版本 id），
     * 每次同步都会遇到同一批历史图片，缓存后不再反复解码。
     */
    fun sha256OfBase64(data: String, cacheKey: String? = null): String? {
        val key = cacheKey?.let { "$it#${data.length}" }
        if (key != null) synchronized(inlineHashes) { inlineHashes[key]?.let { return it } }
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return sha256Hex(bytes).also { if (key != null) synchronized(inlineHashes) { inlineHashes[key] = it } }
    }

    fun readBase64(source: Source): String? = when (source) {
        is Source.Inline -> source.base64
        is Source.FromFile -> runCatching { Base64.getEncoder().encodeToString(source.file.readBytes()) }.getOrNull()
    }

    /** 在图片目录里按哈希找文件：同步落盘的图片以 `<sha256>.<ext>` 命名；其余靠已算过的缓存。 */
    fun find(dir: File?, sha: String?): File? {
        if (dir == null || !isSha256(sha)) return null
        EXTENSIONS.forEach { ext -> File(dir, "$sha.$ext").takeIf { it.isFile && sha256OfFile(it) == sha }?.let { return it } }
        val cached = synchronized(fileHashes) { fileHashes.entries.filter { it.value == sha }.map { File(it.key.path) } }
        return cached.firstOrNull { it.isFile && it.parentFile?.absolutePath == dir.absolutePath && sha256OfFile(it) == sha }
    }

    /** 校验后写入 `<sha256>.<ext>`（临时文件 + fsync + 原子改名）；哈希不符直接拒绝。 */
    fun store(dir: File, expectedSha: String, bytes: ByteArray, mimeType: String): File {
        require(isSha256(expectedSha)) { "无效图片哈希" }
        require(bytes.isNotEmpty()) { "empty clipboard image" }
        require(sha256Hex(bytes) == expectedSha) { "图片内容与哈希不符，已丢弃" }
        dir.mkdirs()
        val file = File(dir, "$expectedSha.${extensionFor(mimeType)}")
        if (file.isFile && sha256OfFile(file) == expectedSha) return file
        val temporary = File.createTempFile("blob-", ".tmp", dir)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(file)) { "unable to commit clipboard image" }
        } finally { temporary.delete() }
        return file
    }

    fun extensionFor(mimeType: String): String = when {
        mimeType.contains("png") -> "png"
        mimeType.contains("gif") -> "gif"
        mimeType.contains("webp") -> "webp"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        else -> "png"
    }

    /** 快照里出现、需要字节的全部图片哈希（未删除的图片条目 + 带图片的历史版本）。 */
    fun referencedHashes(packet: ClipboardSyncPacket): Set<String> {
        val out = LinkedHashSet<String>()
        packet.entries.forEach { e -> if (!e.deleted && e.type.startsWith("image/") && isSha256(e.imageSha256)) out += e.imageSha256!! }
        packet.history.forEach { h -> h.entry?.let { e -> if (e.type.startsWith("image/") && isSha256(e.imageSha256)) out += e.imageSha256!! } }
        return out
    }

    private fun hex(v: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(v.size * 2) { v.forEach { b -> val x = b.toInt() and 255; append(digits[x ushr 4]); append(digits[x and 15]) } }
    }
}
