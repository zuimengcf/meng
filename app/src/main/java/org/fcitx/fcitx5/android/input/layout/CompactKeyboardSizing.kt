/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.layout

/** Pure height budgeting for compact keyboard chrome and content. */
object CompactKeyboardSizing {
    data class Result(
        val contentHeight: Int,
        val bottomPadding: Int,
        val bottomMargin: Int,
        val handleHeight: Int,
    )

    /**
     * Fits the requested parts into [availableHeight]. Non-content chrome is
     * reduced first; content is reduced last, preserving its preferred minimum
     * whenever the available height permits it.
     */
    fun calculate(
        availableHeight: Int,
        desiredContentHeight: Int,
        barHeight: Int,
        handleHeight: Int,
        desiredBottomPadding: Int,
        desiredBottomMargin: Int,
        preferredMinimumContentHeight: Int,
    ): Result {
        val budget = (availableHeight.toLong() - barHeight.coerceAtLeast(0).toLong()).coerceAtLeast(0)
        var content = maxOf(desiredContentHeight, preferredMinimumContentHeight, 0).toLong()
        var padding = desiredBottomPadding.coerceAtLeast(0).toLong()
        var margin = desiredBottomMargin.coerceAtLeast(0).toLong()
        var handle = handleHeight.coerceAtLeast(0).toLong()
        var deficit = (content + padding + margin + handle - budget).coerceAtLeast(0)

        fun reduce(value: Long): Long {
            val reduction = deficit.coerceAtMost(value)
            deficit -= reduction
            return value - reduction
        }
        margin = reduce(margin)
        padding = reduce(padding)
        handle = reduce(handle)
        content = reduce(content)
        return Result(content.toInt(), padding.toInt(), margin.toInt(), handle.toInt())
    }
}
