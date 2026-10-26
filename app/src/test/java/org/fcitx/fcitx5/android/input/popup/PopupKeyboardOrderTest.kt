/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupKeyboardOrderTest {
    @Test
    fun hashRemainsDefaultAndBracketsReadOpeningThenClosing() {
        val keys = listOf("#", "【", "】")
        for (focus in 0..2) {
            val order = PopupKeyboardOrder.columns(3, focus)
            val shown = order.map { keys[it] }
            assertEquals("#", keys[order[focus]])
            assertTrue(shown.indexOf("【") < shown.indexOf("】"))
        }
        assertEquals(listOf("【", "#", "】"), PopupKeyboardOrder.columns(3, 1).map { keys[it] })
    }

    @Test
    fun everyWidthAndAnchorKeepsAllIndicesAndOrderedExtras() {
        for (width in 1..5) for (focus in 0 until width) {
            val order = PopupKeyboardOrder.columns(width, focus).toList()
            assertEquals((0 until width).toList(), order.sorted())
            assertEquals(0, order[focus])
            assertEquals((1 until width).toList(), order.filter { it != 0 })
        }
    }

    @Test
    fun multipleRowsRetainEveryCandidateAndMatchingCommitIndex() {
        for (size in 1..6) {
            val rows = kotlin.math.ceil(size / 5.0).toInt()
            val columns = kotlin.math.round(size.toDouble() / rows).toInt()
            for (focus in 0 until columns) {
                val order = PopupKeyboardOrder.columns(columns, focus)
                val indices = (0 until rows).flatMap { row -> order.map { row * columns + it } }.filter { it < size }
                // The UI may have a partially filled last row; each rendered label indexes the same commit array.
                assertEquals((0 until size).toList(), indices.sorted())
                assertEquals(0, order[focus])
            }
        }
    }
}
