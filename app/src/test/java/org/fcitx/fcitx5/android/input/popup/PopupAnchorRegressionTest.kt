/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.popup

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.config.AlphabetPopupCandidates
import org.fcitx.fcitx5.android.input.keyboard.config.ChineseKeyboardSymbols
import org.fcitx.fcitx5.android.input.keyboard.config.KeySpec
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutCodec
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutLoader
import org.junit.Assert.*
import org.junit.Test

class PopupAnchorRegressionTest {
    private fun assertAnchor(grid: PopupKeyboardLayout.Grid, left: Int, right: Int, message: String) {
        val symbolCenter = left + grid.offsetX + (grid.focusColumn + 0.5) * grid.keyWidth
        assertTrue("$message: symbol=$symbolCenter key=${(left + right) / 2.0}",
            abs(symbolCenter - (left + right) / 2.0) <= 0.5)
        val column = floor(((right - left) / 2.0 - grid.offsetX) / grid.keyWidth).toInt()
        assertEquals("$message: key center must hit the default symbol", 0, grid.rows.single()[column])
    }

    @Test
    fun jPairMustNotForceTheDefaultSymbolAwayFromTheKeyCenter() {
        val grid = PopupKeyboardLayout.create(listOf("#", "J", "【", "】"), 38, 0, 380, 247, 285, 1)
        assertAnchor(grid, 247, 285, "J")
        val row = grid.rows.single().toList()
        assertEquals(row.indexOf(0) + 1, row.indexOf(1))
        assertEquals(row.indexOf(2) + 1, row.indexOf(3))
        assertEquals(38, grid.keyWidth)
    }

    @Test
    fun everyActualLetterStaysAnchoredAcrossWidthsOriginsModesAndCaps() {
        for (name in listOf("Text", "English")) {
            val config = KeyboardLayoutCodec.decode(File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText())
            val definitions = KeyboardLayoutLoader.toKeyDefs(config)
            for (outerWidth in listOf(180, 240, 320, 360, 380, 411, 480, 600, 800)) {
                for (origin in listOf(0, 53, 407)) {
                    config.rows.take(3).forEachIndexed { rowIndex, row ->
                        val widths = definitions[rowIndex].map { it.appearance.percentWidth.toDouble() }
                        val padding = maxOf(0.0, (1.0 - widths.sum()) / 2)
                        row.forEachIndexed keyLoop@{ index, id ->
                            val key = config.keys[id] as? KeySpec.Letter ?: return@keyLoop
                            // Packed rows and expanded touch targets share these AppearanceView bounds.
                            val start = padding + widths.take(index).sum()
                            val left = origin + (start * outerWidth).roundToInt()
                            val right = origin + ((start + widths[index]) * outerWidth).roundToInt()
                            for (chinese in listOf(false, true)) for (uppercase in listOf(false, true)) {
                                val items = ChineseKeyboardSymbols.displayedCandidates(key.char,
                                    AlphabetPopupCandidates.resolve(key.char,
                                        ChineseKeyboardSymbols.resolve(key.char, key.alt, key.popup), uppercase), chinese)
                                val shown = PopupKeyboardCandidates.indices(items, 2).map(items::get)
                                val grid = PopupKeyboardLayout.create(shown, 38, origin, origin + outerWidth,
                                    left, right, key.popupLetterOffset, key.popupShiftX)
                                val message = "$name/$id width=$outerWidth origin=$origin cn=$chinese caps=$uppercase"
                                assertAnchor(grid, left, right, message)
                                assertTrue(message, left + grid.offsetX >= origin)
                                assertTrue(message, left + grid.offsetX + grid.keyWidth * shown.size <= origin + outerWidth)
                                val order = grid.rows.single().toList()
                                assertEquals(message, shown.indices.toSet(), order.toSet())
                                assertEquals(message, order.indexOf(0) + (if (id in listOf("p", "l")) -1 else 1), order.indexOf(1))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun missingJsonPositionUsesPlLeftAndOtherLettersRightWithoutOverwritingExplicitCustomValues() {
        for (char in ('a'..'z').toList() + ('A'..'Z').toList()) {
            val expected = if (char.lowercaseChar() in "pl") -1 else 1
            val text = """{"rows":[["key"]],"keys":{"key":{"type":"letter","char":"$char"}}}"""
            val key = KeyboardLayoutCodec.decode(text).keys.getValue("key") as KeySpec.Letter
            assertEquals("$char missing JSON position", expected, key.popupLetterOffset)
            assertEquals("$char constructor default", expected, KeySpec.Letter(char.toString()).popupLetterOffset)
            val custom = text.replace("\"char\":\"$char\"", "\"char\":\"$char\",\"popup_letter_offset\":1")
            assertEquals(1, (KeyboardLayoutCodec.decode(custom).keys.getValue("key") as KeySpec.Letter).popupLetterOffset)
        }
    }

    @Test
    fun codeFallbackLayoutsUsePlLeftInBothLanguages() {
        var count = 0
        for (layout in listOf(TextKeyboard.builtinLayout, TextKeyboard.englishBuiltinLayout)) {
            for (key in layout.flatten()) {
                val appearance = key.appearance as? KeyDef.Appearance.AltText ?: continue
                val popup = key.popup?.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>()?.singleOrNull() ?: continue
                val expected = if (appearance.displayText.lowercase() in listOf("p", "l")) -1 else 1
                assertEquals(appearance.displayText, expected, popup.letterOffset)
                count++
            }
        }
        assertEquals("must actually inspect both complete fallback alphabets", 52, count)
    }

    @Test
    fun originalKeyHoldsTheSymbolButSlidingIntoThePopupSelectsTheVisibleCell() {
        for ((items, direction) in listOf(
            listOf("）", "L", "》", "}") to -1,
            listOf("#", "J", "【", "】") to 1,
            listOf("0", "P") to -1
        )) {
            // A wide landscape key has multiple popup columns above its horizontal footprint.
            val grid = PopupKeyboardLayout.create(items, 38, 0, 800, 640, 720, direction)
            for (triggerX in listOf(0.1f, 15f, 40f, 65f, 79.9f)) {
                val column = PopupKeyboardHitTest.column(triggerX - grid.offsetX, 100f,
                    grid.keyWidth, 48, grid.focusColumn, grid.offsetX, 80)
                assertEquals("stationary hold must keep ${items[0]}", 0, grid.rows.single()[column])
            }
            grid.rows.single().forEachIndexed { column, index ->
                val x = (column + 0.5f) * grid.keyWidth
                val hit = PopupKeyboardHitTest.column(x, 24f, grid.keyWidth, 48,
                    grid.focusColumn, grid.offsetX, 80)
                assertEquals("visible cell must hit its own candidate", index, grid.rows.single()[hit])
            }
            val letterColumn = grid.rows.single().indexOf(1)
            val outsideX = if (direction < 0) -1f else 81f
            val hit = PopupKeyboardHitTest.column(outsideX - grid.offsetX, 100f,
                grid.keyWidth, 48, grid.focusColumn, grid.offsetX, 80).coerceIn(0, items.lastIndex)
            assertEquals(letterColumn, hit)
        }
    }

    @Test
    fun appearanceInsetAndWindowOriginDoNotShiftTheHitPoint() {
        for (origin in listOf(0, 53, 407)) {
            val (x, y) = PopupGeometry.triggerCoordinates(origin + 100, 700, origin + 112, 709, 32f, 29f)
            assertEquals(20f, x, 0f)
            assertEquals(20f, y, 0f)
            val grid = PopupKeyboardLayout.create(listOf("#", "J", "【", "】"),
                38, origin, origin + 380, origin + 112, origin + 152, 1)
            val column = PopupKeyboardHitTest.column(x - grid.offsetX, 100f,
                grid.keyWidth, 48, grid.focusColumn, grid.offsetX, 40)
            assertEquals(0, grid.rows.single()[column])
        }
    }

    @Test
    fun previousSingleRowFactoryCopyUpgradesWithoutChangingCustomizedCopies() {
        for (name in listOf("Text", "English")) {
            val defaults = KeyboardLayoutCodec.decode(File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText())
            val old = defaults.copy(keys = defaults.keys.mapValues { (id, spec) ->
                if (id in listOf("p", "l") && spec is KeySpec.Letter) spec.copy(popupLetterOffset = 1) else spec
            })
            assertEquals(defaults, ChineseKeyboardSymbols.upgradeTextTemplate(old, defaults))
            val custom = old.copy(keys = old.keys +
                ("j" to (old.keys.getValue("j") as KeySpec.Letter).copy(popup = listOf("自定义"))))
            assertEquals(custom, ChineseKeyboardSymbols.upgradeTextTemplate(custom, defaults))
        }
    }
}
