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
    private const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_YAML_BYTES = 1024 * 1024
    private val SCHEMA_ID = Regex("[A-Za-z0-9_.-]{1,64}")
    const val SCHEMA_LIST_BEGIN = "# >>> meng-schema-market：方案商店自动维护，请勿手改此块"
    const val SCHEMA_LIST_END = "# <<< meng-schema-market"

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

    fun sourceWithReleaseProbe(source: SchemaSource, version: String?, checked: Boolean, digest: String? = null): SchemaSource =
        source.copy(
            version = version?.removePrefix("v") ?: source.version,
            versionCheckFailed = !checked,
            archiveDigest = digest.takeIf { checked },
        )

    fun sourceWithAssetProbe(
        source: SchemaSource,
        fingerprint: String?,
        checked: Boolean,
        displayVersion: String? = null,
    ): SchemaSource = source.copy(
        assetFingerprint = fingerprint,
        versionCheckFailed = !checked,
        displayVersion = displayVersion ?: source.displayVersion,
    )

    /**
     * git 源：把分支 HEAD 落地为可锚定版本。
     * 展示用 commit 日期（如 2026-06-30，直观），判定用完整 SHA（assetFingerprint =
     * "git:<full sha>"），使 hasUpdate 走指纹比对——同一天多次提交也能准确判更新。
     */
    fun sourceWithGitHead(
        source: SchemaSource,
        sha: String?,
        date: String?,
        checked: Boolean,
    ): SchemaSource {
        val fullSha = sha?.takeIf { it.isNotBlank() && it.length >= 7 } ?: return source.copy(versionCheckFailed = !checked)
        val shortSha = fullSha.take(7)
        val shownVersion = date?.takeIf { it.isNotBlank() } ?: "git·$shortSha"
        return source.copy(
            version = "git·$shortSha",
            displayVersion = shownVersion,
            assetFingerprint = "git:$fullSha",
            versionCheckFailed = !checked,
        )
    }

    fun hasUpdate(
        installedVersion: String?,
        latest: SchemaSource,
        installedFingerprint: String? = null,
    ): Boolean {
        if (installedVersion == null || latest.versionCheckFailed) return false
        // 资产型/LTS 源（如 lmdg、git 源）：tag 恒定或 commit 频繁，版本号字符串比对没有意义，
        // 以资产指纹（SHA-256 / git:全SHA）是否变化为准；指纹相同即无更新。
        if (latest.assetFingerprint != null) {
            return if (installedFingerprint != null) {
                installedFingerprint != latest.assetFingerprint
            } else {
                // 历史遗留安装未记录指纹（如恒定 LTS tag 的 lmdg，或从旧版升级的 git 源）：
                // 无法确认内容是否最新，视为可更新——点一次更新装好即记录指纹，之后判据恢复精确。
                true
            }
        }
        return installedVersion != latest.version
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

    /**
     * 已存在时不覆盖的文件：Rime 全局配置、custom/ 目录，以及用户自己维护的补丁和短语
     * （任意层级的 *.custom.yaml、custom_phrase*.txt）。雾凇一类方案的用户正是在这些文件里加词、改配置。
     */
    fun isProtectedConfig(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return isGlobalConfig(path) || path.startsWith("custom/") ||
            name.endsWith(".custom.yaml") || (name.startsWith("custom_phrase") && name.endsWith(".txt"))
    }

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
        /** 受保护但可以更新的路径：现有内容与本方案上次安装时完全一致（用户没改过），更新时照常替换。 */
        replaceableProtected: Set<String> = emptySet(),
        /** 解压总量上限，防 zip 炸弹把存储写满。 */
        maxTotalBytes: Long = MAX_EXTRACT_BYTES,
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
                if (versionBytes.toString(Charsets.UTF_8).trim().isEmpty()) {
                    throw IllegalArgumentException("version.txt 为空")
                }
            }

            val outputPaths = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var totalBytes = 0L
            normalized.forEach { (entry, path) ->
                if (!seen.add(path)) throw IllegalArgumentException("ZIP 包含重复路径：$path")

                // Existing global/user configuration is validated as package content but kept intact.
                val liveTarget = File(installCanonical, path)
                ensureInside(installCanonical, liveTarget)
                val installable = !entry.isDirectory && !isUserDataPath(path) &&
                    !(isProtectedConfig(path) && liveTarget.exists() && path !in replaceableProtected)
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
                            totalBytes += n
                            if (totalBytes > maxTotalBytes) throw IllegalArgumentException("ZIP 解压后体积异常过大，已拒绝")
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

    /** 读取 ZIP 内（规范化后）某个路径的文本，用于读包里的 default.yaml；不存在返回 null。 */
    fun readZipText(zipFile: File, wanted: String, stripTopDir: Boolean): String? = ZipFile(zipFile).use { archive ->
        val all = archive.entries().toListCompat()
        val prefix = if (stripTopDir) commonTopDirectory(all.map { it.name }) else null
        val entry = all.firstOrNull { !it.isDirectory && runCatching { normalizeEntryName(it.name, prefix) }.getOrNull() == wanted }
            ?: return@use null
        if (entry.size > MAX_YAML_BYTES) return@use null
        archive.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /**
     * 从 default.yaml 文本里取 schema_list 的方案 id（只认 `- schema: xxx` / `- {schema: xxx}`，注释行跳过）。
     * 只做逐行解析，不引入 YAML 库；遇到下一个顶层键即结束。
     */
    fun parseSchemaList(yaml: String): List<String> {
        val result = mutableListOf<String>()
        var inList = false
        val item = Regex("""^\s*-\s*\{?\s*schema\s*:\s*["']?([A-Za-z0-9_.-]+)""")
        yaml.lineSequence().forEach { raw ->
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            if (!line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("-")) {
                inList = line.trim().removeSurrounding("\"").startsWith("schema_list") && line.trim().endsWith(":")
                return@forEach
            }
            if (inList) item.find(line)?.groupValues?.get(1)?.let { if (it !in result) result += it }
        }
        return result
    }

    /**
     * 把方案商店需要追加的方案写进 default.custom.yaml 的托管块（`schema_list/+` 只追加，不动用户已有的列表）。
     * 返回新文件内容；返回 null 表示文件结构无法安全改写（如 patch 写成了行内 `{}`），调用方应提示用户手动添加。
     */
    fun rewriteSchemaListPatch(existing: String?, append: List<String>): String? {
        val ids = append.filter { SCHEMA_ID.matches(it) }.distinct()
        val lines = (existing ?: "").lines().toMutableList()
        // 先去掉旧的托管块
        val begin = lines.indexOfFirst { it.trim() == SCHEMA_LIST_BEGIN }
        if (begin >= 0) {
            val end = lines.subList(begin, lines.size).indexOfFirst { it.trim() == SCHEMA_LIST_END }
            if (end < 0) return null
            repeat(end + 1) { lines.removeAt(begin) }
        }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (ids.isEmpty()) return if (existing == null) null else lines.joinToString("\n", postfix = "\n")
        var patchIdx = lines.indexOfFirst { it.trimEnd() == "patch:" }
        if (patchIdx < 0 && lines.any { it.startsWith("patch:") }) return null // 行内写法，不安全
        if (patchIdx < 0) {
            if (lines.isNotEmpty()) lines += ""
            lines += "patch:"
            patchIdx = lines.size - 1
        }
        // 沿用 patch 下第一个键的缩进
        val indent = lines.drop(patchIdx + 1).firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.takeWhile { it == ' ' }?.takeIf { it.isNotEmpty() } ?: "  "
        val block = mutableListOf("$indent$SCHEMA_LIST_BEGIN", "$indent\"schema_list/+\":")
        ids.forEach { block += "$indent  - schema: $it" }
        block += "$indent$SCHEMA_LIST_END"
        lines.addAll(patchIdx + 1, block)
        return lines.joinToString("\n", postfix = "\n")
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
        ".meng_schema_manifest", ".meng_schema_lists" -> true
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
        val head = prefix.copyOf(count).toString(Charsets.UTF_8)
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

    /** 按 ZIP 魔数（PK\x03\x04 / PK\x05\x06 / PK\x07\x08）判断文件是否为 zip 包。 */
    fun isZipFile(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 4) return false
        file.inputStream().use { input ->
            val head = ByteArray(4)
            val n = input.read(head)
            n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                (head[2] == 0x03.toByte() && head[3] == 0x04.toByte() ||
                    head[2] == 0x05.toByte() && head[3] == 0x06.toByte() ||
                    head[2] == 0x07.toByte() && head[3] == 0x08.toByte())
        }
    }.getOrDefault(false)

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

    private fun java.util.Enumeration<out java.util.zip.ZipEntry>.toListCompat(): List<java.util.zip.ZipEntry> {
        val result = mutableListOf<java.util.zip.ZipEntry>()
        while (hasMoreElements()) result += nextElement()
        return result
    }
}
