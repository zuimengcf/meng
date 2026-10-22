/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

/** Selects punctuation width from the active input method/sub-mode. */
object KeyboardPunctuationMode {
    private val chineseToEnglish = linkedMapOf(
        "……" to "...",
        "——" to "--",
        "《" to "<",
        "》" to ">",
        "“" to "\"",
        "”" to "\"",
        "‘" to "'",
        "’" to "'",
        "。" to ".",
        "，" to ",",
        "？" to "?",
        "！" to "!",
        "：" to ":",
        "；" to ";",
        "（" to "(",
        "）" to ")",
        "【" to "[",
        "】" to "]",
        "～" to "~",
        "、" to ",",
        "〈" to "<", "〉" to ">",
        "「" to "\"", "」" to "\"",
        "『" to "\"", "』" to "\"",
        "〔" to "[", "〕" to "]",
        "·" to ".", "￥" to "$",
        "—" to "-", "…" to "..."
    )

    private val englishToChinese = linkedMapOf(
        "..." to "……",
        "--" to "——",
        "<" to "《",
        ">" to "》",
        "\"" to "“",
        "'" to "‘",
        "." to "。",
        "," to "，",
        "?" to "？",
        "!" to "！",
        ":" to "：",
        ";" to "；",
        "(" to "（",
        ")" to "）",
        "[" to "【",
        "]" to "】",
        "~" to "～"
    )

    private val rimeAsciiIcons = setOf("fcitx_rime_latin", "fcitx_rime_latin_upper")

    fun isChinese(
        languageCode: String,
        addon: String,
        uniqueName: String,
        subModeIcon: String,
        subModeLabel: String,
        subModeName: String,
        icon: String = ""
    ): Boolean {
        val language = languageCode.trim().lowercase()
        val addonText = addon.lowercase()
        val uniqueText = uniqueName.lowercase()
        val modeIcon = subModeIcon.trim().lowercase()
        val fallbackIcon = icon.trim().lowercase()
        val isRime = "rime" in addonText || "rime" in uniqueText || "rime" in modeIcon || "rime" in fallbackIcon
        if (!isRime) return language.startsWith("zh")

        // rimeengine.cpp reports these icons from RimeStatus::is_ascii_mode/is_disabled.
        if (modeIcon in rimeAsciiIcons || modeIcon == "fcitx_rime_disable") return false
        if (modeIcon == "fcitx-rime") return true
        if (fallbackIcon in rimeAsciiIcons || fallbackIcon == "fcitx_rime_disable") return false

        // rimestate.cpp returns asciiModeName() for both subMode and subModeLabel
        // in ASCII mode (normally "Latin Mode" / "abc" or "ABC"); schema names
        // are returned for Chinese mode.  This is deliberately only a fallback.
        val modeTexts = listOf(subModeLabel, subModeName).map { it.trim().lowercase() }
        if (modeTexts.any { it == "abc" || it == "latin mode" || it == "ascii mode" || it == "ascii_mode" || it == "latin" }) {
            return false
        }
        return true
    }

    fun display(text: String, chinese: Boolean): String =
        replaceLongest(text, if (chinese) englishToChinese else chineseToEnglish)

    fun candidates(items: List<String>, chinese: Boolean): List<String> {
        val seen = LinkedHashSet<String>(items.size)
        items.forEach { seen += display(it, chinese) }
        return seen.toList()
    }

    private fun replaceLongest(text: String, replacements: Map<String, String>): String {
        if (text.isEmpty()) return text
        val keys = replacements.keys.sortedByDescending { it.length }
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val key = keys.firstOrNull { text.startsWith(it, index) }
            if (key == null) {
                result.append(text[index])
                index++
            } else {
                result.append(replacements.getValue(key))
                index += key.length
            }
        }
        return result.toString()
    }
}
