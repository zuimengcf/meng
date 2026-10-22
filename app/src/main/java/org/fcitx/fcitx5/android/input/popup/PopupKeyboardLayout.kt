/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.ceil

/** 候選索引和實際格位分離：顯示、命中、提交共用同一份座標，不鏡像字母或成對符號。 */
object PopupKeyboardLayout {
    data class Grid(
        /** 第 0 行為底排；-1 是保留格，不壓縮，也不能提交。 */
        val rows: List<IntArray>,
        val focusColumn: Int,
        val offsetX: Int,
        val keyWidth: Int
    )

    private val pairs = mapOf(
        "「" to "」", "『" to "』", "《" to "》", "〈" to "〉",
        "【" to "】", "〔" to "〕", "（" to "）", "(" to ")",
        "[" to "]", "{" to "}", "“" to "”", "‘" to "’"
    )

    fun create(
        keys: List<String>, keyWidth: Int,
        outerLeft: Int, outerRight: Int, triggerLeft: Int, triggerRight: Int,
        letterOffset: Int? = null, shiftX: Int = 0
    ): Grid {
        require(keys.isNotEmpty() && keyWidth > 0 && outerRight > outerLeft)
        require(letterOffset == null || letterOffset == -1 || letterOffset == 1)
        val hasLetter = letterOffset != null && keys.size > 1
        val minimumColumns = if (hasLetter) 2 else 1
        val cellWidth = minOf(keyWidth, ((outerRight - outerLeft) / minimumColumns).coerceAtLeast(1))
        val nominalRows = ceil(keys.size / 5.0).toInt()
        val columns = ceil(keys.size.toDouble() / nominalRows).toInt()
            .coerceAtLeast(minimumColumns)
            .coerceAtMost(((outerRight - outerLeft) / cellWidth).coerceAtLeast(1))
        var focus = (columns - 1) / 2
        val leftSpace = triggerLeft - outerLeft
        val rightSpace = outerRight - triggerRight
        while (cellWidth * focus > leftSpace) focus--
        while (cellWidth * (columns - focus - 1) > rightSpace) focus++
        focus = focus.coerceIn(0, columns - 1)
        if (hasLetter) focus = if (letterOffset == 1)
            focus.coerceAtMost(columns - 2) else focus.coerceAtLeast(1)

        val rows: List<IntArray>
        if (!hasLetter) {
            val order = PopupKeyboardOrder.columns(columns, focus)
            rows = List(ceil(keys.size.toDouble() / columns).toInt()) { row ->
                IntArray(columns) { col -> (row * columns + order[col]).takeIf { it < keys.size } ?: -1 }
            }
        } else {
            val grid = mutableListOf(IntArray(columns) { -1 })
            grid[0][focus] = 0
            grid[0][focus + letterOffset] = 1
            var index = 2
            while (index < keys.size) {
                val count = if (index + 1 < keys.size && pairs[keys[index]] == keys[index + 1]) 2 else 1
                var placed = false
                for (row in grid) {
                    val column = (0..columns - count).firstOrNull { start ->
                        (start until start + count).all { row[it] == -1 }
                    } ?: continue
                    repeat(count) { row[column + it] = index + it }
                    placed = true
                    break
                }
                if (!placed) {
                    val row = IntArray(columns) { -1 }
                    repeat(count) { row[it] = index + it }
                    grid.add(row)
                }
                index += count
            }
            rows = grid
        }
        val desiredLeft = triggerLeft + (triggerRight - triggerLeft - cellWidth) / 2 - cellWidth * focus
        val maxLeft = (outerRight - columns * cellWidth).coerceAtLeast(outerLeft)
        // 先完成自动避让，再应用 JSON 显式平移，避免 P/L 的 -1 被右边界裁剪抵消。
        val baseLeft = desiredLeft.coerceIn(outerLeft, maxLeft)
        val left = (baseLeft + cellWidth * shiftX).coerceIn(outerLeft, maxLeft)
        return Grid(rows, focus, left - triggerLeft, cellWidth)
    }
}
