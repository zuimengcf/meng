/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.layout

internal object CompactPanelCorners {
    fun radius(requestedPx: Float, width: Int, height: Int): Float {
        if (!requestedPx.isFinite() || width <= 0 || height <= 0) return 0f
        return requestedPx.coerceIn(0f, minOf(width, height) / 2f)
    }

    /** Android Path order: TL(x,y), TR(x,y), BR(x,y), BL(x,y). */
    fun radii(mode: KeyboardMode, requestedPx: Float, width: Int, height: Int): FloatArray {
        val radius = radius(requestedPx, width, height)
        return when (mode) {
            KeyboardMode.OneHandLeft -> floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            KeyboardMode.OneHandRight -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            else -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        }
    }
}
