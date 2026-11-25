/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.plugin.PluginHost
import org.fcitx.fcitx5.android.plugin.PluginInfo
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.toast

/**
 * 插件详情页：开关 / 运行统计 / 插件注册菜单（自定义规则等）/ 更新 / 卸载（带确认）。
 */
class PluginDetailFragment : PaddingPreferenceFragment() {

    private val args: SettingsRoute.PluginDetail by lazyRoute()

    private val installPluginLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) installUpdate(uri)
    }

    private fun installUpdate(uri: android.net.Uri) {
        val r = PluginHost.installMeng(uri)
        if (r.success) {
            requireContext().toast("${getString(R.string.plugin_install_ok)} ${r.pluginId} v${r.version}")
            preferenceScreen = createPreferenceScreen()
        } else {
            requireContext().toast(getString(R.string.plugin_install_invalid) + (r.error?.let { ": $it" } ?: ""))
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = createPreferenceScreen()
    }

    private fun currentPlugin(): PluginInfo? =
        PluginHost.snapshot().firstOrNull { it.manifest.id == args.pluginId }

    private fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(requireContext()).apply {
            val info = currentPlugin()
            if (info == null) {
                addPreference(getString(R.string.plugin_detail_missing), getString(R.string.plugin_detail_missing_hint)) {
                    activity?.onBackPressedDispatcher?.onBackPressed()
                }
                return@apply
            }
            val m = info.manifest

            // ===== 状态 / 开关 =====
            addCategory(R.string.plugins_app) {
                isIconSpaceReserved = false
                val stateText = when (info.state) {
                    org.fcitx.fcitx5.android.plugin.PluginState.Enabled -> getString(R.string.plugin_state_enabled)
                    org.fcitx.fcitx5.android.plugin.PluginState.Disabled -> getString(R.string.plugin_state_disabled)
                    org.fcitx.fcitx5.android.plugin.PluginState.Tripped -> getString(R.string.plugin_state_tripped)
                    org.fcitx.fcitx5.android.plugin.PluginState.Incompatible -> getString(R.string.plugin_state_incompatible)
                }
                val pref = SwitchPreferenceCompat(requireContext()).apply {
                    isSingleLineTitle = false
                    title = "${m.name}  v${m.versionText.ifEmpty { m.version.toString() }}"
                    summary = stateText
                    setIcon(m.iconRes())
                    isChecked = info.state == org.fcitx.fcitx5.android.plugin.PluginState.Enabled
                    isEnabled = info.state != org.fcitx.fcitx5.android.plugin.PluginState.Incompatible
                    setOnPreferenceChangeListener { _, newValue ->
                        val checked = newValue as Boolean
                        if (info.state == org.fcitx.fcitx5.android.plugin.PluginState.Tripped && checked) {
                            PluginHost.resetBreaker(m.id)
                        }
                        PluginHost.setEnabled(m.id, checked)
                        true
                    }
                }
                addPreference(pref)
            }

            // ===== 插件注册菜单 =====
            // 语言包+编辑语义全由插件注册（menu.editType/title/editorTitle/hint），宿主零特判
            if (m.menus.isNotEmpty()) {
                addCategory(R.string.plugin_menus) {
                    isIconSpaceReserved = false
                    m.menus.forEach { menu ->
                        when (menu.type) {
                            "toggle" -> {
                                val tp = SwitchPreferenceCompat(requireContext()).apply {
                                    isSingleLineTitle = false
                                    title = menu.title
                                    isChecked = PluginHost.menuSwitchValue(m.id, menu)
                                    setOnPreferenceChangeListener { _, newValue ->
                                        PluginHost.setMenuSwitchValue(m.id, menu, newValue as Boolean)
                                        true
                                    }
                                }
                                addPreference(tp)
                            }
                            // text + editType 表示可编辑（行列表/键值映射）
                            else -> addPreference(menu.title) {
                                when (menu.editType) {
                                    "lines" -> openLineEditor(m, menu)
                                    "kv" -> openKvEditor(m, menu)
                                    else -> requireContext().toast(getString(R.string.plugin_menu_unimplemented))
                                }
                            }
                        }
                    }
                }
            }

            // ===== 插件自定义设置（插件 manifest 声明，宿主存储）=====
            if (m.settings.isNotEmpty()) {
                addCategory(R.string.plugin_settings) {
                    isIconSpaceReserved = false
                    m.settings.forEach { st ->
                        when (st.type) {
                            "switch" -> {
                                val sp = SwitchPreferenceCompat(requireContext()).apply {
                                    isSingleLineTitle = false
                                    title = st.label
                                    isChecked = PluginHost.settingOf(m.id, st.key, st.defaultValue) == "true"
                                    setOnPreferenceChangeListener { _, nv ->
                                        val checked = nv as Boolean
                                        PluginHost.setSetting(m.id, st.key, checked.toString())
                                        // 功能打开 → 单次授权必须撤销（单次只对关闭的功能生效）
                                        if (checked && PluginHost.isOnceMode(m.id, st.key)) {
                                            PluginHost.clearOnceMode(m.id, st.key, st.defaultValue)
                                        }
                                        preferenceScreen = createPreferenceScreen()
                                        true
                                    }
                                }
                                addPreference(sp)
                                // ⚡单次（quick 开关专属）：只对「关闭」的功能生效。
                                // 功能关闭时勾单次 = 下一次粘贴临时生效一次，用完自动回常闭；
                                // 功能打开时单次不生效、开关禁用（单次是关闭状态的额外赋权）。
                                if (st.quick) {
                                    val funcOn = PluginHost.settingOf(m.id, st.key, st.defaultValue) == "true"
                                    val onceChecked = PluginHost.isOnceMode(m.id, st.key)
                                    val op = SwitchPreferenceCompat(requireContext()).apply {
                                        isSingleLineTitle = false
                                        title = "⚡仅下一次：${st.label}"
                                        summary = when {
                                            funcOn -> "功能已开启，单次不生效（仅对关闭的功能有效）"
                                            onceChecked -> "已授权，下一次粘贴生效一次后自动关闭"
                                            else -> "功能关闭时勾选 = 下一次粘贴临时开启一次"
                                        }
                                        isChecked = onceChecked
                                        isEnabled = !funcOn  // 功能开 → 单次开关禁用
                                        setOnPreferenceChangeListener { _, nv ->
                                            if (nv as Boolean) {
                                                PluginHost.enableOnceMode(m.id, st.key, st.defaultValue)
                                            } else {
                                                PluginHost.clearOnceMode(m.id, st.key, st.defaultValue)
                                            }
                                            true
                                        }
                                    }
                                    addPreference(op)
                                }
                            }
                            "select" -> {
                                addPreference(st.label, selectDisplayLabel(st)) {
                                    showSelectSetting(m, st)
                                }
                            }
                            else -> {
                                addPreference(st.label, PluginHost.settingOf(m.id, st.key, st.defaultValue)) {
                                    showTextSetting(m, st)
                                }
                            }
                        }
                    }
                }
            }

            // ===== 运行统计 =====
            addCategory(R.string.plugin_stats) {
                isIconSpaceReserved = false
                val s = info.stats
                addPreference(getString(R.string.plugin_stat_calls), s.calls.toString())
                addPreference(getString(R.string.plugin_stat_failures), s.failures.toString())
                addPreference(getString(R.string.plugin_stat_avg_ms),
                    if (s.calls > 0) "%.1f ms".format(s.totalMillis.toDouble() / s.calls) else "-")
            }

            // ===== 操作 =====
            addCategory(R.string.plugin_operations) {
                isIconSpaceReserved = false
                addPreference(R.string.plugin_update) {
                    installPluginLauncher.launch("application/octet-stream")
                }
                addPreference(R.string.plugin_uninstall) {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.plugin_uninstall_confirm_title))
                        .setMessage(getString(R.string.plugin_uninstall_confirm_message, m.name))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (PluginHost.uninstall(m.id)) {
                                requireContext().toast(R.string.plugin_uninstall_ok)
                                activity?.onBackPressedDispatcher?.onBackPressed()
                            } else {
                                requireContext().toast(R.string.plugin_uninstall_failed)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    /** select 设置：当前值对应的显示标签（支持 "值|标签" 格式，否则显示原值） */
    private fun selectDisplayLabel(st: org.fcitx.fcitx5.android.plugin.PluginSetting): String {
        val current = PluginHost.settingOf(args.pluginId, st.key, st.defaultValue)
        return st.options.firstOrNull { it.substringBefore('|') == current }
            ?.substringAfter('|', missingDelimiterValue = current) ?: current
    }

    /** 单选设置：弹窗选择（选项支持 "值|标签" 格式） */
    private fun showSelectSetting(m: org.fcitx.fcitx5.android.plugin.PluginManifest, st: org.fcitx.fcitx5.android.plugin.PluginSetting) {
        val current = PluginHost.settingOf(m.id, st.key, st.defaultValue)
        val labels = st.options.map { it.substringAfter('|', missingDelimiterValue = it) }.toTypedArray()
        val curIdx = st.options.indexOfFirst { it.substringBefore('|') == current }.let { if (it < 0) 0 else it }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(st.label)
            .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                PluginHost.setSetting(m.id, st.key, st.options[which].substringBefore('|'))
                dialog.dismiss()
                preferenceScreen = createPreferenceScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 文本设置：弹窗输入 */
    private fun showTextSetting(m: org.fcitx.fcitx5.android.plugin.PluginManifest, st: org.fcitx.fcitx5.android.plugin.PluginSetting) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(PluginHost.settingOf(m.id, st.key, st.defaultValue))
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(st.label)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PluginHost.setSetting(m.id, st.key, input.text.toString())
                preferenceScreen = createPreferenceScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 通用行列表编辑器：每行一项（如自定义规则），存 customRules，语言包由插件注册 */
    private fun openLineEditor(m: org.fcitx.fcitx5.android.plugin.PluginManifest, menu: org.fcitx.fcitx5.android.plugin.PluginMenu) {
        val current = PluginHost.customRules(m.id).joinToString("\n")
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            hint = menu.hint ?: "每行一项"
            gravity = android.view.Gravity.TOP
            setPadding(40, 40, 40, 40)
            textSize = 14f
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(input) }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(menu.editorTitle ?: menu.title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lines = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                PluginHost.setCustomRules(m.id, lines)
                requireContext().toast(R.string.plugin_menu_custom_rules_saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 通用键值映射编辑器：每行 "key=value"（如自定义镜像 A→B），存 customMirror，语言包由插件注册 */
    private fun openKvEditor(m: org.fcitx.fcitx5.android.plugin.PluginManifest, menu: org.fcitx.fcitx5.android.plugin.PluginMenu) {
        val current = PluginHost.customMirror(m.id)
            .entries.joinToString("\n") { "${it.key.trim()}=${it.value.trim()}" }
        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            hint = menu.hint ?: "每行一条 key=value"
            gravity = android.view.Gravity.TOP
            setPadding(40, 40, 40, 40)
            textSize = 14f
            setSingleLine(false)
            minLines = 6
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(input) }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(menu.editorTitle ?: menu.title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val map = input.text.toString().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx <= 0) null
                        else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    }
                    .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                    .toMap()
                PluginHost.setCustomMirror(m.id, map)
                requireContext().toast(getString(R.string.plugin_menu_custom_mirror_saved))
                preferenceScreen = createPreferenceScreen()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}