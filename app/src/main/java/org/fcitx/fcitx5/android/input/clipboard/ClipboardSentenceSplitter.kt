/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import kotlin.text.Regex

/**
 * 把一段长文本按句读切成多段，用于「剪切板分句」功能。
 *
 * 规则：
 * - 句读标点（。！？!?…，；）与换行 —— 一定切；省略号 `…`、`。。` 连续记为一个切点。
 * - 英文 `,` `;` 仅当两侧至少一侧不是 ASCII 字母数字时才切（保护 `1,234`、IP）。
 * - 成对包裹符（「」『』“”（）《》〈〉【】〔〕，以及成对的 `"` 与 `` ` ``）
 *   是**切分边界**：开符之前切、闭符之后切；包裹符**内部不切**，
 *   避免 `「第一段。」「第二段。」` 被切成带孤引号的碎片。
 *   未闭合的开符不提供保护：其后的句读照常生效。
 * - 直单引号 `'` 与弯单引号 `‘` `’` **不参与**配对（保护 `don't`、`it’s`）。
 * - URL 整体保护，不切。
 * - 每段默认剔除首尾的句读标点、冒号与悬空的包裹符（可关闭）。
 */
object ClipboardSentenceSplitter {

    // 中文逗号、中文分号 —— 一定切
    private const val HARD_CUT = "。！？!?\n…，；"

    // 半角逗号、分号 —— 条件切（避免切在数字/字母中间）
    private const val SOFT_CUT = ",;"

    // 成对包裹符：开符 → 闭符（弯单引号不参与，保护 don't / it’s）
    private val PAIRS: Map<Char, Char> = mapOf(
        '「' to '」', '『' to '』',
        '“' to '”',
        '（' to '）', '《' to '》', '〈' to '〉',
        '【' to '】', '〔' to '〕',
        '(' to ')', '[' to ']', '{' to '}',
    )

    // 直引号与反引号按出现次序奇开偶闭
    private const val STRAIGHT_QUOTES = "\"`"

    // URL 终止符：空白、引号、CJK 标点、中文括号、CJK 引号。
    // 注意**不包含** `?` `&` `,` `.` `;` `!`，因为这些在 URL 里合法。
    private val URL_PATTERN = Regex(
        "https?://[^\\s<>\"'、，。！？；：（）《》「」『』“”‘’]+"
    )

    /**
     * 把 [text] 切分为多段。
     * 返回的每段都是原文的**连续子串**（默认剔除首尾标点与悬空包裹符，去除首尾空白）。
     * 空文本返回空列表。
     */
    fun split(text: String, stripPunctuation: Boolean = true): List<String> {
        if (text.isEmpty()) return emptyList()

        // 第一步：把 URL 替换成 \x00N\x00 占位符，避免在 URL 内部误切。
        val urls = mutableListOf<String>()
        val protectedText = URL_PATTERN.replace(text) { match ->
            val idx = urls.size
            urls.add(match.value)
            "\u0000${idx}\u0000"
        }

        // 第二步：平衡预检——开/闭数量平衡的符号对才启用「内部不切」保护；
        // 不平衡的（如未闭合的引号）当普通文本，其内部句读照常生效。
        val activePairs = HashMap<Char, Char>()
        for ((o, c) in PAIRS) {
            val oo = protectedText.count { it == o }
            if (oo > 0 && oo == protectedText.count { it == c }) activePairs[o] = c
        }
        val activeStraight = STRAIGHT_QUOTES.filter { q ->
            val cnt = protectedText.count { it == q }
            cnt >= 2 && cnt % 2 == 0
        }

        // 第三步：扫描切分点（带包裹符深度计数）。
        val cuts = mutableListOf<Int>()
        val n = protectedText.length
        val depths = HashMap<Char, Int>()
        var straightOpen = false // 直引号/反引号当前是否处于“开”状态
        var straightChar = ' '   // 当前未闭合的直引号字符
        var i = 0
        while (i < n) {
            val ch = protectedText[i]

            // 包裹符处理
            if (ch in activeStraight) {
                if (straightOpen && ch == straightChar) {
                    // 闭符：切在闭符之后
                    cuts.add(i + 1)
                    straightOpen = false
                    i++
                    continue
                } else if (!straightOpen) {
                    // 开符：切在开符之前（若 i==0 则无需切点）
                    if (i > 0) cuts.add(i)
                    straightOpen = true
                    straightChar = ch
                    i++
                    continue
                }
                // straightOpen 且 ch != straightChar：另一个直引号，当作普通文本
                i++
                continue
            }
            if (activePairs.containsKey(ch) && (depths[ch] ?: 0) == 0) {
                // 开符：切在开符之前
                if (i > 0) cuts.add(i)
                depths[ch] = 1
                i++
                continue
            }
            if (activePairs.containsValue(ch)) {
                val open = activePairs.entries.first { it.value == ch }.key
                if ((depths[open] ?: 0) > 0) {
                    // 闭符：切在闭符之后
                    cuts.add(i + 1)
                    depths[open] = 0
                    i++
                    continue
                }
                // 悬空闭符：当普通文本
                i++
                continue
            }

            if (depths.values.any { it > 0 } || straightOpen) {
                // 处于包裹符内部 —— 不切
                i++
                continue
            }

            if (HARD_CUT.indexOf(ch) >= 0) {
                cuts.add(i + 1)
                i++
                continue
            }
            if (SOFT_CUT.indexOf(ch) >= 0) {
                val left: Char? = if (i > 0) protectedText[i - 1] else null
                val right: Char? = if (i + 1 < n) protectedText[i + 1] else null
                // 两侧都是 ASCII 字母/数字 —— 视为千位分隔，不切
                if (left?.isAsciiAlnum() == true && right?.isAsciiAlnum() == true) {
                    i++
                    continue
                }
                cuts.add(i + 1)
                i++
                continue
            }
            i++
        }

        // 第四步：按切分点抽出片段。
        val result = mutableListOf<String>()
        var prev = 0
        for (c in cuts) {
            if (c <= prev) continue
            var seg = protectedText.substring(prev, c).trim()
            if (seg.isNotEmpty()) {
                if (stripPunctuation) seg = stripEdgePunctuation(seg)
                if (seg.isNotEmpty()) result.add(seg)
            }
            prev = c
        }
        var tail = protectedText.substring(prev).trim()
        if (tail.isNotEmpty()) {
            if (stripPunctuation) tail = stripEdgePunctuation(tail)
            if (tail.isNotEmpty()) result.add(tail)
        }

        // 第五步：还原占位符。
        if (urls.isEmpty()) return result
        for (s in result.indices) {
            var s2 = result[s]
            for (k in urls.indices) {
                s2 = s2.replace("\u0000${k}\u0000", urls[k])
            }
            result[s] = s2
        }
        return result
    }

    private fun Char.isAsciiAlnum(): Boolean = this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

    /**
     * 剔除片段首尾的标点与悬空包裹符：
     * 尾部循环剥 [句读标点 + 半角句点 + 冒号 + 闭包裹符]，
     * 头部循环剥 [开包裹符]。
     */
    private fun stripEdgePunctuation(s: String): String {
        val trailCuts = "$HARD_CUT$SOFT_CUT.：:"
        val opens = PAIRS.keys.toList() + STRAIGHT_QUOTES.toList()
        val closes = PAIRS.values.toList() + STRAIGHT_QUOTES.toList()
        var start = 0
        var end = s.length
        // 尾部：标点/冒号/闭包裹符循环剥
        while (end > start) {
            val ch = s[end - 1]
            if (trailCuts.indexOf(ch) < 0 && !closes.contains(ch)) break
            end--
        }
        // 头部：开包裹符循环剥
        while (start < end) {
            val ch = s[start]
            if (!opens.contains(ch)) break
            start++
        }
        // 尾部剥完闭符后可能又露出标点，再来一轮
        while (end > start) {
            val ch = s[end - 1]
            if (trailCuts.indexOf(ch) < 0 && !closes.contains(ch)) break
            end--
        }
        return if (start > 0 || end < s.length) s.substring(start, end).trim() else s
    }
}
