/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

internal object ClipboardEntrySearch {
    fun matches(query: String, text: String, id: Int, syncId: String): Boolean {
        val term = query.trim()
        return term.isEmpty() || text.contains(term, ignoreCase = true) ||
            id.toString() == term || syncId.contains(term, ignoreCase = true)
    }
}
