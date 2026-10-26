/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardLayoutCodecTest {
    @Test
    fun jsonRoundTripPreservesChineseMulticharacterPunctuationAndEscapes() {
        val config = KeyboardLayoutConfig(
            rows = listOf(listOf("x", "comma")),
            keys = linkedMapOf(
                "x" to KeySpec.Letter(
                    char = "x",
                    alt = "\\\"",
                    variant = "normal",
                    popup = listOf("你好……", "《》")
                ),
                "comma" to KeySpec.Comma(
                    width = 0.25f,
                    variant = "alternative",
                    char = "，\\\""
                )
            )
        )

        val encoded = KeyboardLayoutCodec.encode(config)
        assertEquals(config, KeyboardLayoutCodec.decode(encoded))
        assertTrue(encoded.contains("你好"))
        assertTrue(encoded.contains("\\\\\\\""))
    }

    @Test
    fun legacyLetterJsonWithoutPopupStillLoads() {
        val decoded = KeyboardLayoutCodec.decode(
            """{"rows":[["a"]],"keys":{"a":{"type":"letter","char":"a","alt":"1"}}}"""
        )
        assertEquals(KeySpec.Letter("a", "1", "normal", null), decoded.keys.getValue("a"))
    }

    @Test
    fun chineseSymbolsResolveAndCoverCommonPunctuation() {
        assertEquals(listOf("！", "自定义"), ChineseKeyboardSymbols.resolve("q", "！", listOf("自定义")))
        assertEquals(listOf("3"), ChineseKeyboardSymbols.resolve("e", "3", null))
        val required = listOf("，", "。", "？", "！", "：", "；", "、", "（", "）", "【", "】", "《", "》", "“", "”", "‘", "’", "……", "——", "·").toSet()
        val available = ChineseKeyboardSymbols.candidates.values.flatten().map { KeyboardPunctuationMode.display(it, true) }.toSet()
        assertTrue("missing punctuation: ${required - available}", available.containsAll(required))
        assertTrue(ChineseKeyboardSymbols.candidates.getValue("k").containsAll(listOf("《", "（")))
        assertTrue(ChineseKeyboardSymbols.candidates.getValue("l").containsAll(listOf("》", "）")))
    }

    @Test
    fun invalidLayoutsAreRejected() {
        val cases = listOf(
            KeyboardLayoutConfig(listOf(listOf("missing")), mapOf("a" to KeySpec.Letter("a"))) to "bad row reference",
            KeyboardLayoutConfig(listOf(listOf("a", "a")), mapOf("a" to KeySpec.Letter("a"))) to "duplicate id",
            KeyboardLayoutConfig(listOf(emptyList()), mapOf("a" to KeySpec.Letter("a"))) to "empty row",
            KeyboardLayoutConfig(listOf(listOf("s")), mapOf("s" to KeySpec.Symbol("!", width = 1.01f))) to "bad width",
            KeyboardLayoutConfig(listOf(listOf("a")), mapOf("a" to KeySpec.Letter("a", "1", popup = listOf("1") + List(32) { "x" }))) to "too many popup candidates",
            KeyboardLayoutConfig(listOf(listOf("a")), mapOf("a" to KeySpec.Letter("a", "1", popup = listOf("")))) to "blank popup"
        )
        cases.forEach { (config, message) ->
            assertThrows(message, IllegalArgumentException::class.java) { KeyboardLayoutCodec.validate(config) }
        }
    }

    @Test
    fun malformedJsonIsRejected() {
        val malformed = listOf(
            """{"rows":[["a"]],"keys":{"a":{"type":"mystery","char":"a"}}}""",
            """{"rows":[["a"]],"keys":{"a":{"type":"letter","char":"a","unknown":true}}}""",
            """{"rows":[["a"]],"keys":{"a":{"type":"letter","char":"a","alt":"1\u0001"}}}"""
        )
        malformed.forEach { text -> assertThrows(Exception::class.java) { KeyboardLayoutCodec.decode(text) } }
        assertThrows(Exception::class.java) {
            KeyboardLayoutCodec.decode("""{"rows":[["a"]],"keys":{"a":{"type":"letter","char":"ğ"}}}""")
        }
    }

    @Test
    fun oversizedInputIsRejectedBeforeDecoding() {
        val oversized = "{" + "x".repeat(KeyboardLayoutCodec.MAX_BYTES) + "}"
        assertTrue(oversized.toByteArray(Charsets.UTF_8).size > KeyboardLayoutCodec.MAX_BYTES)
        assertThrows(Exception::class.java) { KeyboardLayoutCodec.decode(oversized) }
    }

    @Test
    fun defaultTextAndNumberAssetsRemainValid() {
        val text = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        val number = KeyboardLayoutCodec.decode(asset("Number-layout.json").readText())
        assertTrue(text.rows.isNotEmpty())
        assertTrue(number.rows.isNotEmpty())
        val letters = text.keys.values.filterIsInstance<KeySpec.Letter>()
        assertTrue(letters.isNotEmpty())
        assertTrue(letters.all { it.alt != null && it.popup != null && it.alt !in it.popup })
        letters.forEach { assertEquals(ChineseKeyboardSymbols.candidates.getValue(it.char.lowercase()), ChineseKeyboardSymbols.resolve(it.char, it.alt, it.popup)) }
        val legacy = ChineseKeyboardSymbols.legacyTextTemplate(text)
        KeyboardLayoutCodec.validate(legacy)
        assertEquals("(", (legacy.keys.getValue("k") as KeySpec.Letter).alt)
        assertEquals(")", (legacy.keys.getValue("l") as KeySpec.Letter).alt)
        assertTrue(legacy.keys.values.filterIsInstance<KeySpec.Letter>().all { it.popup == null })
        assertEquals(listOf("≠", "≈"), (text.keys.getValue("g") as KeySpec.Letter).popup)
        assertTrue(letters.all { spec -> spec.popup.orEmpty().none { it.length == 1 && it[0] in 'A'..'Z' } })
        assertTrue(letters.flatMap { listOfNotNull(it.alt) + it.popup.orEmpty() }.none { it == "ğ" || it == "Ğ" })
        assertEquals("（", (text.keys.getValue("k") as KeySpec.Letter).alt)
        assertEquals("）", (text.keys.getValue("l") as KeySpec.Letter).alt)
        assertTrue(ChineseKeyboardSymbols.candidates.getValue("k").contains("《"))
        assertTrue(ChineseKeyboardSymbols.candidates.getValue("l").contains("》"))
    }

    @Test
    fun originalRowsKeyOrderAndBaseSymbolAssignmentsArePreserved() {
        val text = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        val expectedRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("caps", "z", "x", "c", "v", "b", "n", "m", "backspace"),
            listOf("symbol123", "comma", "lang", "space", "dot", "return")
        )
        assertEquals(expectedRows, text.rows)
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "a", "s", "d", "f", "g", "h", "j", "k", "l", "z", "x", "c", "v", "b", "n", "m", "caps", "backspace", "symbol123", "comma", "lang", "space", "dot", "return"), text.keys.keys.toList())
        val baseSymbols = linkedMapOf(
            "q" to "1",
            "w" to "2",
            "e" to "3",
            "r" to "4",
            "t" to "5",
            "y" to "6",
            "u" to "7",
            "i" to "8",
            "o" to "9",
            "p" to "0",
            "a" to "@",
            "s" to "*",
            "d" to "+",
            "f" to "-",
            "g" to "=",
            "h" to "/",
            "j" to "#",
            "k" to "（",
            "l" to "）",
            "z" to "'",
            "x" to ":",
            "c" to "\"",
            "v" to "?",
            "b" to "!",
            "n" to "~",
            "m" to "."
        )
        baseSymbols.forEach { (key, alt) ->
            val letter = text.keys.getValue(key) as KeySpec.Letter
            assertEquals(key, letter.char)
            assertEquals(alt, letter.alt)
            assertEquals(alt, ChineseKeyboardSymbols.resolve(letter.char, letter.alt, letter.popup).first())
        }
        val k = text.keys.getValue("k") as KeySpec.Letter
        val l = text.keys.getValue("l") as KeySpec.Letter
        assertTrue("《" in k.popup.orEmpty())
        assertTrue("》" in l.popup.orEmpty())
    }

    @Test
    fun renderedDefinitionsUseLiteralAltPopupAndCommitActions() {
        val config = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        val definitions = KeyboardLayoutLoader.toKeyDefs(config).flatten()
        val ids = config.rows.flatten()
        ids.zip(definitions).forEach { (id, definition) ->
            val spec = config.keys.getValue(id)
            if (spec is KeySpec.Letter) {
                val appearance = definition.appearance as org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.AltText
                assertTrue(appearance.rawAltText)
                assertTrue(appearance.modeAwarePunctuation)
                assertEquals(spec.alt, appearance.altText)
                val swipe = definition.behaviors.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Behavior.Swipe>().single()
                assertEquals(spec.alt, (swipe.action as org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction).text)
                val popup = definition.popup!!.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Popup.Keyboard.Explicit>().single()
                assertEquals(ChineseKeyboardSymbols.resolve(spec.char, spec.alt, spec.popup), popup.items.toList())
                val preview = definition.popup!!.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Popup.AltPreview>().single()
                assertTrue(preview.rawAlternative)
                assertEquals(spec.alt, preview.alternative)
            } else if (id == "dot" || id == "comma") {
                val appearance = definition.appearance as org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Text
                val action = definition.behaviors.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Behavior.Press>().single().action
                    as org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
                assertTrue(appearance.rawText)
                assertEquals(appearance.displayText, action.text)
                assertTrue(appearance.modeAwarePunctuation)
                assertEquals(if (id == "dot") "." else ",", action.text)
                for (chinese in listOf(false, true)) {
                    assertEquals(
                        KeyboardPunctuationMode.display(appearance.displayText, chinese),
                        KeyboardPunctuationMode.display(action.text, chinese)
                    )
                }
            }
        }
    }

    @Test
    fun oldFullPopupAndNewExtrasDecodeToTheSameConfiguration() {
        val old = """{"rows":[["k"]],"keys":{"k":{"type":"letter","char":"k","alt":"（","popup":["（","《","《"]}}}"""
        val current = """{"rows":[["k"]],"keys":{"k":{"type":"letter","char":"k","alt":"（","popup":["《"]}}}"""
        val decoded = KeyboardLayoutCodec.decode(old)
        assertEquals(KeyboardLayoutCodec.decode(current), decoded)
        val letter = decoded.keys.getValue("k") as KeySpec.Letter
        assertEquals(listOf("《"), letter.popup)
        assertEquals(listOf("（", "《"), ChineseKeyboardSymbols.resolve(letter.char, letter.alt, letter.popup))
    }

    @Test
    fun exportAlwaysWritesOnlyDistinctExtrasAndPreservesRows() {
        val original = KeyboardLayoutConfig(listOf(listOf("k")), linkedMapOf(
            "k" to KeySpec.Letter("k", "（", popup = listOf("（", "《", "《"))
        ))
        val exported = KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(original))
        assertEquals(original.rows, exported.rows)
        assertEquals(original.keys.keys.toList(), exported.keys.keys.toList())
        assertEquals(listOf("《"), (exported.keys.getValue("k") as KeySpec.Letter).popup)
        assertEquals(exported, KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(exported)))
    }

    @Test
    fun explicitEmptyExtrasDoNotRestoreDefaultCandidates() {
        val empty = KeyboardLayoutConfig(listOf(listOf("e")), mapOf("e" to KeySpec.Letter("e", "3", popup = emptyList())))
        val roundTrip = KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(empty))
        assertEquals(empty, roundTrip)
        assertEquals(listOf("3"), ChineseKeyboardSymbols.resolve("e", "3", emptyList()))
        assertEquals(listOf("3"), ChineseKeyboardSymbols.resolve("e", "3", null))
        val withoutAlt = empty.copy(keys = mapOf("e" to KeySpec.Letter("e", popup = listOf("！"))))
        KeyboardLayoutCodec.validate(withoutAlt)
        assertEquals(withoutAlt, KeyboardLayoutCodec.decode(KeyboardLayoutCodec.encode(withoutAlt)))
    }

    @Test
    fun extrasCannotOverflowTheResolvedCandidateLimit() {
        val extras = (1..32).map { "extra$it" }
        val valid = KeyboardLayoutConfig(listOf(listOf("a")), mapOf("a" to KeySpec.Letter("a", "1", popup = extras)))
        KeyboardLayoutCodec.validate(valid)
        val overflow = valid.copy(keys = mapOf("a" to KeySpec.Letter("a", "1", popup = extras + "extra33")))
        assertThrows(IllegalArgumentException::class.java) { KeyboardLayoutCodec.validate(overflow) }
    }

    @Test
    fun everyExtraCountThrough32IsAcceptedByCodecAndLoaderWithAltPreserved() {
        for (count in 0..32) {
            val config = KeyboardLayoutConfig(
                rows = listOf(listOf("a")),
                keys = mapOf("a" to KeySpec.Letter("a", "ALT", popup = List(count) { "extra$it" }))
            )
            val encoded = KeyboardLayoutCodec.encode(config)
            val decoded = KeyboardLayoutCodec.decode(encoded)
            val loaded = KeyboardLayoutLoader.decodeConfig(encoded)
            for (result in listOf(decoded, loaded)) {
                val letter = result.keys.getValue("a") as KeySpec.Letter
                assertEquals("ALT", letter.alt)
                assertEquals(count, letter.popup.orEmpty().size)
            }
        }
    }

    @Test
    fun regressionFixtureImportsUpgradesAndMatchesDefaultAsset() {
        val previous = KeyboardLayoutLoader.decodeConfig(resource("/keyboard/Text-layout-import-regression.json"))
        val default = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        assertEquals(ChineseKeyboardSymbols.previousMultiRowTemplate(default), previous)
        val imported = ChineseKeyboardSymbols.upgradeTextTemplate(previous, default)
        assertEquals(default, imported)

        val definitions = KeyboardLayoutLoader.toKeyDefs(imported).flatten()
        val ids = imported.rows.flatten()
        val emptyPopupLetters = setOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "f")
        emptyPopupLetters.forEach { id ->
            val letter = imported.keys.getValue(id) as KeySpec.Letter
            assertEquals(emptyList<String>(), letter.popup)
            val definition = definitions[ids.indexOf(id)]
            val explicit = definition.popup.orEmpty()
                .filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Popup.Keyboard.Explicit>()
                .single()
            assertEquals(listOf(letter.alt), explicit.items.toList())
        }

        val m = imported.keys.getValue("m") as KeySpec.Letter
        assertEquals(".", m.alt)
        assertTrue("\\" in m.popup.orEmpty())
        assertTrue("." !in m.popup.orEmpty())
        val mDefinition = definitions[ids.indexOf("m")]
        val appearance = mDefinition.appearance as org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.AltText
        val swipe = mDefinition.behaviors
            .filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Behavior.Swipe>()
            .single().action as org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
        assertEquals(".", KeyboardPunctuationMode.display(appearance.altText, false))
        assertEquals(".", ChineseKeyboardSymbols.display("m", appearance.altText, true))
        assertEquals(".", KeyboardPunctuationMode.display(swipe.text, false))
        assertEquals(".", if (swipe.modeAwarePunctuation) KeyboardPunctuationMode.display(swipe.text, true) else swipe.text)
        assertTrue("\\" in KeyboardPunctuationMode.candidates(ChineseKeyboardSymbols.resolve(m.char, m.alt, m.popup), false))
    }

    @Test
    fun defaultsHaveNoCrossKeyDuplicatesAndRemainUniqueInEachMode() {
        val config = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        val letters = config.keys.values.filterIsInstance<KeySpec.Letter>()
        val raw = letters.flatMap { listOfNotNull(it.alt) + it.popup.orEmpty() }
        assertEquals(raw.size, raw.distinct().size)
        val chinese = raw.map { KeyboardPunctuationMode.display(it, true) }
        assertEquals(chinese.size, chinese.distinct().size)
        for (letter in letters) {
            assertFalse(letter.popup.orEmpty().contains(letter.alt))
            assertEquals(letter.popup.orEmpty().distinct(), letter.popup.orEmpty())
            val full = ChineseKeyboardSymbols.resolve(letter.char, letter.alt, letter.popup)
            for (mode in listOf(false, true)) {
                val shown = KeyboardPunctuationMode.candidates(full, mode)
                assertEquals(shown.distinct(), shown)
                assertEquals(KeyboardPunctuationMode.display(letter.alt!!, mode), shown.first())
                assertEquals(full.map { KeyboardPunctuationMode.display(it, mode) }.distinct(), shown)
            }
        }
    }

    @Test
    fun mDisplaysAndCommitsLiteralPeriodInBothModes() {
        val config = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        val m = config.keys.getValue("m") as KeySpec.Letter
        assertEquals(".", m.alt)
        assertTrue("\\" in m.popup.orEmpty())
        assertFalse("." in m.popup.orEmpty())
        val definition = KeyboardLayoutLoader.toKeyDefs(config).flatten()[config.rows.flatten().indexOf("m")]
        val appearance = definition.appearance as org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.AltText
        val swipe = definition.behaviors.filterIsInstance<org.fcitx.fcitx5.android.input.keyboard.KeyDef.Behavior.Swipe>().single().action as org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
        val full = ChineseKeyboardSymbols.resolve(m.char, m.alt, m.popup)
        for ((chinese, expected) in listOf(false to ".", true to ".")) {
            assertEquals(expected, ChineseKeyboardSymbols.display("m", appearance.altText, chinese))
            assertFalse(swipe.modeAwarePunctuation)
            assertEquals(expected, if (swipe.modeAwarePunctuation) KeyboardPunctuationMode.display(swipe.text, chinese) else swipe.text)
            val shown = ChineseKeyboardSymbols.displayedCandidates("m", full, chinese)
            assertEquals(expected, shown.first())
            assertTrue("\\" in shown)
        }
    }

    @Test
    fun numberRowIsPureAndMovedSymbolsRemainOnBottomRow() {
        val config = KeyboardLayoutCodec.decode(asset("Text-layout.json").readText())
        "qwertyuiop".zip("1234567890").forEach { (id, digit) ->
            val key = config.keys.getValue(id.toString()) as KeySpec.Letter
            assertEquals(digit.toString(), key.alt)
            assertEquals(emptyList<String>(), key.popup)
            assertEquals(listOf(digit.toString()), ChineseKeyboardSymbols.resolve(key.char, key.alt, key.popup))
        }
        val bottom = "zxcvbnm".flatMap { (config.keys.getValue(it.toString()) as KeySpec.Letter).popup.orEmpty() }
        assertTrue(bottom.containsAll(listOf("……", "￥", "%", "‰", "—", "——", "·", "〔", "〕")))
        assertFalse("ü" in bottom)
        assertTrue(bottom.none { it in setOf("「", "」", "『", "』") })
        assertEquals("。", ChineseKeyboardSymbols.display("dot", ".", true))
        assertEquals(".", ChineseKeyboardSymbols.display("M", ".", true))
        assertEquals("。", ChineseKeyboardSymbols.display("m", "。", true))
    }

    private fun asset(name: String): File = File("src/main/assets/usr/share/fcitx5/keyboard/$name")

    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream(name)) {
        "missing test resource: $name"
    }.bufferedReader().use { it.readText() }
}
