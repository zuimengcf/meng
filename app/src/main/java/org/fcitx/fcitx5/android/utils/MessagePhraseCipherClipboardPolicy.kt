/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/** Clipboard policy for phrase auto-decryption; never persists clipboard text. */
object MessagePhraseCipherClipboardPolicy {
    fun shouldListen(historyEnabled: Boolean, autoDecryptEnabled: Boolean): Boolean =
        historyEnabled || autoDecryptEnabled

    /** Auto-processed ciphertext and its plaintext are kept out of persistent history. */
    fun shouldStoreHistory(
        historyEnabled: Boolean,
        autoDecryptEnabled: Boolean,
        validCiphertextHandled: Boolean,
    ): Boolean = historyEnabled && !(autoDecryptEnabled && validCiphertextHandled)

    fun decryptCopiedText(text: String, key: String): String? =
        runCatching { MessagePhraseCipherOperations.decrypt(text, key) }.getOrNull()

    /** Return a transient plaintext only for an enabled, valid ciphertext. */
    fun autoDecryptClipboardText(text: String, key: String, enabled: Boolean): String? =
        if (!enabled) null else decryptCopiedText(text, key)?.takeIf { it != text }
}

/** Prevent duplicate clipboard callbacks from repeating an auto-decrypt notification. */
internal class MessagePhraseCipherClipboardEventDeduplicator(
    private val legacyDuplicateWindowMs: Long = 100L,
) {
    private var lastText: String? = null
    private var lastTimestamp: Long? = null
    private var lastSeenAtMs = Long.MIN_VALUE

    @Synchronized
    fun isDuplicate(text: String, timestamp: Long?, nowMs: Long): Boolean {
        val duplicate = if (timestamp != null) {
            lastTimestamp == timestamp && lastText == text
        } else {
            lastTimestamp == null && lastText == text && nowMs - lastSeenAtMs in 0..legacyDuplicateWindowMs
        }
        if (duplicate) return true

        lastText = text
        lastTimestamp = timestamp
        lastSeenAtMs = nowMs
        return false
    }
}
