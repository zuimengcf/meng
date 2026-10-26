/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CalculatorSessionTest {
    private val engine = CalculatorEngine()
    private val session = CalculatorSession(engine)

    private fun type(text: String) {
        text.forEach { char ->
            session.handleSym(when (char) {
                in '0'..'9' -> 0xffb0 + (char - '0')
                '+' -> 0xffab
                '-' -> 0xffad
                '*' -> 0xffaa
                '/' -> 0xffaf
                ',' -> 0xffac
                '.' -> 0xffae
                '=' -> 0xffbd
                else -> error("Unsupported test character: $char")
            })
        }
    }

    @Test fun backspaceBeforeOperatorTracksActualDigits() {
        session.startInput(true)
        type("12")
        session.handleSym(0xff08)
        type("+3")
        assertEquals("1+3", engine.expression())
        assertEquals("4", engine.result())
    }

    @Test fun clearDropsDigitsEvenWithoutVisibleCandidate() {
        session.startInput(true)
        type("12")
        session.clear() // 布局切换、外部光标变化、粘贴等边界
        assertEquals("", engine.expression())
        type("3+4")
        assertEquals("7", engine.result())
    }

    @Test fun newEditorDropsActiveExpression() {
        session.startInput(true)
        type("1+2")
        session.startInput(true)
        assertEquals("", engine.expression())
        type("3+4")
        assertEquals("7", engine.result())
    }

    @Test fun newEditorDropsDigitsWithoutOperator() {
        session.startInput(true)
        type("12")
        session.startInput(true)
        type("3+4")
        assertEquals("3+4", engine.expression())
    }

    @Test fun sensitiveEditorNeverAccumulatesOrShowsResult() {
        session.startInput(true)
        type("1+2")
        session.startInput(false)
        type("123+456")
        assertEquals("", engine.expression())
        assertFalse(engine.isActive())
        session.startInput(true)
        type("3+4")
        assertEquals("7", engine.result())
    }

    @Test fun endingInputDisablesFurtherTrackingUntilNextStart() {
        session.startInput(true)
        type("1+2")
        session.endInput()
        type("3+4")
        assertEquals("", engine.expression())
        session.startInput(true)
        type("5+6")
        assertEquals("11", engine.result())
    }

    @Test fun uninitializedSessionDoesNotTrack() {
        type("1+2")
        assertEquals("", engine.expression())
    }

    @Test fun commaIsAnInputBoundaryNotAnIgnoredCharacter() {
        session.startInput(true)
        type("1+2,3")
        assertEquals("3", engine.expression())
        assertFalse(engine.isActive())
    }

    @Test fun ordinaryKeyClearsPartialDigits() {
        session.startInput(true)
        type("12")
        session.handleSym('a'.code)
        type("3+4")
        assertEquals("3+4", engine.expression())
        assertEquals("7", engine.result())
    }

    @Test fun backspaceOnEmptySessionIsSafe() {
        session.startInput(true)
        repeat(3) { session.handleSym(0xff08) }
        assertEquals("", engine.expression())
    }
}
