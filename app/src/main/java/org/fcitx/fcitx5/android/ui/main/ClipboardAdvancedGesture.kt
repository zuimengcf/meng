/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

/** Uses monotonic time supplied by the UI; no hidden mode is persisted between launches. */
internal class ClipboardAdvancedGesture {
    private var count = 0
    private var lastTap = Long.MIN_VALUE

    fun tap(now: Long): Boolean {
        count = if (lastTap != Long.MIN_VALUE && now >= lastTap && now - lastTap <= 1200L) count + 1 else 1
        lastTap = now
        if (count < 6) return false
        count = 0
        lastTap = Long.MIN_VALUE
        return true
    }
}
