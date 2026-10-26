/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickerPopupActionTest {
    @Test
    fun lessThanAlternativesKeepSelectedText() {
        // Regression: PopupKeyboardUi produces CommitAction, not FcitxKeyAction.
        listOf("《", "≤", "«", "‹", "⟨").forEach { selected ->
            assertEquals(selected, KeyAction.CommitAction(selected).pickerCommitText())
        }
    }

    @Test
    fun otherAlternativesAndMultiCharacterSelectionsArePreserved() {
        listOf("》", "≥", "“", "😊", "<tag>", "自定义符号").forEach { selected ->
            assertEquals(selected, KeyAction.CommitAction(selected).pickerCommitText())
        }
    }

    @Test
    fun legacyEngineKeyActionsRemainSupported() {
        assertEquals("≤", KeyAction.FcitxKeyAction("≤", code = 0).pickerCommitText())
    }

    @Test
    fun baseSymbolStillCommitsVerbatim() {
        assertEquals("<", KeyAction.CommitAction("<").pickerCommitText())
    }

    @Test
    fun missingPopupSelectionDoesNotConsumeBaseClick() {
        val missing: KeyAction? = null
        assertNull(missing.pickerCommitText())
    }

    @Test
    fun unrelatedActionsAreNotTreatedAsText() {
        assertNull(KeyAction.QuickPhraseAction.pickerCommitText())
        assertNull(KeyAction.LayoutSwitchAction().pickerCommitText())
    }
}
