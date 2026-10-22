/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.KeyboardLayoutEditActivity
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.toast

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()

        screen.addPreference(
            R.string.keyboard_position_menu_hint_title,
            R.string.keyboard_position_menu_hint
        )

        // 自定义键盘布局：中文、英文、数字可分别编辑 + 打开文件目录（高级）
        screen.addPreference(R.string.keyboard_layout_customize, R.string.keyboard_layout_editor_summary) {
            val items = arrayOf(
                "中文键盘",
                "英文键盘",
                ctx.getString(R.string.keyboard_editor_numbers),
                ctx.getString(R.string.open_layout_file),
            )
            AlertDialog.Builder(ctx)
                .setTitle(R.string.keyboard_layout_customize)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> { // 中文键盘编辑器
                            ctx.startActivity(
                                Intent(ctx, KeyboardLayoutEditActivity::class.java)
                                    .putExtra(KeyboardLayoutEditActivity.EXTRA_LAYOUT, TextKeyboardLayoutName)
                            )
                        }
                        1 -> { // 英文键盘编辑器
                            ctx.startActivity(
                                Intent(ctx, KeyboardLayoutEditActivity::class.java)
                                    .putExtra(KeyboardLayoutEditActivity.EXTRA_LAYOUT, EnglishKeyboardLayoutName)
                            )
                        }
                        2 -> { // 数字键盘编辑器
                            ctx.startActivity(
                                Intent(ctx, KeyboardLayoutEditActivity::class.java)
                                    .putExtra(KeyboardLayoutEditActivity.EXTRA_LAYOUT, NumberKeyboardLayoutName)
                            )
                        }
                        3 -> { // 打开文件目录（高级）
                            try {
                                ctx.startActivity(buildDocumentsProviderIntent())
                            } catch (e: Exception) {
                                ctx.toast(e)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private companion object {
        const val TextKeyboardLayoutName = "Text"
        const val EnglishKeyboardLayoutName = "English"
        const val NumberKeyboardLayoutName = "Number"
    }
}
