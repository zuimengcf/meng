/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Pure-JVM file and policy helpers for the schema market. */
internal object SchemaMarketFileOps {
    private val globalConfigFiles = setOf(
        "default.yaml",
        "default.custom.yaml",
        "punctuation.yaml",
        "symbols.yaml",
        "key_bindings.yaml",
        "installation.yaml",
        "user.yaml",
    )

    private const val MAX_VERSION_FILE_BYTES = 4096

    data class OwnedFile(val path: String, val sha256: String?)

    fun orderedMirrors(mirrors: List<SchemaMirror>): List<SchemaMirror> =
        mirrors.withIndex().sortedWith(
            compareBy<IndexedValue<SchemaMirror>> { mirrorRank(it.value.status) }
                .thenBy { it.index }
        ).map { it.value }

    private fun mirrorRank(status: MirrorStatus): Int = when (status) {
        MirrorStatus.OK -> 0
        MirrorStatus.SLOW -> 1
        MirrorStatus.UNKNOWN -> 2
        MirrorStatus.FAIL -> 3
    }

    /** Try sources in the same order used by the UI; a failed attempt never stops fallback. */
    fun <T : Any> firstSuccessfulMirror(
        mirrors: List<SchemaMirror>,
        attempt: (SchemaMirror) -> T?,
    ): Pair<SchemaMirror, T>? {
        orderedMirrors(mirrors).forEach { mirror ->
            val value = runCatching { attempt(mirror) }.getOrNull()
            if (value != null) return mirror to value
        }
        return null
    }

    fun sourceWithReleaseProbe(source: SchemaSource, version: String?, checked: Boolean): SchemaSource =
        source.copy(
            version = version?.removePrefix("v") ?: source.version,
            versionCheckFailed = !checked,
        )

    fun sourceWithAssetProbe(source: SchemaSource, fingerprint: String?, checked: Boolean): SchemaSource =
        source.copy(
            assetFingerprint = fingerprint,
            versionCheckFailed = !checked,
        )

    fun hasUpdate(
        installedVersion: String?,
        latest: SchemaSource,
        installedFingerprint: String? = null,
    ): Boolean {
        if (installedVersion == null || latest.versionCheckFailed) return false
        return installedVersion != latest.version ||
            (latest.assetFingerprint != null && installedFingerprint != latest.assetFingerprint)
    }

    fun serializeOwnershipManifest(files: Map<String, String>): String = files.toSortedMap().entries
        .joinToString("\n") { (path, fingerprint) -> "$fingerprint\t$path" }

    fun parseOwnershipManifest(lines: List<String>): List<OwnedFile> = lines.mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val separator = line.indexOf('\t')
        val candidate = if (separator < 0) OwnedFile(line, null)
            else OwnedFile(line.substring(separator + 1), line.substring(0, separator))
        candidate.takeIf { safeRelativePath(it.path) }
    }

    fun shouldDeleteOwnedFile(owned: OwnedFile, otherManifests: Set<String>, currentFingerprint: String?): Boolean =
        safeRelativePath(owned.path) && owned.path !in otherManifests &&
            owned.sha256 != null && currentFingerprint == owned.sha256

    private fun safeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
            path.none { it.code < 0x20 || it.code == 0x7f } &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() }

    private fun isProtectedConfig(path: String): Boolean = isGlobalConfig(path) || path.startsWith("custom/")

    fun isExplicitlyUninstalled(schemaId: String, tombstones: Set<String>): Boolean = schemaId in tombstones

    fun releaseTagFromUrl(url: String): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return null
        val marker = "/releases/tag/"
        val start = path.indexOf(marker)
        if (start < 0) return null
        val raw = path.substring(start + marker.length).substringBefore('/')
            .takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Validate the ZIP central directory, every entry's size and CRC, path safety, required
     * package markers and (when requested) a non-empty version.txt. Extract only to stagingDir.
     * Paths such as rime/foo.yaml are rooted at installRoot/foo.yaml; custom/ is preserved.
     */
    fun extractValidatedZip(
        zipFile: File,
        stagingDir: File,
        installRoot: File,
        expectedFiles: Set<String>,
        requireVersionFile: Boolean,
        stripTopDir: Boolean,
    ): List<String> {
        if (!zipFile.isFile || zipFile.length() <= 0L) throw IllegalArgumentException("ZIP 文件为空")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            throw IllegalStateException("无法创建临时解压目录")
        }
        val installCanonical = installRoot.canonicalFile
        val entries = ZipFile(zipFile).use { archive ->
            val allEntries = archive.entries().toListCompat()
            if (allEntries.none { !it.isDirectory }) throw IllegalArgumentException("ZIP 中没有方案文件")
            val prefix = if (stripTopDir) commonTopDirectory(allEntries.map { it.name }) else null
            val normalized = allEntries.mapNotNull { entry ->
                val name = normalizeEntryName(entry.name, prefix) ?: return@mapNotNull null
                if (isReservedInstallPath(name)) {
                    throw IllegalArgumentException("ZIP 包含方案商店保留路径：$name")
                }
                entry to name
            }
            val presentFiles = normalized.filterNot { it.first.isDirectory }.map { it.second }.toSet()
            val missing = expectedFiles.filterNot { expected ->
                val normalizedExpected = expected.removePrefix("rime/")
                normalizedExpected in presentFiles
            }
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException("ZIP 缺少预期方案文件：${missing.joinToString()}")
            }

            if (requireVersionFile) {
                val versionEntry = normalized.firstOrNull { (entry, path) ->
                    !entry.isDirectory && path == "version.txt"
                }?.first ?: throw IllegalArgumentException("ZIP 缺少 version.txt")
                val versionBytes = archive.getInputStream(versionEntry).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(256)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_VERSION_FILE_BYTES) {
                            throw IllegalArgumentException("version.txt 异常过大")
                        }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                if (versionBytes.toString(Charsets.UTF_8.name()).trim().isEmpty()) {
                    throw IllegalArgumentException("version.txt 为空")
                }
            }

            val outputPaths = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            normalized.forEach { (entry, path) ->
                if (!seen.add(path)) throw IllegalArgumentException("ZIP 包含重复路径：$path")

                // Existing global/user configuration is validated as package content but kept intact.
                val liveTarget = File(installCanonical, path)
                ensureInside(installCanonical, liveTarget)
                val installable = !entry.isDirectory && !isUserDataPath(path) &&
                    !(isProtectedConfig(path) && liveTarget.exists())
                val target = if (installable) File(stagingDir, path) else null
                if (target != null) {
                    ensureInside(stagingDir.canonicalFile, target)
                    target.parentFile?.mkdirs()
                }
                val crc = CRC32()
                var byteCount = 0L
                archive.getInputStream(entry).use { input ->
                    val output = target?.let(::FileOutputStream)
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output?.write(buffer, 0, n)
                            crc.update(buffer, 0, n)
                            byteCount += n
                        }
                    } finally {
                        output?.close()
                    }
                }
                if (entry.size >= 0 && byteCount != entry.size) {
                    throw IllegalArgumentException("ZIP 文件大小校验失败：$path")
                }
                if (entry.crc >= 0 && crc.value != entry.crc) {
                    throw IllegalArgumentException("ZIP CRC 校验失败：$path")
                }
                if (target != null) outputPaths += path
            }
            outputPaths
        }
        return entries
    }

    private fun commonTopDirectory(names: List<String>): String? {
        val nonEmpty = names.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return null
        val cleaned = nonEmpty.map { it.trimEnd('/') }
        val firstSegments = cleaned.map { it.substringBefore('/') }
        val candidate = firstSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (firstSegments.any { it != candidate }) return null
        if (cleaned.any { name ->
                !name.startsWith("$candidate/") && name != candidate
            }) return null
        // A top-level plain file means there is no wrapper directory to strip.
        if (cleaned.any { name -> !name.contains('/') && name != candidate }) return null
        return "$candidate/"
    }

    private fun normalizeEntryName(rawName: String, topPrefix: String?): String? {
        if (rawName.isBlank()) return null
        val slashName = rawName.replace('\\', '/')
        if (slashName.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(slashName)) {
            throw IllegalArgumentException("ZIP 包含绝对路径：$rawName")
        }
        var name = if (topPrefix != null) slashName.removePrefix(topPrefix) else slashName
        name = name.removePrefix("rime/")
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw IllegalArgumentException("ZIP 包含路径穿越：$rawName")
        if (segments.isEmpty()) return null
        val normalized = segments.joinToString("/")
        if (!safeRelativePath(normalized)) throw IllegalArgumentException("ZIP 包含非法路径：$rawName")
        return normalized
    }

    private fun ensureInside(root: File, target: File) {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (!targetPath.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("目标路径越界：$target")
        }
    }

    fun isSafeTarget(root: File, target: File): Boolean = runCatching {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        targetPath.startsWith(rootPath + File.separator)
    }.getOrDefault(false)

    private fun isGlobalConfig(path: String): Boolean =
        !path.contains('/') && path in globalConfigFiles

    private fun isReservedInstallPath(path: String): Boolean = when (path.substringBefore('/')) {
        ".meng_schema_versions", ".meng_schema_fingerprints", ".meng_schema_uninstalled",
        ".meng_schema_manifest" -> true
        else -> path.substringBefore('/').startsWith(".meng-stage-")
    }

    fun isSafeArtifactName(name: String): Boolean =
        safeRelativePath(name) && '/' !in name && !isReservedInstallPath(name)

    private fun isUserDataPath(path: String): Boolean {
        val segments = path.split('/')
        if (segments.firstOrNull() == "build" || "build" in segments) return true
        val lower = path.lowercase()
        return lower.contains(".userdb") || lower.contains(".user.") ||
            lower.endsWith(".user.txt") || lower.contains("userdict") ||
            lower.endsWith("_user.txt")
    }

    fun validateSingleFile(file: File, expectedFingerprint: String? = null): String {
        if (!file.isFile || file.length() <= 0L) throw IllegalArgumentException("下载文件为空")
        val prefix = ByteArray(512)
        val count = file.inputStream().use { it.read(prefix) }.coerceAtLeast(0)
        val head = prefix.copyOf(count).toString(Charsets.UTF_8.name())
            .trimStart('\uFEFF', '\u0000', ' ', '\t', '\r', '\n')
            .take(256).lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html") ||
            head.startsWith("<head") || head.startsWith("<body") ||
            head.startsWith("<?xml") && head.contains("html")) {
            throw IllegalArgumentException("服务器返回了网页而不是模型文件")
        }
        val hash = sha256(file)
        val expectedHash = expectedFingerprint?.let {
            if (it.startsWith("sha256:", ignoreCase = true)) it.substringAfter(':') else it
        }
        val expectedSize = expectedFingerprint?.takeIf { it.startsWith("asset:") }
            ?.substringAfterLast(':')?.toLongOrNull()?.takeIf { it > 0L }
        if (expectedSize != null && file.length() != expectedSize) {
            throw IllegalArgumentException("模型文件大小与上游资产记录不符")
        }
        if (expectedHash != null && expectedHash.matches(Regex("(?i)[0-9a-f]{64}")) &&
            !hash.equals(expectedHash, ignoreCase = true)) {
            throw IllegalArgumentException("模型 SHA-256 校验失败")
        }
        return "sha256:$hash"
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class Replacement(val staged: File?, val target: File)

    /** Replace or delete a set of files, rolling every earlier replacement back on any failure. */
    fun applyAtomically(replacements: List<Replacement>) {
        if (replacements.isEmpty()) return
        val canonicalTargets = replacements.map { it.target.canonicalPath }
        require(canonicalTargets.distinct().size == canonicalTargets.size) { "重复事务目标" }
        val backups = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        var committed = false
        try {
            replacements.forEach { replacement ->
                val target = replacement.target
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("无法创建目录：$it") }
                if (target.isDirectory) error("事务目标是目录：$target")
                val backup = File(target.parentFile, ".${target.name}.backup-${UUID.randomUUID()}")
                if (target.exists()) {
                    if (!target.renameTo(backup)) error("无法备份旧文件：$target")
                    backups += backup to target
                }
                val staged = replacement.staged
                if (staged != null) {
                    if (!staged.isFile || !staged.renameTo(target)) error("无法安装新文件：$target")
                    installed += target
                }
            }
            committed = true
        } catch (error: Throwable) {
            installed.asReversed().forEach { runCatching { it.delete() } }
            backups.asReversed().forEach { (backup, target) ->
                runCatching {
                    target.parentFile?.mkdirs()
                    if (backup.exists()) backup.renameTo(target)
                }
            }
            throw error
        } finally {
            // On rollback, keep any backup whose restore itself failed; never discard the last copy.
            if (committed) backups.forEach { (backup, _) -> runCatching { backup.deleteRecursively() } }
            replacements.forEach { replacement ->
                replacement.staged?.let { runCatching { it.deleteRecursively() } }
            }
        }
    }

    private fun java.util.Enumeration<java.util.zip.ZipEntry>.toListCompat(): List<java.util.zip.ZipEntry> {
        val result = mutableListOf<java.util.zip.ZipEntry>()
        while (hasMoreElements()) result += nextElement()
        return result
    }
}
