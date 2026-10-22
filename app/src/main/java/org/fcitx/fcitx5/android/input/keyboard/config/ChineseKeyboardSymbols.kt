/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

/** 字面符号：键面、滑动预览、长按候选和提交内容使用同一字符串。 */
object ChineseKeyboardSymbols {
    // 數字行僅保留數字；擴充符號集中在底排長按，K/L原位保持圓括號。
    val candidates: Map<String, List<String>> = linkedMapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("3"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6"),
        "u" to listOf("7"),
        "i" to listOf("8"),
        "o" to listOf("9"),
        "p" to listOf("0"),
        "a" to listOf("@", "，", "、"),
        "s" to listOf("*", "；"),
        "d" to listOf("+", "±"),
        "f" to listOf("-"),
        "g" to listOf("=", "≠", "≈"),
        "h" to listOf("/", "÷"),
        "j" to listOf("#", "【", "】"),
        "k" to listOf("（", "《", "{"),
        "l" to listOf("）", "》", "}"),
        "z" to listOf("'", "’", "「", "」", "『", "』"),
        "x" to listOf(":", "×", "%", "‰"),
        "c" to listOf("\"", "”", "〔", "〕"),
        "v" to listOf("?", "……"),
        "b" to listOf("!", "￥"),
        "n" to listOf("~", "—", "——"),
        "m" to listOf(".", "\\", "&", "_", "·", "ü")
    )

    /** M 的英文點號不隨中英文模式轉換；其他鍵（含獨立句號鍵）維持原策略。 */
    fun isLiteralPeriod(key: String, text: String): Boolean = key.equals("m", ignoreCase = true) && text == "."

    fun display(key: String, text: String, chinese: Boolean): String =
        if (isLiteralPeriod(key, text)) text else KeyboardPunctuationMode.display(text, chinese)

    fun displayedCandidates(key: String, items: List<String>, chinese: Boolean): List<String> =
        items.map { display(key, it, chinese) }.distinct()

    /** 精确识别旧出厂配置；只替换从未自定义过的外部副本。 */
    fun legacyTextTemplate(defaults: KeyboardLayoutConfig): KeyboardLayoutConfig {
        val letters = "qwertyuiopasdfghjklzxcvbnm"
        val alts = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "@", "*", "+", "-", "=", "/", "#", "(", ")", "'", ":", "\"", "?", "!", "~", "\\")
        val oldAlts = letters.map(Char::toString).zip(alts).toMap()
        return defaults.copy(keys = defaults.keys.mapValues { (id, key) ->
            when {
                key is KeySpec.Letter && id in oldAlts -> key.copy(alt = oldAlts.getValue(id), popup = null)
                key is KeySpec.Symbol && id == "dot" -> key.copy(char = ".")
                key is KeySpec.Comma && id == "comma" -> key.copy(char = ",")
                else -> key
            }
        })
    }

    /** alt为隐含首项，popup仅存额外候选；兼容旧版完整列表，null保留旧版默认回退，[]明确无额外候选。 */
    fun resolve(char: String, alt: String?, popup: List<String>?): List<String> =
        listOfNotNull(alt?.takeIf { it.isNotEmpty() }).plus(
            popup ?: candidates[char.lowercase()].orEmpty()
        ).distinct()
}
