/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class PickerLayout(context: Context, theme: Theme, switchKey: KeyDef, compact: Boolean = false) :
    ConstraintLayout(context) {

    private var compactMode = compact

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef) : BaseKeyboard(
        context, theme, listOf(
            listOf(
                LayoutSwitchKey("ABC", TextKeyboard.Name),
                PunctuationKey(","),
                switchKey,
                SpaceKey(),
                PunctuationKey("."),
                ReturnKey()
            )
        )
    ) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

        val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

        override fun onReturnDrawableUpdate(returnDrawable: Int) {
            `return`.img.imageResource = returnDrawable
        }
    }

    val embeddedKeyboard = Keyboard(context, theme, switchKey)

    val pager = view(::ViewPager2) { }

    val tabsUi = PickerTabsUi(context, theme)

    val paginationUi = PickerPaginationUi(context, theme)

    init {
        add(pager, lParams {
            topOfParent()
            centerHorizontally()
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(pager)
            centerHorizontally()
            bottomOfParent()
            // Compact windows need a useful tool row even when the panel is short.
            matchConstraintPercentHeight = if (compact) 0.20f else 0.25f
            matchConstraintMinHeight = if (compact) dp(36) else 0
        })
        add(paginationUi.root, lParams(matchConstraints, dp(2)) {
            centerHorizontally()
            below(pager, dp(-1))
        })
    }

    fun setCompact(compact: Boolean) {
        if (compactMode == compact) return
        compactMode = compact
        val panelHeight = height.takeIf { it > 0 } ?: dp(180)
        embeddedKeyboard.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintPercentHeight = if (compact)
                maxOf(0.20f, dp(36).toFloat() / panelHeight).coerceAtMost(1f) else 0.25f
            matchConstraintMinHeight = if (compact) dp(36).coerceAtMost(panelHeight) else 0
        }
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (compactMode && h > 0) {
            embeddedKeyboard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                // Keep the percentage responsive while minHeight protects short panels.
                matchConstraintMinHeight = dp(36).coerceAtMost(h)
                matchConstraintPercentHeight = maxOf(0.20f, dp(36).toFloat() / h).coerceAtMost(1f)
            }
        }
    }
}
