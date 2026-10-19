/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.layout

import kotlin.math.roundToInt

/** Pure geometry used by the input view for positioning its keyboard panel. */
object KeyboardPlacement {
    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)

    fun calculate(
        mode: KeyboardMode,
        availableWidth: Int,
        availableHeight: Int,
        panelHeight: Int,
        safeTop: Int,
        safeBottom: Int,
        minWidth: Int,
        widthPercent: Int,
        edgeMargin: Int,
        bottomMargin: Int,
        xPercent: Int,
        yPercent: Int,
        safeLeft: Int = 0,
        safeRight: Int = 0,
    ): Bounds {
        if (availableWidth <= 0 || availableHeight <= 0) return Bounds(0, 0, 0, 0)

        val leftSafe = safeLeft.coerceIn(0, availableWidth)
        val rightSafe = safeRight.coerceIn(0, availableWidth - leftSafe)
        val safeWidth = availableWidth - leftSafe - rightSafe
        val width = when (mode) {
            KeyboardMode.Standard -> availableWidth
            KeyboardMode.OneHandLeft,
            KeyboardMode.OneHandRight,
            KeyboardMode.Floating -> widthFor(safeWidth, minWidth, widthPercent)
        }
        return when (mode) {
            KeyboardMode.Standard -> {
                val height = panelHeight.coerceIn(0, availableHeight)
                Bounds(0, availableHeight - height, availableWidth, height)
            }
            KeyboardMode.OneHandLeft,
            KeyboardMode.OneHandRight -> {
                val topSafe = safeTop.coerceIn(0, availableHeight)
                val height = panelHeight.coerceIn(0, availableHeight - topSafe)
                // User spacing yields to actual keys when space runs short.
                val margin = bottomMargin.coerceIn(0, availableHeight - topSafe - height)
                val bottom = availableHeight - margin
                val horizontalMargin = edgeMargin.coerceIn(0, safeWidth - width)
                val left = if (mode == KeyboardMode.OneHandLeft) {
                    (leftSafe + horizontalMargin).coerceAtMost(availableWidth - rightSafe - width)
                } else {
                    (availableWidth - rightSafe - width - horizontalMargin).coerceAtLeast(leftSafe)
                }
                Bounds(left, bottom - height, width, height)
            }
            KeyboardMode.Floating -> {
                val topInset = safeTop.coerceIn(0, availableHeight)
                val bottomInset = safeBottom.coerceIn(0, availableHeight)
                val safeBottomEdge = (availableHeight - bottomInset).coerceAtLeast(topInset)
                val safeHeight = safeBottomEdge - topInset
                val height = panelHeight.coerceIn(0, safeHeight)
                val x = leftSafe + offsetForPercent(xPercent, safeWidth - width)
                val y = topInset + offsetForPercent(yPercent, safeHeight - height)
                Bounds(x, y, width, height)
            }
        }
    }

    /** Convert a dragged offset into a persisted 0..100 position. */
    fun percentForOffset(offset: Int, travel: Int): Int {
        if (travel <= 0) return 0
        return (offset.toDouble() * 100.0 / travel).roundToInt().coerceIn(0, 100)
    }

    private fun widthFor(availableWidth: Int, minWidth: Int, widthPercent: Int): Int {
        val requested = (availableWidth.toLong() * widthPercent.coerceIn(0, 100) / 100L).toInt()
        return maxOf(requested, minWidth.coerceAtLeast(0)).coerceIn(0, availableWidth)
    }

    private fun offsetForPercent(percent: Int, travel: Int): Int {
        if (travel <= 0) return 0
        return (travel.toDouble() * percent.coerceIn(0, 100) / 100.0).roundToInt()
    }
}
