/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

/**
 * A download source URL for a Rime schema package.
 *
 * 每个下载源（官方源或国内镜像）都带一个连通状态，用于弹窗里直观展示那个源可用。
 * 状态通过 [SchemaMarketManager.probeLinks] 在运行时探测得到。
 */
data class SchemaMirror(
    /** 展示标签，如「官方」「镜像 1」 */
    val label: String,
    /** 完整下载 URL */
    val url: String,
    /** 连通状态（探测后由 [SchemaMarketManager.probeLinks] 更新） */
    val status: MirrorStatus = MirrorStatus.UNKNOWN,
    /** 探测到的响应延迟（毫秒），未探测或失败时为 null */
    val latencyMs: Long? = null,
)

enum class MirrorStatus {
    /** 未探测 */
    UNKNOWN,
    /** 可用 */
    OK,
    /** 慢或不稳定（超过阈值但可达） */
    SLOW,
    /** 不可用 */
    FAIL,
}

/** 安装源类型：release 单资产 或 git 源码整包（codeload）。 */
enum class SourceType {
    /** 从 GitHub release 下载单个资产 zip */
    RELEASE,
    /** 直接从仓库拉 codeload 源码 zip（不依赖 release） */
    GIT_SOURCE,
}

/**
 * A Rime schema package source that can be downloaded or locally imported.
 *
 * 「梦」的方案商店把一套 Rime 方案（schema + 词库 + lua 扩展）封装为一个 zip
 * 安装包。支持两种安装方式：
 * - 应用内直接下载（带进度条 + 完成通知），见 [SchemaMarketManager.downloadAndInstall]；
 * - 用户自行下载后本地导入（文件选择器），见 [SchemaMarketManager.importZip]。
 *
 * 版本与下载链接由运行时从 GitHub API 动态探测得到（见 [SchemaMarketManager.fetchLatestRelease]），
 * 因此 [version] 并非编译期硬编码到唯一版本，而是「当前最新 release」——这才是
 * 真正可行的更新检测依据。
 */
data class SchemaSource(
    /** 展示名，如「万象拼音」 */
    val displayName: String,
    /** 方案 schema_id，如 wanxiang */
    val schemaId: String,
    /** GitHub owner，用于动态探测与拼装下载链接 */
    val owner: String,
    /** GitHub repo */
    val repo: String,
    /** 要下载的资产文件名，如 rime-wanxiang-base.zip */
    val assetName: String,
    /** 探测到的最新版本号（去掉 v 前缀），用于展示与更新比对 */
    val version: String,
    /** 下载源列表（官方源 + 国内镜像），带连通状态，仅用于下载/复制/浏览器打开 */
    val mirrors: List<SchemaMirror>,
    /** 重量级可选组件提示文案（如大模型语法包，需另行下载），无则不显示 */
    val extraNote: String = "",
    /**
     * 可选附加组件（如万象 lmdg 大模型语法包）。
     * 若非空，[SchemaMarketManager.downloadAndInstall] 在装完主包后追加下载并写入 rime 数据目录。
     */
    val extraAsset: SchemaExtraAsset? = null,
    /**
     * 安全接管标志（默认 false）。
     *
     * 为 true 时，安装此类整包方案采用「接管但不覆盖」策略：
     * 解压时跳过 Rime 的全局默认文件（default.yaml / punctuation.yaml / key_bindings.yaml
     * / symbols.yaml / installation.yaml / user.yaml 等），只铺入纯方案文件
     * （schema/dict/lua/opencc/gram），使新方案可被 Rime 识别为可切换输入法，
     * 但绝不覆盖已装方案或用户全局设置。
     */
    val safeTakeover: Boolean = false,
    /**
     * 安装源类型（默认 [SourceType.RELEASE]）。
     * - RELEASE：从 GitHub release 下载单资产（如 rime-wanxiang-base.zip）。
     * - GIT_SOURCE：不依赖 release，直接从仓库拉 `codeload/<repo>/zip/<branch>` 源码整包
     *   （如 rime-ice / oh-my-rime 等无 release 或只有分片的仓库）。
     */
    val sourceType: SourceType = SourceType.RELEASE,
    /** GIT_SOURCE 时使用的分支（默认 main） */
    val gitBranch: String = "main",
    /** GIT_SOURCE 时是否剥离 codeload zip 的顶层目录（如 rime-ice-master/） */
    val stripTopDir: Boolean = true,
) {
    /** 是否为 git 源码源 */
    val isGitSource: Boolean get() = sourceType == SourceType.GIT_SOURCE

    /** codeload 源码 zip 下载 URL（GIT_SOURCE 时使用） */
    val gitUrl: String get() = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$gitBranch"

    /** 安装用主下载 URL：git 源用 codeload，release 源用镜像第一个 */
    val installUrl: String
        get() = if (isGitSource) gitUrl else (mirrors.firstOrNull()?.url ?: gitUrl)
    /** GitHub API 查询最新 release 的地址 */
    val releaseApiUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    /** 全部 URL（便于复制/展示） */
    val allUrls: List<String> get() = mirrors.map { it.url }

    /** 返回给定 index（[mirrors] 下标）或全部链接；方便 Fragment 构造。 */
    fun urlAt(index: Int): String? = mirrors.getOrNull(index)?.url
}

/**
 * 附加组件描述（如万象 lmdg 大模型语法包）。
 * 语法模型与词库紧耦合，通常体积巨大（数百 MB），故作为主包之外的可选项单独下载。
 */
data class SchemaExtraAsset(
    /** 用于独立记录安装版本与更新检测的 id（与主包 schemaId 区分） */
    val schemaId: String,
    /** 展示名，如「万象大模型语法包 (lmdg)」 */
    val displayName: String,
    /** 来源仓库 owner */
    val owner: String,
    /** 来源仓库 repo */
    val repo: String,
    /** release tag（如 LTS） */
    val tag: String,
    /** 要下载的资产文件名（如 wanxiang-lts-zh-hans.gram） */
    val assetName: String,
    /** 资产大小（字节，用于进度展示；未知为 null） */
    val sizeBytes: Long? = null,
    /** 当前探测到的最新版本（tag 或空串），用于展示与更新比对 */
    val version: String = tag,
)

/**
 * 内置方案源。目前内置「万象拼音」主包 与「万象大模型语法包 (lmdg)」两个独立项目。
 *
 * 万象拼音（rime-wanxiang）是 Rime 社区热门的带调拼音方案，依赖 librime-lua。
 * fcitx5-android 预编译的 librime 已内置 LuaTranslator/lua_gears，故可完整运行。
 *
 * lmdg（大模型语法包）与拼音主包分开，作为列表里的独立项目单独下载/更新/卸载，
 * 独立记录版本，互不挤占。
 *
 * 版本不硬编码：运行时调用 GitHub API 探测最新 release tag（见 fetchLatestRelease），
 * 探测失败时用 [WANXIANG_FALLBACK_TAG] 兜底展示。
 *
 * 已装版本以 rime 数据目录里的真实 `version.txt` 为准（见 SchemaMarketManager.realInstalledVersion）。
 */
object SchemaSources {
    const val WANXIANG_OWNER = "amzxyz"
    const val WANXIANG_REPO = "rime-wanxiang"
    const val WANXIANG_ASSET = "rime-wanxiang-base.zip"

    /** 探测失败时的兜底 tag（不代表最新，仅用于离线展示），可随知晓的新发布手动更新 */
    const val WANXIANG_FALLBACK_TAG = "v18.1.3"

    /** 万象大模型语法包（lmdg）来源：rime-wanxiang-data 仓库的 LTS release */
    const val LMDG_OWNER = "amzxyz"
    const val LMDG_REPO = "rime-wanxiang-data"
    const val LMDG_TAG = "LTS"
    const val LMDG_ASSET = "wanxiang-lts-zh-hans.gram"
    const val LMDG_ASSET_SIZE = 398_391_340L // 约 380MB（来自 GitHub API）

    /** 白霜拼音（rime-frost）：整包方案，含词库/多方案/Lua/语法模型 */
    const val FROST_OWNER = "gaboolic"
    const val FROST_REPO = "rime-frost"
    const val FROST_ASSET = "rime-frost-schemas.zip"
    const val FROST_FALLBACK_TAG = "1.0.4"

    /** 雾凇拼音（rime-ice）：无 release，用 git 源码整包 */
    const val ICE_OWNER = "iDvel"
    const val ICE_REPO = "rime-ice"
    const val ICE_BRANCH = "main"

    /** 薄荷配置（oh-my-rime）：无 release，用 git 源码整包 */
    const val MINT_OWNER = "Mintimate"
    const val MINT_REPO = "oh-my-rime"
    const val MINT_BRANCH = "main"

    private const val GH = "https://github.com"
    private val mirrorRoots = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
    )

    private fun buildMirrors(owner: String, repo: String, tag: String, asset: String): List<SchemaMirror> {
        val base = "$GH/$owner/$repo/releases/download/$tag/$asset"
        val list = mutableListOf(SchemaMirror("官方源", base))
        mirrorRoots.forEachIndexed { i, root ->
            list.add(SchemaMirror("镜像 ${i + 1}", root + base))
        }
        return list
    }

    /** 用探测到的最新 tag 构造万象拼音主包源 */
    fun wanxiangLatest(tag: String): SchemaSource = SchemaSource(
        displayName = "万象拼音",
        schemaId = "wanxiang",
        owner = WANXIANG_OWNER,
        repo = WANXIANG_REPO,
        assetName = WANXIANG_ASSET,
        version = tag.removePrefix("v"),
        mirrors = buildMirrors(WANXIANG_OWNER, WANXIANG_REPO, tag, WANXIANG_ASSET),
        extraNote = "独立安装；大模型语法包 lmdg 请在列表中单独下载",
        extraAsset = null,
    )

    /** 万象大模型语法包（lmdg）作为独立项目。独立 schemaId、独立镜像与版本记录。 */
    fun lmdgSource(): SchemaSource = SchemaSource(
        displayName = "万象大模型语法包 (lmdg)",
        schemaId = "wanxiang-lmdg",
        owner = LMDG_OWNER,
        repo = LMDG_REPO,
        assetName = LMDG_ASSET,
        version = LMDG_TAG,
        mirrors = buildMirrors(LMDG_OWNER, LMDG_REPO, LMDG_TAG, LMDG_ASSET),
        extraNote = "约 380MB 大模型语法文件，可显著提升候选质量（可选）",
        extraAsset = null,
    )

    /** 白霜拼音（rime-frost）：整包「接管但不覆盖」方案源。 */
    fun frostLatest(tag: String): SchemaSource = SchemaSource(
        displayName = "白霜拼音 (rime-frost)",
        schemaId = "rime_frost",
        owner = FROST_OWNER,
        repo = FROST_REPO,
        assetName = FROST_ASSET,
        version = tag.removePrefix("v"),
        mirrors = buildMirrors(FROST_OWNER, FROST_REPO, tag, FROST_ASSET),
        extraNote = "整包方案：白霜词库 + 多输入方案 + Lua + 语法模型；采用「接管但不覆盖」，不破坏现有方案",
        extraAsset = null,
        safeTakeover = true,
    )

    /** 雾凇拼音（rime-ice）：git 源码整包源（无 release）。 */
    fun iceGitSource(): SchemaSource = SchemaSource(
        displayName = "雾凇拼音 (rime-ice)",
        schemaId = "rime_ice",
        owner = ICE_OWNER,
        repo = ICE_REPO,
        assetName = ICE_REPO + ".zip",
        version = "git·" + ICE_BRANCH,
        mirrors = listOf(SchemaMirror("GitHub 源码", "https://codeload.github.com/$ICE_OWNER/$ICE_REPO/zip/refs/heads/$ICE_BRANCH")),
        extraNote = "雾凇拼音：社区热门拼音方案（仓库无 release，使用源码整包安装）",
        extraAsset = null,
        safeTakeover = true,
        sourceType = SourceType.GIT_SOURCE,
        gitBranch = ICE_BRANCH,
        stripTopDir = true,
    )

    /** 薄荷配置（oh-my-rime）：git 源码整包源（无 release）。 */
    fun mintGitSource(): SchemaSource = SchemaSource(
        displayName = "薄荷配置 (oh-my-rime)",
        schemaId = "oh_my_rime",
        owner = MINT_OWNER,
        repo = MINT_REPO,
        assetName = MINT_REPO + ".zip",
        version = "git·" + MINT_BRANCH,
        mirrors = listOf(SchemaMirror("GitHub 源码", "https://codeload.github.com/$MINT_OWNER/$MINT_REPO/zip/refs/heads/$MINT_BRANCH")),
        extraNote = "薄荷配置包：小鹤双拼/五笔/九宫格等整套配置（仓库无 release，使用源码整包安装）",
        extraAsset = null,
        safeTakeover = true,
        sourceType = SourceType.GIT_SOURCE,
        gitBranch = MINT_BRANCH,
        stripTopDir = true,
    )

    /** 全部内置项目（列表顺序）：万象主包 + lmdg + 白霜 + 雾凇 + 薄荷 */
    fun allLatest(tag: String): List<SchemaSource> =
        listOf(
            wanxiangLatest(tag),
            lmdgSource(),
            frostLatest(tag),
            iceGitSource(),
            mintGitSource(),
        )

    /** 离线/探测失败的展示兜底源列表 */
    fun allFallback(): List<SchemaSource> =
        listOf(
            wanxiangLatest(WANXIANG_FALLBACK_TAG),
            lmdgSource(),
            frostLatest(FROST_FALLBACK_TAG),
            iceGitSource(),
            mintGitSource(),
        )
}