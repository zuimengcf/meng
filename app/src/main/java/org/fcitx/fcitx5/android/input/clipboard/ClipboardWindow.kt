/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.Intent

import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.layout.KeyboardMode
import org.fcitx.fcitx5.android.input.dialog.keyboardPositionDialogBuilder
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme
import splitties.views.setPaddingDp

class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val snackbarCtx by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private val keyboardMode by AppPrefs.getInstance().keyboard.keyboardMode
    private var lastAttachedKeyboardMode: KeyboardMode? = null

    /** 当前选中的分组 id（0 = 全部，-1 = 未分组，>0 = 某分组）。 */
    private var currentGroupId = 0

    /** 临时分句分组 id；>0 表示当前存在一个待清理的临时分组。 */
    private var segmentGroupId = 0

    /** 当前搜索关键字（空串 = 不搜索）。 */
    private var currentQuery = ""

    private fun buildPager(): Pager<Int, ClipboardEntry> {
        val groupId = currentGroupId
        val query = currentQuery.trim()
        return Pager(PagingConfig(pageSize = 16, enablePlaceholders = false)) {
            if (query.isNotEmpty()) {
                // 有搜索：限定当前分组（全部/未分组/某分组）
                when (groupId) {
                    0 -> ClipboardManager.search(query)
                    -1 -> ClipboardManager.searchIn(-1, query) // 未分组搜索
                    else -> ClipboardManager.searchIn(groupId, query)
                }
            } else {
                // 无搜索：按分组过滤
                when (groupId) {
                    0 -> ClipboardManager.allEntries()
                    -1 -> ClipboardManager.entriesInGroup(0)
                    else -> ClipboardManager.entriesInGroup(groupId)
                }
            }
        }
    }

    private var adapterSubmitJob: Job? = null
    private var pagerSubmissionId = 0L
    private var suppressEmptyStateDuringPagerReset: Long? = null
    private var awaitingNonEmptyAfterPagerReset: Long? = null

    private fun submitCurrentPager(clearPreviousPage: Boolean = false) {
        val submissionId = ++pagerSubmissionId
        adapterSubmitJob?.cancel()
        suppressEmptyStateDuringPagerReset = null
        awaitingNonEmptyAfterPagerReset = null
        val pager = buildPager()
        adapterSubmitJob = service.lifecycleScope.launch {
            if (clearPreviousPage) {
                suppressEmptyStateDuringPagerReset = submissionId
                try {
                    adapter.submitData(PagingData.empty<ClipboardEntry>())
                } finally {
                    if (suppressEmptyStateDuringPagerReset == submissionId) {
                        suppressEmptyStateDuringPagerReset = null
                    }
                }
                if (pagerSubmissionId != submissionId) return@launch
                if (adapter.snapshot().items.isEmpty()) {
                    awaitingNonEmptyAfterPagerReset = submissionId
                }
            }
            pager.flow.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }
    }

    private val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            clipboardMaskSensitive
        ) {
            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onMoveToGroup(entry: ClipboardEntry) {
                service.lifecycleScope.launch {
                    val groups = ClipboardManager.getGroups()
                    promptMoveToGroup(entry, groups)
                }
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    ClipboardManager.delete(id)
                    showDeletedSnackbar()
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                val isImage = entry.type.startsWith("image/") && entry.imagePath != null
                if (isImage) {
                    // 优先直插当前输入框（目标支持 image/* 时）；失败降级复制到系统剪贴板
                    val ok = service.commitImage(entry.imagePath!!, entry.type)
                    if (!ok) {
                        // 用 FileProvider content URI 写入系统剪贴板（跨应用可读），
                        // 并抑制本次主动写入触发的重新捕获，避免产生重复条目
                        ClipboardManager.copyImageToSystemClipboard(entry.imagePath!!, entry.text)
                        context.toast(R.string.clipboard_image_copied)
                    }
                } else {
                    service.commitText(entry.text)
                }
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }

            override fun onSegment(entry: ClipboardEntry) {
                if (entry.type.startsWith("image/") || entry.imagePath != null) {
                    context.toast(R.string.segment_single)
                    return
                }
                service.lifecycleScope.launch {
                    // 先清理上一次的临时分组（仅数据库，UI 稍后统一刷新）
                    if (segmentGroupId > 0) {
                        val oldGid = segmentGroupId
                        segmentGroupId = 0
                        ClipboardManager.purgeGroup(oldGid)
                    }
                    val groupId = ClipboardManager.segmentToGroup(entry.id)
                    if (groupId < 0) {
                        context.toast(R.string.segment_single)
                        // 如果刚清理过旧分组，需刷新分组栏
                        val groups = ClipboardManager.getGroups()
                        populateGroupFilterBar(groups)
                        submitCurrentPager()
                        return@launch
                    }
                    segmentGroupId = groupId
                    // 刷新分组栏，然后切换到新分组
                    val groups = ClipboardManager.getGroups()
                    populateGroupFilterBar(groups)
                    currentGroupId = groupId
                    refreshGroupButtonHighlight()
                    submitCurrentPager()
                }
            }
        }
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                // Standard keeps the historical two-column presentation.  Compact
                // windows can be substantially narrower (and large fonts make an
                // entry wider), so derive the span from the actual container width.
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    val width = right - left
                    val oldWidth = oldRight - oldLeft
                    if (width > 0 && (width != oldWidth || keyboardMode != lastAttachedKeyboardMode)) {
                        updateSpanCount(width)
                    }
                }
                adapter = this@ClipboardWindow.adapter
            }
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    service.lifecycleScope.launch {
                        ClipboardManager.delete(entry.id)
                        showDeletedSnackbar()
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener {
                service.lifecycleScope.launch {
                    promptDeleteAll(ClipboardManager.haveUnpinned())
                }
            }
            // 搜索开关（放大镜）：未过滤时点击启动独立搜索输入窗口；过滤中点击取消搜索过滤
            searchToggleButton.setOnClickListener {
                if (currentQuery.isNotEmpty()) {
                    // 取消搜索过滤：清空关键词，恢复全部条目
                    currentQuery = ""
                    org.fcitx.fcitx5.android.ui.main.ClipboardSearchResult.keyword = ""
                    updateSearchButtonState()
                    submitCurrentPager()
                } else {
                    AppUtil.launchClipboardSearch(context)
                    // 从搜索窗口返回后应用关键词，见 onAttached 与 onSearchApplied
                }
            }
        }
    }


    private val adapterPagesUpdatedListener: () -> Unit = {
        val empty = adapter.snapshot().items.isEmpty()
        if (empty) {
            if (suppressEmptyStateDuringPagerReset != pagerSubmissionId &&
                awaitingNonEmptyAfterPagerReset != pagerSubmissionId
            ) {
                stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to true)
            }
        } else {
            if (awaitingNonEmptyAfterPagerReset == pagerSubmissionId) {
                awaitingNonEmptyAfterPagerReset = null
            }
            stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to false)
        }
    }

    /** 填充分组筛选条：全部 + 各分组。 */
    private fun populateGroupFilterBar(groups: List<org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup>) {
        val bar = ui.groupFilterBar
        bar.removeAllViews()
        val allBtn = groupFilterButton(context.getString(R.string.group_all), 0)
        bar.addView(allBtn)
        groups.forEach { g ->
            bar.addView(groupFilterButton(g.name, g.id))
        }
        refreshGroupButtonHighlight()
    }

    /** 构造单个分组筛选按钮。 */
    private fun groupFilterButton(label: String, groupId: Int): android.widget.TextView {
        return android.widget.TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(theme.keyTextColor)
            setBackgroundColor(theme.clipboardEntryColor)
            setPaddingDp(12, 8, 12, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (currentGroupId != groupId) {
                    service.lifecycleScope.launch {
                        // 顺序执行：先清完临时分组（事务提交后），再切分组、再刷新，
                        // 避免 purge 的 Room invalidate 与新 Pager 查询竞争造成临时空白
                        if (segmentGroupId > 0 && currentGroupId == segmentGroupId && groupId != segmentGroupId) {
                            purgeSegmentGroup()
                        }
                        val clearPreviousPage = groupId == 0 && adapter.snapshot().items.isNotEmpty()
                        currentGroupId = groupId
                        refreshGroupButtonHighlight()
                        submitCurrentPager(clearPreviousPage)
                        ui.recyclerView.stopScroll()
                        ui.recyclerView.scrollToPosition(0)
                    }
                }
            }
            tag = groupId
        }
    }

    /** 高亮当前选中的分组按钮。 */
    private fun refreshGroupButtonHighlight() {
        for (i in 0 until ui.groupFilterBar.childCount) {
            val btn = ui.groupFilterBar.getChildAt(i) as? android.widget.TextView ?: continue
            val gid = btn.tag as? Int ?: 0
            val active = gid == currentGroupId
            btn.setBackgroundColor(
                if (active) theme.genericActiveBackgroundColor else theme.clipboardEntryColor
            )
            btn.setTextColor(
                if (active) theme.genericActiveForegroundColor else theme.keyTextColor
            )
        }
    }

    /** 清理临时分句分组及其条目，并从分组栏移除按钮。 */
    private suspend fun purgeSegmentGroup() {
        if (segmentGroupId <= 0) return
        val gid = segmentGroupId
        segmentGroupId = 0
        ClipboardManager.purgeGroup(gid)
        val groups = ClipboardManager.getGroups()
        populateGroupFilterBar(groups)
        if (currentGroupId == gid) {
            currentGroupId = 0
            refreshGroupButtonHighlight()
            submitCurrentPager()
        }
    }

    override fun onCreateView(): View = ui.root

    /** 弹出「移到分组」选择框：未分组 + 全部分组。 */
    private fun promptMoveToGroup(
        entry: ClipboardEntry,
        groups: List<org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup>
    ) {
        val labels = arrayOf(context.getString(R.string.group_ungrouped)) + groups.map { it.name }
        val groupIds = arrayOf(0) + groups.map { it.id }
        val dialog = keyboardPositionDialogBuilder(context)
            .setTitle(R.string.move_to_group)
            .setItems(labels) { _, which ->
                val gid = groupIds[which]
                service.lifecycleScope.launch {
                    ClipboardManager.setEntryGroup(entry.id, gid)
                    submitCurrentPager()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    val ids = ClipboardManager.deleteAll(skipPinned)
                    if (ids.isNotEmpty()) showDeletedSnackbar()
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showDeletedSnackbar() {
        snackbarInstance = Snackbar.make(snackbarCtx, ui.root, "已删除", Snackbar.LENGTH_LONG)
            .setBackgroundTint(theme.popupBackgroundColor)
            .setTextColor(theme.popupTextColor)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) snackbarInstance = null
                }
            }).apply {
                val hMargin = snackbarCtx.dp(24)
                val vMargin = snackbarCtx.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = hMargin
                    rightMargin = hMargin
                    bottomMargin = vMargin
                }
                show()
            }
    }

    /** 依据当前是否搜索过滤，切换顶栏搜索按钮图标（放大镜 ↔ 取消 X）。 */
    private fun updateSearchButtonState() {
        ui.searchToggleButton.setImageResource(
            if (currentQuery.isNotEmpty()) R.drawable.ic_baseline_close_24
            else R.drawable.ic_baseline_search_24
        )
        ui.searchToggleButton.contentDescription = context.getString(
            if (currentQuery.isNotEmpty()) R.string.clipboard_search_cancel_filter
            else R.string.clipboard_search
        )
    }

    private fun updateSpanCount(width: Int = ui.recyclerView.width) {
        if (width <= 0) return
        val spanCount = if (keyboardMode == KeyboardMode.Standard) {
            2
        } else {
            (width / context.dp(180f)).toInt().coerceIn(1, 2)
        }
        (ui.recyclerView.layoutManager as? StaggeredGridLayoutManager)?.let {
            if (it.spanCount != spanCount) it.spanCount = spanCount
        }
    }

    override fun onAttached() {
        updateSpanCount()
        lastAttachedKeyboardMode = keyboardMode
        // 若用户刚通过独立搜索输入窗口（ClipboardSearchActivity）返回，应用其关键词
        currentQuery = org.fcitx.fcitx5.android.ui.main.ClipboardSearchResult.keyword
        updateSearchButtonState()
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        adapter.addOnPagesUpdatedListener(adapterPagesUpdatedListener)
        service.lifecycleScope.launch {
            val groups = ClipboardManager.getGroups()
            populateGroupFilterBar(groups)
        }
        submitCurrentPager()
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    override fun onDetached() {
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.removeOnPagesUpdatedListener(adapterPagesUpdatedListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        adapterSubmitJob = null
        pagerSubmissionId++
        suppressEmptyStateDuringPagerReset = null
        awaitingNonEmptyAfterPagerReset = null
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
        // 退出剪切板时清理临时分句分组
        if (segmentGroupId > 0) {
            val gid = segmentGroupId
            segmentGroupId = 0
            service.lifecycleScope.launch { ClipboardManager.purgeGroup(gid) }
        }
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override val anchorExtensionStart = true

    override fun onCreateBarExtension(): View = ui.extension
}
