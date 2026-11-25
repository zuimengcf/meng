/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.FileSource
import org.fcitx.fcitx5.android.core.data.PluginLoadFailed
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
class PluginFragment : PaddingPreferenceFragment() {
    // 安装/更新插件：选择 .meng 独立安装包
    private val installPluginLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) installPlugin(uri)
    }
    private fun installPlugin(uri: Uri) {
        // 兼容 zip 批量容器与单 .meng 包：宿主按内容自动检测，无需区分 mime。
        val r = org.fcitx.fcitx5.android.plugin.PluginHost.installMengOrZip(uri)
        val ok = r.results.filter { it.success }
        if (ok.isNotEmpty()) {
            // 官方/第三方提示：指纹校验命中的标「官方」，其余提示第三方（免责）
            val parts = ok.map { res ->
                val tag = if (res.official) "[官方]" else "[第三方]"
                "$tag ${res.pluginId ?: "?"}"
            }
            val names = parts.joinToString().take(80)
            requireContext().toast(getString(R.string.plugin_install_ok) + " $names")
            preferenceScreen = createPreferenceScreen()
        } else {
            val err = r.results.firstOrNull()?.error
            requireContext().toast(getString(R.string.plugin_install_invalid) + (err?.let { ": $it" } ?: ""))
        }
    }
    private var firstRun = true

    private lateinit var synced: DataManager.PluginSet
    private lateinit var detected: DataManager.PluginSet
    /** 上一次渲染的应用级（.meng）插件 id 快照；用于检测卸载/安装后的变化 */
    private var lastAppPluginIds: Set<String>? = null

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshPreferencesWhenNeeded()
        }
    }

    private fun DataManager.whenSynced(block: () -> Unit) {
        lifecycleScope.launch {
            if (!synced) {
                suspendCancellableCoroutine {
                    if (synced) {
                        it.resumeWith(Result.success(Unit))
                    } else {
                        addOnNextSyncedCallback {
                            it.resumeWith(Result.success(Unit))
                        }
                    }
                }
            }
            block.invoke()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DataManager.whenSynced {
            synced = DataManager.getSyncedPluginSet()
            detected = DataManager.detectPlugins()
            lastAppPluginIds = appPluginIds()
            preferenceScreen = createPreferenceScreen()
        }
    }

    /** 当前 Host 纳管的应用级（.meng）插件 id 集合 */
    private fun appPluginIds(): Set<String> =
        org.fcitx.fcitx5.android.plugin.PluginHost.snapshot().map { it.manifest.id }.toSet()

    private fun refreshPreferencesWhenNeeded() {
        DataManager.whenSynced {
            val newDetected = DataManager.detectPlugins()
            val newAppPluginIds = appPluginIds()
            if (detected != newDetected || lastAppPluginIds != newAppPluginIds) {
                detected = newDetected
                lastAppPluginIds = newAppPluginIds
                preferenceScreen = createPreferenceScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().registerReceiver(packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        /**
         * [onResume] got called after [onCreatePreferences] when the fragment is created and
         * shown for the first time
         */
        if (firstRun) {
            firstRun = false
            return
        }
        // try refresh plugin list when the user navigate back from other apps
        refreshPreferencesWhenNeeded()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(packageChangeReceiver)
    }

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            if (synced != detected) {
                addPreference(R.string.plugin_needs_reload, icon = R.drawable.ic_baseline_info_24) {
                    DataManager.addOnNextSyncedCallback {
                        synced = DataManager.getSyncedPluginSet()
                        detected = DataManager.detectPlugins()
                        preferenceScreen = createPreferenceScreen()
                    }
                    // DataManager.sync and and restart fcitx
                    FcitxDaemon.restartFcitx()
                }
            }
            val (loaded, failed) = synced
            val appPlugins = org.fcitx.fcitx5.android.plugin.PluginHost.snapshot()
            val hasEnginePlugins = loaded.isNotEmpty() || failed.isNotEmpty()
            if (!hasEnginePlugins && appPlugins.isEmpty()) {
                // use PreferenceCategory to show a divider below the "reload" preference
                addCategory(R.string.no_plugins) {
                    isIconSpaceReserved = false
                    @SuppressLint("PrivateResource")
                    // we can't hide PreferenceCategory's title,
                    // but we can make it looks like a normal preference
                    layoutResource = androidx.preference.R.layout.preference_material
                }
            }
            if (loaded.isNotEmpty()) {
                addCategory(R.string.plugins_loaded) {
                    isIconSpaceReserved = false
                    loaded.forEach {
                        addPreference(it.name, "${it.versionName}\n${it.description}") {
                            startPluginAboutActivity(it.packageName)
                        }
                    }
                }
            }
            if (failed.isNotEmpty()) {
                addCategory(R.string.plugins_failed) {
                    isIconSpaceReserved = false
                    failed.forEach { (packageName, reason) ->
                        val summary = when (reason) {
                            is PluginLoadFailed.DataDescriptorParseError -> {
                                getString(R.string.invalid_data_descriptor)
                            }
                            is PluginLoadFailed.MissingDataDescriptor -> {
                                getString(R.string.missing_data_descriptor)
                            }
                            PluginLoadFailed.MissingPluginDescriptor -> {
                                getString(R.string.missing_plugin_descriptor)
                            }
                            is PluginLoadFailed.PathConflict -> {
                                val owner = when (reason.existingSrc) {
                                    FileSource.Main -> getString(R.string.main_program)
                                    is FileSource.Plugin -> reason.existingSrc.descriptor.name
                                }
                                getString(R.string.path_conflict, reason.path, owner)
                            }
                            is PluginLoadFailed.PluginAPIIncompatible -> {
                                getString(R.string.incompatible_api, reason.api)
                            }
                            PluginLoadFailed.PluginDescriptorParseError -> {
                                getString(R.string.invalid_plugin_descriptor)
                            }
                        }
                        addPreference(packageName, summary) {
                            startPluginAboutActivity(packageName)
                        }
                    }
                }
            }
            // ===== 应用级插件（.meng 独立安装，不随主程序内置）=====
            // 「更多」面板排序入口（静态功能 + 插件 + 引擎状态统一排序）
            addPreference("更多面板排序", "长按拖动调整「更多」面板中功能与插件的顺序", icon = R.drawable.ic_baseline_drag_handle_24) {
                navigateWithAnim(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.StatusAreaOrder)
            }
            // 常驻「安装插件」入口（不只在空列表时显示）
            addPreference(getString(R.string.plugin_install_prompt), getString(R.string.plugin_install_prompt_hint), icon = R.drawable.ic_baseline_plus_24) {
                installPluginLauncher.launch("application/octet-stream")
            }
            addCategory(R.string.plugins_app) {
                isIconSpaceReserved = false
                if (appPlugins.isEmpty()) {
                    // 未安装任何插件：提示
                    addPreference(getString(R.string.plugins_app), getString(R.string.no_plugins), icon = R.drawable.ic_baseline_extension_24)
                } else {
                    // 每个插件一行：名称+版本+状态，点击进详情页
                    appPlugins.forEach { info ->
                        val m = info.manifest
                        val stateText = when (info.state) {
                            org.fcitx.fcitx5.android.plugin.PluginState.Enabled ->
                                getString(R.string.plugin_state_enabled)
                            org.fcitx.fcitx5.android.plugin.PluginState.Disabled ->
                                getString(R.string.plugin_state_disabled)
                            org.fcitx.fcitx5.android.plugin.PluginState.Tripped ->
                                getString(R.string.plugin_state_tripped)
                            org.fcitx.fcitx5.android.plugin.PluginState.Incompatible ->
                                getString(R.string.plugin_state_incompatible)
                        }
                        // 官方徽标：安装时指纹校验已通过（id 在官方表内视为官方包）
                        val officialTag = if (org.fcitx.fcitx5.android.plugin.OfficialFingerprints.isOfficialId(m.id)) " · 官方" else ""
                        addPreference("${m.name}$officialTag  v${m.versionText.ifEmpty { m.version.toString() }}", stateText, icon = m.iconRes()) {
                            navigateWithAnim(org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute.PluginDetail(m.id))
                        }
                    }
                }
            }
        }
    private fun startPluginAboutActivity(pkg: String): Boolean {
        val ctx = requireContext()
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                Intent(DataManager.PLUGIN_INTENT),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            pm.queryIntentActivities(Intent(DataManager.PLUGIN_INTENT), PackageManager.MATCH_ALL)
        }.firstOrNull {
            it.activityInfo.packageName == pkg
        }?.also {
            ctx.startActivity(Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            })
        } ?: run {
            // fallback to settings app info page if activity not found
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.fromParts("package", pkg, null)
            })
        }
        return true
    }

}
