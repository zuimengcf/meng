/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.floor

/** 坐标均相对实际弹层；显示格与命中格使用同一个宽度和偏移。 */
internal object PopupKeyboardHitTest {
    fun column(
        x: Float, y: Float,
        keyWidth: Int, keyHeight: Int,
        defaultColumn: Int, offsetX: Int, triggerWidth: Int
    ): Int {
        val triggerX = x + offsetX
        // 手指仍在原键横向范围、且没有上滑进入弹层时，长按应提交副符号。
        // 原键可能比弹层格宽，不能把落点偏左/偏右或轻微抖动当作选择相邻字母。
        if (y >= keyHeight && triggerX >= 0f && triggerX < triggerWidth) return defaultColumn
        return floor(x / keyWidth).toInt()
    }
}
