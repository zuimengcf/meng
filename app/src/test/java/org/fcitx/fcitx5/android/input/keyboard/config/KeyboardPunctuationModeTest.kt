/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPunctuationModeTest {
    @Test
    fun routesKeyboardAndRimeModes() {
        assertFalse(KeyboardPunctuationMode.isChinese("en", "keyboard", "keyboard", "", "", ""))
        assertTrue(KeyboardPunctuationMode.isChinese("zh_CN", "keyboard", "keyboard", "", "", ""))
        assertTrue(KeyboardPunctuationMode.isChinese("en", "rime", "rime", "fcitx-rime", "注音", "注音"))
        assertTrue(KeyboardPunctuationMode.isChinese("zh", "rime", "rime", "fcitx-rime", "注", "注音", "fcitx_rime_latin"))
        assertFalse(KeyboardPunctuationMode.isChinese("zh_CN", "rime", "rime", "fcitx_rime_latin", "abc", "Latin Mode"))
        assertFalse(KeyboardPunctuationMode.isChinese("zh_CN", "rime", "rime", "fcitx_rime_latin_upper", "ABC", "Latin Mode"))
        assertFalse(KeyboardPunctuationMode.isChinese("zh_CN", "rime", "rime", "fcitx_rime_disable", "", ""))
    }

    @Test
    fun rimeAsciiUsesLabelFallbackWhenIconIsUnavailable() {
        assertFalse(KeyboardPunctuationMode.isChinese("zh", "rime", "rime", "", "abc", "Latin Mode"))
        assertFalse(KeyboardPunctuationMode.isChinese("zh", "rime", "rime", "", "ABC", "自訂 ASCII"))
        assertTrue(KeyboardPunctuationMode.isChinese("zh", "rime", "rime", "", "注", "注音"))
    }

    @Test
    fun convertsPunctuationInBothDirectionsAndPreservesOtherSymbols() {
        assertEquals("。，？！：；（）【】‘“～=+-/@*#\\", KeyboardPunctuationMode.display(".,?!:;()[]'\"~=+-/@*#\\", true))
        assertEquals(",.?!:;()[]''\"\"~", KeyboardPunctuationMode.display("，。？！：；（）【】‘’“”～", false))
        assertEquals("...--<>\"\"''", KeyboardPunctuationMode.display("……——《》“”‘’", false))
        assertEquals("……——《》“‘‘", KeyboardPunctuationMode.display("...--<>\"''", true))
    }

    @Test
    fun candidatesKeepFirstConvertedValueAndDistinctOrder() {
        val items = listOf("。", ".", "你好……", "你好...", "=", "=")
        assertEquals(listOf("。", "你好……", "="), KeyboardPunctuationMode.candidates(items, true))
        assertEquals(listOf(".", "你好...", "="), KeyboardPunctuationMode.candidates(items, false))
        assertEquals("你好……", KeyboardPunctuationMode.candidates(listOf("你好...", "你好……"), true).first())
    }

    @Test
    fun bracketsAndKeyBoundPunctuationSwitchWithoutRelocating() {
        assertEquals(listOf("（", "《", "〈", "【"), KeyboardPunctuationMode.candidates(listOf("（", "《", "〈", "【"), true))
        assertEquals(listOf("(", "<", "["), KeyboardPunctuationMode.candidates(listOf("（", "《", "〈", "【"), false))
        assertEquals("）", KeyboardPunctuationMode.display(")", true))
        assertEquals(")", KeyboardPunctuationMode.display("）", false))
        assertEquals("？", KeyboardPunctuationMode.display("?", true))
        assertEquals("?", KeyboardPunctuationMode.display("？", false))
    }

    @Test
    fun englishModeNormalizesAllDefaultChinesePunctuationFamilies() {
        assertEquals(",<>\"\"\"\"[].$-...", KeyboardPunctuationMode.display("、〈〉「」『』〔〕·￥—…", false))
    }

    @Test
    fun repeatedModeConversionIsIdempotent() {
        for (chinese in listOf(false, true)) {
            val once = KeyboardPunctuationMode.display("（《》）？：、……——", chinese)
            assertEquals(once, KeyboardPunctuationMode.display(once, chinese))
        }
    }

    @Test
    fun emptyAndLiteralTextAreSafe() {
        assertEquals("", KeyboardPunctuationMode.display("", false))
        assertEquals(listOf("x", "xy"), KeyboardPunctuationMode.candidates(listOf("x", "x", "xy"), false))
        assertEquals("x\uD800", KeyboardPunctuationMode.display("x\uD800", false))
    }
}
