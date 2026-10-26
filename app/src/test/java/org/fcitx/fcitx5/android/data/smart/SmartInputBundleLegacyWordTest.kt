/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.data.stats.db.FreqWord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInputBundleLegacyWordTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun exportsAndValidatesLegacyWordLongerThan512Characters() {
        val word = "你".repeat(513)
        val encoded = SmartInputBundle.encode(config(), snapshot(word))

        val payload = SmartInputBundle.validate(encoded)
        assertEquals(word, payload.learning.words.single().word)
    }

    @Test
    fun importsVersion2BackupWithLegacyLongWord() {
        val word = "中".repeat(1024)
        val version3 = Json.parseToJsonElement(SmartInputBundle.encode(config(), snapshot(word))).jsonObject
        val version2 = JsonObject(version3.filterKeys { it != "manual_rules" } + ("version" to JsonPrimitive(2))).toString()

        val payload = SmartInputBundle.validate(version2)
        assertEquals(word, payload.learning.words.single().word)
        assertTrue(payload.preview.preservesManualRules)
    }

    @Test
    fun longLegacyWordCanBeSavedInRollbackJournalAndRecoveredAfterPublishFailure() {
        val word = "文".repeat(2048)
        val previous = SmartInputBundle.encode(config(), snapshot(word))
        val replacement = SmartInputBundle.validate(SmartInputBundle.encode(config(), snapshot("新词")))
        val directory = Files.createTempDirectory("smart-input-journal-").toFile().also(roots::add)
        val journal = SmartInputBundleJournal(directory)
        var publishCalls = 0
        var recoveredWord: String? = null

        val failure = runCatching {
            runBlocking {
                journal.apply(previous, replacement) { payload ->
                    publishCalls++
                    if (publishCalls == 1) error("simulated publish failure")
                    recoveredWord = payload.learning.words.single().word
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, publishCalls)
        assertEquals(word, recoveredWord)
        assertFalse(journal.hasPending)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun config() = linkedMapOf(
        "suggestion" to "1", "correction" to "1", "neighbor" to "1", "fuzzy" to "1",
        "habit" to "1", "learning" to "1", "suggestion_limit" to "15", "correction_limit" to "5",
        "suggestion_label" to "", "correction_label" to "", "suggestion_source" to "builtin",
        "correction_source" to "builtin", "correction_placement" to "append"
    )

    private fun snapshot(word: String) = InputStatsManager.LearningSnapshot(
        daily = emptyList(),
        words = listOf(FreqWord(word = word, count = 1, updatedAt = 1)),
        associations = emptyList(),
        corrections = emptyList()
    )
}
