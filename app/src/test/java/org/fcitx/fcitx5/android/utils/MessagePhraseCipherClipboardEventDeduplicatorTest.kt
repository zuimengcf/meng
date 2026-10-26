package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePhraseCipherClipboardEventDeduplicatorTest {
    @Test
    fun duplicateAndroidClipboardCallbackDoesNotRepeatNotification() {
        val deduplicator = MessagePhraseCipherClipboardEventDeduplicator()

        assertFalse(deduplicator.isDuplicate("ciphertext", timestamp = 1_000L, nowMs = 10L))
        assertTrue(deduplicator.isDuplicate("ciphertext", timestamp = 1_000L, nowMs = 50_000L))
        assertFalse(deduplicator.isDuplicate("ciphertext", timestamp = 1_001L, nowMs = 50_001L))
        assertTrue(deduplicator.isDuplicate("ciphertext", timestamp = 1_001L, nowMs = 50_002L))
    }

    @Test
    fun legacyCallbacksAreDeduplicatedOnlyWithinTheShortWindow() {
        val deduplicator = MessagePhraseCipherClipboardEventDeduplicator()

        assertFalse(deduplicator.isDuplicate("ciphertext", timestamp = null, nowMs = 1_000L))
        assertTrue(deduplicator.isDuplicate("ciphertext", timestamp = null, nowMs = 1_050L))
        assertFalse(deduplicator.isDuplicate("ciphertext", timestamp = null, nowMs = 1_101L))
        assertFalse(deduplicator.isDuplicate("different ciphertext", timestamp = null, nowMs = 1_102L))
    }
}
