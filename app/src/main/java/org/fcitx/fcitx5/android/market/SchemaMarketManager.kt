/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 方案商店执行器：网络探测、安装事务和本地安装状态均由此处统一管理。 */
object SchemaMarketManager {
    private const val VERSION_FILE_NAME = ".meng_schema_versions"
    private const val FINGERPRINT_FILE_NAME = ".meng_schema_fingerprints"
    private const val MANIFEST_DIR_NAME = ".meng_schema_manifest"
    private const val UNINSTALLED_FILE_NAME = ".meng_schema_uninstalled"
    private const val RELEASE_CACHE = "schema_market_release_cache"
    private const val SLOW_THRESHOLD_MS = 3000L
    private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16

    data class ReleaseProbe(val version: String?, val checked: Boolean)
    data class AssetFingerprintProbe(val fingerprint: String?, val checked: Boolean, val version: String? = null)
    data class GitHeadProbe(val version: String?, val date: String?, val checked: Boolean)

    /** Query the latest tag, then try GitHub's /releases/latest redirect if the API is unavailable. */
    suspend fun fetchLatestRelease(
        source: SchemaSource,
        context: Context = appContext,
    ): ReleaseProbe = withContext(Dispatchers.IO) {
        val verified = fetchApiReleaseTag(source) ?: fetchRedirectReleaseTag(source)
        val prefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val cacheKey = "release_${source.owner}_${source.repo}"
        if (verified != null) {
            prefs.edit().putString(cacheKey, verified).apply()
            ReleaseProbe(verified, checked = true)
        } else {
            val lastKnown = prefs.getString(cacheKey, null)
            ReleaseProbe(lastKnown, checked = false)
        }
    }

    private fun fetchApiReleaseTag(source: SchemaSource): String? = runCatching {
        val conn = URL(source.releaseApiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.w("Release probe HTTP $code for ${source.releaseApiUrl}")
                return@runCatching null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                .optString("tag_name").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        Timber.w(it, "Release API probe failed: ${source.releaseApiUrl}")
        null
    }

    private fun fetchRedirectReleaseTag(source: SchemaSource): String? {
        val latestUrl = "https://github.com/${source.owner}/${source.repo}/releases/latest"
        fun request(method: String): String? = runCatching {
            val conn = URL(latestUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            if (method == "GET") conn.setRequestProperty("Range", "bytes=0-0")
            try {
                val code = conn.responseCode
                if (code !in 200..399) return@runCatching null
                if (method == "GET") runCatching { conn.inputStream.use { it.read() } }
                SchemaMarketFileOps.releaseTagFromUrl(conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Timber.w(it, "Release redirect probe failed: $latestUrl ($method)")
            null
        }
        return request("HEAD") ?: request("GET")
    }

    /** Read the current digest/asset revision for an asset on a release that remains tagged LTS. */
    suspend fun fetchReleaseAssetFingerprint(
        source: SchemaSource,
        context: Context = appContext,
    ): AssetFingerprintProbe = withContext(Dispatchers.IO) {
        val endpoint = "https://api.github.com/repos/${source.owner}/${source.repo}/releases/tags/${source.version}"
        val found = runCatching {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets") ?: return@runCatching null
                val asset = (0 until assets.length()).asSequence()
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == source.assetName }
                    ?: return@runCatching null
                val digest = asset.optString("digest").takeIf {
                    it.startsWith("sha256:", ignoreCase = true) && it.substringAfter(':').matches(Regex("(?i)[0-9a-f]{64}"))
                }
                // LTS tag 永远叫 LTS：用资产更新时间（如 2026-11-14）作为可锚定的版本号，
                // 替代永远不变的 "LTS"，让用户看到具体版本。
                val updatedAt = asset.optString("updated_at").takeIf { it.isNotBlank() }
                    ?.substringBefore('T') ?: null
                Triple(
                    digest ?: "asset:${asset.optString("id")}:${asset.optString("updated_at")}:${asset.optLong("size", -1)}",
                    updatedAt,
                    asset.optLong("size", -1),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Timber.w(it, "Release asset fingerprint probe failed: $endpoint")
            null
        }
        val prefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val key = "asset_${source.owner}_${source.repo}_${source.assetName}"
        if (found != null) {
            prefs.edit().putString(key, found.first).apply()
            AssetFingerprintProbe(found.first, checked = true, version = found.second)
        } else {
            AssetFingerprintProbe(prefs.getString(key, null), checked = false)
        }
    }

    /**
     * Query the current HEAD commit of a git-source branch.
     * 返回 commit 日期（给人看，如 2026-06-30）与完整 SHA（给更新判定用）。
     */
    suspend fun fetchGitHead(
        source: SchemaSource,
        context: Context = appContext,
    ): GitHeadProbe = withContext(Dispatchers.IO) {
        val endpoint = "https://api.github.com/repos/${source.owner}/${source.repo}/commits/${source.gitBranch}"
        val verified = runCatching {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val sha = json.optString("sha").takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return@runCatching null
                // commit.committer.date 形如 2026-06-30T12:34:56Z -> 取日期部分
                val date = json.optJSONObject("commit")?.optJSONObject("committer")
                    ?.optString("date")?.takeIf { it.isNotBlank() }?.substringBefore('T')
                sha to date
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Timber.w(it, "Git head probe failed: $endpoint")
            null
        }
        val prefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val shaKey = "githead_${source.owner}_${source.repo}_${source.gitBranch}"
        val dateKey = "gitdate_${source.owner}_${source.repo}_${source.gitBranch}"
        if (verified != null) {
            prefs.edit()
                .putString(shaKey, verified.first)
                .putString(dateKey, verified.second)
                .apply()
            GitHeadProbe(verified.first, verified.second, checked = true)
        } else {
            GitHeadProbe(prefs.getString(shaKey, null), prefs.getString(dateKey, null), checked = false)
        }
    }

    /** 并发探测该源所有镜像的连通性（节省时间，串行会因单个镜像超时拖慢界面）。 */
    suspend fun probeLinks(source: SchemaSource): SchemaSource = withContext(Dispatchers.IO) {
        val updated = source.mirrors.map { mirror ->
            mirror to async { probeOne(mirror.url) }
        }.map { (mirror, deferred) ->
            val result = deferred.await()
            mirror.copy(status = result.status, latencyMs = result.latencyMs)
        }
        source.copy(mirrors = updated)
    }

    private data class ProbeResult(val status: MirrorStatus, val latencyMs: Long?)

    private fun probeOne(url: String): ProbeResult {
        val latency = requestProbe(url, "HEAD") ?: requestProbe(url, "GET")
        if (latency == null) return ProbeResult(MirrorStatus.FAIL, null)
        return ProbeResult(
            if (latency > SLOW_THRESHOLD_MS) MirrorStatus.SLOW else MirrorStatus.OK,
            latency,
        )
    }

    private fun requestProbe(url: String, method: String): Long? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.instanceFollowRedirects = true
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
        if (method == "GET") conn.setRequestProperty("Range", "bytes=0-0")
        val start = System.currentTimeMillis()
        try {
            val code = conn.responseCode
            if (code !in 200..399) return@runCatching null
            if (method == "GET") runCatching { conn.inputStream.use { it.read() } }
            System.currentTimeMillis() - start
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        Timber.w(it, "Probe $method failed: $url")
        null
    }

    /** Pick the first-ranked mirror for display; installs iterate over all ranked mirrors. */
    fun pickBestMirror(source: SchemaSource): String? =
        SchemaMarketFileOps.orderedMirrors(source.mirrors).firstOrNull()?.url

    /** Download to a unique temporary file; the live target is not touched by this function. */
    private fun downloadToTemp(
        url: String,
        temp: File,
        onProgress: (Long, Long?) -> Unit,
    ): File? {
        temp.delete()
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Timber.w(error, "Could not open download URL: $url")
            return null
        }
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.w("Download HTTP $code for $url")
                null
            } else if (conn.contentType?.contains("text/html", ignoreCase = true) == true) {
                Timber.w("Download returned HTML for $url")
                null
            } else {
                val total = conn.contentLengthLong.takeIf { it > 0 }
                var downloaded = 0L
                temp.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                        output.fd.sync()
                    }
                }
                if (downloaded == 0L || (total != null && downloaded != total)) {
                    temp.delete()
                    Timber.w("Incomplete download from $url: $downloaded / $total")
                    null
                } else temp
            }
        } catch (error: Exception) {
            temp.delete()
            Timber.w(error, "Download failed: $url")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Install one release artifact; mirror attempts continue until download and validation succeed. */
    suspend fun downloadAndInstall(
        source: SchemaSource,
        onProgress: (Long, Long?) -> Unit,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                return@withContext InstallResult(false, "无法创建方案数据目录", source.version)
            }
            rimeLuaDir(context).mkdirs()
            val mirrors = if (source.isGitSource) {
                listOf(SchemaMirror("GitHub 源码", source.gitUrl))
            } else source.mirrors
            var lastError = "下载失败，请检查网络或换镜像重试"
            // 安装记录用可锚定版本（如 LTS 资产日期 / git 短 SHA），让「已安装 vX」显示真实版本，而非恒定的 LTS tag
            val recordVersion = source.displayVersion ?: source.version
            val installed = SchemaMarketFileOps.firstSuccessfulMirror(mirrors) { mirror ->
                val temp = createTempFile(dataDir, source.schemaId, "download", ".part")
                try {
                    val downloaded = downloadToTemp(mirror.url, temp, onProgress) ?: return@firstSuccessfulMirror null
                    if (isSingleFileAsset(source.assetName)) {
                        val expected = source.assetFingerprint.takeUnless { source.versionCheckFailed }
                        val contentFingerprint = SchemaMarketFileOps.validateSingleFile(downloaded, expected)
                        val recordedFingerprint = if (source.versionCheckFailed) contentFingerprint
                            else source.assetFingerprint ?: contentFingerprint
                        installSingleArtifact(
                            downloaded, dataDir, source.schemaId, source.assetName,
                            recordVersion, recordedFingerprint, context,
                        )
                    } else {
                        installArchive(downloaded, source.copy(version = recordVersion), context)
                    }
                    InstallResult(true, null, recordVersion)
                } catch (error: Exception) {
                    lastError = error.message ?: "文件校验或安装失败"
                    Timber.w(error, "Schema install attempt failed from ${mirror.url}")
                    null
                } finally {
                    temp.delete()
                }
            }
            installed?.second ?: InstallResult(false, lastError, source.version)
        }
    }

    /** Install a standalone model asset, preserving the old file until validation and commit complete. */
    suspend fun downloadAndInstallExtra(
        extra: SchemaExtraAsset,
        onProgress: (Long, Long?) -> Unit,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            dataDir.mkdirs()
            val urls = extraMirrorUrls(extra)
            val mirrors = urls.mapIndexed { index, url -> SchemaMirror(if (index == 0) "官方源" else "镜像 $index", url) }
            var lastError = "下载失败，请检查网络或换镜像重试"
            val installed = SchemaMarketFileOps.firstSuccessfulMirror(mirrors) { mirror ->
                val temp = createTempFile(dataDir, extra.schemaId, "download", ".part")
                try {
                    val downloaded = downloadToTemp(mirror.url, temp, onProgress) ?: return@firstSuccessfulMirror null
                    val fingerprint = SchemaMarketFileOps.validateSingleFile(downloaded)
                    installSingleArtifact(
                        downloaded, dataDir, extra.schemaId, extra.assetName,
                        extra.version, fingerprint, context,
                    )
                    InstallResult(true, null, extra.version)
                } catch (error: Exception) {
                    lastError = error.message ?: "模型文件校验或安装失败"
                    Timber.w(error, "Extra asset install attempt failed from ${mirror.url}")
                    null
                } finally {
                    temp.delete()
                }
            }
            installed?.second ?: InstallResult(false, lastError, extra.version)
        }
    }

    private fun extraMirrorUrls(extra: SchemaExtraAsset): List<String> {
        val base = "https://github.com/${extra.owner}/${extra.repo}/releases/download/${extra.tag}/${extra.assetName}"
        return listOf(base) + listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh.chjina.com/",
            "https://gh.monlor.com/",
            "https://gh.nxnow.top/",
            "https://ghproxy.cfd/",
            "https://github.ednovas.xyz/",
        ).map { it + base }
    }

    private fun isSingleFileAsset(assetName: String): Boolean =
        assetName.endsWith(".gram", ignoreCase = true) || assetName.endsWith(".txt", ignoreCase = true)

    private fun installArchive(zipFile: File, source: SchemaSource, context: Context) {
        val dataDir = rimeDataDir(context)
        val staging = File(dataDir, ".meng-stage-${UUID.randomUUID()}")
        try {
            val files = SchemaMarketFileOps.extractValidatedZip(
                zipFile = zipFile,
                stagingDir = staging,
                installRoot = dataDir,
                expectedFiles = source.expectedFiles,
                requireVersionFile = source.requireVersionFile,
                stripTopDir = source.stripTopDir,
            )
            if (files.isEmpty()) throw IllegalArgumentException("ZIP 中没有可安装的方案文件")
            val ownership = files.associateWith { path ->
                "sha256:${SchemaMarketFileOps.sha256(File(staging, path))}"
            }
            val replacements = files.map { relative ->
                SchemaMarketFileOps.Replacement(File(staging, relative), File(dataDir, relative))
            }.toMutableList()
            replacements += installStateReplacements(
                context, source.schemaId, source.version, source.assetFingerprint, ownership,
            )
            applyInDataDir(dataDir, replacements)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installSingleArtifact(
        staged: File,
        dataDir: File,
        schemaId: String,
        assetName: String,
        version: String,
        fingerprint: String,
        context: Context = appContext,
    ) {
        require(SchemaMarketFileOps.isSafeArtifactName(assetName)) { "非法资产文件名" }
        val target = File(dataDir, assetName)
        val replacements = mutableListOf(SchemaMarketFileOps.Replacement(staged, target))
        val ownership = mapOf(assetName to "sha256:${SchemaMarketFileOps.sha256(staged)}")
        replacements += installStateReplacements(context, schemaId, version, fingerprint, ownership)
        applyInDataDir(dataDir, replacements)
    }

    private fun installStateReplacements(
        context: Context,
        schemaId: String,
        version: String,
        fingerprint: String?,
        ownership: Map<String, String>,
    ): List<SchemaMarketFileOps.Replacement> {
        val dataDir = rimeDataDir(context)
        val versions = readKeyValues(File(dataDir, VERSION_FILE_NAME)).toMutableMap().apply { put(schemaId, version) }
        val fingerprints = readKeyValues(File(dataDir, FINGERPRINT_FILE_NAME)).toMutableMap().apply {
            remove(schemaId)
            if (fingerprint != null) put(schemaId, fingerprint)
        }
        val uninstalled = readLines(File(dataDir, UNINSTALLED_FILE_NAME)).toMutableSet().apply { remove(schemaId) }
        val manifestDir = File(dataDir, MANIFEST_DIR_NAME)
        if (!manifestDir.exists() && !manifestDir.mkdirs()) error("无法创建安装清单目录")
        val manifest = File(manifestDir, "$schemaId.txt")
        return listOf(
            stagedTextReplacement(dataDir, File(dataDir, VERSION_FILE_NAME), serializeKeyValues(versions)),
            stagedTextReplacement(dataDir, File(dataDir, FINGERPRINT_FILE_NAME), serializeKeyValues(fingerprints)),
            stagedTextReplacement(dataDir, manifest, SchemaMarketFileOps.serializeOwnershipManifest(ownership)),
            stagedTextReplacement(dataDir, File(dataDir, UNINSTALLED_FILE_NAME), uninstalled.sorted().joinToString("\n")),
        )
    }

    private fun stagedTextReplacement(root: File, target: File, content: String): SchemaMarketFileOps.Replacement {
        val staged = createTempFile(root, "meta", "state", ".tmp")
        FileOutputStream(staged).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        return SchemaMarketFileOps.Replacement(staged, target)
    }

    private fun applyInDataDir(dataDir: File, replacements: List<SchemaMarketFileOps.Replacement>) {
        try {
            require(replacements.all { SchemaMarketFileOps.isSafeTarget(dataDir, it.target) }) {
                "事务目标超出方案数据目录"
            }
            SchemaMarketFileOps.applyAtomically(replacements)
        } finally {
            replacements.forEach { replacement ->
                replacement.staged?.let { staged -> runCatching { staged.deleteRecursively() } }
            }
        }
    }

    private fun createTempFile(parent: File, id: String, kind: String, suffix: String): File {
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "schema" }
        return File.createTempFile(".meng-$safeId-$kind-", suffix, parent)
    }

    /** Local SAF import uses exactly the same archive validator and commit transaction as downloads. */
    suspend fun importZip(
        source: SchemaSource,
        zipUri: android.net.Uri,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                return@withContext InstallResult(false, "无法创建方案数据目录", source.version)
            }
            val tempZip = createTempFile(dataDir, source.schemaId, "import", ".zip")
            return@withContext try {
                val input = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext InstallResult(false, "无法打开所选文件", source.version)
                input.use { sourceStream ->
                    FileOutputStream(tempZip).use { output ->
                        sourceStream.copyTo(output)
                        output.fd.sync()
                    }
                }
                installArchive(tempZip, source, context)
                InstallResult(true, null, source.version)
            } catch (error: Exception) {
                Timber.e(error, "Schema import failed: ${source.schemaId}")
                InstallResult(false, error.message ?: "ZIP 校验或安装失败", source.version)
            } finally {
                tempZip.delete()
            }
        }
    }

    fun rimeDataDir(context: Context = appContext): File {
        val dataDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "data")
        return File(dataDir, "rime")
    }

    fun rimeLuaDir(context: Context = appContext): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "data"), "lua")

    fun installedVersions(context: Context = appContext): Map<String, String> =
        readKeyValues(File(rimeDataDir(context), VERSION_FILE_NAME))

    fun installedFingerprints(context: Context = appContext): Map<String, String> =
        readKeyValues(File(rimeDataDir(context), FINGERPRINT_FILE_NAME))

    private fun readKeyValues(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        }.getOrElse {
            Timber.w(it, "Failed to read ${file.name}")
            emptyMap()
        }
    }

    private fun serializeKeyValues(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }

    private fun readLines(file: File): List<String> =
        if (!file.isFile) emptyList() else runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun readManifest(context: Context, schemaId: String): List<SchemaMarketFileOps.OwnedFile> {
        val file = File(File(rimeDataDir(context), MANIFEST_DIR_NAME), "$schemaId.txt")
        return SchemaMarketFileOps.parseOwnershipManifest(readLines(file))
    }

    private fun uninstalledIds(context: Context): Set<String> =
        readLines(File(rimeDataDir(context), UNINSTALLED_FILE_NAME)).toSet()

    /** Installed status is based on real artifact files, with an explicit uninstall tombstone. */
    fun realInstalledVersion(schemaId: String, context: Context = appContext): String? {
        if (SchemaMarketFileOps.isExplicitlyUninstalled(schemaId, uninstalledIds(context))) return null
        val dataDir = rimeDataDir(context)
        if (!dataDir.exists()) return null
        val versions = installedVersions(context)
        fun exists(name: String) = File(dataDir, name).let { it.isFile && it.length() > 0L }
        return when (schemaId) {
            "wanxiang" -> if (exists("wanxiang.schema.yaml")) {
                File(dataDir, "version.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
                    ?: versions[schemaId] ?: "已安装"
            } else null
            "wanxiang-lmdg" -> if (exists(SchemaSources.LMDG_ASSET)) versions[schemaId] ?: SchemaSources.LMDG_TAG else null
            "rime_frost" -> if (exists("rime_frost.schema.yaml")) versions[schemaId] ?: "已安装" else null
            "rime_ice" -> if (versions[schemaId] != null || exists("double_pinyin.schema.yaml")) versions[schemaId] ?: "已安装" else null
            "oh_my_rime" -> if (versions[schemaId] != null || readManifest(context, schemaId).isNotEmpty()) versions[schemaId] ?: "已安装" else null
            else -> if (readManifest(context, schemaId).isNotEmpty()) versions[schemaId] ?: "已安装" else null
        }
    }

    fun isInstalled(schemaId: String, context: Context = appContext): Boolean =
        realInstalledVersion(schemaId, context) != null

    fun hasUpdate(source: SchemaSource, context: Context = appContext): Boolean =
        SchemaMarketFileOps.hasUpdate(
            realInstalledVersion(source.schemaId, context), source,
            installedFingerprints(context)[source.schemaId],
        )

    fun isExtraInstalled(extra: SchemaExtraAsset, context: Context = appContext): Boolean =
        realInstalledVersion(extra.schemaId, context) != null

    fun hasExtraUpdate(extra: SchemaExtraAsset, context: Context = appContext): Boolean {
        val installed = realInstalledVersion(extra.schemaId, context) ?: return false
        return installed != extra.version
    }

    /** Uninstall only files owned by this market manifest and not referenced by another manifest. */
    fun remove(source: SchemaSource, context: Context = appContext): Boolean = removeById(
        source.schemaId, source.assetName, source.expectedFiles, source.requireVersionFile, context,
    )

    fun removeExtra(extra: SchemaExtraAsset, context: Context = appContext): Boolean = removeById(
        extra.schemaId, extra.assetName, setOf(extra.assetName), false, context,
    )

    private fun removeById(
        schemaId: String,
        assetName: String,
        fallbackFiles: Set<String>,
        requireVersionFile: Boolean,
        context: Context,
    ): Boolean {
        val dataDir = rimeDataDir(context)
        if (!dataDir.exists() && !dataDir.mkdirs()) return false
        val ownManifest = readManifest(context, schemaId)
        val manifestDir = File(dataDir, MANIFEST_DIR_NAME)
        val otherRefs = manifestDir.listFiles().orEmpty()
            .filter { it.isFile && it.name != "$schemaId.txt" }
            .flatMap { file ->
                SchemaMarketFileOps.parseOwnershipManifest(readLines(file)).map { it.path }
            }
            .toSet()
        val replacements = ownManifest.mapNotNull { owned ->
            val target = File(dataDir, owned.path)
            val safeTarget = SchemaMarketFileOps.isSafeTarget(dataDir, target)
            val current = if (safeTarget && target.isFile) "sha256:${SchemaMarketFileOps.sha256(target)}" else null
            if (safeTarget && SchemaMarketFileOps.shouldDeleteOwnedFile(owned, otherRefs, current) && target.isFile) {
                SchemaMarketFileOps.Replacement(null, target)
            } else null
        }.toMutableList()
        // Legacy installs have no content manifest; remove only the explicitly selected asset/marker.
        if (ownManifest.none { it.sha256 != null }) {
            val legacyFiles = if (isSingleFileAsset(assetName)) setOf(assetName)
                else fallbackFiles + if (requireVersionFile) setOf("version.txt") else emptySet()
            legacyFiles.forEach { relative ->
                if (relative.startsWith('/') || '\\' in relative || relative.split('/').any { it == ".." || it.isEmpty() }) return@forEach
                val target = File(dataDir, relative)
                if (SchemaMarketFileOps.isSafeTarget(dataDir, target) && target.isFile && relative !in otherRefs) {
                    replacements += SchemaMarketFileOps.Replacement(null, target)
                }
            }
        }
        val versions = installedVersions(context).toMutableMap().apply { remove(schemaId) }
        val fingerprints = installedFingerprints(context).toMutableMap().apply { remove(schemaId) }
        val uninstalled = uninstalledIds(context).toMutableSet().apply { add(schemaId) }
        replacements += stagedTextReplacement(dataDir, File(dataDir, VERSION_FILE_NAME), serializeKeyValues(versions))
        replacements += stagedTextReplacement(dataDir, File(dataDir, FINGERPRINT_FILE_NAME), serializeKeyValues(fingerprints))
        replacements += stagedTextReplacement(dataDir, File(dataDir, UNINSTALLED_FILE_NAME), uninstalled.sorted().joinToString("\n"))
        val manifest = File(manifestDir, "$schemaId.txt")
        if (manifest.exists()) replacements += SchemaMarketFileOps.Replacement(null, manifest)
        return runCatching {
            applyInDataDir(dataDir, replacements)
            true
        }.getOrElse {
            Timber.e(it, "Failed to uninstall schema $schemaId")
            false
        }
    }

    private val mutex = Mutex()
}

/** Result returned by every install operation. */
data class InstallResult(
    val success: Boolean,
    val error: String?,
    val installedVersion: String?,
)
