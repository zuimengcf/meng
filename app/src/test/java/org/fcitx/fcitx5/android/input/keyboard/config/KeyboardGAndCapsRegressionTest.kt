/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import java.io.File
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodSubMode
import org.fcitx.fcitx5.android.input.keyboard.CapsKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.SeparatorKey
import org.junit.Assert.*
import org.junit.Test

class KeyboardGAndCapsRegressionTest {
    private fun defaults(name: String = "Text") = KeyboardLayoutCodec.decode(
        File("src/main/assets/usr/share/fcitx5/keyboard/$name-layout.json").readText()
    )

    private fun previousPositions(config: KeyboardLayoutConfig) =
        ChineseKeyboardSymbols.previousMultiRowTemplate(config).copy(
            keys = ChineseKeyboardSymbols.previousMultiRowTemplate(config).keys.mapValues { (id, key) ->
                if (key is KeySpec.Letter) key.copy(popupLetterOffset = 1, popupShiftX = 0,
                    popup = if (id == "z" && config.keys["caps"] == KeySpec.Separator)
                        listOf("’", "「", "」", "『", "』") else key.popup) else key
            }
        )

    private fun ime(language: String, addon: String, icon: String = "", label: String = "") =
        InputMethodEntry(addon, addon, "", "", "", language, addon, false,
            InputMethodSubMode("", label, icon))

    @Test
    fun gUsesTheSameRuntimeCaseFeatureAsEveryOtherAlphabetKey() {
        for (name in listOf("Text", "English")) {
            val spec = defaults(name).keys.getValue("g") as KeySpec.Letter
            val symbols = ChineseKeyboardSymbols.resolve(spec.char, spec.alt, spec.popup)
            assertEquals(listOf("=", "≠", "≈"), symbols)
            assertEquals(listOf("=", "G", "≠", "≈"), AlphabetPopupCandidates.resolve("g", symbols))
            assertEquals(listOf("=", "g", "≠", "≈"), AlphabetPopupCandidates.resolve("G", symbols, uppercase = true))
        }
    }

    @Test
    fun customPopupSymbolsAreKeptAndExplicitEmptySymbolsStillHaveCaseInput() {
        val items = listOf("=", "自定义")
        assertEquals(listOf("=", "G", "自定义"), AlphabetPopupCandidates.resolve("g", items))
        assertEquals(listOf("=", "g", "自定义"), AlphabetPopupCandidates.resolve("g", items, uppercase = true))
        assertEquals(listOf("G"), AlphabetPopupCandidates.resolve("g", emptyList()))
        assertEquals(listOf("="), ChineseKeyboardSymbols.resolve("g", "=", emptyList()))
    }

    @Test
    fun chineseUsesItsOriginalSeparatorPositionAndEnglishUsesIndependentCaps() {
        val chinese = defaults()
        val english = defaults("English")
        assertEquals(KeySpec.Separator, chinese.keys.getValue("caps"))
        assertFalse(chinese.keys.containsKey("separator"))
        assertEquals(KeySpec.Caps, english.keys.getValue("caps"))
        assertFalse(english.keys.containsKey("separator"))

        val cnKey = KeyboardLayoutLoader.toKeyDefs(chinese).flatten()[chinese.rows.flatten().indexOf("caps")]
        val enKey = KeyboardLayoutLoader.toKeyDefs(english).flatten()[english.rows.flatten().indexOf("caps")]
        assertTrue(cnKey is SeparatorKey)
        assertEquals(KeyAction.FcitxKeyAction("'"), cnKey.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        assertTrue(enKey is CapsKey)
        assertEquals(KeyAction.CapsAction(false), enKey.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        assertEquals(KeyAction.CapsAction(true), enKey.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action)
        assertEquals(KeyAction.CapsAction(true), enKey.behaviors.filterIsInstance<KeyDef.Behavior.DoubleTap>().single().action)
        assertEquals("（", (chinese.keys.getValue("k") as KeySpec.Letter).alt)
        assertEquals("(", (english.keys.getValue("k") as KeySpec.Letter).alt)
    }

    @Test
    fun modeSelectionCoversRimeChineseLowerAndUpperAsciiAndNativeEnglish() {
        assertEquals("Text", TextKeyboardLayouts.forInputMethod(ime("zh", "rime", "fcitx-rime", "拼音")))
        assertEquals("English", TextKeyboardLayouts.forInputMethod(ime("zh", "rime", "fcitx_rime_latin", "abc")))
        assertEquals("English", TextKeyboardLayouts.forInputMethod(ime("zh", "rime", "fcitx_rime_latin_upper", "ABC")))
        assertEquals("English", TextKeyboardLayouts.forInputMethod(ime("en", "keyboard")))
        assertEquals("Text", TextKeyboardLayouts.forInputMethod(ime("zh_CN", "pinyin")))
    }

    @Test
    fun symbolAndNumberReturnRoutesFollowActiveModeWithoutStealingNumberPage() {
        for (active in listOf("Text", "English")) {
            assertEquals(active, TextKeyboardLayouts.resolveTarget("Text", active))
            assertEquals(active, TextKeyboardLayouts.resolveTarget("English", active))
            assertEquals("Number", TextKeyboardLayouts.resolveTarget("Number", active))
            assertEquals("Symbol", TextKeyboardLayouts.resolveTarget("Symbol", active))
        }
        assertTrue(TextKeyboardLayouts.isText("Text"))
        assertTrue(TextKeyboardLayouts.isText("English"))
        assertFalse(TextKeyboardLayouts.isText("Number"))
    }

    @Test
    fun explicitlyConfiguredSeparatorsAreNotConvertedToCaps() {
        val config = KeyboardLayoutConfig(listOf(listOf("caps", "separator")), linkedMapOf(
            "caps" to KeySpec.Separator, "separator" to KeySpec.Separator
        ))
        assertTrue(KeyboardLayoutLoader.toKeyDefs(config).flatten().all { it is SeparatorKey })
    }

    @Test
    fun unchangedPreviousTemplateUpgradesWithoutLosingOtherKeys() {
        val defaults = defaults()
        val g = defaults.keys.getValue("g") as KeySpec.Letter
        val oldPositions = previousPositions(defaults)
        val previous = oldPositions.copy(
            rows = defaults.rows.map { it.filterNot { id -> id == "separator" } },
            keys = oldPositions.keys - "separator" + ("caps" to KeySpec.Separator) +
                ("g" to g.copy(popup = listOf("≠", "≈"))) +
                ("m" to (oldPositions.keys.getValue("m") as KeySpec.Letter).let { it.copy(popup = it.popup.orEmpty() + "ü") })
        )
        assertEquals(defaults, ChineseKeyboardSymbols.upgradeTextTemplate(previous, defaults))
        assertEquals(defaults, ChineseKeyboardSymbols.upgradeTextTemplate(defaults, defaults))
        assertEquals(listOf("≠", "≈"), (previous.keys.getValue("g") as KeySpec.Letter).popup)
    }

    @Test
    fun rejectedVersionTemplatesForBothDefaultsUpgradeToCurrentDefaults() {
        for (name in listOf("Text", "English")) {
            val defaults = defaults(name)
            val g = defaults.keys.getValue("g") as KeySpec.Letter
            val oldG = g.copy(popup = listOf("G", "ğ", "≠", "≈"))
            val oldPositions = previousPositions(defaults)
            val rejected = if (name == "Text") oldPositions.copy(
                rows = defaults.rows.map { row -> row.flatMap { id ->
                    if (id == "space") listOf("separator", id) else listOf(id)
                } },
                keys = oldPositions.keys +
                    ("caps" to KeySpec.Caps) + ("separator" to KeySpec.Separator) +
                    ("g" to oldG)
            ) else oldPositions.copy(keys = oldPositions.keys + ("g" to oldG))
            assertEquals(defaults, ChineseKeyboardSymbols.upgradeTextTemplate(rejected, defaults))
        }
    }

    @Test
    fun customPreviousTemplateIsNotUpgradedAndLayoutsDoNotShareState() {
        val defaults = defaults()
        val g = defaults.keys.getValue("g") as KeySpec.Letter
        val custom = defaults.copy(keys = defaults.keys +
            ("g" to g.copy(popup = listOf("≠", "≈"))) + ("caps" to KeySpec.Caps))
        assertEquals(custom, ChineseKeyboardSymbols.upgradeTextTemplate(custom, defaults))
        val english = defaults("English")
        val editedEnglish = english.copy(keys = english.keys + ("caps" to KeySpec.Separator))
        assertEquals(KeySpec.Caps, english.keys.getValue("caps"))
        assertEquals(KeySpec.Separator, editedEnglish.keys.getValue("caps"))
        assertEquals(KeySpec.Separator, defaults.keys.getValue("caps"))
        assertFalse(defaults.keys.containsKey("separator"))
    }

    @Test
    fun chineseDefaultSymbolsOnlyDropTheTwoRequestedQuotePairs() {
        val layout = defaults()
        fun allSymbols(config: KeyboardLayoutConfig) = config.keys.values.filterIsInstance<KeySpec.Letter>()
            .flatMap { listOfNotNull(it.alt) + it.popup.orEmpty() }.toSet()
        val previous = ChineseKeyboardSymbols.previousMultiRowTemplate(layout)
        assertEquals(allSymbols(previous) - setOf("「", "」", "『", "』"), allSymbols(layout))
        val expected = setOf("；", "￥", "’", "·", "\\", "&", "_")
        val actual = setOf("s", "z", "m", "b").flatMap { id ->
            (layout.keys.getValue(id) as KeySpec.Letter).popup.orEmpty()
        }.toSet()
        assertEquals(expected, actual)
        assertEquals(expected, setOf("s", "z", "m", "b").flatMap { id ->
            ChineseKeyboardSymbols.candidates.getValue(id).drop(1)
        }.toSet())
    }

    @Test
    fun bothLayoutsKeepPureNumberRowMAsciiPeriodAndKlLongPressBookMarks() {
        for (name in listOf("Text", "English")) {
            val layout = defaults(name)
            "qwertyuiop".zip("1234567890").forEach { (id, digit) ->
                val spec = layout.keys.getValue(id.toString()) as KeySpec.Letter
                assertEquals(digit.toString(), spec.alt)
                assertEquals(emptyList<String>(), spec.popup)
            }
            assertEquals(".", (layout.keys.getValue("m") as KeySpec.Letter).alt)
            assertFalse("ü" in (layout.keys.getValue("m") as KeySpec.Letter).popup.orEmpty())
            for (key in listOf("k", "l")) {
                val spec = layout.keys.getValue(key) as KeySpec.Letter
                assertTrue(spec.alt in listOf("（", "）", "(", ")"))
                assertTrue(spec.popup.orEmpty().any { it in listOf("《", "》", "<", ">") })
            }
        }
    }

    @Test
    fun englishLayoutSwitchTargetAndBothJsonRoundTripsRemainValid() {
        for (name in listOf("Text", "English")) {
            val config = defaults(name)
            assertEquals(config, KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(config)))
        }
        val config = KeyboardLayoutConfig(listOf(listOf("text")), mapOf(
            "text" to KeySpec.LayoutSwitch("ABC", "English")
        ))
        KeyboardLayoutCodec.validate(config)
    }

    @Test
    fun importRegressionFixtureIsThePreviousMultiRowTemplate() {
        val current = defaults()
        val fixture = KeyboardLayoutCodec.decode(
            File("src/test/resources/keyboard/Text-layout-import-regression.json").readText()
        )
        assertEquals(ChineseKeyboardSymbols.previousMultiRowTemplate(current), fixture)
        assertEquals(current, ChineseKeyboardSymbols.upgradeTextTemplate(fixture, current))
        val custom = fixture.copy(keys = fixture.keys +
            ("a" to (fixture.keys.getValue("a") as KeySpec.Letter).copy(popup = listOf("自定义"))))
        assertEquals(custom, ChineseKeyboardSymbols.upgradeTextTemplate(custom, current))
    }

    @Test
    fun legacyTemplateRecognitionRetainsOriginalGAndSeparatorShape() {
        val legacy = ChineseKeyboardSymbols.legacyTextTemplate(defaults())
        assertNull((legacy.keys.getValue("g") as KeySpec.Letter).popup)
        assertEquals(KeySpec.Separator, legacy.keys.getValue("caps"))
    }
}
