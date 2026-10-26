package org.fcitx.fcitx5.android.utils

import java.math.BigInteger

/**
 * Reversible phrase-based obfuscation for text the user chooses to send.
 * The first code point stays clear; later code points are encoded as complete simplified Chinese SVO sentences.
 * This is lightweight obfuscation, not cryptographic protection.
 */
class MessagePhraseCipher(key: String = DEFAULT_KEY) {
    private val keyBytes = key.toByteArray(Charsets.UTF_8)
    private val keySeed = fingerprint(keyBytes)

    init {
        require(keyBytes.isNotEmpty()) { "Key must not be empty" }
    }

    /** Leaves the first code point visible and carries the remaining text in complete SVO sentences. */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return FORMAT_PREFIX + EMPTY_TEXT_MARKERS[checksum(plainText)] + SENTENCE_END

        val firstCodePoint = Character.codePointAt(plainText, 0)
        val payload = encodePayload(plainText, firstCodePoint)
        val sentenceDigits = encodeSentencePayload(payload)
        val output = StringBuilder(plainText.length * 16 + 8)
        output.append(FORMAT_PREFIX).appendCodePoint(firstCodePoint).append(START_SEPARATOR)
        sentenceDigits.forEach { digit ->
            output.append(MessagePhraseCipherSentenceBook.sentenceFor(digit)).append(SENTENCE_END)
        }
        return output.toString()
    }

    /** Decodes the clear first code point and the following sequence of complete SVO sentences. */
    fun decrypt(cipherText: String): String {
        require(cipherText.startsWith(FORMAT_PREFIX)) { "Invalid phrase code" }
        val markedCipherText = cipherText.removePrefix(FORMAT_PREFIX)
        require(markedCipherText.endsWith(SENTENCE_END)) { "Invalid phrase code" }
        val withoutTerminator = markedCipherText.dropLast(1)

        val emptyMarkerIndex = EMPTY_TEXT_MARKERS.indexOf(withoutTerminator)
        if (emptyMarkerIndex >= 0) {
            require(checksum("") == emptyMarkerIndex) { "Wrong key or invalid phrase code" }
            return ""
        }

        require(withoutTerminator.isNotEmpty()) { "Invalid phrase code" }
        val firstCodePoint = Character.codePointAt(withoutTerminator, 0)
        val firstLength = Character.charCount(firstCodePoint)
        require(
            withoutTerminator.length > firstLength &&
                withoutTerminator[firstLength] == START_SEPARATOR
        ) { "Invalid phrase code" }

        val sentenceText = withoutTerminator.substring(firstLength + 1)
        require(sentenceText.isNotEmpty()) { "Invalid phrase code" }
        val sentenceIndices = sentenceText.split(SENTENCE_END).map { sentence ->
            require(sentence.isNotEmpty()) { "Invalid phrase code" }
            MessagePhraseCipherSentenceBook.sentenceIndices[sentence]
                ?: throw IllegalArgumentException("Invalid phrase code")
        }

        var payload = BIG_ZERO
        sentenceIndices.asReversed().forEach { digit ->
            payload = payload.shiftLeft(SENTENCE_DIGIT_BITS).or(BigInteger.valueOf(digit.toLong()))
        }
        require(payload.signum() > 0) { "Invalid phrase code" }

        val checkValue = payload.and(CHECK_VALUE_MASK).toInt()
        var remaining = payload.shiftRight(CHECK_BITS)
        val codePoints = ArrayList<Long>()
        while (remaining > BIG_ONE) {
            codePoints.add(remaining.and(CODE_BLOCK_MASK).toLong())
            remaining = remaining.shiftRight(CODE_BLOCK_BITS)
        }
        require(remaining == BIG_ONE) { "Invalid phrase code" }

        val decoded = StringBuilder().appendCodePoint(firstCodePoint)
        var previousCodePoint = firstCodePoint
        codePoints.asReversed().forEachIndexed { index, code ->
            val codePoint = decodeCodePoint(code, index + 1, previousCodePoint)
            decoded.appendCodePoint(codePoint)
            previousCodePoint = codePoint
        }

        val plainText = decoded.toString()
        require(checksum(plainText) == checkValue) { "Wrong key or invalid phrase code" }
        return plainText
    }

    private fun encodePayload(plainText: String, firstCodePoint: Int): BigInteger {
        var payload = BIG_ONE
        var cursor = Character.charCount(firstCodePoint)
        var previousCodePoint = firstCodePoint
        var codePointIndex = 1

        while (cursor < plainText.length) {
            val codePoint = Character.codePointAt(plainText, cursor)
            val code = encodeCodePoint(codePoint, codePointIndex, previousCodePoint)
            payload = payload.shiftLeft(CODE_BLOCK_BITS).or(BigInteger.valueOf(code))
            previousCodePoint = codePoint
            cursor += Character.charCount(codePoint)
            codePointIndex++
        }

        return payload.shiftLeft(CHECK_BITS).or(BigInteger.valueOf(checksum(plainText).toLong()))
    }

    private fun encodeSentencePayload(payload: BigInteger): List<Int> {
        var remaining = payload
        val digits = ArrayList<Int>()
        while (remaining.signum() > 0) {
            digits.add(remaining.and(SENTENCE_DIGIT_MASK).toInt())
            remaining = remaining.shiftRight(SENTENCE_DIGIT_BITS)
        }
        return digits
    }

    private fun encodeCodePoint(codePoint: Int, index: Int, previousCodePoint: Int): Long =
        if (codePoint in BASIC_HAN_START until BASIC_HAN_END) {
            val rank = codePoint - BASIC_HAN_START
            (rank.toLong() + shortOffset(index, previousCodePoint)) % BASIC_HAN_COUNT
        } else {
            val rank = toLongCodeRank(codePoint)
            val encryptedRank =
                (rank * LONG_CODE_MULTIPLIER + longOffset(index, previousCodePoint)) % LONG_CODE_POINT_COUNT
            BASIC_HAN_COUNT.toLong() + encryptedRank
        }

    private fun decodeCodePoint(code: Long, index: Int, previousCodePoint: Int): Int {
        require(code in 0 until CODE_POINT_SPACE) { "Invalid phrase code" }
        return if (code < BASIC_HAN_COUNT) {
            val rank = Math.floorMod(
                code - shortOffset(index, previousCodePoint),
                BASIC_HAN_COUNT.toLong(),
            ).toInt()
            BASIC_HAN_START + rank
        } else {
            val encryptedRank = code - BASIC_HAN_COUNT
            val shiftedRank = Math.floorMod(
                encryptedRank - longOffset(index, previousCodePoint),
                LONG_CODE_POINT_COUNT,
            )
            val rank = (shiftedRank * LONG_CODE_MULTIPLIER_INVERSE) % LONG_CODE_POINT_COUNT
            fromLongCodeRank(rank)
        }
    }

    private fun shortOffset(index: Int, previousCodePoint: Int): Long =
        chainedOffset(index, previousCodePoint, BASIC_HAN_COUNT.toLong())

    private fun longOffset(index: Int, previousCodePoint: Int): Long =
        chainedOffset(index, previousCodePoint, LONG_CODE_POINT_COUNT)

    private fun chainedOffset(index: Int, previousCodePoint: Int, modulus: Long): Long {
        val state = keySeed.toLong() +
            index.toLong() * OFFSET_STEP +
            previousCodePoint.toLong() * PREVIOUS_CODE_POINT_STEP
        return Math.floorMod(state, modulus)
    }

    private fun toLongCodeRank(codePoint: Int): Long =
        if (codePoint < BASIC_HAN_START) codePoint.toLong()
        else (codePoint - BASIC_HAN_COUNT).toLong()

    private fun fromLongCodeRank(rank: Long): Int =
        if (rank < BASIC_HAN_START) rank.toInt()
        else (rank + BASIC_HAN_COUNT).toInt()

    private fun checksum(text: String): Int {
        var crc = CRC16_INITIAL xor (keySeed and 0xffff)
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            crc = crc16Update(crc, byte.toInt() and 0xff)
        }
        val folded = crc xor (crc ushr 4) xor (crc ushr 8) xor (crc ushr 12)
        return folded and (CHECK_VALUE_COUNT - 1)
    }

    companion object {
        const val DEFAULT_KEY = "meng"
        const val FORMAT_PREFIX = "〔MPC1〕"

        private const val BASIC_HAN_START = 0x4e00
        private const val BASIC_HAN_END = 0xa000
        private const val BASIC_HAN_COUNT = BASIC_HAN_END - BASIC_HAN_START
        private const val CRC16_INITIAL = 0xffff
        private const val OFFSET_STEP = 2_654_435_761L
        private const val PREVIOUS_CODE_POINT_STEP = 1_000_000_007L
        private const val LONG_CODE_MULTIPLIER = 1_000_003L
        private const val LONG_CODE_MULTIPLIER_INVERSE = 842_347L
        private const val LONG_CODE_POINT_COUNT = 0x110000L - BASIC_HAN_COUNT
        private const val CODE_POINT_SPACE = 0x110000L
        private const val CODE_BLOCK_BITS = 21
        private const val CHECK_BITS = 4
        private const val CHECK_VALUE_COUNT = 16
        private const val SENTENCE_SUBJECT_COUNT = 64
        private const val SENTENCE_PREDICATE_COUNT = 256
        private const val SENTENCE_RADIX = SENTENCE_SUBJECT_COUNT * SENTENCE_PREDICATE_COUNT
        private const val SENTENCE_DIGIT_BITS = 14
        private const val CODE_BLOCK_MASK_VALUE = 0x1fffffL
        private const val START_SEPARATOR = '，'
        private const val SENTENCE_END = '。'

        private val BIG_ZERO = BigInteger.ZERO
        private val BIG_ONE = BigInteger.ONE
        private val CHECK_VALUE_MASK = BigInteger.valueOf(CHECK_VALUE_COUNT - 1L)
        private val SENTENCE_DIGIT_MASK = BigInteger.valueOf(SENTENCE_RADIX - 1L)
        private val CODE_BLOCK_MASK = BigInteger.valueOf(CODE_BLOCK_MASK_VALUE)
        private val EMPTY_TEXT_MARKERS = MessagePhraseCipherSentenceBook.sentences.take(CHECK_VALUE_COUNT)

        private fun fingerprint(bytes: ByteArray): Int {
            var hash = 0x811c9dc5.toInt()
            bytes.forEach { byte ->
                hash = (hash xor (byte.toInt() and 0xff)) * 0x01000193
            }
            return hash
        }

        private fun crc16Update(initial: Int, value: Int): Int {
            var crc = initial xor ((value and 0xff) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
            return crc
        }
    }
}
