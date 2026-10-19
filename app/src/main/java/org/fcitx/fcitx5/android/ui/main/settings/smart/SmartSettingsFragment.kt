/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.smart

import android.os.Bundle
import androidx.preference.PreferenceScreen
import androidx.preference.Preference
import androidx.recyclerview.widget.SimpleItemAnimator
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.smart.SmartInputApi
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast

/**
 * 「梦」智能输入设置页：智能统计 / 智能联想 / 智能纠错三组（由 Smart.createUi 渲染）。
 * - 「智能统计」分组内追加「查看统计」「清空统计」入口；
 * - 跳转统计展示页（日/周/月/全部 + 字数 + TOP 常用词 + 连续性联想数据）。
 */
class SmartSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().smart) {

    private var suggestionLimitPreference: Preference? = null
    private val smart = AppPrefs.getInstance().smart
    private val suggestionEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        suggestionLimitPreference?.isEnabled = enabled
    }
    private val correctionEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        listOf(
            smart.correctNeighbor.key,
            smart.correctFuzzy.key,
            smart.correctHabit.key,
        ).forEach { findPreference<Preference>(it)?.isEnabled = enabled }
    }

    private fun installExtras() {
        // 必须在 createUi 之前设置：createUi 在 onCreatePreferences 里先于 onPreferenceUiCreated 调用。
        smart.statsGroupExtra = { category ->
            category.addPreference(
                R.string.smart_stats_view,
                R.string.smart_stats_view_hint,
                onClick = { navigateWithAnim(SettingsRoute.InputStats) }
            )
            category.addPreference(
                R.string.smart_stats_clear,
                R.string.smart_stats_clear_hint,
                onClick = {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.smart_stats_clear)
                        .setMessage(R.string.smart_stats_clear_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            InputStatsManager.clearAllAsync()
                            requireContext().toast(R.string.smart_stats_cleared)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            )
        }
        smart.suggestionGroupExtra = { category ->
            val values = intArrayOf(5, 10, 15, 20, 30, 50)
            lateinit var preference: Preference
            preference = Preference(requireContext()).apply {
                key = "smart_suggestion_limit_ui"
                isIconSpaceReserved = false
                isSingleLineTitle = false
                title = getString(R.string.smart_suggestion_limit)
                summary = getString(R.string.smart_suggestion_limit_advanced_summary, 15)
                setOnPreferenceClickListener {
                    if (!isAdded) return@setOnPreferenceClickListener true
                    SmartInputApi.getConfig(object : SmartInputApi.Callback<Map<String, String>> {
                        override fun onSuccess(config: Map<String, String>) {
                            if (!isAdded) return
                            val current = config["suggestion_limit"]?.toIntOrNull()?.coerceIn(0, 50) ?: 15
                            showSuggestionLimitDialog(preference, values, current)
                        }
                        override fun onError(error: Throwable) {
                            if (isAdded) requireContext().toast(R.string.smart_suggestion_limit_failed)
                        }
                    })
                    true
                }
            }
            suggestionLimitPreference = preference
            preference.isEnabled = AppPrefs.getInstance().smart.suggestEnabled.getValue()
            category.addPreference(preference)
            SmartInputApi.getConfig(object : SmartInputApi.Callback<Map<String, String>> {
                override fun onSuccess(config: Map<String, String>) {
                    if (isAdded) preference.summary = getString(
                        R.string.smart_suggestion_limit_advanced_summary,
                        config["suggestion_limit"]?.toIntOrNull()?.coerceIn(0, 50) ?: 15
                    )
                }
                override fun onError(error: Throwable) = Unit
            })
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Fragment 可能复用并重新创建 view；闭包只在该 view 的生命周期内挂到 Smart。
        installExtras()
        super.onCreatePreferences(savedInstanceState, rootKey)
    }

    private fun showSuggestionLimitDialog(preference: Preference, values: IntArray, current: Int) {
        val labels = values.map(Int::toString).toTypedArray()
        val checked = values.indexOf(current)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.smart_suggestion_limit)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = values[which]
                SmartInputApi.configure(
                    mapOf("suggestion_limit" to selected.toString()),
                    object : SmartInputApi.Callback<Map<String, String>> {
                        override fun onSuccess(config: Map<String, String>) {
                            if (isAdded) preference.summary = getString(
                                R.string.smart_suggestion_limit_advanced_summary,
                                config["suggestion_limit"]?.toIntOrNull() ?: selected
                            )
                        }
                        override fun onError(error: Throwable) {
                            if (isAdded) requireContext().toast(R.string.smart_suggestion_limit_failed)
                        }
                    }
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Preference changes rebind the row. Avoid RecyclerView cross-fading the entire
        // statistics row; the switch's own checked-state animation remains intact.
        (listView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        smart.suggestEnabled.registerOnChangeListener(suggestionEnabledListener)
        smart.pinyinCorrectEnabled.registerOnChangeListener(correctionEnabledListener)
        suggestionEnabledListener.onChange(smart.suggestEnabled.key, smart.suggestEnabled.getValue())
        correctionEnabledListener.onChange(smart.pinyinCorrectEnabled.key, smart.pinyinCorrectEnabled.getValue())
        suggestionLimitPreference = findPreference("smart_suggestion_limit_ui")
        suggestionLimitPreference?.isEnabled = smart.suggestEnabled.getValue()
    }

    override fun onDestroyView() {
        smart.suggestEnabled.unregisterOnChangeListener(suggestionEnabledListener)
        smart.pinyinCorrectEnabled.unregisterOnChangeListener(correctionEnabledListener)
        smart.statsGroupExtra = null
        smart.suggestionGroupExtra = null
        suggestionLimitPreference = null
        super.onDestroyView()
    }

}
