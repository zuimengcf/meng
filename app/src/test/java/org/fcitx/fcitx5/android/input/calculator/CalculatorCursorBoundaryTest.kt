/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.calculator

import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证服务采用的预测匹配策略；不替代 Android 编辑器实机测试。 */
class CalculatorCursorBoundaryTest {
    private val engine = CalculatorEngine()
    private val session = CalculatorSession(engine)
    private val cursor = CursorTracker()

    private fun onSelection(start: Int, end: Int = start) {
        if (!cursor.consume(start, end)) {
            session.clear()
            cursor.resetTo(start, end)
        }
    }

    @Test fun predictedInputCallbacksKeepExpressionIncludingBatchedUpdates() {
        session.startInput(true)
        cursor.resetTo(0)
        listOf(0xffb1, 0xffab, 0xffb2).forEachIndexed { index, sym ->
            session.handleSym(sym)
            cursor.predict(index + 1)
        }
        onSelection(1)
        assertEquals("1+2", engine.expression())
        onSelection(3) // 编辑器合并第二、第三次光标通知
        assertEquals("3", engine.result())
    }

    @Test fun externalCursorMoveDropsActiveExpression() {
        session.startInput(true)
        cursor.resetTo(3)
        listOf(0xffb1, 0xffab, 0xffb2).forEach(session::handleSym)
        onSelection(0)
        assertEquals("", engine.expression())
        listOf(0xffb4, 0xffab, 0xffb5).forEach(session::handleSym)
        assertEquals("9", engine.result())
    }

    @Test fun externalSelectionDropsUnshownDigits() {
        session.startInput(true)
        cursor.resetTo(2)
        listOf(0xffb1, 0xffb2).forEach(session::handleSym)
        onSelection(0, 2)
        assertEquals("", engine.expression())
    }
}
