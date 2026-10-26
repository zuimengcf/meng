/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/** Raised when the original text exceeds the product limit; callers must leave the editor unchanged. */
class MessagePhraseCipherTooLongException(val actualCodePointCount: Int) :
    IllegalArgumentException("Original text exceeds ${MessagePhraseCipherOperations.MAX_ORIGINAL_CODE_POINTS} Unicode code points")

/** Applies the original-text length policy around the reversible phrase cipher. */
object MessagePhraseCipherOperations {
    const val MAX_ORIGINAL_CODE_POINTS = 50
    private const val MAX_CIPHER_CODE_POINTS = 1024

    fun encrypt(originalText: String, key: String = MessagePhraseCipher.DEFAULT_KEY): String {
        requireOriginalTextWithinLimit(originalText)
        return MessagePhraseCipher(key).encrypt(originalText)
    }

    fun decrypt(cipherText: String, key: String = MessagePhraseCipher.DEFAULT_KEY): String {
        require(codePointCount(cipherText) <= MAX_CIPHER_CODE_POINTS) { "Ciphertext is too long" }
        val originalText = MessagePhraseCipher(key).decrypt(cipherText)
        requireOriginalTextWithinLimit(originalText)
        return originalText
    }

    fun codePointCount(text: String): Int = Character.codePointCount(text, 0, text.length)

    private fun requireOriginalTextWithinLimit(text: String) {
        val count = codePointCount(text)
        if (count > MAX_ORIGINAL_CODE_POINTS) {
            throw MessagePhraseCipherTooLongException(count)
        }
    }
}
