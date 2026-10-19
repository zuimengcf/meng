/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
import java.util.zip.ZipInputStream

/**
 * 方案商店执行器。
 *
 * 职责：
 * - 版本探测：联网调用 GitHub API 探测某个仓库的最新 release tag（用于真实更新检测）。
 *   仅做轻量 API 请求（返回小 JSON），不做大文件下载。
 * - 本地导入：接收用户通过系统文件选择器（SAF）选中的方案 zip 安装包，
 *   解压写盘到 fcitx5 Rime 的用户数据目录并记录版本。
 *
 * 方案安装包的下载链接由 UI 弹窗展示给用户自行下载（见 SchemaSource.links），
 * 应用内不进行大文件下载，避免无进度条、下载缓慢的问题。
 *
 * Rime 数据根目录 = getExternalFilesDir(null)/data/rime
 * Rime Lua 目录      = getExternalFilesDir(null)/data/lua
 * （与 native-lib.cpp 里 FCITX_DATA_HOME 的数据布局一致）
 */
object SchemaMarketManager {

    private const val VERSION_FILE_NAME = ".meng_schema_versions"

    /**
     * 探测仓库最新 release 的 tag（如 v18.1.3）。
     * @return 成功返回最新 tag；失败返回 null（调用方用兜底源展示）。
     */
    suspend fun fetchLatestRelease(source: SchemaSource): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(source.releaseApiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            conn.connect()
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
            } else {
                Timber.w("Release probe HTTP ${conn.responseCode} for ${source.releaseApiUrl}")
                null
            }
        }.getOrElse {
            Timber.w(it, "Release probe failed: ${source.releaseApiUrl}")
            null
        }
    }

    /**
     * 对 [source] 的每个下载源做连通性探测（HEAD 请求，少量字节），更新其状态与延迟。
     * @return 探测后的源（各 mirror.status / mirror.latencyMs 已填充）
     */
    suspend fun probeLinks(source: SchemaSource): SchemaSource = withContext(Dispatchers.IO) {
        val updated = source.mirrors.map { mirror ->
            val result = probeOne(mirror.url)
            mirror.copy(status = result.status, latencyMs = result.latencyMs)
        }
        source.copy(mirrors = updated)
    }

    /**
     * 探测单个 URL 的连通性与延迟：
     * 优先 HEAD；服务器不支持 HEAD 时用一个 range/超短 GET 试探。
     * 以响应时间区分 OK / SLOW，以异常或非 2xx/3xx 区分 FAIL。
     */
    private fun probeOne(url: String): ProbeResult {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            conn.requestMethod = "HEAD"
            val start = System.currentTimeMillis()
            conn.connect()
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            conn.disconnect()
            val reachable = code in 200..399
            if (!reachable) {
                ProbeResult(MirrorStatus.FAIL, null)
            } else if (elapsed > SLOW_THRESHOLD_MS) {
                ProbeResult(MirrorStatus.SLOW, elapsed)
            } else {
                ProbeResult(MirrorStatus.OK, elapsed)
            }
        }.getOrElse {
            Timber.w(it, "Probe failed: $url")
            ProbeResult(MirrorStatus.FAIL, null)
        }
    }

    /** 单次探测结果：状态 + 延迟毫秒（失败或未测为 null）。 */
    private data class ProbeResult(val status: MirrorStatus, val latencyMs: Long?)

    /**
     * 下载官方 release zip 到临时文件。
     * [onProgress] 每读取一批数据回调 (downloadedBytes, totalBytesOrNull)。
     * 返回下载完成的文件；失败返回 null（调用方清缓存）。
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Meng-InputMethod/0.1")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                Timber.w("Download HTTP $code for $url")
                null
            } else {
                val total = conn.contentLengthLong.takeIf { it > 0 }
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                var downloaded = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (dest.length() > 0) dest else null
            }
        }.getOrElse {
            Timber.w(it, "Download failed: $url")
            null
        }
    }

    /**
     * 选择一个可用下载源；按连接数组顺序优先返回状态为 OK 的第一个，
     * 其次 SLOW，再其次 UNKNOWN（未探测），最后才是 FAIL（作为兜底尝试）。
     */
    fun pickBestMirror(source: SchemaSource): String? {
        val ok = source.mirrors.firstOrNull { it.status == MirrorStatus.OK }
        val slow = source.mirrors.firstOrNull { it.status == MirrorStatus.SLOW }
        val unknown = source.mirrors.firstOrNull { it.status == MirrorStatus.UNKNOWN }
        val any = source.mirrors.firstOrNull { it.status == MirrorStatus.FAIL }
        return (ok ?: slow ?: unknown ?: any)?.url
    }

    private const val SLOW_THRESHOLD_MS = 3000L
    private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16 // 64KB

    /**
     * 应用内直接下载官方 release 方案并安装。
     * 流程：选源(优先 OK 镜像) -> 下载到 cache -> 落盘 -> 记版本。
     *
     * 支持两种资产：
     * - 整包 zip（如 rime-wanxiang-base.zip）：解压到 rime 数据目录。
     * - 单文件（如 lmdg 的 *.gram）：直接放到 rime 数据目录（不当作 zip 解压）。
     *
     * [onProgress] 收到 (downloadedBytes, totalBytesOrNull)。
     * @return 成功返回 InstallResult(success=true)。失败返回带错误信息的结果。
     */
    suspend fun downloadAndInstall(
        source: SchemaSource,
        onProgress: (Long, Long?) -> Unit,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            rimeLuaDir(context).mkdirs()
            // 下载主 URL：release 源选镜像，git 源用 codeload
            val url = if (source.isGitSource) source.gitUrl else pickBestMirror(source)
            if (url == null) {
                return@withContext InstallResult(false, "没有可用的下载源", source.version)
            }
            Timber.d("Downloading schema ${source.schemaId} from $url")
            val dataDir = rimeDataDir(context)
            dataDir.mkdirs()
            val isSingleFile = !source.isGitSource &&
                (source.assetName.endsWith(".gram") || source.assetName.endsWith(".txt"))
            val dest = if (isSingleFile) File(dataDir, source.assetName)
            else File(context.cacheDir, "${source.schemaId}-${source.version}.zip")
            try {
                val file = download(url, dest, onProgress)
                if (file == null) {
                    dest.delete()
                    return@withContext InstallResult(false, "下载失败，请检查网络或换镜像重试", source.version)
                }
                if (!isSingleFile) {
                    unzip(
                        file, dataDir,
                        skipGlobalConfig = source.safeTakeover,
                        stripTopDir = source.stripTopDir,
                    )
                    file.delete()
                }
                saveInstalledVersion(context, source.schemaId, source.version)
                InstallResult(true, null, source.version)
            } catch (e: Exception) {
                Timber.e(e, "Schema download/install failed: ${source.schemaId}")
                dest.delete()
                InstallResult(false, e.message ?: "未知错误", source.version)
            }
        }
    }

    /**
     * 应用内独立下载附加组件（如万象 lmdg 大模型语法包）。
     * 单独选源、单独下载、以自身 schemaId 独立记录版本，便于与主包分开更新。
     * @return 成功返回 InstallResult(success=true)。失败返回带错误信息的结果。
     */
    suspend fun downloadAndInstallExtra(
        extra: SchemaExtraAsset,
        onProgress: (Long, Long?) -> Unit,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val url = pickBestExtraMirror(extra)
            if (url == null) {
                return@withContext InstallResult(false, "没有可用的下载源", extra.version)
            }
            Timber.d("Downloading extra ${extra.schemaId} from $url")
            val dataDir = rimeDataDir(context)
            dataDir.mkdirs()
            val target = File(dataDir, extra.assetName)
            try {
                val ok = download(url, target, onProgress) != null
                if (!ok) {
                    Timber.w("Extra asset download failed: ${extra.assetName}")
                    return@withContext InstallResult(false, "下载失败，请检查网络或换镜像重试", extra.version)
                }
                saveInstalledVersion(context, extra.schemaId, extra.version)
                InstallResult(true, null, extra.version)
            } catch (e: Exception) {
                Timber.e(e, "Extra asset download/install failed: ${extra.schemaId}")
                InstallResult(false, e.message ?: "未知错误", extra.version)
            }
        }
    }

    /**
     * 为附加组件（大模型语法包）选择可用镜像。
     * 附加组件来自独立仓库（如 rime-wanxiang-data），构造其 releases 下载链接并选最优。
     */
    private fun pickBestExtraMirror(extra: SchemaExtraAsset): String? {
        val base = "https://github.com/${extra.owner}/${extra.repo}/releases/download/${extra.tag}/${extra.assetName}"
        val candidates = listOf(base) + listOf(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://gh.llkk.cc/",
        ).map { it + base }
        // 附加组件也做轻量连通探测，优先可用项
        val probed = candidates.map { url ->
            val result = probeOne(url)
            url to result.status
        }
        val ok = probed.firstOrNull { it.second == MirrorStatus.OK }
        val slow = probed.firstOrNull { it.second == MirrorStatus.SLOW }
        val any = probed.firstOrNull { it.second != MirrorStatus.FAIL }
        return (ok ?: slow ?: any)?.first
    }

    /** rime 用户数据根目录 */
    fun rimeDataDir(context: Context = appContext): File {
        val dataDir = File(
            (context.getExternalFilesDir(null) ?: context.filesDir),
            "data"
        )
        return File(dataDir, "rime")
    }

    /** rime lua 目录 */
    fun rimeLuaDir(context: Context = appContext): File {
        val dataDir = File(
            (context.getExternalFilesDir(null) ?: context.filesDir),
            "data"
        )
        return File(dataDir, "lua")
    }

    /** 已安装方案版本表（schemaId -> version）。不存在则视为未安装。 */
    fun installedVersions(context: Context = appContext): Map<String, String> {
        val f = File(rimeDataDir(context), VERSION_FILE_NAME)
        if (!f.exists()) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        }.getOrElse {
            Timber.w(it, "Failed to read schema versions")
            emptyMap()
        }
    }

    /**
     * 基于 rime 用户数据目录里的真实文件判断某项目是否已安装，并给出已装版本。
     *
     * 不要只信空的 `.meng_schema_versions` 记录——用户可能通过本地导入、手动拷贝等方式
     * 装过方案，而版本记录为空。这里以数据目录里的实体为准：
     * - 万象主包：`wanxiang.schema.yaml` 存在即已装，版本读 `version.txt`（如 18.0.9）。
     * - lmdg：`wanxiang-lts-zh-hans.gram` 存在即已装。
     *
     * @return 已装版本号；未装返回 null。
     */
    fun realInstalledVersion(schemaId: String, context: Context = appContext): String? {
        val dataDir = rimeDataDir(context)
        if (!dataDir.exists()) return null
        return when (schemaId) {
            "wanxiang" -> {
                if (File(dataDir, "wanxiang.schema.yaml").exists()) {
                    // 优先读万象自带的 version.txt；读不到回退 .meng_schema_versions 记录
                    val vf = File(dataDir, "version.txt")
                    vf.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
                        ?: installedVersions(context)[schemaId]
                        ?: "已安装"
                } else null
            }
            "wanxiang-lmdg" -> {
                if (File(dataDir, SchemaSources.LMDG_ASSET).exists()) {
                    installedVersions(context)[schemaId] ?: SchemaSources.LMDG_TAG
                } else null
            }
            "rime_frost" -> {
                if (File(dataDir, "rime_frost.schema.yaml").exists()) {
                    installedVersions(context)[schemaId] ?: "已安装"
                } else null
            }
            "rime_ice" -> {
                // 雾凇无单一 schema 文件，按版本记录 + 任意标记文件判断
                if (installedVersions(context)[schemaId] != null || File(dataDir, "double_pinyin.schema.yaml").exists()) {
                    installedVersions(context)[schemaId] ?: "已安装"
                } else null
            }
            "oh_my_rime" -> {
                // 薄荷是配置包，无单一 schema，按版本记录判断
                installedVersions(context)[schemaId]
            }
            else -> installedVersions(context)[schemaId]
        }
    }

    private fun saveInstalledVersion(context: Context, schemaId: String, version: String) {
        val versions = installedVersions(context).toMutableMap()
        versions[schemaId] = version
        val f = File(rimeDataDir(context), VERSION_FILE_NAME)
        rimeDataDir(context).mkdirs()
        f.writeText(versions.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /** 判断某方案是否已安装 */
    fun isInstalled(schemaId: String, context: Context = appContext): Boolean =
        installedVersions(context).containsKey(schemaId)

    /** 判断某方案是否有可用更新（已装且版本不同） */
    fun hasUpdate(source: SchemaSource, context: Context = appContext): Boolean {
        val current = installedVersions(context)[source.schemaId] ?: return false
        return current != source.version
    }

    /** 判断某附加组件是否已安装（按独立 schemaId） */
    fun isExtraInstalled(extra: SchemaExtraAsset, context: Context = appContext): Boolean =
        installedVersions(context).containsKey(extra.schemaId)

    /** 判断某附加组件是否有可用更新（已装且版本不同） */
    fun hasExtraUpdate(extra: SchemaExtraAsset, context: Context = appContext): Boolean {
        val current = installedVersions(context)[extra.schemaId] ?: return false
        return current != extra.version
    }

    private val mutex = Mutex()

    /**
     * 从用户本地选中的方案 zip 安装包导入并安装。
     * 解压到 rime 数据目录 -> 记录版本。
     */
    suspend fun importZip(
        source: SchemaSource,
        zipUri: android.net.Uri,
        context: Context = appContext,
    ): InstallResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            rimeLuaDir(context).mkdirs()
            Timber.d("Importing schema ${source.schemaId} from content:// $zipUri")
            try {
                val dataDir = rimeDataDir(context)
                dataDir.mkdirs()
                // 从 content URI 读出 zip 字节 -> 解压并安全写入 rime 数据目录
                val input = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext InstallResult(false, "无法打开所选文件", source.version)
                val tmpZip = File(context.cacheDir, "${source.schemaId}-${source.version}.zip")
                input.use { it.copyTo(FileOutputStream(tmpZip)) }
                if (tmpZip.length() == 0L) {
                    tmpZip.delete()
                    return@withContext InstallResult(false, "所选文件为空或损坏", source.version)
                }
                unzip(
                    tmpZip, dataDir,
                    skipGlobalConfig = source.safeTakeover,
                    stripTopDir = source.stripTopDir,
                )
                tmpZip.delete()
                saveInstalledVersion(context, source.schemaId, source.version)
                InstallResult(true, null, source.version)
            } catch (e: Exception) {
                Timber.e(e, "Schema import failed: ${source.schemaId}")
                InstallResult(false, e.message ?: "未知错误", source.version)
            }
        }
    }

    /**
     * 卸载一个方案：删除版本记录，并清理 rime 数据目录里对应的实体文件。
     * - lmdg（*.gram）为单文件，直接删除该文件。
     * - 万象主包等 zip 整包：包内文件不可枚举，保守做法是仅移除版本记录；
     *   Rime 重新部署时会忽略不再引用的词库。
     */
    fun remove(source: SchemaSource, context: Context = appContext) {
        val dataDir = rimeDataDir(context)
        val versions = installedVersions(context).toMutableMap()
        versions.remove(source.schemaId)
        val vf = File(dataDir, VERSION_FILE_NAME)
        dataDir.mkdirs()
        vf.writeText(versions.entries.joinToString("\n") { "${it.key}=${it.value}" })
        // 单文件资产（lmdg *.gram）直接删除实体文件
        if (source.assetName.endsWith(".gram") || source.assetName.endsWith(".txt")) {
            File(dataDir, source.assetName).delete()
        }
    }

    /** 卸载附加组件（仅删除其版本记录） */
    fun removeExtra(extra: SchemaExtraAsset, context: Context = appContext) {
        val dataDir = rimeDataDir(context)
        val versions = installedVersions(context).toMutableMap()
        versions.remove(extra.schemaId)
        val vf = File(dataDir, VERSION_FILE_NAME)
        dataDir.mkdirs()
        vf.writeText(versions.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /**
     * 解压 zip 到 [destDir]。对每个条目做路径规范化并校验不超出 destDir，
     * 防止 zip-slip 路径穿越；跳过目录条目；跳过顶层 packaging 目录。
     *
     * 关键保护：
     * - [isUserDataPath]：跳过 Rime 的用户词典 / 构建产物路径（`build/`、`*.userdb*`、
     *   `.user.*` 等），确保安装/更新方案绝不会覆盖用户积累的自造词、词频与构建缓存。
     * - [skipGlobalConfig] 为 true 时（整包方案「接管但不覆盖」策略）：额外跳过 Rime 的
     *   全局默认文件（`default.yaml`、`punctuation.yaml`、`key_bindings.yaml`、
     *   `default.custom.yaml`、`installation.yaml` 等）。这些文件已被现有方案占用或
     *   属于用户/全局配置，直接覆盖会破坏已装方案（如万象）；跳过它们，只铺入纯方案
     *   文件（schema/dict/lua/opencc/gram），使新方案可被 Rime 自动识别为可切换输入法，
     *   但绝不影响其它方案与全局设置。
     */
    private fun unzip(
        zipFile: File,
        destDir: File,
        skipGlobalConfig: Boolean = false,
        stripTopDir: Boolean = false,
    ) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var topPrefix: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                // 剥离 codeload 源码 zip 的顶层目录（如 rime-ice-main/ ）
                var name = rawName
                if (stripTopDir) {
                    val idx = rawName.indexOf('/')
                    if (idx > 0) {
                        val top = rawName.substring(0, idx)
                        if (topPrefix == null) topPrefix = top + "/"
                        name = rawName.removePrefix(topPrefix!!)
                    } else if (rawName.isNotEmpty() && !rawName.contains('/')) {
                        // 顶层单文件目录项（如 "rime-ice-main"）跳过
                        name = ""
                    }
                }
                if (name.isEmpty()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (isUserDataPath(name)) {
                    Timber.d("Skipping user-data path in schema package: $name")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (skipGlobalConfig && isGlobalConfigPath(name)) {
                    // 「接管但不覆盖」：仅当目标全局文件尚不存在时才写入（为全新环境补上入口），
                    // 若已存在（如已装万象带来的 default.yaml）则跳过，绝不覆盖已有方案/全局设置。
                    val target = File(destDir, name) // 此处 name 已剥离顶层目录
                    if (target.exists()) {
                        Timber.d("Safe takeover: skip existing global config $name")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    Timber.d("Safe takeover: fill missing global config $name")
                }
                // 跳过官方打包可能存在的顶层目录前缀（如 rime/ ）
                val cleanName = name.removePrefix(destDir.name + "/")
                if (!entry.isDirectory && cleanName.isNotBlank()) {
                    val target = File(destDir, cleanName)
                    // 路径穿越防护
                    val canonicalDest = destDir.canonicalPath
                    val canonicalTarget = target.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalDest + File.separator)) {
                        Timber.w("Blocked zip-slip traversal: $name")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 顶层 Rime 全局默认 / 用户配置文件清单。这些文件决定全局行为或已被某方案占用，
     * 整包方案安装时应跳过，以免破坏已装方案或用户设置。
     */
    private fun isGlobalConfigPath(name: String): Boolean {
        val clean = name.removePrefix("rime/").trimStart('/')
        val base = clean.substringBefore('/') // 顶层文件名（目录内文件不视为顶层全局配置）
        return base in GLOBAL_CONFIG_FILES
    }

    private val GLOBAL_CONFIG_FILES = setOf(
        "default.yaml",
        "default.custom.yaml",
        "punctuation.yaml",
        "symbols.yaml",      // 全局符号表（万象也用同名 symbols.yaml，避免互相覆盖）
        "key_bindings.yaml",
        "installation.yaml",
        "user.yaml",
    )

    /**
     * 判断 zip 内路径是否属于 Rime 用户数据/构建产物，若是不解压（避免覆盖用户词库）。
     * 覆盖：`build/` 目录、`.userdb` / `.userdb.*` / `*.user.*` 用户词典、`*.user.txt`、
     * Rime 的 `default.custom.yaml` 等用户自定义（这些不该被方案包覆盖）。
     * 仅保护与「用户积累数据」相关的路径，方案源文件（.yaml / .dict.yaml / lua）正常解压。
     */
    private fun isUserDataPath(name: String): Boolean {
        if (name.isEmpty()) return false
        val segments = name.split('/')
        // 顶层 build 目录下的皆为 Rime 构建产物（含用户词库）
        if (segments.first() == "build" || segments.contains("build")) return true
        val lower = name.lowercase()
        return lower.contains(".userdb") ||
                lower.contains(".user.") ||
                lower.endsWith(".user.txt") ||
                lower.contains("userdict") ||
                lower.endsWith("_user.txt")
    }
}

/** 安装结果 */
data class InstallResult(
    val success: Boolean,
    val error: String?,
    val installedVersion: String?,
)