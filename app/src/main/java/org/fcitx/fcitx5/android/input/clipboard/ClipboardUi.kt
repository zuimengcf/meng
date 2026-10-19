/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.setPaddingDp
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    /**
     * 分组按钮容器（水平）。位于顶栏扩展区，超出宽度时由外层 [groupFilterScroll] 横向滚动。
     * 首个项为「全部」，后续为各分组名。
     */
    val groupFilterBar = horizontalLayout { }

    /** 包裹 [groupFilterBar] 的横向滚动容器，放在顶栏扩展区，宽度受限时滚动。 */
    val groupFilterScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            groupFilterBar,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * 搜索开关按钮（放大镜）。点击启动独立搜索输入窗口（[ClipboardSearchActivity]）。
     * 剪切板窗口是输入法内嵌 View，无法直接弹出软键盘，故用独立 Activity 承载输入。
     */
    val searchToggleButton = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_baseline_search_24)
        contentDescription = ctx.getString(R.string.clipboard_search)
        setPaddingDp(6, 6, 6, 6)
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    /**
     * 内容区：仅列表。分组筛选与搜索均置于输入法顶栏（与「剪切板」标题同一行），见 [extension]。
     */
    val filterAndListLayout = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        add(recyclerView, lParams(matchParent, 0, weight = 1f))
    }

    val viewAnimator =  view(::ViewAnimator) {
        add(filterAndListLayout, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    /** 输入法顶栏（TitleUi）扩展区，与「剪切板」标题同一行呈现：
     * 分组筛选栏（横向滚动） + 搜索开关（放大镜，点击弹独立输入窗口） + 删除全部。 */
    val extension = horizontalLayout {
        add(groupFilterScroll, lParams(0, matchParent) {
            weight = 1f
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        add(searchToggleButton, lParams(dp(40), matchParent) {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        add(deleteAllButton, lParams(dp(40), matchParent) {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setDeleteButtonShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
            }
        }
    }
}