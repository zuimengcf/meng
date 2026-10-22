/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.abs

/** 显示、命中与提交共用同一索引；固定单行，无占位空槽。 */
object PopupKeyboardLayout {
    data class Grid(
        /** 始终恰好一行，所有格都有有效候选索引。 */
        val rows: List<IntArray>,
        val focusColumn: Int,
        val offsetX: Int,
        val keyWidth: Int
    )

    fun create(
        keys: List<String>, keyWidth: Int,
        outerLeft: Int, outerRight: Int, triggerLeft: Int, triggerRight: Int,
        letterOffset: Int? = null, shiftX: Int = 0
    ): Grid {
        require(keys.size in 1..PopupKeyboardCandidates.MAX_ITEMS && keyWidth > 0)
        require(outerRight - outerLeft >= keys.size)
        require(letterOffset == null || letterOffset == -1 || letterOffset == 1)
        val columns = keys.size
        // 窄屏缩小格宽而不换行；AutoScaleTextView 同步适配文字。
        val cellWidth = minOf(keyWidth, (outerRight - outerLeft) / columns)
        val hasLetter = letterOffset != null && keys.size > 1
        val pairedPrefix = !hasLetter && keys.size > 1 && PopupKeyboardCandidates.isPair(keys[0], keys[1])
        val adjacentOffset = if (hasLetter) letterOffset else if (pairedPrefix) 1 else null
        val prefix = if (adjacentOffset != null) 2 else 1
        val groups = PopupKeyboardCandidates.groups(keys, prefix)
        var preferredFocus = (columns - 1) / 2
        val leftSpace = triggerLeft - outerLeft
        val rightSpace = outerRight - triggerRight
        while (cellWidth * preferredFocus > leftSpace) preferredFocus--
        while (cellWidth * (columns - preferredFocus - 1) > rightSpace) preferredFocus++
        preferredFocus = preferredFocus.coerceIn(0, columns - 1)

        // 旧算法在锚点两边留下孤立空格时会把一对符号赶到第二行。
        // 最多五格，枚举最近的可行锚点即可同时满足邻位、配对和不换行。
        var chosen: IntArray? = null
        var focus = 0
        for (candidate in (0 until columns).sortedWith(compareBy<Int> { abs(it - preferredFocus) }.thenBy { it })) {
            if (adjacentOffset != null && candidate + adjacentOffset !in 0 until columns) continue
            val row = IntArray(columns) { -1 }
            row[candidate] = 0
            if (adjacentOffset != null) row[candidate + adjacentOffset] = 1
            fun place(groupIndex: Int): Boolean {
                if (groupIndex == groups.size) {
                    // 副符号本身若是开引号，闭引号也必须在其右边；字母邻位不变。
                    return keys.indices.drop(1).all { index ->
                        !PopupKeyboardCandidates.isPair(keys[0], keys[index]) || row.indexOf(0) < row.indexOf(index)
                    }
                }
                val group = groups[groupIndex]
                for (start in 0..columns - group.size) {
                    if (group.indices.any { row[start + it] != -1 }) continue
                    group.forEachIndexed { delta, index -> row[start + delta] = index }
                    if (place(groupIndex + 1)) return true
                    group.indices.forEach { row[start + it] = -1 }
                }
                return false
            }
            if (place(0)) {
                chosen = row
                focus = candidate
                break
            }
        }
        val row = checkNotNull(chosen) { "五项候选必须能排入单行" }
        check(row.none { it < 0 })
        val desiredLeft = triggerLeft + (triggerRight - triggerLeft - cellWidth) / 2 - cellWidth * focus
        val maxLeft = outerRight - columns * cellWidth
        val baseLeft = desiredLeft.coerceIn(outerLeft, maxLeft)
        val left = (baseLeft + cellWidth * shiftX).coerceIn(outerLeft, maxLeft)
        return Grid(listOf(row), focus, left - triggerLeft, cellWidth)
    }
}
