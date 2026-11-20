/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import timber.log.Timber

/**
 * 下载 / 导入 / 卸载在应用级作用域里执行，不随商店页面销毁而取消：
 * 原来用 viewModelScope，离开页面后 IO 线程里的下载照样跑完、文件也装好了，
 * 但协程已取消，成功通知、Rime 部署、状态记录全部丢失，进度通知永远挂着。
 */
object SchemaMarketTasks {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** schemaId -> 是否正在操作中 */
    val busy = MutableLiveData<Map<String, Boolean>>(emptyMap())

    /** 一次性消息：带序号，界面消费后记下序号，旋转屏幕不会重放。 */
    data class Message(val id: Long, val text: String)
    val message = MutableLiveData<Message?>(null)
    @Volatile var consumedMessageId = 0L
    private var nextId = 1L

    /** 安装结果变化（成功/卸载）时递增，页面据此刷新已安装状态。 */
    val installedGeneration = MutableLiveData(0L)

    fun post(text: String) { message.value = Message(nextId++, text) }
    fun setBusy(id: String, value: Boolean) { busy.value = busy.value.orEmpty() + (id to value) }
    fun isBusy(id: String) = busy.value.orEmpty()[id] == true
    fun installedChanged() { installedGeneration.value = (installedGeneration.value ?: 0L) + 1 }

    /** 页面不在时也要能部署：优先用页面注入的连接，没有就用输入法当前的连接。 */
    @Volatile var preferredFcitx: FcitxConnection? = null
    fun deploy() {
        val conn = preferredFcitx ?: FcitxDaemon.getFirstConnectionOrNull()
        if (conn == null) { Timber.w("Schema installed but fcitx not connected; deploy on next start"); return }
        conn.runIfReady { deployRime() }
    }

    @Volatile private var recovered = false
    fun recoverOnce() {
        if (recovered) return
        recovered = true
        scope.launch {
            runCatching { SchemaMarketManager.recoverInterrupted() }
                .onSuccess { if (it > 0) Timber.i("Schema market cleaned %d interrupted files", it) }
                .onFailure { Timber.w(it, "Schema market recovery failed") }
        }
    }
}

/**
 * 方案商店的 ViewModel：只负责页面数据（源列表、已安装版本、探测状态），
 * 实际的下载 / 安装 / 卸载交给 [SchemaMarketTasks] 在应用级作用域执行。
 */
class SchemaMarketViewModel : ViewModel() {

    private val _sources = MutableLiveData<List<SchemaSource>>()
    val sources: LiveData<List<SchemaSource>> get() = _sources

    /** schemaId -> 已安装版本 */
    private val _installedVersions = MutableLiveData<Map<String, String>>()
    val installedVersions: LiveData<Map<String, String>> get() = _installedVersions

    val busy: LiveData<Map<String, Boolean>> get() = SchemaMarketTasks.busy

    /** schemaId -> 是否正在探测镜像 */
    private val _probing = MutableLiveData<Map<String, Boolean>>()
    val probing: LiveData<Map<String, Boolean>> get() = _probing

    val message: LiveData<SchemaMarketTasks.Message?> get() = SchemaMarketTasks.message
    val installedGeneration: LiveData<Long> get() = SchemaMarketTasks.installedGeneration

    private var refreshJob: Job? = null

    init {
        SchemaMarketTasks.recoverOnce()
        refreshSources(force = false)
    }

    /** 由 Fragment 注入可用的 fcitx 连接 */
    fun attachFcitx(connection: FcitxConnection) {
        SchemaMarketTasks.preferredFcitx = connection
    }

    override fun onCleared() {
        SchemaMarketTasks.preferredFcitx = null
        super.onCleared()
    }

    /** 以 rime 数据目录里的真实文件为准；读文件放到 IO 线程，不卡界面。 */
    private val _installedFingerprints = MutableLiveData<Map<String, String>>(emptyMap())
    val installedFingerprints: LiveData<Map<String, String>> get() = _installedFingerprints

    fun refreshInstalled() {
        viewModelScope.launch {
            val (versions, fingerprints) = withContext(Dispatchers.IO) {
                SchemaSources.allFallback().mapNotNull { s ->
                    SchemaMarketManager.realInstalledVersion(s.schemaId)?.let { s.schemaId to it }
                }.toMap() to SchemaMarketManager.installedFingerprints()
            }
            _installedFingerprints.value = fingerprints
            _installedVersions.value = versions
        }
    }

    /**
     * 先立即渲染兜底源（标记检测中），再逐个探测版本与镜像。
     * [force] 为 false 时 30 分钟内复用缓存的探测结果（GitHub 未登录 API 每小时只有 60 次）；
     * 正在刷新时重复点击直接忽略。
     */
    fun refreshSources(force: Boolean = true) {
        if (refreshJob?.isActive == true) return
        _sources.value = SchemaSources.allFallback().map { it.copy(checking = true) }
        refreshInstalled()
        refreshJob = viewModelScope.launch {
            SchemaSources.allFallback().forEach { f ->
                val probed = runCatching { probeOne(f, force) }.getOrNull() ?: f.copy(checking = false, versionCheckFailed = true)
                val withMirrors = SchemaMarketManager.probeLinks(probed.copy(checking = false))
                val current = _sources.value.orEmpty()
                _sources.value = current.map { if (it.schemaId == withMirrors.schemaId) withMirrors else it }
            }
        }
    }

    private suspend fun probeOne(f: SchemaSource, force: Boolean): SchemaSource? = when {
        f.schemaId == "wanxiang" -> {
            val probe = SchemaMarketManager.fetchLatestRelease(f, force = force)
            val base = SchemaSources.wanxiangLatest(probe.version ?: SchemaSources.WANXIANG_FALLBACK_TAG)
            SchemaMarketFileOps.sourceWithReleaseProbe(base, probe.version, probe.checked, probe.digest)
        }
        f.schemaId == "rime_frost" -> {
            val probe = SchemaMarketManager.fetchLatestRelease(f, force = force)
            val base = SchemaSources.frostLatest(probe.version ?: SchemaSources.FROST_FALLBACK_TAG)
            SchemaMarketFileOps.sourceWithReleaseProbe(base, probe.version, probe.checked, probe.digest)
        }
        f.schemaId == "wanxiang-lmdg" -> {
            val probe = SchemaMarketManager.fetchReleaseAssetFingerprint(f, force = force)
            SchemaMarketFileOps.sourceWithAssetProbe(f, probe.fingerprint, probe.checked, probe.version)
        }
        f.isGitSource -> {
            val probe = SchemaMarketManager.fetchGitHead(f, force = force)
            SchemaMarketFileOps.sourceWithGitHead(f, probe.version, probe.date, probe.checked)
        }
        else -> f
    }

    /** 对给定源的所有下载链接做连通性探测；完成后原位更新该源。 */
    fun probeMirrors(source: SchemaSource) {
        _probing.value = _probing.value.orEmpty() + (source.schemaId to true)
        viewModelScope.launch {
            val probed = SchemaMarketManager.probeLinks(source)
            _probing.value = _probing.value.orEmpty() + (source.schemaId to false)
            val current = _sources.value.orEmpty()
            _sources.value = current.map { if (it.schemaId == probed.schemaId) probed else it }
        }
    }

    /** 卸载一个方案（界面已做二次确认） */
    fun uninstall(source: SchemaSource) {
        if (SchemaMarketTasks.isBusy(source.schemaId)) return
        SchemaMarketTasks.setBusy(source.schemaId, true)
        SchemaMarketTasks.scope.launch {
            val ok = withContext(Dispatchers.IO) { SchemaMarketManager.remove(source) }
            SchemaMarketTasks.setBusy(source.schemaId, false)
            if (ok) {
                SchemaMarketTasks.post("已卸载「${source.displayName}」")
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaMarketTasks.post("卸载失败，文件和安装状态未更改")
            }
        }
    }

    /** 应用内直接下载并安装，带进度通知 */
    fun downloadAndInstall(source: SchemaSource) {
        if (source.checking) { SchemaMarketTasks.post("正在检测最新版本，请稍候再下载"); return }
        if (SchemaMarketTasks.isBusy(source.schemaId)) return
        SchemaMarketTasks.setBusy(source.schemaId, true)
        SchemaMarketTasks.scope.launch {
            var lastPct = -2
            var lastAt = 0L
            val result = try {
                SchemaMarketManager.downloadAndInstall(
                    source,
                    onProgress = { downloaded, total ->
                        val pct = if (total != null && total > 0) (downloaded * 100 / total).toInt() else null
                        val now = SystemClock.elapsedRealtime()
                        // 按百分比变化且至少间隔 500ms 才刷新通知；没有总长度时只按时间节流，避免被系统限流
                        if ((pct ?: -1) != lastPct && now - lastAt >= 500L) {
                            lastPct = pct ?: -1; lastAt = now
                            SchemaDownloadNotifier.showProgress(source, pct, bytesText(downloaded, total))
                        }
                    }
                )
            } catch (e: Throwable) {
                InstallResult(false, e.message ?: e.javaClass.simpleName, source.version)
            } finally {
                SchemaMarketTasks.setBusy(source.schemaId, false)
            }
            if (result.success) {
                SchemaDownloadNotifier.showSuccess(source)
                SchemaMarketTasks.post("已安装「${source.displayName}」v${result.installedVersion}" +
                    (result.notice?.let { "\n$it" } ?: ""))
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaDownloadNotifier.showFailure(source, result.error)
                SchemaMarketTasks.post("下载失败: ${result.error ?: "未知错误"}")
            }
        }
    }

    /** 应用内独立下载并安装附加组件（万象 lmdg） */
    fun downloadAndInstallExtra(extra: SchemaExtraAsset, source: SchemaSource) {
        if (SchemaMarketTasks.isBusy(extra.schemaId)) return
        SchemaMarketTasks.setBusy(extra.schemaId, true)
        SchemaMarketTasks.scope.launch {
            var lastAt = 0L
            val notifySource = source.copy(version = extra.version)
            val result = try {
                SchemaMarketManager.downloadAndInstallExtra(
                    extra,
                    onProgress = { downloaded, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAt >= 500L) {
                            lastAt = now
                            val pct = if (total != null && total > 0) (downloaded * 100 / total).toInt() else null
                            SchemaDownloadNotifier.showProgress(notifySource, pct, bytesText(downloaded, total))
                        }
                    }
                )
            } catch (e: Throwable) {
                InstallResult(false, e.message ?: e.javaClass.simpleName, extra.version)
            } finally {
                SchemaMarketTasks.setBusy(extra.schemaId, false)
            }
            if (result.success) {
                SchemaDownloadNotifier.showSuccess(source)
                SchemaMarketTasks.post("已安装「${extra.displayName}」")
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaDownloadNotifier.showFailure(notifySource, result.error)
                SchemaMarketTasks.post("下载失败: ${result.error ?: "未知错误"}")
            }
        }
    }

    /** 卸载附加组件 */
    fun uninstallExtra(extra: SchemaExtraAsset) {
        SchemaMarketTasks.scope.launch {
            val ok = withContext(Dispatchers.IO) { SchemaMarketManager.removeExtra(extra) }
            if (ok) {
                SchemaMarketTasks.post("已卸载「${extra.displayName}」")
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaMarketTasks.post("卸载失败，文件和安装状态未更改")
            }
        }
    }

    /** 用户通过系统文件选择器选中的 zip，调用本地导入 */
    fun importZip(source: SchemaSource, uri: Uri) {
        if (SchemaMarketTasks.isBusy(source.schemaId)) return
        SchemaMarketTasks.setBusy(source.schemaId, true)
        SchemaMarketTasks.scope.launch {
            val result = try { SchemaMarketManager.importZip(source, uri) }
                catch (e: Throwable) { InstallResult(false, e.message ?: e.javaClass.simpleName, source.version) }
                finally { SchemaMarketTasks.setBusy(source.schemaId, false) }
            if (result.success) {
                SchemaMarketTasks.post("已安装「${source.displayName}」v${result.installedVersion}" +
                    (result.notice?.let { "\n$it" } ?: ""))
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaMarketTasks.post("导入失败: ${result.error ?: "未知错误"}")
            }
        }
    }

    /** 用户通过系统文件选择器选中的单文件模型（.gram 等），调用本地导入 */
    fun importExtraAsset(extra: SchemaExtraAsset, uri: Uri) {
        if (SchemaMarketTasks.isBusy(extra.schemaId)) return
        SchemaMarketTasks.setBusy(extra.schemaId, true)
        SchemaMarketTasks.scope.launch {
            val result = try { SchemaMarketManager.importExtraAsset(extra, uri) }
                catch (e: Throwable) { InstallResult(false, e.message ?: e.javaClass.simpleName, extra.version) }
                finally { SchemaMarketTasks.setBusy(extra.schemaId, false) }
            if (result.success) {
                SchemaMarketTasks.post("已安装「${extra.displayName}」v${result.installedVersion}")
                SchemaMarketTasks.installedChanged()
                SchemaMarketTasks.deploy()
            } else {
                SchemaMarketTasks.post("导入失败: ${result.error ?: "未知错误"}")
            }
        }
    }

    private fun bytesText(downloaded: Long, total: Long?): String {
        val mb = downloaded / 1024.0 / 1024.0
        val totalMb = total?.let { it / 1024.0 / 1024.0 }
        return if (totalMb != null) String.format("%.1f / %.1f MB", mb, totalMb) else String.format("%.1f MB", mb)
    }
}
