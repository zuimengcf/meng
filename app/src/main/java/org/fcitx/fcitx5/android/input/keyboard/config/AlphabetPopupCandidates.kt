/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

/**
 * 字母长按的大小写输入是键盘功能，不是 JSON 的额外符号。
 * 两套键盘共用完整 a-z 规则：小写状态可长按输入大写英文，英文大写状态反向输入小写。
 * 仅补普通 ASCII 字母，不从旧音标/注音预设恢复其他字符。
 */
object AlphabetPopupCandidates {
    fun resolve(key: String, items: List<String>, uppercase: Boolean = false): List<String> {
        if (key.length != 1 || (key[0] !in 'a'..'z' && key[0] !in 'A'..'Z')) return items
        val letter = if (uppercase) key.lowercase() else key.uppercase()
        // 兼容之前只给 g 手工加 G 的配置，避免重复、陈旧大小写候选；其他自定义内容保留。
        val symbols = items.filterNot { it.length == 1 && it.equals(key, ignoreCase = true) }
        return buildList {
            symbols.firstOrNull()?.let(::add)
            add(letter)
            addAll(symbols.drop(1))
        }.distinct()
    }
}
