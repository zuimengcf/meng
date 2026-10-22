/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

/** 仅限制弹窗显示，不改写用户保存的旧长按列表。 */
object PopupKeyboardCandidates {
    const val MAX_ITEMS = 5

    private val pairs = mapOf(
        "「" to "」", "『" to "』", "《" to "》", "〈" to "〉",
        "【" to "】", "〔" to "〕", "（" to "）", "(" to ")",
        "[" to "]", "{" to "}", "“" to "”", "‘" to "’"
    )

    internal fun isPair(first: String, second: String) = pairs[first] == second

    internal fun groups(keys: List<String>, start: Int): List<List<Int>> = buildList {
        var index = start
        while (index < keys.size) {
            val size = if (index + 1 < keys.size && isPair(keys[index], keys[index + 1])) 2 else 1
            add((index until index + size).toList())
            index += size
        }
    }

    /** 返回原列表索引；labels 与提交内容必须按同一索引筛选。 */
    fun indices(keys: List<String>, reservedCount: Int = 1): List<Int> {
        require(reservedCount in 1..2)
        val prefix = minOf(reservedCount, keys.size)
        return buildList {
            addAll(0 until prefix)
            for (group in groups(keys, prefix)) {
                if (size + group.size <= MAX_ITEMS) addAll(group)
            }
        }
    }
}
