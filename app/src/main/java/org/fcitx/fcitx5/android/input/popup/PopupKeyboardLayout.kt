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
        val maxCellWidth = minOf(keyWidth, (outerRight - outerLeft) / columns)
        val hasLetter = letterOffset != null && keys.size > 1
        val pairedPrefix = !hasLetter && keys.size > 1 && PopupKeyboardCandidates.isPair(keys[0], keys[1])
        val adjacentOffset = if (hasLetter) letterOffset else if (pairedPrefix) 1 else null
        val prefix = if (adjacentOffset != null) 2 else 1
        val groups = PopupKeyboardCandidates.groups(keys, prefix)
        var preferredFocus = (columns - 1) / 2
        val leftSpace = triggerLeft - outerLeft
        val rightSpace = outerRight - triggerRight
        while (maxCellWidth * preferredFocus > leftSpace) preferredFocus--
        while (maxCellWidth * (columns - preferredFocus - 1) > rightSpace) preferredFocus++
        preferredFocus = preferredFocus.coerceIn(0, columns - 1)

        // 排列约束与几何约束必须一起求解。不能先选最近的可排列列位，再把
        // 整条弹窗夹到屏幕边缘：J 的成对括号会因此把默认 # 挤到字母 J 下方。
        val centerTwice = triggerLeft + triggerRight
        var chosen: IntArray? = null
        var focus = 0
        var cellWidth = 0
        var baseLeft = 0
        var bestCenterError = Int.MAX_VALUE
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
            if (!place(0)) continue

            // 以原键中心为锚点计算两侧可容纳格宽。先保留最大格宽，再比较
            // 居中误差与原列位偏好；只有窄屏/极端边缘确有需要时才缩小。
            val width = minOf(
                maxCellWidth,
                (centerTwice - 2 * outerLeft) / (2 * candidate + 1),
                (2 * outerRight - centerTwice) / (2 * (columns - candidate) - 1)
            ).coerceAtLeast(1)
            val desiredLeft = Math.floorDiv(centerTwice - width, 2) - width * candidate
            val left = desiredLeft.coerceIn(outerLeft, outerRight - columns * width)
            val centerError = abs(2 * left + (2 * candidate + 1) * width - centerTwice)
            if (width > cellWidth || (width == cellWidth && centerError < bestCenterError)) {
                chosen = row
                focus = candidate
                cellWidth = width
                baseLeft = left
                bestCenterError = centerError
            }
        }
        val row = checkNotNull(chosen) { "五项候选必须能排入单行" }
        check(row.none { it < 0 })
        // 明确配置的整体平移仍然保留；缺省 shiftX=0 时副符号对齐原键中心。
        val left = (baseLeft + cellWidth * shiftX).coerceIn(outerLeft, outerRight - columns * cellWidth)
        return Grid(listOf(row), focus, left - triggerLeft, cellWidth)
    }
}
