/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

/** Aggregate wire-body allocation limit, held until response processing finishes. */
internal class ClipboardRequestBudget(private val maximum: Long) {
    private var used = 0L
    init { require(maximum > 0) }
    @Synchronized fun reserve(bytes: Long): Boolean {
        if (bytes <= 0 || bytes > maximum - used) return false
        used += bytes
        return true
    }
    @Synchronized fun release(bytes: Long) {
        require(bytes >= 0 && bytes <= used)
        used -= bytes
    }
}
