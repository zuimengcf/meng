/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetPopupCandidatesTest {
    private fun defaults(name: String) = KeyboardLayoutCodec.decode(
        File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText()
    )

    private fun items(name: String, key: String): List<String> {
        val spec = defaults(name).keys.getValue(key) as KeySpec.Letter
        return listOfNotNull(spec.alt) + spec.popup.orEmpty()
    }

    @Test
    fun bothAssetLayoutsResolveEveryLetterInBothCapsStates() {
        for (layoutName in listOf("Text", "English")) {
            val layout = defaults(layoutName)
            for (key in "abcdefghijklmnopqrstuvwxyz") {
                val lower = key.toString()
                val upper = lower.uppercase()
                val source = items(layoutName, lower)
                val extras = source.drop(1).filterNot { it == lower || it == upper }

                val lowerState = AlphabetPopupCandidates.resolve(lower, source)
                val upperState = AlphabetPopupCandidates.resolve(upper, source, uppercase = true)

                assertEquals("$layoutName/$lower lower", source.first(), lowerState.first())
                assertEquals("$layoutName/$lower upper", source.first(), upperState.first())
                assertEquals("$layoutName/$lower lower", upper, lowerState[1])
                assertEquals("$layoutName/$lower upper", lower, upperState[1])
                assertEquals(1, lowerState.drop(1).count { it == upper })
                assertEquals(1, upperState.drop(1).count { it == lower })
                assertEquals(extras, lowerState.drop(2))
                assertEquals(extras, upperState.drop(2))
                assertFalse(lowerState.drop(2).any { it == upper || it == lower })
                assertFalse(upperState.drop(2).any { it == upper || it == lower })
            }
        }
    }

    @Test
    fun numberRowHasOnlyDigitAndFunctionalLetterAndQpPopupIsEmptyInJson() {
        for (layoutName in listOf("Text", "English")) {
            val layout = defaults(layoutName)
            "qwertyuiop".zip("1234567890").forEach { (key, digit) ->
                val spec = layout.keys.getValue(key.toString()) as KeySpec.Letter
                assertEquals(emptyList<String>(), spec.popup)
                assertEquals(listOf(digit.toString(), key.uppercase()),
                    AlphabetPopupCandidates.resolve(key.toString(), listOf(digit.toString())))
                assertEquals(listOf(digit.toString(), key.toString()),
                    AlphabetPopupCandidates.resolve(key.uppercase(), listOf(digit.toString()), uppercase = true))
            }
        }
    }

    @Test
    fun mHasAsciiPeriodAndNoUmlautAndKlRetainModeSpecificBrackets() {
        for (layoutName in listOf("Text", "English")) {
            val layout = defaults(layoutName)
            val m = layout.keys.getValue("m") as KeySpec.Letter
            assertEquals(".", m.alt)
            assertFalse("ü" in m.popup.orEmpty())
            assertFalse("ü" in AlphabetPopupCandidates.resolve("m", listOf(".") + m.popup.orEmpty()))

            for (key in listOf("k", "l")) {
                val source = items(layoutName, key)
                val chinese = ChineseKeyboardSymbols.displayedCandidates(key, source, chinese = true)
                val english = ChineseKeyboardSymbols.displayedCandidates(key, source, chinese = false)
                assertTrue(chinese.any { it in listOf("（", "）") })
                assertTrue(chinese.any { it in listOf("《", "》") })
                assertTrue(english.any { it in listOf("(", ")") })
                assertTrue(english.any { it in listOf("<", ">") })
                assertEquals(source.size, chinese.size)
                assertEquals(source.size, english.size)
            }
        }
    }

    @Test
    fun allLowerAndUpperAsciiKeysProduceOnlyTheExpectedAsciiCaseLetter() {
        for (key in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") {
            val lower = key.lowercase()
            val expected = if (key.isLowerCase()) key.uppercase() else lower
            val result = AlphabetPopupCandidates.resolve(key.toString(), emptyList(), uppercase = key.isUpperCase())
            assertEquals(listOf(expected), result)
            assertFalse(result.any { it == "ğ" || it == "Ğ" })
            assertTrue(result.single().all { it.code < 128 })
        }
    }

    @Test
    fun emptySingleExistingCaseAndCustomMultiCharacterCandidatesAreHandled() {
        assertEquals(listOf("A"), AlphabetPopupCandidates.resolve("a", emptyList()))
        assertEquals(listOf("A"), AlphabetPopupCandidates.resolve("a", listOf("A")))
        assertEquals(listOf("a"), AlphabetPopupCandidates.resolve("A", listOf("A"), uppercase = true))
        assertEquals(listOf("=", "G", "≠", "≈"),
            AlphabetPopupCandidates.resolve("g", listOf("=", "G", "≠", "≈")))
        assertEquals(listOf("=", "g", "≠", "≈"),
            AlphabetPopupCandidates.resolve("G", listOf("=", "G", "≠", "≈"), uppercase = true))
        assertEquals(listOf("@", "B", "自定义", "多字符符号"),
            AlphabetPopupCandidates.resolve("b", listOf("@", "自定义", "多字符符号")))
        assertEquals(listOf("@", "b", "自定义", "多字符符号"),
            AlphabetPopupCandidates.resolve("B", listOf("@", "自定义", "多字符符号"), uppercase = true))
    }

    @Test
    fun nonAsciiAndNonLetterKeysDoNotReceiveAFunctionalCandidate() {
        val cases = listOf(
            "ß" to listOf("§", "自定义"),
            "K" to listOf("K"),
            "ğ" to emptyList(),
            "Ğ" to emptyList(),
            "中" to emptyList(),
            "1" to listOf("!"),
            "." to listOf("…", "多字符")
        )
        for ((key, source) in cases) {
            assertEquals(source, AlphabetPopupCandidates.resolve(key, source))
            assertEquals(source, AlphabetPopupCandidates.resolve(key, source, uppercase = true))
        }
    }
}
