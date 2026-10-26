/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng for Android Contributors
 */
package org.fcitx.fcitx5.android.input.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorEngineTest {
    @Test
    fun deleteThenAppendEvaluatesUpdatedExpression() {
        val engine = CalculatorEngine()
        "12".forEach(engine::handle)
        assertFalse(engine.handleDelete())
        engine.handle('+')
        engine.handle('3')
        assertEquals("1+3", engine.expression())
        assertEquals("4", engine.result())
    }

    @Test
    fun clearRemovesPureNumberTracking() {
        val engine = CalculatorEngine()
        "12".forEach(engine::handle)
        assertEquals("12", engine.expression())
        engine.clear()
        assertEquals("", engine.expression())
        assertFalse(engine.isActive())
        assertNull(engine.result())
    }

    @Test
    fun unsupportedCommaClearsTrackingInsteadOfJoiningNumbers() {
        val engine = CalculatorEngine()
        engine.handle('1')
        engine.handle('+')
        engine.handle('2')
        assertFalse(engine.handle(','))
        engine.handle('3')
        engine.handle('+')
        engine.handle('4')
        assertEquals("3+4", engine.expression())
        assertEquals("7", engine.result())
    }

    @Test
    fun unarySignsAfterMultiplyAndDivideAreParsed() {
        val engine = CalculatorEngine()
        "1*-2".forEach(engine::handle)
        assertEquals("-2", engine.result())
        engine.clear()
        "1/-2".forEach(engine::handle)
        assertEquals("-0.5", engine.result())
    }

    @Test
    fun invalidRepeatedMultiplicationIsRejected() {
        val engine = CalculatorEngine()
        "1**2".forEach(engine::handle)
        assertNull(engine.result())
    }

    @Test
    fun parenthesesAndDecimalDivisionKeepPrecision() {
        val engine = CalculatorEngine()
        "(1+2)*0.1".forEach(engine::handle)
        assertEquals("0.3", engine.result())
        engine.clear()
        "1/0".forEach(engine::handle)
        assertNull(engine.result())
    }

    @Test
    fun finishClearsExpressionAndNextInputStartsFresh() {
        val engine = CalculatorEngine()
        "1+2".forEach(engine::handle)
        engine.finish()
        assertEquals("", engine.expression())
        assertFalse(engine.isActive())
        engine.handle('4')
        engine.handle('+')
        engine.handle('1')
        assertEquals("4+1", engine.expression())
        assertEquals("5", engine.result())
    }
}
