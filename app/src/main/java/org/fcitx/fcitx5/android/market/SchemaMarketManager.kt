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
    private const val SCHEMA_LIST_FILE_NAME = ".meng_schema_lists"
    /** GitHub 未登录 API 每小时 60 次，国内运营商共享出口 IP 很容易耗尽：探测结果缓存 30 分钟，手动刷新才强制重查。 */
    private const val PROBE_TTL_MS = 30 * 60_000L
    private val BACKUP_NAME = Regex("""^\.(.+)\.backup-[0-9a-fA-F-]{36}$""")

    /** [digest] 是该 release 里 [SchemaSource.assetName] 资产的 sha256（GitHub API 的 digest 字段），拿不到为 null。 */
    data class ReleaseProbe(val version: String?, val checked: Boolean, val digest: String? = null)
    data class AssetFingerprintProbe(val fingerprint: String?, val checked: Boolean, val version: String? = null)
    data class GitHeadProbe(val version: String?, val date: String?, val checked: Boolean)

    /** Query the latest tag, then try GitHub's /releases/latest redirect if the API is unavailable. */
    suspend fun fetchLatestRelease(
        source: SchemaSource,
        context: Context = appContext,
        force: Boolean = false,
    ): ReleaseProbe = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val cacheKey = "release_${source.owner}_${source.repo}"
        val digestKey = "release_digest_${source.owner}_${source.repo}_${source.assetName}"
        if (!force && System.currentTimeMillis() - prefs.getLong("${cacheKey}_at", 0L) in 0 until PROBE_TTL_MS) {
            prefs.getString(cacheKey, null)?.let { return@withContext ReleaseProbe(it, true, prefs.getString(digestKey, null)) }
        }
        val api = fetchApiRelease(source)
        // 重定向兜底只能拿到 tag，拿不到校验值：此时安装只允许走官方源（见 downloadAndInstall）。
        val verified = api?.first ?: fetchRedirectReleaseTag(source)
        if (verified != null) {
            val digest = api?.takeIf { it.first == verified }?.second
            prefs.edit().putString(cacheKey, verified).putLong("${cacheKey}_at", System.currentTimeMillis())
                .apply { if (digest != null) putString(digestKey, digest) else remove(digestKey) }.apply()
            ReleaseProbe(verified, checked = true, digest = digest)
        } else {
            ReleaseProbe(prefs.getString(cacheKey, null), checked = false)
        }
    }

    private fun fetchApiRelease(source: SchemaSource): Pair<String, String?>? = runCatching {
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
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@runCatching null
            val assets = json.optJSONArray("assets")
            val digest = assets?.let { arr ->
                (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == source.assetName }
                    ?.optString("digest")
                    ?.takeIf { it.startsWith("sha256:", ignoreCase = true) && it.substringAfter(':').matches(Regex("(?i)[0-9a-f]{64}")) }
            }
            tag to digest
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
        force: Boolean = false,
    ): AssetFingerprintProbe = withContext(Dispatchers.IO) {
        val cachePrefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val cacheKey = "asset_${source.owner}_${source.repo}_${source.assetName}"
        if (!force && System.currentTimeMillis() - cachePrefs.getLong("${cacheKey}_at", 0L) in 0 until PROBE_TTL_MS) {
            cachePrefs.getString(cacheKey, null)?.let {
                return@withContext AssetFingerprintProbe(it, checked = true, version = cachePrefs.getString("${cacheKey}_date", null))
            }
        }
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
            prefs.edit().putString(key, found.first).putLong("${key}_at", System.currentTimeMillis())
                .putString("${key}_date", found.second).apply()
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
        force: Boolean = false,
    ): GitHeadProbe = withContext(Dispatchers.IO) {
        val cachePrefs = context.applicationContext.getSharedPreferences(RELEASE_CACHE, Context.MODE_PRIVATE)
        val headKey = "githead_${source.owner}_${source.repo}_${source.gitBranch}"
        if (!force && System.currentTimeMillis() - cachePrefs.getLong("${headKey}_at", 0L) in 0 until PROBE_TTL_MS) {
            cachePrefs.getString(headKey, null)?.let {
                return@withContext GitHeadProbe(it, cachePrefs.getString("gitdate_${source.owner}_${source.repo}_${source.gitBranch}", null), checked = true)
            }
        }
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
                .putLong("${shaKey}_at", System.currentTimeMillis())
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
    @Volatile private var lastDownloadError: String? = null

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
                // 下载件 + 解压暂存（zip）或旧文件备份（单文件）同时占空间：至少留 2 倍体积再加 64MB 余量。
                val free = temp.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (total != null && free < total * 2 + 64L * 1024 * 1024) {
                    lastDownloadError = "存储空间不足：需要约 ${(total * 2) / 1024 / 1024} MB，可用 ${free / 1024 / 1024} MB"
                    return null
                }
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
            lastInstallNotice = null
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
            // 第三方镜像只在拿到官方校验值时使用：包里的 lua 跑在输入法进程里，被替换的包能读到全部按键。
            val singleFile = isSingleFileAsset(source.assetName)
            val officialDigest = if (singleFile) source.assetFingerprint?.takeIf { !source.versionCheckFailed && it.startsWith("sha256:", true) }
                else source.archiveDigest
            val usable = if (officialDigest != null || source.isGitSource) mirrors
                else mirrors.filter { isOfficialUrl(it.url) }.ifEmpty { mirrors.take(1) }
            if (usable.size < mirrors.size) Timber.i("No upstream digest for %s; third-party mirrors skipped", source.schemaId)
            val installed = SchemaMarketFileOps.firstSuccessfulMirror(usable) { mirror ->
                val temp = createTempFile(dataDir, source.schemaId, "download", ".part")
                try {
                    lastDownloadError = null
                    val downloaded = downloadToTemp(mirror.url, temp, onProgress)
                        ?: run { lastDownloadError?.let { lastError = it }; return@firstSuccessfulMirror null }
                    if (!singleFile && officialDigest != null) {
                        val actual = SchemaMarketFileOps.sha256(downloaded)
                        if (!actual.equals(officialDigest.substringAfter(':'), ignoreCase = true)) {
                            throw IllegalArgumentException("${mirror.label} 下载的安装包与 GitHub 官方 SHA-256 不一致，已拒绝")
                        }
                    }
                    if (singleFile) {
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
                    InstallResult(true, null, recordVersion, lastInstallNotice)
                } catch (error: Exception) {
                    lastError = error.message ?: "文件校验或安装失败"
                    Timber.w(error, "Schema install attempt failed from ${mirror.url}")
                    null
                } finally {
                    temp.delete()
                }
            }
            installed?.second ?: InstallResult(false,
                if (usable.size < mirrors.size) "$lastError（未取得官方校验值，仅尝试了官方源；稍后点刷新再试）" else lastError,
                source.version)
        }
    }

    private fun isOfficialUrl(url: String): Boolean =
        url.startsWith("https://github.com/") || url.startsWith("https://codeload.github.com/")

    /** Install a standalone model asset, preserving the old file until validation and commit complete. */
    suspend fun downloadAndInstallExtra(
        extra: SchemaExtraAsset,
        onProgress: (Long, Long?) -> Unit,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            dataDir.mkdirs()
            val urls = extraMirrorUrls(extra).take(1) // 无官方校验值，只走官方源
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

    /** 最近一次安装的非致命提示（如无法自动加入方案列表），随 InstallResult 带回界面。 */
    @Volatile private var lastInstallNotice: String? = null

    private fun installArchive(zipFile: File, source: SchemaSource, context: Context) {
        val dataDir = rimeDataDir(context)
        val staging = File(dataDir, ".meng-stage-${UUID.randomUUID()}")
        lastInstallNotice = null
        try {
            // 本方案上次装的受保护文件（default.yaml、*.custom.yaml 等）若用户没改过，更新时照常替换；改过的保持不动。
            val replaceable = readManifest(context, source.schemaId).filter { owned ->
                owned.sha256 != null && SchemaMarketFileOps.isProtectedConfig(owned.path) &&
                    File(dataDir, owned.path).let { it.isFile && "sha256:${SchemaMarketFileOps.sha256(it)}" == owned.sha256 }
            }.map { it.path }.toSet()
            val files = SchemaMarketFileOps.extractValidatedZip(
                zipFile = zipFile,
                stagingDir = staging,
                installRoot = dataDir,
                expectedFiles = source.expectedFiles,
                requireVersionFile = source.requireVersionFile,
                stripTopDir = source.stripTopDir,
                replaceableProtected = replaceable,
            )
            if (files.isEmpty()) throw IllegalArgumentException("ZIP 中没有可安装的方案文件")
            val ownership = files.associateWith { path ->
                "sha256:${SchemaMarketFileOps.sha256(File(staging, path))}"
            }
            val replacements = files.map { relative ->
                SchemaMarketFileOps.Replacement(File(staging, relative), File(dataDir, relative))
            }.toMutableList()

            // 方案列表：包里的 default.yaml 没装上（已有别的方案的 default.yaml）时，它的方案进不了 schema_list，
            // Rime 不会部署、也切换不到。把这些 id 追加到 default.custom.yaml 的托管块。
            val pkgIds = SchemaMarketFileOps.readZipText(zipFile, "default.yaml", source.stripTopDir)
                ?.let { SchemaMarketFileOps.parseSchemaList(it) }.orEmpty()
                .ifEmpty { source.expectedFiles.filter { it.endsWith(".schema.yaml") }.map { it.removeSuffix(".schema.yaml") } }
            fun schemaFileExists(id: String) = "$id.schema.yaml" in files || File(dataDir, "$id.schema.yaml").isFile
            val lists = readKeyValues(File(dataDir, SCHEMA_LIST_FILE_NAME)).toMutableMap()
            lists[source.schemaId] = pkgIds.filter(::schemaFileExists).joinToString(",")
            val defaultYaml = if ("default.yaml" in files) File(staging, "default.yaml") else File(dataDir, "default.yaml")
            schemaListReplacements(dataDir, staging, files, lists, defaultYaml)?.let { replacements += it }

            replacements += installStateReplacements(
                context, source.schemaId, source.version, source.assetFingerprint, ownership,
            )
            applyInDataDir(dataDir, replacements)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 生成 .meng_schema_lists 与 default.custom.yaml 的事务替换项。
     * default.custom.yaml 由用户拥有：只增删我们自己的托管块，其余内容原样保留。
     */
    private fun schemaListReplacements(
        dataDir: File,
        staging: File?,
        stagedFiles: Collection<String>,
        lists: Map<String, String>,
        effectiveDefaultYaml: File?,
    ): List<SchemaMarketFileOps.Replacement>? {
        val already = effectiveDefaultYaml?.takeIf { it.isFile }?.let { runCatching { SchemaMarketFileOps.parseSchemaList(it.readText()) }.getOrNull() }.orEmpty().toSet()
        val append = lists.values.flatMap { v -> v.split(',').filter { it.isNotBlank() } }.distinct().filter { it !in already }
        val customName = "default.custom.yaml"
        val stagedCustom = staging?.let { File(it, customName) }?.takeIf { customName in stagedFiles }
        val liveCustom = File(dataDir, customName)
        val existing = (stagedCustom ?: liveCustom).takeIf { it.isFile }?.readText()
        val out = mutableListOf<SchemaMarketFileOps.Replacement>()
        out += stagedTextReplacement(dataDir, File(dataDir, SCHEMA_LIST_FILE_NAME),
            serializeKeyValues(lists.filterValues { it.isNotBlank() }))
        if (append.isEmpty() && existing?.contains(SchemaMarketFileOps.SCHEMA_LIST_BEGIN) != true) return out
        val rewritten = SchemaMarketFileOps.rewriteSchemaListPatch(existing, append)
        if (rewritten == null) {
            if (append.isNotEmpty()) lastInstallNotice = "default.custom.yaml 结构特殊，未能自动加入方案列表；请手动在 schema_list 中添加：${append.joinToString()}"
            return out
        }
        if (stagedCustom != null) {
            FileOutputStream(stagedCustom).use { it.write(rewritten.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        } else {
            out += stagedTextReplacement(dataDir, liveCustom, rewritten)
        }
        return out
    }

    /**
     * 上次安装/卸载中途进程被杀时留下的临时文件与备份：原文件缺失的备份还原回去，其余清掉。
     * 只在没有安装事务进行时调用（持有 mutex）。lmdg 的 .part 一个就有数百 MB。
     */
    suspend fun recoverInterrupted(context: Context = appContext): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            if (!dataDir.isDirectory) return@withContext 0
            var touched = 0
            dataDir.listFiles().orEmpty().forEach { f ->
                val n = f.name
                val junk = (n.startsWith(".meng-stage-") && f.isDirectory) ||
                    (n.startsWith(".meng-") && (n.endsWith(".part") || n.endsWith(".tmp") || n.endsWith(".zip")) && f.isFile)
                if (junk && f.deleteRecursively()) touched++
            }
            dataDir.walkTopDown()
                .onEnter { dir -> dir == dataDir || (dir.name != "build" && dir.name != "sync" && !dir.name.endsWith(".userdb") && !dir.name.startsWith(".meng-stage-")) }
                .filter { it.isFile }
                .forEach { f ->
                    val m = BACKUP_NAME.matchEntire(f.name) ?: return@forEach
                    val original = File(f.parentFile, m.groupValues[1])
                    val restore = !original.exists()
                    val ok = if (restore) f.renameTo(original) else f.delete()
                    if (ok) touched++
                    Timber.w("Schema market recovered interrupted file %s (restored=%s)", f.name, restore)
                }
            touched
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
                if (!SchemaMarketFileOps.isZipFile(tempZip)) {
                    // 单文件资产源（如 lmdg 的 .gram）本地导入的不是 zip：直接走单文件安装（校验+原子替换+状态记录），
                    // 不再误当 zip 解压。
                    if (isSingleFileAsset(source.assetName)) {
                        val fingerprint = SchemaMarketFileOps.validateSingleFile(tempZip)
                        installSingleArtifact(
                            tempZip, dataDir, source.schemaId, source.assetName,
                            source.displayVersion ?: source.version, fingerprint, context,
                        )
                        return@withContext InstallResult(true, null, source.displayVersion ?: source.version)
                    }
                    return@withContext InstallResult(false, "所选文件不是 ZIP 安装包", source.version)
                }
                installArchive(tempZip, source, context)
                InstallResult(true, null, source.version, lastInstallNotice)
            } catch (error: Exception) {
                Timber.e(error, "Schema import failed: ${source.schemaId}")
                InstallResult(false, error.message ?: "ZIP 校验或安装失败", source.version)
            } finally {
                tempZip.delete()
            }
        }
    }

    /** 本地导入单文件资产（大模型语法包 .gram 等）：与下载安装同一套校验与状态记录，不当作 zip 解压。 */
    suspend fun importExtraAsset(
        extra: SchemaExtraAsset,
        fileUri: android.net.Uri,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dataDir = rimeDataDir(context)
            if (!dataDir.exists() && !dataDir.mkdirs()) {
                return@withContext InstallResult(false, "无法创建方案数据目录", extra.version)
            }
            val temp = createTempFile(dataDir, extra.schemaId, "import", ".part")
            return@withContext try {
                val input = context.contentResolver.openInputStream(fileUri)
                    ?: return@withContext InstallResult(false, "无法打开所选文件", extra.version)
                input.use { sourceStream ->
                    FileOutputStream(temp).use { output ->
                        sourceStream.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (SchemaMarketFileOps.isZipFile(temp)) {
                    return@withContext InstallResult(false, "这是 ZIP 安装包，请通过方案列表的「导入」安装", extra.version)
                }
                val fingerprint = SchemaMarketFileOps.validateSingleFile(temp)
                installSingleArtifact(
                    temp, dataDir, extra.schemaId, extra.assetName,
                    extra.version, fingerprint, context,
                )
                InstallResult(true, null, extra.version)
            } catch (error: Exception) {
                Timber.e(error, "Extra asset import failed: ${extra.schemaId}")
                InstallResult(false, error.message ?: "模型文件校验或安装失败", extra.version)
            } finally {
                temp.delete()
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
                // version.txt 可能带 v 前缀或多行说明：取第一行并去掉 v，与 tag 的写法一致，避免永远提示「可更新」。
                File(dataDir, "version.txt").takeIf { it.isFile && it.length() < 4096 }?.readLines()
                    ?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() }
                    ?: versions[schemaId] ?: "已安装"
            } else null
            "wanxiang-lmdg" -> if (exists(SchemaSources.LMDG_ASSET)) {
                // 历史遗留安装（版本表无记录、指纹无记录）返回「已安装」而非恒定 LTS tag：
                // hasUpdate 对无指纹的旧安装视为可更新，点一次更新后判据恢复精确的指纹比对。
                versions[schemaId] ?: "已安装"
            } else null
            "rime_frost" -> if (exists("rime_frost.schema.yaml")) versions[schemaId] ?: "已安装" else null
            // 白霜等整包也带 double_pinyin.schema.yaml，用它判断会把别的包误认成雾凇
            "rime_ice" -> if (versions[schemaId] != null || exists("rime_ice.schema.yaml")) versions[schemaId] ?: "已安装" else null
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
        runCatching {
            val lists = readKeyValues(File(dataDir, SCHEMA_LIST_FILE_NAME)).toMutableMap().apply { remove(schemaId) }
            val defaultYaml = File(dataDir, "default.yaml")
            val defaultDeleted = replacements.any { it.staged == null && it.target.canonicalPath == defaultYaml.canonicalPath }
            schemaListReplacements(dataDir, null, emptyList(), lists, defaultYaml.takeUnless { defaultDeleted })
                ?.forEach { r ->
                    if (replacements.none { it.target.canonicalPath == r.target.canonicalPath }) replacements += r
                    else r.staged?.delete()
                }
        }.onFailure { Timber.w(it, "Could not update schema list patch on uninstall") }
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
    /** 安装成功但有需要用户知道的情况（如未能自动加入方案列表）。 */
    val notice: String? = null,
)
