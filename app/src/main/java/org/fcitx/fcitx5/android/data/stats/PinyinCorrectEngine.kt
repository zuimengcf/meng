/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.stats.db.PinyinCorrect

/**
 * 「梦」拼音纠错引擎（功能 3）。
 *
 * 三种纠错策略统一在这里生成「候选修正码」，供调用方在用户继续输入时注入引擎：
 * 1. 邻键错位 [neighborCandidates]：QWERTY 相邻键替换，得出「更可能正确」的编码；
 * 2. 模糊音 [fuzzyCandidates]：常见前后鼻音/平翘舌混淆，得出可接受的编码；
 * 3. 自学习习惯码 [habitCandidates]：直接查询统计层学习到的「习惯码 → 词」映射（含纯首字母简拼）。
 *
 * 调用方先从自学习表拿热度最高的 `correct`（用户最常打出的词/码），
 * 再叠加上邻键/模糊音的「形近码」候补，取自信度较高者作为修正。
 *
 * 所有开关（总开关 + 三个子开关）都在这里统一判断，调用方无需再管配置。
 */
object PinyinCorrectEngine {

    /** 总开关是否开启。 */
    fun isEnabled(): Boolean =
        AppPrefs.getInstance().smart.pinyinCorrectEnabled.getValue()

    /** 邻键错位开关。 */
    fun neighborEnabled(): Boolean = isEnabled() && AppPrefs.getInstance().smart.correctNeighbor.getValue()

    /** 模糊音开关。 */
    fun fuzzyEnabled(): Boolean = isEnabled() && AppPrefs.getInstance().smart.correctFuzzy.getValue()

    /** 自学习习惯码开关。 */
    fun habitEnabled(): Boolean = isEnabled() && AppPrefs.getInstance().smart.correctHabit.getValue()

    /**
     * QWERTY 键盘相邻键映射：`key -> 其邻键集合`。
     * 邻键错位最常见的场景是敲到了目标键旁边的一个键。
     */
    private val neighborKeys: Map<Char, List<Char>> = buildMap {
        put('q', listOf('w', 'a'))
        put('w', listOf('q', 'e', 's', 'a'))
        put('e', listOf('w', 'r', 'd', 's'))
        put('r', listOf('e', 't', 'f', 'd'))
        put('t', listOf('r', 'y', 'g', 'f'))
        put('y', listOf('t', 'u', 'h', 'g'))
        put('u', listOf('y', 'i', 'j', 'h'))
        put('i', listOf('u', 'o', 'k', 'j'))
        put('o', listOf('i', 'p', 'l', 'k'))
        put('p', listOf('o', 'l'))
        put('a', listOf('q', 'w', 's', 'z'))
        put('s', listOf('a', 'w', 'e', 'd', 'x', 'z'))
        put('d', listOf('s', 'e', 'r', 'f', 'c', 'x'))
        put('f', listOf('d', 'r', 't', 'g', 'v', 'c'))
        put('g', listOf('f', 't', 'y', 'h', 'b', 'v'))
        put('h', listOf('g', 'y', 'u', 'j', 'n', 'b'))
        put('j', listOf('h', 'u', 'i', 'k', 'm', 'n'))
        put('k', listOf('j', 'i', 'o', 'l', 'm'))
        put('l', listOf('k', 'o', 'p'))
        put('z', listOf('a', 's', 'x'))
        put('x', listOf('z', 's', 'd', 'c'))
        put('c', listOf('x', 'd', 'f', 'v'))
        put('v', listOf('c', 'f', 'g', 'b'))
        put('b', listOf('v', 'g', 'h', 'n'))
        put('n', listOf('b', 'h', 'j', 'm'))
        put('m', listOf('n', 'j', 'k'))
    }

    /**
     * 常见模糊音分组。`错拼 → 正确` 的可接受替换，覆盖前后鼻音/平翘舌等。
     * 仅在开启模糊音开关时生效，且仅作为候补，不当主答案。
     */
    private val fuzzySubstitutions: Map<String, String> = mapOf(
        // 前鼻音 → 后鼻音（错 n 应 en）
        "an" to "ang", "en" to "eng", "in" to "ing", "un" to "ong",
        // 后鼻音 → 前鼻音（错 ang 应 an 等）
        "ang" to "an", "eng" to "en", "ing" to "in",
        // 平翘舌
        "z" to "zh", "c" to "ch", "s" to "sh",
        "zh" to "z", "ch" to "c", "sh" to "s",
        // l/n
        "l" to "n", "n" to "l",
        // h/f
        "h" to "f", "f" to "h",
    )

    /**
     * 生成邻键错位候补编码。对 `code` 中每个字母，依次替换为其邻键生成候选。
     * 返回去重后的候选列表（不含原码）。
     */
    fun neighborCandidates(code: String): List<String> {
        if (!neighborEnabled()) return emptyList()
        val lower = code.lowercase()
        if (lower.isEmpty() || lower.any { it !in 'a'..'z' }) return emptyList()
        val result = mutableSetOf<String>()
        for (i in lower.indices) {
            val ch = lower[i]
            neighborKeys[ch]?.forEach { nb ->
                if (nb != ch) {
                    result += lower.substring(0, i) + nb + lower.substring(i + 1)
                }
            }
        }
        return result.toList()
    }

    /**
     * 生成模糊音候补编码。将 code 中出现的易混淆音节片段做替换。
     * 返回去重后的候选列表（不含原码）。
     */
    fun fuzzyCandidates(code: String): List<String> {
        if (!fuzzyEnabled()) return emptyList()
        val lower = code.lowercase()
        if (lower.isEmpty() || lower.any { it !in 'a'..'z' }) return emptyList()
        val result = mutableSetOf<String>()
        for ((wrong, correct) in fuzzySubstitutions) {
            if (wrong in lower) {
                result += lower.replace(wrong, correct)
            }
        }
        return result.toList()
    }

    /**
     * 汇聚所有可用候选码（邻键 + 模糊音），去重并保证不含原码。
     * 供调用方在无自学习命中时，仍能按引擎规则尝试修正音节。
     */
    fun allStaticCandidates(code: String): List<String> =
        (neighborCandidates(code) + fuzzyCandidates(code)).distinct().filter { it != code.lowercase() }

    /**
     * 自学习习惯码命中：查统计层记录的「习惯码 → 上屏词」，按热度取前 `limit` 个的 `correct`。
     * 这是纠错最可靠的依据（用户确实这么敲过、且打出了那个词）。
     *
     * 注：此为挂起函数，调用方需在 IO 上下文中调用。
     */
    suspend fun habitCandidates(code: String, limit: Int = 5): List<PinyinCorrect> {
        if (!habitEnabled()) return emptyList()
        return InputStatsManager.correctFor(code, limit)
    }
}
