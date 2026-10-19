/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.daemon.FcitxConnection

/**
 * 方案商店的 ViewModel。
 *
 * 负责：
 * - 异步探测各方案的最新 release（GitHub API），据此动态构造最新源；
 *   并对各下载源做连通性探测（填充 [SchemaMirror.status]）。
 * - 应用内直接下载并安装（带进度通知）。
 * - 本地导入（zip）与卸载。
 * - 上述操作成功后触发 Rime 部署。
 */
class SchemaMarketViewModel : ViewModel() {

    private val _sources = MutableLiveData<List<SchemaSource>>()
    val sources: LiveData<List<SchemaSource>> get() = _sources

    /** schemaId -> 已安装版本 */
    private val _installedVersions = MutableLiveData<Map<String, String>>()
    val installedVersions: LiveData<Map<String, String>> get() = _installedVersions

    /** schemaId -> 是否正在操作中（true=导入中/下载中, false=空闲） */
    private val _busy = MutableLiveData<Map<String, Boolean>>()
    val busy: LiveData<Map<String, Boolean>> get() = _busy

    /** schemaId -> 是否正在探测镜像 */
    private val _probing = MutableLiveData<Map<String, Boolean>>()
    val probing: LiveData<Map<String, Boolean>> get() = _probing

    /** 一次性操作消息（Toast） */
    private val _message = MutableLiveData("")
    val message: LiveData<String> get() = _message

    @Volatile
    private var fcitx: FcitxConnection? = null

    init {
        refreshInstalled()
        refreshSources()
    }

    /** 由 Fragment 注入可用的 fcitx 连接 */
    fun attachFcitx(connection: FcitxConnection) {
        fcitx = connection
    }

    fun refreshInstalled() {
        // 以 rime 数据目录里的真实文件为准（读 vversion.txt / .gram 实体），
        // 而非只信可能为空的 `.meng_schema_versions` 记录。
        val versions = mutableMapOf<String, String>()
        SchemaSources.allFallback().forEach { s ->
            SchemaMarketManager.realInstalledVersion(s.schemaId)?.let {
                versions[s.schemaId] = it
            }
        }
        _installedVersions.value = versions
    }

    /**
     * 异步探测各方案的最新 release，并据此动态构造源的版本与链接，
     * 随后对下载源做连通性探测。
     * 探测失败时用兜底源（仅展示，不作为更新判定依据）。
     */
    fun refreshSources() {
        viewModelScope.launch {
            val fallback = SchemaSources.allFallback()
            // 逐个探测每个可动态探测版本的方案（万象/白霜走 release API；lmdg 固定 tag 不探测）
            val sources = fallback.mapNotNull { f ->
                if (f.isGitSource) {
                    f // git 源码源：直接展示，不探测 release 版本
                } else if (f.schemaId == "wanxiang") {
                    val latest = SchemaMarketManager.fetchLatestRelease(f)
                    if (latest != null) SchemaSources.wanxiangLatest(latest)
                    else SchemaSources.wanxiangLatest(SchemaSources.WANXIANG_FALLBACK_TAG)
                } else if (f.schemaId == "rime_frost") {
                    val latest = SchemaMarketManager.fetchLatestRelease(f)
                    if (latest != null) SchemaSources.frostLatest(latest)
                    else SchemaSources.frostLatest(SchemaSources.FROST_FALLBACK_TAG)
                } else {
                    f // lmdg 等固定 release 源直接展示，不探测版本
                }
            }
            _sources.value = sources
            sources.forEach { probeMirrors(it) }
        }
    }

    /** 对给定源的所有下载链接做连通性探测 */
    fun probeMirrors(source: SchemaSource) {
        _probing.value = _probing.value.orEmpty() + (source.schemaId to true)
        viewModelScope.launch {
            val probed = SchemaMarketManager.probeLinks(source)
            _probing.value = _probing.value.orEmpty() + (source.schemaId to false)
            val others = _sources.value.orEmpty().filter { it.schemaId != probed.schemaId }
            _sources.value = others + probed
        }
    }

    /** 卸载一个方案 */
    fun uninstall(source: SchemaSource) {
        viewModelScope.launch {
            SchemaMarketManager.remove(source)
            refreshInstalled()
            _message.value = "已卸载「${source.displayName}」"
            triggerRimeDeploy()
        }
    }

    /** 应用内直接下载并安装，带进度通知；仅主包，不附 lmdg */
    fun downloadAndInstall(source: SchemaSource) {
        if (_busy.value.orEmpty()[source.schemaId] == true) return
        _busy.value = _busy.value.orEmpty() + (source.schemaId to true)
        viewModelScope.launch {
            var lastPct = -1
            val result = SchemaMarketManager.downloadAndInstall(
                source,
                onProgress = { downloaded, total ->
                    val pct = if (total != null && total > 0) (downloaded * 100 / total).toInt() else null
                    if (pct == null || pct != lastPct) {
                        lastPct = pct ?: -1
                        SchemaDownloadNotifier.showProgress(source, pct, bytesText(downloaded, total))
                    }
                }
            )
            _busy.value = _busy.value.orEmpty() + (source.schemaId to false)
            if (result.success) {
                SchemaDownloadNotifier.showSuccess(source)
                refreshInstalled()
                _message.value = "已安装「${source.displayName}」v${result.installedVersion}"
                triggerRimeDeploy()
            } else {
                SchemaDownloadNotifier.showFailure(source, result.error)
                _message.value = "下载失败: ${result.error ?: "未知错误"}"
            }
        }
    }

    /** 应用内独立下载并安装附加组件（万象 lmdg），独立进度通知与版本记录 */
    fun downloadAndInstallExtra(extra: SchemaExtraAsset, source: SchemaSource) {
        if (_busy.value.orEmpty()[extra.schemaId] == true) return
        _busy.value = _busy.value.orEmpty() + (extra.schemaId to true)
        viewModelScope.launch {
            var lastPct = -1
            val result = SchemaMarketManager.downloadAndInstallExtra(
                extra,
                onProgress = { downloaded, total ->
                    val pct = if (total != null && total > 0) (downloaded * 100 / total).toInt() else null
                    if (pct == null || pct != lastPct) {
                        lastPct = pct ?: -1
                        SchemaDownloadNotifier.showProgress(source.copy(version = extra.version), pct, bytesText(downloaded, total))
                    }
                }
            )
            _busy.value = _busy.value.orEmpty() + (extra.schemaId to false)
            if (result.success) {
                SchemaDownloadNotifier.showSuccess(source)
                refreshInstalled()
                _message.value = "已安装「${extra.displayName}」"
                triggerRimeDeploy()
            } else {
                SchemaDownloadNotifier.showFailure(source.copy(version = extra.version), result.error)
                _message.value = "下载失败: ${result.error ?: "未知错误"}"
            }
        }
    }

    /** 卸载附加组件（仅删除版本记录） */
    fun uninstallExtra(extra: SchemaExtraAsset) {
        viewModelScope.launch {
            SchemaMarketManager.removeExtra(extra)
            refreshInstalled()
            _message.value = "已卸载「${extra.displayName}」"
        }
    }

    /** 用户通过系统文件选择器选中的 zip，调用本地导入 */
    fun importZip(source: SchemaSource, uri: Uri) {
        if (_busy.value.orEmpty()[source.schemaId] == true) return
        _busy.value = _busy.value.orEmpty() + (source.schemaId to true)
        viewModelScope.launch {
            val result = SchemaMarketManager.importZip(source, uri)
            _busy.value = _busy.value.orEmpty() + (source.schemaId to false)
            if (result.success) {
                refreshInstalled()
                _message.value = "已安装「${source.displayName}」v${result.installedVersion}"
                triggerRimeDeploy()
            } else {
                _message.value = "导入失败: ${result.error ?: "未知错误"}"
            }
        }
    }

    private fun bytesText(downloaded: Long, total: Long?): String {
        val mb = downloaded / 1024.0 / 1024.0
        val totalMb = total?.let { it / 1024.0 / 1024.0 }
        return if (totalMb != null) {
            String.format("%.1f / %.1f MB", mb, totalMb)
        } else {
            String.format("%.1f MB", mb)
        }
    }

    /** 安装后触发 Rime 重新部署，使新方案生效 */
    private fun triggerRimeDeploy() {
        fcitx?.runIfReady {
            deployRime()
        }
    }
}