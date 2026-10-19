/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

/** Shared deterministic rules for IME learning. No Android or network dependency. */
internal object SmartInputText {
    fun normalizeCode(code: String): String? {
        if (code.isEmpty() || code.length > 64 ||
            code.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' && it != ' ' }) return null
        return code.filter { it != '\'' && it != ' ' }.lowercase(java.util.Locale.ROOT)
            .takeIf { it.isNotEmpty() }
    }

    fun isHanWord(text: String): Boolean = text.isNotEmpty() && text.all { it in '\u4e00'..'\u9fff' }

    fun tail(text: String): String? {
        val run = text.takeLastWhile { it in '\u4e00'..'\u9fff' }
        return run.takeLast(2).takeIf { it.isNotEmpty() }
    }
}
