/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

/** 首項留在手指上方；其餘候選由左至右排列，避免成對括號被鏡像。 */
object PopupKeyboardOrder {
    fun columns(columnCount: Int, initialFocus: Int): IntArray {
        require(columnCount > 0 && initialFocus in 0 until columnCount)
        var next = 1
        return IntArray(columnCount) { column ->
            if (column == initialFocus) 0 else next++
        }
    }
}
