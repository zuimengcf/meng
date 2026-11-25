/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.TtsAudioCommit
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.KeyboardPosition
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.MessagePhraseCipher
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Plugin
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.PluginSettings
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ReloadConfig
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.TtsAudio
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.MessagePhraseCipherOperations
import org.fcitx.fcitx5.android.utils.MessagePhraseCipherTooLongException
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.ui.main.iconRes
import org.fcitx.fcitx5.android.plugin.PluginHost
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector
    private var messagePhraseCipherKey by AppPrefs.getInstance().internal.messagePhraseCipherKey
    private var messagePhraseCipherEnabled by AppPrefs.getInstance().internal.messagePhraseCipherEnabled

    private val staticEntries by lazy {
        StatusAreaOrder.defaultEntries(context)
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    // ===== 插件快速开关（「更多」面板）=====
    // 每次状态刷新重算：已安装插件各一格，active 反映当前启用状态。
    // 点击弹菜单：开/关（独立开关，立即生效）+ 打开插件设置（跳主应用详情页）。
    private fun pluginEntries(): Array<StatusAreaEntry> {
        val snapshot = PluginHost.snapshot()
        return snapshot.map { info ->
            StatusAreaEntry.Android(
                info.manifest.name,
                info.manifest.iconRes(),
                StatusAreaEntry.Android.Type.Plugin,
                info.state == org.fcitx.fcitx5.android.plugin.PluginState.Enabled,
                pluginId = info.manifest.id
            )
        }.toTypedArray()
    }

    private fun showPluginMenu(anchor: View, pluginId: String) {
        val enabled = PluginHost.isEnabled(pluginId)
        val info = PluginHost.snapshot().firstOrNull { it.manifest.id == pluginId }
        val popup = PopupMenu(context, anchor).apply {
            menu.add(
                context.getString(if (enabled) R.string.plugin_toggle_disable else R.string.plugin_toggle_enable)
            ).setOnMenuItemClickListener {
                PluginHost.setEnabled(pluginId, !enabled)
                // 立即刷新面板显示
                refreshEntries()
                true
            }
            // 快捷面板「功能单独启用」入口：quick 开关三态循环 关闭 → ⚡单次 → 打开 → 关闭。
            // 单次授权只对「关闭」的功能生效：功能关 + 单次开 → 下一次粘贴生效一次自动回常闭；
            // 功能打开时循环回到「开」（单次不生效）。
            info?.manifest?.settings
                ?.filter { it.quick && it.type == "switch" }
                ?.forEach { ps ->
                    val funcOn = PluginHost.settingOf(pluginId, ps.key, ps.defaultValue) == "true"
                    val once = PluginHost.isOnceMode(pluginId, ps.key)
                    // 当前状态：功能开 → 「开」；功能关 + 单次 → 「⚡单次」；否则 → 「关」
                    val state: Int = when {
                        funcOn -> 2            // 打开
                        once -> 1              // 单次（仅功能关时可达）
                        else -> 0              // 关闭
                    }
                    val stateLabel = when (state) {
                        2 -> "开"
                        1 -> "⚡单次"
                        else -> "关"
                    }
                    menu.add("${ps.label}：$stateLabel").setOnMenuItemClickListener {
                        when (state) {
                            // 关闭 → 单次（打标记，功能保持关）
                            0 -> PluginHost.enableOnceMode(pluginId, ps.key, ps.defaultValue)
                            // 单次 → 打开（清单次标记，功能置开；单次不生效）
                            1 -> {
                                PluginHost.clearOnceMode(pluginId, ps.key, ps.defaultValue)
                                PluginHost.setSetting(pluginId, ps.key, "true")
                            }
                            // 打开 → 关闭（功能置关）
                            else -> PluginHost.setSetting(pluginId, ps.key, "false")
                        }
                        refreshEntries()
                        true
                    }
                }
            menu.add(context.getString(R.string.plugin_open_settings)).setOnMenuItemClickListener {
                AppUtil.launchMainToPluginDetail(context, pluginId)
                true
            }
        }
        popupMenu?.dismiss()
        popupMenu = popup
        popup.setOnDismissListener {
            if (popupMenu === popup) popupMenu = null
        }
        popup.show()
    }

    /** 重新计算并刷新面板条目（插件开关切换后调用） */
    private fun refreshEntries() {
        val data = fcitx.runImmediately { statusArea() }
        onStatusAreaUpdate(data)
    }

    // ===== 文字转语音直发 =====
    // 链路：读取输入框全文 → 系统 TTS 合成音频 → commitContent 提交回当前输入框（目标应用按语音消息处理）。
    // 注意：不做 supportsAudioCommit 前置拦截——目标是否收音频由 commitContent 现场判定，
    // 失败时 TtsAudioCommit 内部 Toast「当前输入框不支持语音」，不浪费合成。
    private fun sendTtsAudio() {
        android.util.Log.i("MengTts", "sendTtsAudio tapped")
        val text = service.readCurrentEditorTextForPlugin().also {
            android.util.Log.i("MengTts", "readEditorText=${it?.take(50) ?: "null"}")
        } ?: run {
            Toast.makeText(service, R.string.tts_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isBlank()) {
            Toast.makeText(service, R.string.tts_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = TtsAudioCommit.commit(text, service)
        if (!ok) {
            Toast.makeText(service, R.string.tts_target_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMessagePhraseCipherMenu(anchor: View) {
        val popup = PopupMenu(context, anchor).apply {
            val toggleTitle = if (messagePhraseCipherEnabled) {
                R.string.message_phrase_cipher_toggle_enabled
            } else {
                R.string.message_phrase_cipher_toggle_disabled
            }
            menu.add(context.getString(toggleTitle))
                .setOnMenuItemClickListener {
                    messagePhraseCipherEnabled = !messagePhraseCipherEnabled
                    Toast.makeText(
                        service,
                        if (messagePhraseCipherEnabled) {
                            R.string.message_phrase_cipher_enabled_notice
                        } else {
                            R.string.message_phrase_cipher_disabled_notice
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
            menu.add(context.getString(R.string.message_phrase_cipher_encrypt)).apply {
                isEnabled = messagePhraseCipherEnabled
                setOnMenuItemClickListener {
                    encryptCurrentEditorText()
                    true
                }
            }
            menu.add(context.getString(R.string.message_phrase_cipher_set_key))
                .setOnMenuItemClickListener {
                    AppUtil.launchClipboardCipherKeySetting(context)
                    true
                }
        }
        popupMenu?.dismiss()
        popupMenu = popup
        popup.setOnDismissListener {
            if (popupMenu === popup) popupMenu = null
        }
        popup.show()
    }

    private fun encryptCurrentEditorText() {
        if (!service.isMessagePhraseCipherEnabled()) {
            Toast.makeText(service, R.string.message_phrase_cipher_disabled_notice, Toast.LENGTH_SHORT).show()
            return
        }
        if (!service.isMessagePhraseCipherAllowed()) {
            Toast.makeText(service, R.string.message_phrase_cipher_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (messagePhraseCipherKey.isEmpty()) {
            Toast.makeText(service, R.string.message_phrase_cipher_key_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = service.readCurrentEditorTextForMessagePhraseCipher() ?: run {
            Toast.makeText(service, R.string.message_phrase_cipher_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val observedCount = MessagePhraseCipherOperations.codePointCount(snapshot.text)
        if (observedCount > MessagePhraseCipherOperations.MAX_ORIGINAL_CODE_POINTS) {
            Toast.makeText(
                service,
                context.getString(R.string.message_phrase_cipher_too_long, observedCount),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!snapshot.isComplete) {
            Toast.makeText(service, R.string.message_phrase_cipher_read_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (snapshot.text.isEmpty()) {
            Toast.makeText(service, R.string.message_phrase_cipher_empty_input, Toast.LENGTH_SHORT).show()
            return
        }

        val encrypted = try {
            MessagePhraseCipherOperations.encrypt(snapshot.text, messagePhraseCipherKey)
        } catch (e: MessagePhraseCipherTooLongException) {
            Toast.makeText(
                service,
                context.getString(R.string.message_phrase_cipher_too_long, e.actualCodePointCount),
                Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) {
            Toast.makeText(service, R.string.message_phrase_cipher_failed, Toast.LENGTH_SHORT).show()
            return
        }

        if (!service.replaceCurrentEditorTextWithoutLogging(snapshot.text, encrypted)) {
            Toast.makeText(service, R.string.message_phrase_cipher_input_changed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(service, R.string.message_phrase_cipher_encrypted, Toast.LENGTH_SHORT).show()
    }

    var popupMenu: PopupMenu? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        val popup = PopupMenu(context, view)
                        val menu = popup.menu
                        val hasDivider =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !DeviceUtil.isHMOS && !DeviceUtil.isHonorMagicOS) {
                                menu.setGroupDividerEnabled(true)
                                true
                            } else {
                                false
                            }
                        var groupId = 0 // Menu.NONE; ungrouped
                        actions.forEach {
                            if (it.isSeparator) {
                                if (hasDivider) {
                                    groupId++
                                } else {
                                    val dividerString = buildSpannedString {
                                        color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                            append("──────────")
                                        }
                                    }
                                    menu.add(groupId, 0, 0, dividerString).apply {
                                        isEnabled = false
                                    }
                                }
                            } else {
                                menu.add(groupId, 0, 0, it.shortText).apply {
                                    setOnMenuItemClickListener { _ ->
                                        activateAction(it)
                                        true
                                    }
                                }
                            }
                        }
                        popupMenu?.dismiss()
                        popupMenu = popup
                        popup.show()
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, it.displayName
                            )
                        }
                        ReloadConfig -> fcitx.launchOnReady { f ->
                            f.reloadConfig()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                SubtypeManager.syncWith(f.enabledIme())
                            }
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Keyboard -> AppUtil.launchMainToKeyboard(context)
                        KeyboardPosition -> service.showKeyboardPositionMenu()
                        ThemeList -> AppUtil.launchMainToThemeList(context)
                        MessagePhraseCipher -> showMessagePhraseCipherMenu(view)
                        TtsAudio -> sendTtsAudio()
                        Plugin -> entry.pluginId?.let { id ->
                            val isPanel = PluginHost.snapshot()
                                .firstOrNull { it.manifest.id == id }
                                ?.manifest?.action == "panel"
                            if (isPanel) {
                                // 面板型插件：点击直接打开宿主通用面板窗口（UI 由插件自绘）
                                windowManager.attachWindow(
                                    org.fcitx.fcitx5.android.input.picker.PluginPanelWindow(id)
                                )
                            } else {
                                showPluginMenu(view, id)
                            }
                        }
                        PluginSettings -> Unit
                    }
                }
            }

            override fun onItemLongClick(view: View, entry: StatusAreaEntry): Boolean {
                return when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        // Long press is the quick path: invoke the action directly.
                        activateAction(entry.action)
                        true
                    }
                    is StatusAreaEntry.Android -> if (entry.type == KeyboardPosition) {
                        service.showKeyboardPositionMenu()
                        true
                    } else false
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            // A stable four-column tools panel in every keyboard mode, width
            // and font scale. Large labels use taller cells and vertical scrolling,
            // never a different column count during resize or floating movement.
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        adapter.entries = StatusAreaOrder.applyOrder(
            context,
            arrayOf(
                *staticEntries,
                *pluginEntries(),
                *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
            )
        )
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            setOnClickListener { windowManager.attachWindow(EditorInfoWindow()) }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            setOnClickListener { AppUtil.launchMain(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(dp(40), dp(40)))
            }
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
