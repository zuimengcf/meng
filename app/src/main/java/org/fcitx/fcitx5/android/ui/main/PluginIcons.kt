/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.plugin.PluginManifest

/**
 * 插件图标映射：manifest.icon（宿主内置 drawable 名称）→ R.drawable。
 * 未声明或未知名称回退默认扩展图标。UI 侧（插件列表/状态区）统一调用。
 */
fun PluginManifest.iconRes(): Int = when (icon) {
    "text_format" -> R.drawable.ic_baseline_text_format_24
    "language" -> R.drawable.ic_baseline_language_24
    "library_books" -> R.drawable.ic_baseline_library_books_24
    "emoji_symbols" -> R.drawable.ic_baseline_emoji_symbols_24
    "mood" -> R.drawable.ic_baseline_emoji_symbols_24
    "emoji_people" -> R.drawable.ic_baseline_emoji_people_24
    "format_quote" -> R.drawable.ic_baseline_format_quote_24
    "search" -> R.drawable.ic_baseline_search_24
    "content_paste" -> R.drawable.ic_baseline_content_paste_24
    "edit" -> R.drawable.ic_baseline_edit_24
    "code" -> R.drawable.ic_baseline_code_24
    "auto_awesome" -> R.drawable.ic_baseline_auto_awesome_24
    "extension" -> R.drawable.ic_baseline_extension_24
    "tune" -> R.drawable.ic_baseline_tune_24
    "settings" -> R.drawable.ic_baseline_settings_24
    "palette" -> R.drawable.ic_baseline_palette_24
    "list_alt" -> R.drawable.ic_baseline_list_alt_24
    "keyboard" -> R.drawable.ic_baseline_keyboard_24
    "spellcheck" -> R.drawable.ic_baseline_spellcheck_24
    "emoji_objects" -> R.drawable.ic_baseline_emoji_objects_24
    "info" -> R.drawable.ic_baseline_info_24
    "description" -> R.drawable.ic_baseline_description_24
    "check" -> R.drawable.ic_baseline_check_24
    "share" -> R.drawable.ic_baseline_share_24
    "save" -> R.drawable.ic_baseline_save_24
    "delete" -> R.drawable.ic_baseline_delete_24
    "android" -> R.drawable.ic_baseline_android_24
    else -> R.drawable.ic_baseline_extension_24
}