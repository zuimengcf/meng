/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

/** Interface acceptance is not proof of an editor mutation; unsupported/async reads stay unverified. */
object CommitVerification {
    enum class Outcome { CONFIRMED, UNVERIFIED, REJECTED, NOT_RECORDED }

    fun classify(
        text: String,
        before: String?,
        after: String?,
        privacyRestricted: Boolean = false,
        finishComposing: Boolean = false,
        accepted: Boolean = true,
    ): Outcome {
        if (privacyRestricted) return Outcome.NOT_RECORDED
        if (!accepted) return Outcome.REJECTED
        if (text.isEmpty() || before == null || after == null || finishComposing) return Outcome.UNVERIFIED
        if (after == before) return Outcome.UNVERIFIED
        // A truncated suffix is insufficient proof: only exact, observable insertion confirms.
        if (after == before + text) return Outcome.CONFIRMED
        return Outcome.UNVERIFIED
    }
}
