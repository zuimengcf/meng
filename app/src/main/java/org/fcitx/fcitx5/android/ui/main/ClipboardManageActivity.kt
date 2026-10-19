/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.TextView
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import org.fcitx.fcitx5.android.data.clipboard.ClipboardGroupDialog
import org.fcitx.fcitx5.android.data.clipboard.ClipboardRoomForm
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncClient
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncDiscovery
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncForegroundService
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncServer
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncSettings
import org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncNotifications
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.ActivityClipboardManageBinding
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.topPadding
import java.io.File

/** Full-screen clipboard: group filters, list tools and a separate stateful room panel. */
class ClipboardManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipboardManageBinding
    private val scope get() = lifecycleScope
    private val syncUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastListFingerprint = ""
    private var selectedGroup = 0 // 0=全部，-1=未分组，>0=分组 ID
    private var groupSignature = ""
    private var loadGeneration = 0
    private var dragging = false
    private var syncing = false
    private var roomDialog: AlertDialog? = null
    private var roomDialogStatus: TextView? = null
    private var roomDialogMode = ""
    private val pagePrefs by lazy { getSharedPreferences("clipboard_manage_ui", MODE_PRIVATE) }
    private val syncUiRefresh = object : Runnable {
        override fun run() {
            refreshSyncUI()
            refreshEntriesIfChanged()
            syncUiHandler.postDelayed(this, 1000L)
        }
    }

    private fun refreshEntriesIfChanged() {
        if (binding.clipboardManageList.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        scope.launch {
            val fingerprint = withContext(Dispatchers.IO) { org.fcitx.fcitx5.android.data.clipboard.ClipboardSnapshotStore.fingerprint() }
            if (fingerprint != lastListFingerprint) {
                lastListFingerprint = fingerprint
                loadEntries()
            }
        }
    }

    private lateinit var adapter: ClipboardManageAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** 客户端加入的房间地址（非空=已加入，可退出） */
    private var joinedAddress: String = ""
    private var joinedPassword: String = ""

    /** 列表排序模式：0=最新在上（时间倒序） 1=置顶优先 2=自定义拖拽顺序 */
    private var sortMode = 0

    /** 房间默认端口（冷门，避免与常见服务撞车）。 */
    private companion object {
        const val DEFAULT_SYNC_PORT = ClipboardRoomForm.DEFAULT_PORT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = (savedInstanceState?.getInt("sortMode") ?: pagePrefs.getInt("sortMode", 0)).coerceIn(0, 7)
        selectedGroup = savedInstanceState?.getInt("selectedGroup") ?: pagePrefs.getInt("selectedGroup", 0)
        binding = ActivityClipboardManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdge()
        // 处理状态栏/导航栏 insets：顶栏顶部避开状态栏，列表底部避开手势导航条
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.clipboardManageTopbar.topPadding = statusBars.top
            binding.clipboardManageList.updatePadding(bottom = dp(88) + navBars.bottom)
            binding.clipboardManageFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = dp(16) + navBars.right
                bottomMargin = dp(16) + navBars.bottom
            }
            windowInsets
        }

        binding.clipboardManageTitle.text = "全屏剪切板"
        binding.clipboardManageBack.setOnClickListener { finish() }

        adapter = ClipboardManageAdapter(
            this,
            AppPrefs.getInstance().clipboard.clipboardMaskSensitive.getValue(),
            onClick = { entry -> onEntryClick(entry) },
            onShare = { entry -> shareEntry(entry) },
            onDelete = { entry -> confirmDelete(entry) },
            onMoveToGroup = { entry -> promptMoveToGroup(entry) },
            onStartDrag = { holder ->
                if (sortMode == 2 && selectedGroup == 0) {
                    dragging = true
                    itemTouchHelper.startDrag(holder)
                } else toast("请在「全部」中选择自定义排序后拖动")
            }
        )
        binding.clipboardManageList.adapter = adapter
        binding.clipboardManageList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        // 拖动排序：长按拖动手柄上下拖动，松手后持久化 sortOrder
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return if (sortMode == 2 && selectedGroup == 0)
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from in 0 until adapter.itemCount && to in 0 until adapter.itemCount) {
                    adapter.swap(from, to)
                }
                return true
            }

            override fun isLongPressDragEnabled() = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragging) { persistOrder(); dragging = false }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.clipboardManageList)

        // 顶部同步按钮：点击弹出同步操作菜单
        binding.clipboardManageSync.setOnClickListener { showSyncMenu() }

        // 右下角悬浮按钮：弹出操作菜单（同步一次 / 排序方式 / 跳到底部）
        binding.clipboardManageFab.setOnClickListener {
            if (ClipboardSyncClient.joinedRoom.isNotEmpty()) doSyncOnce() else showSyncMenu()
        }
        binding.clipboardRoomCard.setOnClickListener { showSyncMenu() }
        binding.clipboardManageGroups.setOnClickListener {
            ClipboardGroupDialog.show(this) { groupSignature = ""; loadEntries() }
        }
        binding.clipboardManageSort.setOnClickListener { promptSortMode() }

        refreshSyncUI()
        loadEntries()
        if (intent.getBooleanExtra("show_sync_result", false)) {
            binding.root.post { showLatestSyncResult() }
        }
    }

    private fun roomMode() = when {
        ClipboardSyncServer.isRunning -> "host"
        ClipboardSyncClient.joinedRoom.isNotEmpty() -> "client"
        else -> "idle"
    }

    private fun roomAddress(): String = if (ClipboardSyncServer.isRunning)
        "http://${ClipboardSyncServer.localAddress}:${ClipboardSyncServer.port}" else ClipboardSyncClient.joinedRoom

    private fun roomStatus(): String = when (roomMode()) {
        "host" -> "本机房间已开启 · ${ClipboardSyncServer.onlineClientCount} 台设备在线"
        "client" -> if (ClipboardSyncClient.isConnected)
            "已连接房间 · ${ClipboardSyncClient.connectedDeviceCount} 台其他设备在线" else "正在连接房间 · 自动重试中"
        else -> "未连接同步房间"
    }

    private fun roomDetail(): String = when (roomMode()) {
        "host" -> roomAddress() + if (ClipboardSyncServer.localAddress == "127.0.0.1")
            "\n未检测到局域网地址，请连接 Wi-Fi 后重新创建。" else "\n在线设备自动同步；修改端口或密码需先关闭房间。"
        "client" -> roomAddress() + (ClipboardSyncClient.lastSyncError?.let {
            "\n同步提示：${it.take(160)}\n请检查同一网络、房间密码及两端版本。"
        } ?: "\n文字、图片和分组会自动同步，断线后自动重连。")
        else -> "同一局域网内创建或加入房间，自动同步文字、图片和分组。"
    }

    private fun refreshSyncUI() {
        binding.clipboardManageTitle.text = "全屏剪切板"
        binding.clipboardManageSync.text = "房间同步"
        binding.clipboardRoomStatus.text = if (syncing) "正在同步…" else roomStatus()
        binding.clipboardRoomDetail.text = roomDetail()
        binding.clipboardManageFab.contentDescription = if (ClipboardSyncClient.joinedRoom.isNotEmpty()) "立即同步" else "房间同步操作"
        binding.clipboardManageFab.isEnabled = !syncing
        if (roomDialog?.isShowing == true) {
            if (roomDialogMode != roomMode()) { roomDialog?.dismiss(); showSyncMenu() }
            else roomDialogStatus?.text = roomStatus() + "\n\n" + roomDetail()
        }
    }

    /** Room controls use explicit callbacks rather than matching translated menu text. */
    private fun showSyncMenu() {
        if (roomDialog?.isShowing == true) return
        val content = formContent()
        val status = TextView(this).apply {
            text = roomStatus() + "\n\n" + roomDetail()
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(12))
        }
        content.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle("房间同步")
            .setView(scrollContent(content)).setNegativeButton("收起", null).create()
        fun action(label: String, callback: () -> Unit) {
            content.addView(com.google.android.material.button.MaterialButton(this).apply {
                text = label
                setOnClickListener { dialog.dismiss(); callback() }
            })
        }
        when (roomMode()) {
            "host" -> {
                action("复制房间地址") { setSyncClipboard(roomAddress()); toast("已复制房间地址，密码请单独告知对方") }
                action("关闭本机房间") { confirmLeaveRoom(true) }
            }
            "client" -> {
                action(if (syncing) "同步进行中" else "立即同步") { doSyncOnce() }
                action("复制房间地址") { setSyncClipboard(roomAddress()); toast("已复制房间地址") }
                action("退出当前房间") { confirmLeaveRoom(false) }
            }
            else -> {
                action("创建房间 · 本机作为主机") { promptOpenRoom() }
                action("加入房间 · 连接其他设备") { promptJoinRoom() }
            }
        }
        action("同步历史与恢复") { showSyncHistory() }
        action("最近同步结果") { showLatestSyncResult() }
        action("变动通知设置") {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 421)
            } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
        content.addView(TextView(this).apply {
            text = "仅向可信设备提供地址和密码。加入后会双向合并剪切板及分组，删除和修改也会传播。退出不会删除本地内容。"
            setPadding(0, dp(12), 0, dp(8))
            textSize = 13f
        })
        roomDialog = dialog
        roomDialogStatus = status
        roomDialogMode = roomMode()
        dialog.setOnDismissListener { if (roomDialog === dialog) { roomDialog = null; roomDialogStatus = null } }
        dialog.show()
    }

    private fun confirmLeaveRoom(host: Boolean) {
        AlertDialog.Builder(this).setTitle(if (host) "关闭本机房间？" else "退出当前房间？")
            .setMessage(if (host) "其他设备会断开连接。本机剪切板和分组都会保留。" else "将停止自动同步，本机剪切板和分组都会保留。")
            .setPositiveButton(if (host) "关闭房间" else "退出房间") { _, _ ->
                if (host) stopRoom() else {
                    joinedAddress = ""; joinedPassword = ""
                    ClipboardSyncClient.leaveRoom()
                    runCatching { ClipboardSyncSettings.clearMode() }
                    ClipboardSyncForegroundService.requestStop(this)
                    toast("已退出房间")
                    refreshSyncUI()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showLatestSyncResult() {
        val result = ClipboardSyncNotifications.latest()
        if (result == null) { toast("暂无同步结果"); return }
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(result.timestamp))
        AlertDialog.Builder(this).setTitle("最近同步结果 · $time")
            .setMessage(result.detailText()).setPositiveButton("知道了", null)
            .setNeutralButton("最近20次") { _, _ -> showRecentSyncResults() }.show()
    }

    private fun showRecentSyncResults() {
        val results = ClipboardSyncNotifications.recent()
        val formatter = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        AlertDialog.Builder(this).setTitle("最近的同步变动")
            .setItems(results.map { "${formatter.format(java.util.Date(it.timestamp))}\n${it.headline()}" }.toTypedArray()) { _, index ->
                AlertDialog.Builder(this).setTitle("同步变动明细")
                    .setMessage(results[index].detailText()).setPositiveButton("知道了", null).show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun showSyncHistory() {
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                ClipboardManager.revisions().filter { it.entry?.deleted == false || it.group != null }
                    .sortedByDescending { it.ts }
            }
            if (rows.isEmpty()) { toast("暂无可恢复的历史版本"); return@launch }
            val formatter = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val labels = rows.map { row ->
                val value = row.entry?.let { if (it.sensitive) "敏感内容（点选查看）" else if (it.type.startsWith("image/")) "[图片] ${it.text}" else it.text }
                    ?: "[分组] ${row.group?.name.orEmpty()}"
                "${formatter.format(java.util.Date(row.ts))} · ${value.replace('\n', ' ').take(64)}"
            }.toTypedArray()
            AlertDialog.Builder(this@ClipboardManageActivity)
                .setTitle("同步历史与恢复（${rows.size} 个版本）")
                .setItems(labels) { _, index ->
                    val revision = rows[index]
                    val description = revision.entry?.text ?: "分组：${revision.group?.name.orEmpty()}"
                    AlertDialog.Builder(this@ClipboardManageActivity)
                        .setTitle("恢复这个历史版本？")
                        .setMessage("恢复会产生一个新版本并同步到在线设备；不会清除其他历史。\n\n$description")
                        .setPositiveButton("恢复") { _, _ ->
                            scope.launch {
                                val result = runCatching { withContext(Dispatchers.IO) { ClipboardManager.restoreRevision(revision.id) } }
                                if (result.getOrDefault(false)) { toast("历史版本已恢复，等待在线同步"); loadEntries() }
                                else toast("未能恢复：${result.exceptionOrNull()?.message ?: "图片源或历史记录缺失"}")
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    /** 分享条目：文字走 ACTION_SEND text/plain，图片走 ACTION_SEND 图片类型（content URI 授权读取）。 */
    private fun shareEntry(entry: ClipboardEntry) {
        val isImage = entry.type.startsWith("image/") && entry.imagePath != null
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = if (isImage) entry.type else "text/plain"
        if (isImage) {
            val file = File(entry.imagePath!!)
            if (!file.exists()) { toast("图片文件不存在"); return }
            val uri = buildImageContentUri(file) ?: Uri.fromFile(file)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(contentResolver, entry.text, uri)
        } else {
            intent.putExtra(Intent.EXTRA_TEXT, entry.text)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share))
        runCatching { startActivity(chooser) }
            .onFailure { toast("分享失败") }
    }

    /** 条目点击：图片→弹出预览大图；文本→打开编辑页。 */
    private fun onEntryClick(entry: ClipboardEntry) {
        val isImage = entry.type.startsWith("image/") && entry.imagePath != null
        if (isImage) {
            previewImage(entry)
        } else {
            AppUtil.launchClipboardEdit(this, entry.id)
        }
    }

    /** 弹出图片预览对话框，底部提供「复制到剪贴板」按钮。 */
    private fun previewImage(entry: ClipboardEntry) {
        val file = File(entry.imagePath!!)
        if (!file.exists()) {
            toast("图片文件不存在")
            return
        }
        val imageView = android.widget.ImageView(this).apply {
            setImageURI(Uri.fromFile(file))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_image_preview_title)
            .setView(imageView)
            .setPositiveButton(R.string.clipboard_image_copy) { _, _ -> copyImageToClipboard(entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 复制图片到系统剪贴板（用 FileProvider content URI，确保目标可读；并抑制重复捕获）。 */
    private fun copyImageToClipboard(entry: ClipboardEntry) {
        val ok = ClipboardManager.copyImageToSystemClipboard(entry.imagePath!!, entry.text)
        toast(if (ok) R.string.clipboard_image_copied else R.string.clipboard_copy_failed)
    }

    /** 用应用 FileProvider 构造图片的可读 content URI（file:// 对目标不可读，DocumentsProvider 有权限问题）。 */
    private fun buildImageContentUri(file: File): Uri? {
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadEntries() {
        if (dragging) return
        val generation = ++loadGeneration
        scope.launch {
            val groups = withContext(Dispatchers.IO) { ClipboardManager.getGroups() }
            if (generation != loadGeneration) return@launch
            if (selectedGroup > 0 && groups.none { it.id == selectedGroup }) selectedGroup = 0
            renderGroupFilters(groups)
            val filter = selectedGroup
            val entries = withContext(Dispatchers.IO) {
                when (sortMode) {
                    1 -> ClipboardManager.getAllSorted()       // 置顶优先
                    2 -> ClipboardManager.getAllBySortOrder()  // 自定义拖拽
                    3 -> ClipboardManager.getAllByTimeAsc()    // 最旧在上
                    4 -> ClipboardManager.getAllByUpdatedAtDesc() // 最近更新
                    5 -> ClipboardManager.getAllByTextAsc()    // 字母顺序
                    6 -> ClipboardManager.getAllByImageFirst() // 图片优先
                    7 -> ClipboardManager.getAllByTextFirst()  // 纯文本优先
                    else -> ClipboardManager.getAllByTimeDesc() // 最新在上
                }
            }
            if (generation != loadGeneration || dragging) return@launch
            val visible = entries.filter { selectedGroup == 0 ||
                (selectedGroup == -1 && it.groupId == 0) || it.groupId == selectedGroup }
            adapter.submit(visible)
            val name = if (selectedGroup == 0) "全部" else if (selectedGroup == -1) "未分组"
                else groups.firstOrNull { it.id == selectedGroup }?.name ?: "全部"
            binding.clipboardListSummary.text = "$name · ${visible.size} 条"
            binding.clipboardManageSort.contentDescription = "排序：${sortName(sortMode)}"
            pagePrefs.edit().putInt("selectedGroup", selectedGroup).apply()
            binding.clipboardManageEmpty.text = if (filter == 0) "暂无剪切板内容\n复制文字或图片后会显示在这里" else "这个分组还没有内容\n可通过条目上的分组按钮移入内容"
            if (lastListFingerprint.isEmpty()) {
                lastListFingerprint = withContext(Dispatchers.IO) { org.fcitx.fcitx5.android.data.clipboard.ClipboardSnapshotStore.fingerprint() }
            }
            binding.clipboardManageEmpty.visibility =
                if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun renderGroupFilters(groups: List<org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup>) {
        val signature = groups.joinToString { "${it.id}:${it.name}" } + "|$selectedGroup"
        if (signature == groupSignature) return
        groupSignature = signature
        binding.clipboardGroupFilters.removeAllViews()
        val filters = listOf(0 to "全部", -1 to "未分组") + groups.map { it.id to it.name }
        filters.forEach { (id, label) ->
            binding.clipboardGroupFilters.addView(Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = selectedGroup == id
                setOnClickListener {
                    if (selectedGroup != id) { selectedGroup = id; loadEntries() }
                    else isChecked = true
                }
            })
        }
    }

    /** Persist the entire unfiltered custom-order list only; filtered reordering is ambiguous. */
    private fun persistOrder() {
        if (sortMode != 2 || selectedGroup != 0) return
        val ids = (0 until adapter.itemCount).mapNotNull { adapter.entryAt(it)?.id }
        if (ids.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ids.forEachIndexed { i, id -> ClipboardManager.reorderEntry(id, (ids.size - i).toLong()) }
                }
            }.onSuccess { toast(R.string.clipboard_reorder_done) }
                .onFailure { toast("排序保存失败，请重试") }
            loadEntries()
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()

    private fun sortName(mode: Int): String = when (mode) {
        1 -> "置顶优先"
        2 -> "自定义排序（拖拽）"
        3 -> "最旧在上"
        4 -> "最近更新"
        5 -> "字母顺序"
        6 -> "图片优先"
        7 -> "纯文本优先"
        else -> "最新在上"
    }

    /** 弹出排序方式选择框（共 8 种）。 */
    private fun promptSortMode() {
        val labels = arrayOf(
            "最新在上", "置顶优先", "自定义排序（拖拽）",
            "最旧在上", "最近更新", "字母顺序", "图片优先", "纯文本优先"
        )
        AlertDialog.Builder(this)
            .setTitle("选择排序方式")
            .setSingleChoiceItems(labels, sortMode) { dialog, which ->
                sortMode = which
                pagePrefs.edit().putInt("sortMode", sortMode).apply()
                if (which == 2 && selectedGroup != 0) { selectedGroup = 0; toast("自定义排序已切换到全部内容") }
                toast("已切换为：${sortName(sortMode)}")
                dialog.dismiss()
                loadEntries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 滚动到列表底部（配合「最新在上」时查看最旧历史条目）。 */
    private fun scrollToBottom() {
        binding.clipboardManageList.post {
            val n = adapter.itemCount
            if (n > 0) binding.clipboardManageList.scrollToPosition(n - 1)
        }
    }

    /** 删除确认弹窗，确认后标记删除并提供撤销。 */
    private fun confirmDelete(entry: ClipboardEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.clipboard_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { ClipboardManager.delete(entry.id) }
                    pendingDeleteIds.add(entry.id)
                    showUndoSnackbar(entry.id)
                    loadEntries()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 弹出撤销删除 Snackbar；超时或滑动即真正删除。 */
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { if (!pendingDeleteIds.contains(it)) pendingDeleteIds.add(it) }
        val root = binding.root
        Snackbar.make(root, getString(R.string.clipboard_delete_undo_hint), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    }
                    pendingDeleteIds.clear()
                    loadEntries()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    when (event) {
                        Snackbar.Callback.DISMISS_EVENT_SWIPE,
                        Snackbar.Callback.DISMISS_EVENT_MANUAL,
                        Snackbar.Callback.DISMISS_EVENT_TIMEOUT -> {
                            scope.launch {
                                withContext(Dispatchers.IO) { ClipboardManager.realDelete() }
                                pendingDeleteIds.clear()
                            }
                        }
                        Snackbar.Callback.DISMISS_EVENT_ACTION,
                        Snackbar.Callback.DISMISS_EVENT_CONSECUTIVE -> {
                            // user clicked "undo" or deleted more items which makes a new snackbar
                        }
                    }
                }
            }).show()
    }

    /** 弹出「移到分组」选择框：未分组 + 全部分组。 */
    private fun promptMoveToGroup(entry: ClipboardEntry) {
        scope.launch {
            val groups = withContext(Dispatchers.IO) { ClipboardManager.getGroups() }
            val labels = arrayOf(getString(R.string.group_ungrouped)) + groups.map { it.name }
            val groupIds = arrayOf(0) + groups.map { it.id }
            AlertDialog.Builder(this@ClipboardManageActivity)
                .setTitle(R.string.move_to_group)
                .setItems(labels) { _, which ->
                    val gid = groupIds[which]
                    scope.launch {
                        withContext(Dispatchers.IO) { ClipboardManager.setEntryGroup(entry.id, gid) }
                        loadEntries()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setSyncClipboard(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("clipboard sync addr", text))
    }

    private fun formContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }

    private fun scrollContent(content: LinearLayout) = ScrollView(this).apply { addView(content) }

    private fun field(content: LinearLayout, label: String, value: String = "", password: Boolean = false,
                      number: Boolean = false, helper: String? = null): Pair<TextInputLayout, TextInputEditText> {
        val box = TextInputLayout(this).apply {
            hint = label
            helperText = helper
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(box.context).apply {
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            setSingleLine(true)
            if (password) transformationMethod = PasswordTransformationMethod.getInstance()
            setText(value)
            if (password) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        box.addView(input)
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        input.addTextChangedListener { box.error = null }
        return box to input
    }

    private fun formInfo(content: LinearLayout, message: String): TextView = TextView(this).apply {
        text = message
        setPadding(0, dp(8), 0, dp(12))
        content.addView(this)
    }

    private fun promptOpenRoom() {
        if (roomMode() != "idle") { showSyncMenu(); return }
        val content = formContent()
        val info = formInfo(content, "本机作为主机。其他设备连接同一 Wi-Fi 或局域网后，使用房间地址和密码加入；双方内容将自动合并。")
        val (portBox, portInput) = field(content, "监听端口", pagePrefs.getInt("hostPort", DEFAULT_SYNC_PORT).toString(),
            number = true, helper = "范围 1024–65535，默认 $DEFAULT_SYNC_PORT")
        val (pwdBox, pwdInput) = field(content, "房间密码", ClipboardSyncSettings.getDefaultPassword().orEmpty(),
            password = true, helper = "至少 8 个字符；使用系统密钥加密保存，下次创建可复用")
        val dialog = AlertDialog.Builder(this).setTitle("创建同步房间").setView(scrollContent(content))
            .setPositiveButton("创建房间", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val portResult = runCatching { ClipboardRoomForm.port(portInput.text.toString()) }
                val pwdResult = runCatching { ClipboardRoomForm.password(pwdInput.text.toString()) }
                portBox.error = portResult.exceptionOrNull()?.message
                pwdBox.error = pwdResult.exceptionOrNull()?.message
                if (portResult.isFailure || pwdResult.isFailure) return@setOnClickListener
                if (roomMode() != "idle") { info.text = "已有运行中的房间，请先退出或关闭。"; return@setOnClickListener }
                val port = portResult.getOrThrow(); val pwd = pwdResult.getOrThrow()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                info.text = "正在创建房间…"
                scope.launch {
                    val result = runCatching {
                        // Finish startup or rollback even if the activity rotates during bind().
                        withContext(kotlinx.coroutines.NonCancellable) {
                        ClipboardSyncSettings.saveDefaultPassword(pwd)
                        val actualPort = ClipboardSyncServer.start(port, pwd) { /* UI observes database while resumed. */ }
                        check(actualPort > 0) { "无法监听端口 $port，请更换端口或检查网络。" }
                        try {
                            ClipboardSyncSettings.saveHost(actualPort, pwd)
                            ClipboardSyncForegroundService.startHost(this@ClipboardManageActivity, actualPort, pwd)
                        } catch (e: Exception) {
                            ClipboardSyncServer.stop()
                            runCatching { ClipboardSyncSettings.clearMode() }
                            throw e
                        }
                        pagePrefs.edit().putInt("hostPort", actualPort).apply()
                        }
                    }
                    if (isDestroyed || isFinishing) return@launch
                    if (result.isSuccess) {
                        dialog.dismiss(); toast("房间已创建，点击房间同步可复制地址")
                    } else {
                        info.text = "创建失败：${result.exceptionOrNull()?.message ?: "请重试"}"
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.setCancelable(true)
                    }
                    refreshSyncUI()
                }
            }
        }
        dialog.show()
    }

    private fun promptJoinRoom() {
        if (roomMode() != "idle") { showSyncMenu(); return }
        val content = formContent()
        val info = formInfo(content, "填写主机显示的房间地址和密码。加入后会双向同步文字、图片、分组及删除操作，请只加入可信房间。")
        val (addrBox, addrInput) = field(content, "房间地址", pagePrefs.getString("lastRoom", "").orEmpty(),
            helper = "例如 192.168.1.10:$DEFAULT_SYNC_PORT；省略端口时使用 $DEFAULT_SYNC_PORT")
        val (pwdBox, pwdInput) = field(content, "房间密码", password = true,
            helper = "输入主机设置的密码，至少 8 个字符")
        var lastAutoPassword = ""
        fun fillRemembered() {
            val normalized = runCatching { ClipboardRoomForm.address(addrInput.text.toString()) }.getOrNull()
            val remembered = normalized?.let { ClipboardSyncSettings.rememberedPassword(it) }.orEmpty()
            val current = pwdInput.text.toString()
            // Never carry an automatically filled password into another room.
            if (current.isEmpty() || current == lastAutoPassword) { pwdInput.setText(remembered); lastAutoPassword = remembered }
        }
        addrInput.addTextChangedListener { fillRemembered() }
        fillRemembered()
        content.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "发现局域网房间"
            setOnClickListener { autoDiscoverHost(addrInput) }
        })
        val dialog = AlertDialog.Builder(this).setTitle("加入同步房间").setView(scrollContent(content))
            .setPositiveButton("加入并自动同步", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val addr = runCatching { ClipboardRoomForm.address(addrInput.text.toString()) }
                val pwd = runCatching { ClipboardRoomForm.password(pwdInput.text.toString()) }
                addrBox.error = addr.exceptionOrNull()?.message
                pwdBox.error = pwd.exceptionOrNull()?.message
                if (addr.isFailure || pwd.isFailure) return@setOnClickListener
                if (roomMode() != "idle") { info.text = "已有运行中的房间，请先退出或关闭。"; return@setOnClickListener }
                val address = addr.getOrThrow(); val password = pwd.getOrThrow()
                val result = runCatching {
                    ClipboardSyncSettings.saveClient(address, java.net.URI(address).port, password)
                    joinAsClient(address, password)
                }
                if (result.isSuccess) {
                    pagePrefs.edit().putString("lastRoom", address).apply()
                    dialog.dismiss()
                } else {
                    ClipboardSyncClient.leaveRoom(); joinedAddress = ""; joinedPassword = ""
                    runCatching { ClipboardSyncSettings.clearMode() }
                    info.text = "加入失败：${result.exceptionOrNull()?.message ?: "请重试"}"
                    refreshSyncUI()
                }
            }
        }
        dialog.show()
    }

    /** 上下文弹窗选择器：展示自动发现的地址，用户点选后填入地址输入框。 */
    private fun autoDiscoverHost(addrInput: EditText) {
        val ctx = this
        ctx.toast(R.string.clipboard_sync_discovering)
        scope.launch {
            val candidates = withContext(Dispatchers.IO) {
                ClipboardSyncDiscovery.discover()
            }
            if (!addrInput.isAttachedToWindow) return@launch
            if (candidates.isEmpty()) {
                ctx.toast(R.string.clipboard_sync_no_host_found)
                return@launch
            }
            // 带端口排序，优先默认端口
            val sorted = candidates.sortedWith(
                compareByDescending<String> { it.endsWith(":$DEFAULT_SYNC_PORT") }
                    .thenBy { it }
            )
            AlertDialog.Builder(ctx)
                .setTitle("发现的房间 · 请核对主机地址")
                .setItems(sorted.toTypedArray()) { _, which ->
                    if (addrInput.isAttachedToWindow) addrInput.setText(sorted[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 以客户端身份加入房间并同步一次。 */
    private fun joinAsClient(addr: String, pwd: String) {
        if (ClipboardSyncServer.isRunning) { toast("请先停止当前房间"); return }
        joinedAddress = addr
        joinedPassword = pwd
        // 记录到全局（跨 Activity/前台服务共享）
        ClipboardSyncClient.joinRoom(addr, pwd)
        // 拉起前台服务（客户端模式）：加入后常驻，通知栏提供入口与「退出房间」动作
        ClipboardSyncForegroundService.startClient(this, addr, pwd)
        toast("正在连接房间…")
        refreshSyncUI()
        // The background client performs the initial merge; avoid a duplicate foreground exchange.
    }

    /** 以客户端身份同步一次。@param addrOverride 可选，显式指定目标地址（主机自连时用），默认用已加入的房间。 */
    private fun doSyncOnce(addrOverride: String = "", pwdOverride: String = "") {
        if (syncing) { toast("同步正在进行，请稍候"); return }
        // 优先用显式传入地址；否则用全局已加入房间（跨前台服务恢复也读得到）
        val addr = addrOverride.ifBlank { ClipboardSyncClient.joinedRoom.ifBlank { joinedAddress } }
        if (addr.isEmpty()) { showSyncMenu(); return }
        syncing = true
        refreshSyncUI()
        val pwd = pwdOverride.ifBlank { ClipboardSyncClient.joinedRoomPassword.ifBlank { joinedPassword } }
        val progress = binding.clipboardSyncProgress
        progress.visibility = View.VISIBLE
        progress.progress = 0
        progress.max = 100
        scope.launch {
            val phaseText = arrayOf("准备中…")
            val n = runCatching {
                withContext(Dispatchers.IO + kotlinx.coroutines.CoroutineName("clipboard-sync")) {
                    ClipboardSyncClient.syncOnce(addr, pwd) { phase, sent, received, applied ->
                        phaseText[0] = phase
                        runOnUiThread {
                            // 进度条按阶段推进，标题栏显示真实条数
                            val pct = when {
                                phase.startsWith("准备") -> 5
                                phase.startsWith("上报") -> 20
                                phase.startsWith("等待") -> 40
                                phase.startsWith("解析") -> 60
                                phase.startsWith("收到") -> 80
                                phase.startsWith("完成") -> 100
                                else -> 50
                            }
                            progress.progress = pct
                            binding.clipboardRoomStatus.text = "同步中：$phase"
                        }
                    }
                }
            }.getOrElse { e ->
                phaseText[0] = "异常：${e.message?.take(60) ?: e.javaClass.simpleName}"
                progress.progress = 100
                runOnUiThread { binding.clipboardRoomStatus.text = "同步失败" }
                -1
            }
            // 收尾：最后停留一瞬间再隐藏，避免闪烁
            kotlinx.coroutines.delay(300)
            progress.visibility = View.GONE
            if (n >= 0) {
                val summary = ClipboardSyncClient.lastSyncSummary
                if (summary != null) {
                    fun details(title: String, rows: List<org.fcitx.fcitx5.android.data.clipboard.ClipboardSyncChange>): String =
                        buildString {
                            append(title).append("（").append(rows.size).append("）")
                            rows.forEach { row ->
                                append("\n• ").append(row.preview).append("  [").append(row.syncId).append("]")
                            }
                        }
                    val message = buildString {
                        append("新增 ").append(summary.added).append("，编辑 ")
                            .append(summary.updated).append("，删除 ").append(summary.deleted)
                        append("\n已有匹配：").append(summary.mergedExisting)
                            .append("\n现有可见：").append(summary.totalAfter)
                        append("\n\n").append(details("新增", summary.addedDetails))
                        append("\n\n").append(details("编辑", summary.updatedDetails))
                        append("\n\n").append(details("删除", summary.deletedDetails))
                    }
                    val scroll = ScrollView(this@ClipboardManageActivity).apply {
                        isFillViewport = true
                        addView(TextView(this@ClipboardManageActivity).apply {
                            text = message
                            setTextIsSelectable(true)
                            setPadding(dp(8), dp(4), dp(8), dp(4))
                        })
                    }
                    AlertDialog.Builder(this@ClipboardManageActivity)
                        .setTitle("同步完成")
                        .setView(scroll)
                        .setPositiveButton("确定", null)
                        .show()
                } else {
                    toast("同步完成：${phaseText[0]}")
                }
                loadEntries()
            } else {
                toast("同步异常：${phaseText[0]}")
            }
            syncing = false
            refreshSyncUI()
        }
    }

    /** 关闭房间主机，并通知前台服务自停（移除常驻通知）。 */
    private fun stopRoom() {
        ClipboardSyncServer.stop()
        runCatching { ClipboardSyncSettings.clearMode() }
        ClipboardSyncForegroundService.requestStop(this)
        toast(R.string.clipboard_sync_stop_success)
        refreshSyncUI()
    }

    override fun onPause() {
        syncUiHandler.removeCallbacks(syncUiRefresh)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 从全局恢复本地状态（进程重启后 joinedAddress 清空但全局可能还在）
        if (joinedAddress.isEmpty() && ClipboardSyncClient.joinedRoom.isNotEmpty()) {
            joinedAddress = ClipboardSyncClient.joinedRoom
            joinedPassword = ClipboardSyncClient.joinedRoomPassword
        }
        loadEntries()
        refreshSyncUI()
        syncUiHandler.removeCallbacks(syncUiRefresh)
        syncUiHandler.post(syncUiRefresh)
        // 进程重启后恢复前台服务常驻通知：主机在跑补拉主机模式；已加入房间补拉客户端模式
        if (ClipboardSyncServer.isRunning) {
            ClipboardSyncForegroundService.startHost(
                this, ClipboardSyncServer.port, ClipboardSyncServer.passwordHint
            )
        } else if (ClipboardSyncClient.joinedRoom.isNotEmpty()) {
            ClipboardSyncForegroundService.startClient(
                this, ClipboardSyncClient.joinedRoom, ClipboardSyncClient.joinedRoomPassword
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedGroup", selectedGroup)
        outState.putInt("sortMode", sortMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        roomDialog?.dismiss()
        syncUiHandler.removeCallbacks(syncUiRefresh)
        super.onDestroy()
    }
}
