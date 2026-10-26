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
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ReloadConfig
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.MessagePhraseCipherOperations
import org.fcitx.fcitx5.android.utils.MessagePhraseCipherTooLongException
import org.fcitx.fcitx5.android.utils.alpha
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
        arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.keyboard_layout_position),
                R.drawable.ic_baseline_drag_handle_24,
                KeyboardPosition
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_palette_24,
                ThemeList
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.input_method_options),
                R.drawable.ic_baseline_language_24,
                InputMethod
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.reload_config),
                R.drawable.ic_baseline_sync_24,
                ReloadConfig
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                Keyboard
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.message_phrase_cipher),
                R.drawable.ic_baseline_code_24,
                MessagePhraseCipher
            )
        )
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
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
        adapter.entries = arrayOf(
            *staticEntries,
            *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
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
