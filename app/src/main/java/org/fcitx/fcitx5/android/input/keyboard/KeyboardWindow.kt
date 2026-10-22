/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutLoader
import org.fcitx.fcitx5.android.input.keyboard.config.TextKeyboardLayouts
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    private var layoutRevision = KeyboardLayoutLoader.revision
    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            TextKeyboard.EnglishName to TextKeyboard(context, theme, layoutName = TextKeyboard.EnglishName),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        ).also { layoutRevision = KeyboardLayoutLoader.revision }
    }
    private var currentKeyboardName = ""
    private var activeTextLayoutName = TextKeyboard.Name
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        activeTextLayoutName = TextKeyboardLayouts.forInputMethod(fcitx.runImmediately { inputMethodEntryCached })
        attachLayout(activeTextLayoutName)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String, ime: InputMethodEntry? = null) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(ime ?: fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        ContextCompat.getMainExecutor(service).execute {
            // 符号/数字页的 Text 返回入口必须跟随当前引擎模式，而非固定回中文。
            activeTextLayoutName = TextKeyboardLayouts.forInputMethod(fcitx.runImmediately { inputMethodEntryCached })
            val target = TextKeyboardLayouts.resolveTarget(to.ifEmpty { lastSymbolType }, activeTextLayoutName)
            if (keyboards.containsKey(target)) {
                if (remember && !TextKeyboardLayouts.isText(target)) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                // 离开数字键盘时清空计算器态
                if (currentKeyboardName == NumberKeyboard.Name && target != NumberKeyboard.Name) {
                    commonKeyActionListener.clearCalculator()
                }
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                // 切到符号/emoji picker：也清空计算器态
                if (currentKeyboardName == NumberKeyboard.Name) {
                    commonKeyActionListener.clearCalculator()
                }
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    private fun refreshSavedLayouts() {
        if (!::keyboardView.isInitialized || layoutRevision == KeyboardLayoutLoader.revision) return
        val target = currentKeyboardName.ifEmpty { TextKeyboard.Name }
        popup.dismissAll()
        detachCurrentLayout()
        keyboards.clear()
        keyboards[TextKeyboard.Name] = TextKeyboard(context, theme)
        keyboards[TextKeyboard.EnglishName] = TextKeyboard(context, theme, layoutName = TextKeyboard.EnglishName)
        keyboards[NumberKeyboard.Name] = NumberKeyboard(context, theme)
        layoutRevision = KeyboardLayoutLoader.revision
        attachLayout(TextKeyboardLayouts.resolveTarget(target, activeTextLayoutName))
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        refreshSavedLayouts()
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        activeTextLayoutName = TextKeyboardLayouts.forInputMethod(ime)
        if (::keyboardView.isInitialized && TextKeyboardLayouts.isText(currentKeyboardName) &&
            currentKeyboardName != activeTextLayoutName) {
            popup.dismissAll()
            detachCurrentLayout()
            attachLayout(activeTextLayoutName, ime)
            if (windowManager.isAttached(this)) notifyBarLayoutChanged()
        } else currentKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        activeTextLayoutName = TextKeyboardLayouts.forInputMethod(fcitx.runImmediately { inputMethodEntryCached })
        refreshSavedLayouts()
        if (TextKeyboardLayouts.isText(currentKeyboardName) && currentKeyboardName != activeTextLayoutName) {
            popup.dismissAll()
            detachCurrentLayout()
            attachLayout(activeTextLayoutName)
        }
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        commonKeyActionListener.clearCalculator()
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}
