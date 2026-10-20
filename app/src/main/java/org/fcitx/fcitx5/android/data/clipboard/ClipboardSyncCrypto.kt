package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated transport envelope. Password is never placed in URL, headers, or plaintext. */
@Serializable
data class ClipboardSyncEnvelope(
    val protocolVersion: Int = 2,
    val requestId: String,
    val clientId: String,
    val salt: String,
    val nonce: String,
    val ciphertext: String,
    val issuedAt: Long = System.currentTimeMillis(),
    val direction: String = "request",
)

@Serializable
data class ClipboardSyncWireRequest(
    val kind: String,
    val clientId: String,
    val statusHash: String = "",
    val packetJson: String? = null,
    val inventory: ClipboardSyncInventory? = null,
)

@Serializable
data class ClipboardSyncWireResponse(
    val ok: Boolean,
    val requestId: String,
    val kind: String,
    val statusHash: String = "",
    val packetJson: String? = null,
    val error: String? = null,
    val onlineClientIds: List<String> = emptyList(),
    val inventory: ClipboardSyncInventory? = null,
)

object ClipboardSyncCrypto {
    const val PROTOCOL_VERSION = 2
    const val REQUEST_DIRECTION = "request"
    const val RESPONSE_DIRECTION = "response"
    const val CLOCK_SKEW_MS = 5 * 60_000L
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_CACHE_LIMIT = 64
    private val random = SecureRandom()
    private val keyCache = object : LinkedHashMap<String, SecretKey>(KEY_CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKey>?) = size > KEY_CACHE_LIMIT
    }
    private val sessionSalts = object : LinkedHashMap<String, Pair<Long, ByteArray>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ByteArray>>?) = size > 16
    }

    fun requestId(): String = java.util.UUID.randomUUID().toString()
    fun statusHash(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
    /** Header proof used to reject unauthenticated oversized requests before reading the body. */
    fun requestProof(password: String, requestId: String, scope: String = "sync", issuedAt: Long = 0L): String {
        require(password.isNotEmpty() && requestId.isNotBlank())
        require(scope == "sync" || scope == "mcp")
        // Fixed domain salt permits server-side caching before body authentication and avoids
        // letting an attacker force a new expensive KDF salt on every connection.
        val proofKey = key(password, "clipboard-header-auth-v1".toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(proofKey.encoded, "HmacSHA256")) }
        return b64(mac.doFinal("$scope|$requestId|$issuedAt".toByteArray(StandardCharsets.UTF_8)))
    }

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))

    fun encrypt(password: String, requestId: String, clientId: String, plaintext: String, direction: String = REQUEST_DIRECTION, now: Long = System.currentTimeMillis()): ClipboardSyncEnvelope {
        require(password.isNotEmpty()) { "room password must not be empty" }
        require(requestId.isNotBlank() && clientId.isNotBlank()) { "request and client IDs must not be blank" }
        require(direction == REQUEST_DIRECTION || direction == RESPONSE_DIRECTION) { "invalid envelope direction" }
        val salt = synchronized(sessionSalts) {
            val id = statusHash(password)
            sessionSalts[id]?.takeIf { now - it.first in 0 until CLOCK_SKEW_MS }?.second
                ?: ByteArray(SALT_BYTES).also(random::nextBytes).also { sessionSalts[id] = now to it }
        }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce)); updateAAD(aad(requestId, clientId, now, direction))
        }
        return ClipboardSyncEnvelope(PROTOCOL_VERSION, requestId, clientId, b64(salt), b64(nonce), b64(cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))), now, direction)
    }

    fun decrypt(password: String, envelope: ClipboardSyncEnvelope, expectedDirection: String? = null, now: Long = System.currentTimeMillis()): String {
        require(password.isNotEmpty()) { "room password must not be empty" }
        require(envelope.protocolVersion == PROTOCOL_VERSION) { "unsupported protocol version" }
        require(envelope.requestId.isNotBlank() && envelope.clientId.isNotBlank()) { "invalid envelope identity" }
        require(envelope.direction == REQUEST_DIRECTION || envelope.direction == RESPONSE_DIRECTION) { "invalid envelope direction" }
        require(expectedDirection == null || envelope.direction == expectedDirection) { "unexpected envelope direction" }
        require(envelope.issuedAt in (now - CLOCK_SKEW_MS)..(now + CLOCK_SKEW_MS)) { "envelope expired or clock is invalid" }
        val salt = unb64(envelope.salt); val nonce = unb64(envelope.nonce)
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "invalid salt or nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, nonce)); updateAAD(aad(envelope.requestId, envelope.clientId, envelope.issuedAt, envelope.direction))
        }
        return String(cipher.doFinal(unb64(envelope.ciphertext)), StandardCharsets.UTF_8)
    }

    private fun aad(requestId: String, clientId: String, issuedAt: Long, direction: String) =
        "$PROTOCOL_VERSION|$direction|$requestId|$clientId|$issuedAt".toByteArray(StandardCharsets.UTF_8)

    private fun key(password: String, salt: ByteArray): SecretKey {
        val cacheKey = password + "\u0000" + b64(salt)
        synchronized(keyCache) { keyCache[cacheKey]?.let { return it } }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        // SHA1 PRF is available on every supported API 23+ device; never negotiate a silent PRF fallback.
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val result = try { SecretKeySpec(factory.generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
        synchronized(keyCache) { keyCache[cacheKey] = result }
        return result
    }
    private fun b64(v: ByteArray) = Base64.getEncoder().encodeToString(v)
    private fun unb64(v: String) = Base64.getDecoder().decode(v)
    private fun hex(v: ByteArray) = v.joinToString("") { "%02x".format(it) }
}
