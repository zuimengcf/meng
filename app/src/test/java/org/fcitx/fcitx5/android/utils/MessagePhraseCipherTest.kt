package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePhraseCipherTest {
    @Test
    fun firstCodePointStaysVisibleAndBasicHanRoundTrips() {
        val cipher = MessagePhraseCipher()
        val samples = listOf("我爱你", "你好", "静夜思", "龘", "\u9fff")

        samples.forEach { message ->
            val ciphertext = cipher.encrypt(message)
            assertTrue(ciphertext.startsWith(MessagePhraseCipher.FORMAT_PREFIX + firstCodePoint(message)))
            assertEquals(message, cipher.decrypt(ciphertext))
        }
    }

    @Test
    fun laterExtendedAndNonHanCodePointsRoundTrip() {
        val cipher = MessagePhraseCipher()
        val samples = listOf("你㐀", "你𠀀", "你A", "你🙂", "你\uD800")

        samples.forEach { message ->
            assertEquals(message, cipher.decrypt(cipher.encrypt(message)))
        }
    }

    @Test
    fun firstCodePointIsPreservedIncludingWhitespaceAndPunctuation() {
        val samples = listOf("静夜思", "㐀夜思", "𠀀A汉", "A静夜", "🙂你好", " 你好", "，你好", "。你好")
        val keys = listOf(MessagePhraseCipher.DEFAULT_KEY, "my-key")

        keys.forEach { key ->
            val cipher = MessagePhraseCipher(key)
            samples.forEach { message ->
                val ciphertext = cipher.encrypt(message)
                val decoded = cipher.decrypt(ciphertext)
                val first = firstCodePoint(message)
                assertTrue(ciphertext.startsWith(MessagePhraseCipher.FORMAT_PREFIX + first))
                assertTrue(decoded.startsWith(first))
                assertEquals(message, decoded)
            }
        }
    }

    @Test
    fun followingCodepointDependsOnPreviousOriginalCodePoint() {
        val cipher = MessagePhraseCipher()
        val afterMe = cipher.encrypt("你我走")
        val afterYou = cipher.encrypt("你你走")

        assertNotEquals(afterMe, afterYou)
        assertEquals("你我走", cipher.decrypt(afterMe))
        assertEquals("你你走", cipher.decrypt(afterYou))
    }

    @Test
    fun outputContainsOnlyCompleteSimplifiedSvoSentences() {
        val cipher = MessagePhraseCipher()
        val sample = buildString {
            for (codePoint in 0x4e00 until 0x4f00) appendCodePoint(codePoint)
        }
        val ciphertext = cipher.encrypt(sample)
        val firstLength = Character.charCount(Character.codePointAt(sample, 0))
        val markedCipherText = ciphertext.removePrefix(MessagePhraseCipher.FORMAT_PREFIX)
        val sentences = markedCipherText.dropLast(1).substring(firstLength + 1).split('。')
        val sentenceBook = MessagePhraseCipherSentenceBook

        assertTrue(ciphertext.startsWith(MessagePhraseCipher.FORMAT_PREFIX + firstCodePoint(sample) + "，"))
        assertFalse(ciphertext.contains('、'))
        assertTrue(sentences.size > 200)
        assertTrue(sentences.all { it in sentenceBook.sentenceIndices })
        assertEquals(setOf(1, 2, 3, 4), sentenceBook.subjects.map { it.length }.toSet())
        assertTrue(sentenceBook.predicateObjects.map { it.length }.toSet().size > 1)
        assertEquals(sample, cipher.decrypt(ciphertext))
    }

    @Test
    fun sentenceBookHasUniqueSubjectVerbObjectCodes() {
        val book = MessagePhraseCipherSentenceBook
        assertEquals(64, book.subjects.size)
        assertEquals(256, book.predicateObjects.size)
        assertEquals(16_384, book.sentences.size)
        assertEquals(book.sentences.size, book.sentenceIndices.size)
        assertEquals(16, book.sentences.take(16).toSet().size)

        book.sentences.forEachIndexed { index, sentence ->
            val subject = book.subjects[index / book.predicateObjects.size]
            val predicateObject = book.predicateObjects[index % book.predicateObjects.size]
            assertEquals(subject + predicateObject, sentence)
        }
    }

    @Test
    fun unicodeRangeBoundariesRoundTripWithoutCharacterDictionary() {
        val cipher = MessagePhraseCipher()
        val samples = listOf(
            "\u0000", "\u4dff", "一", "\u9fff", "\ua000", "\ud7ff", "\ud800", "\udfff",
            "\ue000", "\uffff", "𠀀", String(Character.toChars(0x10ffff)),
        )

        samples.forEach { message ->
            assertEquals(message, cipher.decrypt(cipher.encrypt(message)))
        }
    }

    @Test
    fun customKeyChangesCiphertextAndWrongKeyIsRejected() {
        val customCipher = MessagePhraseCipher("my-key")
        val ciphertext = customCipher.encrypt("我爱你𠀀")

        assertEquals("我爱你𠀀", customCipher.decrypt(ciphertext))
        assertNotEquals(ciphertext, MessagePhraseCipher().encrypt("我爱你𠀀"))
        assertThrows(IllegalArgumentException::class.java) {
            MessagePhraseCipher().decrypt(ciphertext)
        }
    }

    @Test
    fun arbitraryTextEmojiAndEmptyTextRoundTripAsSvoSentences() {
        val cipher = MessagePhraseCipher()
        val samples = listOf("收到，謝謝！", "Hello 🙂", "一段不在词典中的文字 123", "", "孤立代理项：\uD800")

        samples.forEach { message ->
            val ciphertext = cipher.encrypt(message)
            assertTrue(ciphertext.endsWith('。'))
            if (message.isEmpty()) {
                assertTrue(
                    ciphertext.removePrefix(MessagePhraseCipher.FORMAT_PREFIX).dropLast(1) in
                        MessagePhraseCipherSentenceBook.sentenceIndices
                )
            }
            assertEquals(message, cipher.decrypt(ciphertext))
        }
    }

    @Test
    fun originalTextLimitCountsUnicodeCodePointsAndNeverTruncates() {
        val atLimit = buildString {
            repeat(24) {
                append('A')
                append('，')
            }
            appendCodePoint(0x1f642)
            append('Z')
        }
        assertEquals(50, MessagePhraseCipherOperations.codePointCount(atLimit))
        val ciphertext = MessagePhraseCipherOperations.encrypt(atLimit)
        assertTrue(ciphertext.length > 50)
        assertEquals(atLimit, MessagePhraseCipherOperations.decrypt(ciphertext))

        val overLimit = atLimit + "!"
        val encryptionError = assertThrows(MessagePhraseCipherTooLongException::class.java) {
            MessagePhraseCipherOperations.encrypt(overLimit)
        }
        assertEquals(51, encryptionError.actualCodePointCount)

        val overLimitCiphertext = MessagePhraseCipher().encrypt(overLimit)
        val decryptionError = assertThrows(MessagePhraseCipherTooLongException::class.java) {
            MessagePhraseCipherOperations.decrypt(overLimitCiphertext)
        }
        assertEquals(51, decryptionError.actualCodePointCount)
    }

    @Test
    fun rejectsDamagedCiphertextAndEmptyKey() {
        val cipher = MessagePhraseCipher()
        val ciphertext = cipher.encrypt("测试信息")
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(ciphertext.dropLast(1))
        }
        val firstLength = Character.charCount(Character.codePointAt(ciphertext, 0))
        val firstSentenceStart = firstLength + 1
        val damaged = ciphertext.substring(0, firstSentenceStart) + "A" +
            ciphertext.substring(firstSentenceStart + 1)
        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(damaged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessagePhraseCipher("")
        }
    }

    @Test
    fun clipboardManagerDecryptsValidCipherToTransientResultOnly() {
        assertFalse(MessagePhraseCipherClipboardPolicy.shouldListen(false, false))
        assertTrue(MessagePhraseCipherClipboardPolicy.shouldListen(true, false))
        assertTrue(MessagePhraseCipherClipboardPolicy.shouldListen(false, true))
        assertTrue(MessagePhraseCipherClipboardPolicy.shouldListen(true, true))

        assertTrue(MessagePhraseCipherClipboardPolicy.shouldStoreHistory(true, false, false))
        assertFalse(MessagePhraseCipherClipboardPolicy.shouldStoreHistory(false, true, false))
        // Auto-processed ciphertext and plaintext never enter persistent history.
        assertFalse(MessagePhraseCipherClipboardPolicy.shouldStoreHistory(true, true, true))
        assertTrue(MessagePhraseCipherClipboardPolicy.shouldStoreHistory(true, false, true))

        val plaintext = "复制后自动解密🙂"
        val ciphertext = MessagePhraseCipher().encrypt(plaintext)
        assertEquals(
            plaintext,
            MessagePhraseCipherClipboardPolicy.decryptCopiedText(ciphertext, MessagePhraseCipher.DEFAULT_KEY)
        )
        assertNull(MessagePhraseCipherClipboardPolicy.decryptCopiedText("ordinary copied text", MessagePhraseCipher.DEFAULT_KEY))
        assertNull(
            MessagePhraseCipherClipboardPolicy.decryptCopiedText(
                "A，你们喜欢音乐。", MessagePhraseCipher.DEFAULT_KEY
            )
        )

        val reportedCiphertext = "〔MPC1〕7，演奏者迎接挑战。设计师表达心意。孩子感受快乐。作家整理房间。青年组织团队。"
        assertEquals(
            "7777",
            MessagePhraseCipherClipboardPolicy.decryptCopiedText(
                reportedCiphertext, MessagePhraseCipher.DEFAULT_KEY
            )
        )
        assertEquals(
            "7777",
            MessagePhraseCipherClipboardPolicy.autoDecryptClipboardText(
                reportedCiphertext, MessagePhraseCipher.DEFAULT_KEY, enabled = true
            )
        )
        assertNull(
            MessagePhraseCipherClipboardPolicy.autoDecryptClipboardText(
                reportedCiphertext, MessagePhraseCipher.DEFAULT_KEY, enabled = false
            )
        )
        assertNull(
            MessagePhraseCipherClipboardPolicy.autoDecryptClipboardText(
                "ordinary copied text", MessagePhraseCipher.DEFAULT_KEY, enabled = true
            )
        )
    }

    private fun firstCodePoint(text: String): String {
        val codePoint = Character.codePointAt(text, 0)
        return text.substring(0, Character.charCount(codePoint))
    }
}
