/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupKeyboardLayoutTest {
    private fun positions(grid: PopupKeyboardLayout.Grid): Map<Int, Pair<Int, Int>> {
        assertEquals("popup must always be one row", 1, grid.rows.size)
        val result = mutableMapOf<Int, Pair<Int, Int>>()
        grid.rows.single().forEachIndexed { column, index ->
            assertTrue("empty popup cell", index >= 0)
            assertTrue("duplicate index $index", result.put(index, 0 to column) == null)
        }
        return result
    }

    private fun assertComplete(grid: PopupKeyboardLayout.Grid, size: Int) {
        assertEquals((0 until size).toSet(), positions(grid).keys)
        assertEquals(size, grid.rows.single().size)
    }

    private fun assertAdjacent(grid: PopupKeyboardLayout.Grid, left: Int, right: Int) {
        val found = positions(grid)
        val a = found.getValue(left)
        val b = found.getValue(right)
        assertEquals(a.first, b.first)
        assertEquals(a.second + 1, b.second)
    }

    private fun makeGrid(
        keys: List<String>,
        letterOffset: Int? = null,
        outerRight: Int = 1080,
        triggerLeft: Int = 500,
        shiftX: Int = 0,
    ) = PopupKeyboardLayout.create(
        keys = keys,
        keyWidth = 38,
        outerLeft = 0,
        outerRight = outerRight,
        triggerLeft = triggerLeft,
        triggerRight = triggerLeft + 38,
        letterOffset = letterOffset,
        shiftX = shiftX,
    )

    @Test
    fun everyAlphabetPrefixCountAndDirectionRemainsCompleteAndSingleRow() {
        for (alphabet in listOf(('a'..'z').toList(), ('A'..'Z').toList())) {
            for (letterOffset in listOf(-1, 1)) {
                for (count in 1..5) {
                    val keys = listOf("'") + alphabet.take(count - 1).map(Char::toString)
                    for ((outerRight, triggerLeft) in listOf(2400 to 1200, 570 to 430, 240 to 100, 120 to 1)) {
                        val grid = makeGrid(keys, letterOffset, outerRight, triggerLeft)
                        assertComplete(grid, keys.size)
                        if (keys.size > 1) {
                            val found = positions(grid)
                            val symbol = found.getValue(0)
                            val letter = found.getValue(1)
                            assertEquals(symbol.first, letter.first)
                            assertEquals(symbol.second + letterOffset, letter.second)
                            assertEquals(grid.focusColumn, symbol.second)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun allRequiredPairExamplesKeepLabelsIndicesAndPairsTogether() {
        val examples = listOf(
            listOf("'", "Z", "『", "』", "’"),
            listOf("*", "S", "；", "「", "」"),
            listOf("#", "J", "【", "】"),
            listOf("\"", "C", "”", "〔", "〕"),
        )
        for (keys in examples) {
            val selected = PopupKeyboardCandidates.indices(keys, 2)
            assertEquals(keys.indices.toSet(), selected.toSet())
            val grid = makeGrid(selected.map(keys::get), letterOffset = 1, outerRight = 200, triggerLeft = 81)
            assertComplete(grid, keys.size)
            val shown = grid.rows.single().map { selected[it] }.map(keys::get)
            assertEquals(keys.toSet(), shown.toSet())
            for ((left, right) in listOf("「" to "」", "『" to "』", "【" to "】", "〔" to "〕")) {
                if (left in keys && right in keys) assertAdjacent(grid, keys.indexOf(left), keys.indexOf(right))
            }
        }
    }

    @Test
    fun nonLetterOrderKeepsLegacyColumnsBehaviorAndSingleCandidateWorks() {
        val keys = listOf("#", "【", "】")
        for (focus in 0..2) {
            val order = PopupKeyboardOrder.columns(3, focus)
            assertEquals((0 until 3).toList(), order.sorted())
            assertEquals(0, order[focus])
        }
        assertEquals(listOf("【", "#", "】"), PopupKeyboardOrder.columns(3, 1).map { keys[it] })
        val grid = makeGrid(listOf("only"), outerRight = 40, triggerLeft = 0)
        assertComplete(grid, 1)
        assertEquals(0, grid.focusColumn)
        assertEquals(0, positions(grid).getValue(0).second)
    }

    @Test
    fun shiftAndNarrowScreenChangeGeometryNotCandidateOrder() {
        val keys = listOf("'", "a", "b", "c", "d")
        val base = makeGrid(keys, letterOffset = 1, outerRight = 480, triggerLeft = 442)
        val shifted = makeGrid(keys, letterOffset = 1, outerRight = 480, triggerLeft = 442, shiftX = -1)
        assertComplete(base, keys.size)
        assertComplete(shifted, keys.size)
        assertEquals(positions(base), positions(shifted))
        assertTrue(shifted.keyWidth <= base.keyWidth)
        val narrow = makeGrid(keys, letterOffset = 1, outerRight = 100, triggerLeft = 30)
        assertComplete(narrow, keys.size)
        assertTrue(narrow.keyWidth < 38)
    }
    @Test
    fun remainingApostropheClosesToTheRightOfItsOpeningSymbol() {
        val keys = listOf("‘", "Z", "’")
        val grid = makeGrid(keys, letterOffset = 1, outerRight = 380, triggerLeft = 152)
        assertComplete(grid, keys.size)
        assertEquals(listOf(0, 1, 2), grid.rows.single().toList())
    }

}
