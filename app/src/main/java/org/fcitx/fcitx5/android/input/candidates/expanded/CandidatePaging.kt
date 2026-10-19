/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.expanded

/** Keep the final item when exactly one candidate remains after a full page. */
internal fun nextCandidatePageKey(start: Int, requested: Int, loaded: Int, total: Int): Int? {
    if (loaded <= 0) return null
    val next = start + loaded
    return if (total >= 0) {
        if (next < total) next else null
    } else {
        if (loaded >= requested) next else null
    }
}
