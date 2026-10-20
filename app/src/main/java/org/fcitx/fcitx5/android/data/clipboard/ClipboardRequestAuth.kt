/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

internal object ClipboardRequestAuth {
    enum class Grant { SYNC, CONTROL }
    fun authorize(roomPassword: String, requestId: String?, proof: String?, scope: String, controlToken: String?,
                  issuedAt: Long = 0L, now: Long = System.currentTimeMillis()): Grant? {
        if (requestId.isNullOrBlank() || requestId.length > 128 || proof == null) return null
        if (issuedAt <= 0 || issuedAt < now - ClipboardSyncCrypto.CLOCK_SKEW_MS || issuedAt > now + ClipboardSyncCrypto.CLOCK_SKEW_MS) return null
        val credential = when (scope) {
            "sync" -> roomPassword
            "mcp" -> controlToken?.takeIf { it.length >= 16 &&
                !ClipboardSyncCrypto.constantTimeEquals(it, roomPassword) } ?: return null
            else -> return null
        }
        if (!ClipboardSyncCrypto.constantTimeEquals(proof, ClipboardSyncCrypto.requestProof(credential, requestId, scope, issuedAt))) return null
        return if (scope == "mcp") Grant.CONTROL else Grant.SYNC
    }
}
