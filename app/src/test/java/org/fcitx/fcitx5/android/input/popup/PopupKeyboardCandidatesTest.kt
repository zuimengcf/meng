/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupKeyboardCandidatesTest {
    private fun assertValidSelection(keys: List<String>, selected: List<Int>, reserved: Int) {
        assertTrue(selected.size <= PopupKeyboardCandidates.MAX_ITEMS)
        assertEquals((0 until minOf(reserved, keys.size)).toList(), selected.take(reserved))
        assertTrue(selected.all { it in keys.indices })
        assertEquals(selected.size, selected.toSet().size)
        for (i in selected.drop(reserved).dropLast(1)) {
            if (PopupKeyboardCandidates.isPair(keys[i], keys.getOrNull(i + 1) ?: "")) {
                assertTrue(i + 1 in selected)
            }
        }
    }

    @Test
    fun defaultSelectionIsAtMostFiveAndPreservesPrefixAndWholePairs() {
        val keys = listOf("*", "A", "「", "」", "『", "』", "〔", "〕", "tail")
        val before = keys.toList()
        val selected = PopupKeyboardCandidates.indices(keys)
        assertValidSelection(keys, selected, 1)
        assertEquals(listOf(0, 1, 2, 3, 8), selected)
        assertEquals(before, keys)

        val letters = listOf("'") + ('a'..'z').map(Char::toString)
        assertValidSelection(letters, PopupKeyboardCandidates.indices(letters), 1)
        assertEquals(5, PopupKeyboardCandidates.indices(letters).size)
    }

    @Test
    fun reservedTwoKeepsSymbolAndLetterAndNeverSplitsAGroup() {
        val keys = listOf("'", "Z", "『", "』", "x", "「", "」", "end")
        val before = keys.toList()
        val selected = PopupKeyboardCandidates.indices(keys, reservedCount = 2)
        assertValidSelection(keys, selected, 2)
        assertEquals(listOf(0, 1, 2, 3, 4), selected)
        assertEquals(before, keys)

        val tight = listOf("'", "Z", "「", "」", "『", "』")
        val tightSelected = PopupKeyboardCandidates.indices(tight, 2)
        assertValidSelection(tight, tightSelected, 2)
        assertTrue(2 in tightSelected && 3 in tightSelected)
        assertTrue(4 !in tightSelected || 5 in tightSelected)
    }

    @Test
    fun longNonLettersAndLettersRemainBoundedAndMaySkipAnUnfittingGroup() {
        val symbols = listOf("#", "(", ")", "[", "]", "{", "}", "《", "》", "last")
        val selectedSymbols = PopupKeyboardCandidates.indices(symbols)
        assertValidSelection(symbols, selectedSymbols, 1)
        assertTrue(selectedSymbols.size <= 5)
        assertEquals(selectedSymbols.map(symbols::get), selectedSymbols.map(symbols::get))

        val alphabet = listOf("'") + ('a'..'z').map(Char::toString)
        val selectedLetters = PopupKeyboardCandidates.indices(alphabet)
        assertValidSelection(alphabet, selectedLetters, 1)
        assertEquals(5, selectedLetters.size)
        assertEquals(listOf("'", "a", "b", "c", "d"), selectedLetters.map(alphabet::get))
    }

    @Test
    fun emptyAndSingleListsAreStableAndUnchanged() {
        val empty = emptyList<String>()
        assertEquals(emptyList<Int>(), PopupKeyboardCandidates.indices(empty))
        assertEquals(emptyList<Int>(), PopupKeyboardCandidates.indices(empty, 2))

        val one = listOf("only")
        val before = one.toList()
        assertEquals(listOf(0), PopupKeyboardCandidates.indices(one))
        assertEquals(listOf(0), PopupKeyboardCandidates.indices(one, 2))
        assertEquals(before, one)
    }

    @Test
    fun selectedIndicesKeepLabelsAndCommitValuesAlignedAndFitOneRow() {
        val keys = listOf("'", "Z", "『", "』", "’", "unused")
        val commits = listOf("quote", "letter", "open-double", "close-double", "apostrophe", "ignored")
        val selected = PopupKeyboardCandidates.indices(keys, reservedCount = 2)
        val labels = selected.map(keys::get)
        val values = selected.map(commits::get)
        assertEquals(listOf("'", "Z", "『", "』", "’"), labels)
        assertEquals(listOf("quote", "letter", "open-double", "close-double", "apostrophe"), values)

        val grid = PopupKeyboardLayout.create(
            keys = labels,
            keyWidth = 38,
            outerLeft = 0,
            outerRight = 120,
            triggerLeft = 40,
            triggerRight = 78,
            letterOffset = 1,
        )
        assertEquals(1, grid.rows.size)
        assertEquals(labels.indices.toSet(), grid.rows.single().toSet())
        assertTrue(grid.rows.single().none { it < 0 })
        assertEquals(labels, grid.rows.single().map { labels[it] }.sortedBy { labels.indexOf(it) })
    }
}
