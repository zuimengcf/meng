/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import kotlin.math.roundToInt

/**
 * @param ctx [Context]
 * @param theme [Theme]
 * @param outerBounds bound [Rect] of [PopupComponent] root view.
 * @param triggerBounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine single-row column order. See [focusColumn] and [layout].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param radius popup keyboard and key radius
 * @param keyWidth key width in popup keyboard
 * @param keyHeight key height in popup keyboard
 * @param popupHeight popup preview view height. Used to transform gesture coordinate from
 * trigger view to popup keyboard view. See [offsetX] and [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 */
class PopupKeyboardUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    keyWidth: Int,
    private val keyHeight: Int,
    private val popupHeight: Int,
    private val keys: Array<String>,
    private val labels: Array<String>,
    letterOffset: Int? = null,
    shiftX: Int = 0
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    class PopupKeyUi(override val ctx: Context, val theme: Theme, val text: String) : Ui {

        val textView = view(::AutoScaleTextView) {
            text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            textSize = 23f
            maxLines = 1
            setTextColor(theme.keyTextColor)
        }

        override val root = frameLayout {
            add(textView, lParams {
                gravity = gravityCenter
            })
        }
    }

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.popupBackgroundColor)
    }

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.genericActiveBackgroundColor)
    }

    // 显示与提交按同一原始索引筛选；旧自定义列表再长也不能触发第二行。
    private val shownIndices = PopupKeyboardCandidates.indices(keys.toList(), if (letterOffset != null) 2 else 1)
    private val shownKeys = shownIndices.map { keys[it] }.toTypedArray()
    private val shownLabels = shownIndices.map { labels[it] }.toTypedArray()
    private val layout = PopupKeyboardLayout.create(
        shownKeys.toList(), keyWidth, outerBounds.left, outerBounds.right,
        triggerBounds.left, triggerBounds.right, letterOffset, shiftX
    )
    private val keyWidth = layout.keyWidth
    private val keyOrders = layout.rows
    private val rowCount = keyOrders.size
    private val columnCount = keyOrders.first().size
    private val focusRow = 0
    private val focusColumn = layout.focusColumn

    // 单行位置相对于原按键；所有格位由 layout 统一提供。
    override val offsetX = layout.offsetX
    override val offsetY = (triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1))

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private val keyUis = shownLabels.map {
        PopupKeyUi(ctx, theme, it)
    }

    init {
        markFocus(focusedIndex)
    }

    override val root = verticalLayout root@{
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        // add rows in reverse order, because newly added view shows at bottom
        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            add(horizontalLayout row@{
                for (j in 0 until columnCount) {
                    val keyUi = keyUis.getOrNull(order[j])
                    if (keyUi == null) {
                        // 保留空格寬度，否則候選顯示與手勢座標會錯位。
                        add(frameLayout {}, lParams(keyWidth, keyHeight))
                    } else {
                        add(keyUi.root, lParams(keyWidth, keyHeight))
                    }
                }
            }, lParams(width = matchParent))
        }
    }

    private fun markFocus(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = focusBackground
            textView.setTextColor(theme.genericActiveForegroundColor)
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            textView.setTextColor(theme.popupTextColor)
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        // move to next row when gesture moves above 30% from bottom of current row
        var newRow = rowCount - (y / keyHeight - 0.2).roundToInt()
        // move to next column when gesture moves out of current column
        var newColumn = PopupKeyboardHitTest.column(
            x, y, keyWidth, keyHeight, focusColumn, offsetX, triggerBounds.width()
        )
        // retain focus when gesture moves between ±2 rows/columns of range
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus in keyUis.indices) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger(): KeyAction? {
        val key = shownKeys.getOrNull(focusedIndex) ?: return null
        return KeyAction.CommitAction(key)
    }

}
