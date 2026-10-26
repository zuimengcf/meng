/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import java.io.File
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardLayout
import org.junit.Assert.*
import org.junit.Test

class PopupPositionConfigTest {
    private fun config(key: KeySpec.Letter) = KeyboardLayoutConfig(listOf(listOf("a")), mapOf("a" to key))

    @Test
    fun positionMetadataRoundTripsAndReachesThePopupDefinition() {
        for (offset in listOf(-1, 1)) for (shift in -2..2) {
            val letter = KeySpec.Letter("a", "@", popup = emptyList(), popupLetterOffset = offset, popupShiftX = shift)
            val source = config(letter)
            assertEquals(source, KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(source)))
            val popup = KeyboardLayoutLoader.toKeyDefs(source).single().single().popup!!
                .filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
            assertEquals(offset, popup.letterOffset)
            assertEquals(shift, popup.shiftX)
            assertEquals(listOf("@"), popup.items.toList())
        }
    }

    @Test
    fun absentCoordinatesKeepTheOldJsonReadable() {
        val source = """{"rows":[["a"]],"keys":{"a":{"type":"letter","char":"a","alt":"@","popup":[]}}}"""
        val key = KeyboardLayoutCodec.decode(source).keys.getValue("a") as KeySpec.Letter
        assertEquals(1, key.popupLetterOffset)
        assertEquals(0, key.popupShiftX)
    }

    @Test
    fun invalidOffsetsAreRejectedBeforeSaving() {
        for (offset in listOf(-2, 0, 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                KeyboardLayoutCodec.validate(config(KeySpec.Letter("a", popupLetterOffset = offset)))
            }
        }
        for (shift in listOf(-3, 3)) {
            assertThrows(IllegalArgumentException::class.java) {
                KeyboardLayoutCodec.validate(config(KeySpec.Letter("a", popupShiftX = shift)))
            }
        }
    }

    @Test
    fun everyActualLetterIsSingleRowAndRemainingPairsKeepTheirOrder() {
        for (name in listOf("Text", "English")) {
            val source = KeyboardLayoutCodec.decode(File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText())
            for ((id, spec) in source.keys) {
                if (spec !is KeySpec.Letter) continue
                assertEquals("$name/$id", if (id in listOf("p", "l")) -1 else 1, spec.popupLetterOffset)
                assertEquals("$name/$id", 0, spec.popupShiftX)
                for (uppercase in listOf(false, true)) {
                    val symbols = ChineseKeyboardSymbols.resolve(spec.char, spec.alt, spec.popup)
                    val items = ChineseKeyboardSymbols.displayedCandidates(spec.char,
                        AlphabetPopupCandidates.resolve(spec.char, symbols, uppercase), name == "Text")
                    val grid = PopupKeyboardLayout.create(items, 38, 50, 430, 354, 392,
                        spec.popupLetterOffset, spec.popupShiftX)
                    val cells = mutableMapOf<Int, Pair<Int, Int>>()
                    grid.rows.forEachIndexed { row, columns -> columns.forEachIndexed { column, index ->
                        if (index >= 0) cells[index] = row to column
                    } }
                    assertTrue("$name/$id", items.size <= 5)
                    assertEquals("$name/$id", 1, grid.rows.size)
                    assertFalse("$name/$id", grid.rows.flatMap { it.toList() }.any { it < 0 })
                    assertEquals(items.indices.toSet(), cells.keys)
                    assertEquals("$name/$id", cells.getValue(0).first, cells.getValue(1).first)
                    assertEquals("$name/$id", cells.getValue(0).second + spec.popupLetterOffset, cells.getValue(1).second)
                    assertTrue(items.none { it in setOf("「", "」", "『", "』") })
                    for ((open, close) in listOf("【" to "】", "〔" to "〕")) {
                        if (open in items && close in items) {
                            val a = cells.getValue(items.indexOf(open))
                            val b = cells.getValue(items.indexOf(close))
                            assertEquals(a.first, b.first)
                            assertEquals(a.second + 1, b.second)
                        }
                    }
                }
            }
        }
    }
    @Test
    fun plLettersAreOneCellLeftWithoutMovingTheDefaultSymbolAwayFromTheTrigger() {
        for (name in listOf("Text", "English")) {
            val source = KeyboardLayoutCodec.decode(File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText())
            for (id in listOf("p", "l")) {
                val spec = source.keys.getValue(id) as KeySpec.Letter
                assertEquals(-1, spec.popupLetterOffset)
                assertEquals(0, spec.popupShiftX)
                val items = AlphabetPopupCandidates.resolve(id, ChineseKeyboardSymbols.resolve(id, spec.alt, spec.popup))
                val grid = PopupKeyboardLayout.create(items, 38, 0, 380, 342, 380,
                    spec.popupLetterOffset, spec.popupShiftX)
                assertEquals(0, grid.rows.first()[grid.focusColumn])
                assertEquals(1, grid.rows.first()[grid.focusColumn - 1])
                assertEquals(id.uppercase(), items[grid.rows.first()[grid.focusColumn - 1]])
                assertEquals(0, grid.offsetX + grid.focusColumn * grid.keyWidth)
            }
        }
    }

    @Test
    fun rejectedWholePopupShiftTemplateUpgradesButCustomizedCopyIsKept() {
        for (name in listOf("Text", "English")) {
            val defaults = KeyboardLayoutCodec.decode(File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText())
            val previous = ChineseKeyboardSymbols.previousMultiRowTemplate(defaults)
            val rejected = previous.copy(keys = previous.keys.mapValues { (id, key) ->
                if (id in listOf("p", "l") && key is KeySpec.Letter)
                    key.copy(popupLetterOffset = 1, popupShiftX = -1) else key
            })
            assertEquals(defaults, ChineseKeyboardSymbols.upgradeTextTemplate(rejected, defaults))
            val custom = rejected.copy(keys = rejected.keys +
                ("a" to (rejected.keys.getValue("a") as KeySpec.Letter).copy(popup = listOf("自定义"))))
            assertEquals(custom, ChineseKeyboardSymbols.upgradeTextTemplate(custom, defaults))
        }
    }

}
