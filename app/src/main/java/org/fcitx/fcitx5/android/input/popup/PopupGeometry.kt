/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.popup

/** Convert window-space key bounds to margins local to the popup container. */
internal object PopupGeometry {
    fun entryMargins(
        rootLeft: Int,
        rootTop: Int,
        keyLeft: Int,
        keyRight: Int,
        keyBottom: Int,
        popupWidth: Int,
        popupHeight: Int,
        keyBottomMargin: Int,
    ): Pair<Int, Int> {
        val left = (keyLeft + keyRight - popupWidth) / 2 - rootLeft
        val top = keyBottom - popupHeight - keyBottomMargin - rootTop
        return left to top
    }
}
