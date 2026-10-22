/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardLayoutLoader
import org.fcitx.fcitx5.android.input.keyboard.config.ChineseKeyboardSymbols
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme,
    /** 可选：显式指定布局（供布局编辑器做「真实主题渲染」的预览）。null 时用 [Layout]（磁盘/内置）。 */
    keyLayoutOverride: List<List<KeyDef>>? = null
) : BaseKeyboard(context, theme, keyLayoutOverride ?: Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        /**
         * 布局来源：优先读外部 JSON 配置（用户可自定义），失败/缺失时回退到代码内置 [builtinLayout]。
         */
        val Layout: List<List<KeyDef>> get() =
            KeyboardLayoutLoader.loadOrNull(Name) ?: builtinLayout

        private fun defaultLetter(char: String): AlphabetKey {
            val items = ChineseKeyboardSymbols.candidates.getValue(char.lowercase())
            return AlphabetKey(char, items.first(), popup = arrayOf(
                KeyDef.Popup.AltPreview(char, items.first(), rawAlternative = true),
                KeyDef.Popup.Keyboard.Explicit(items.toTypedArray())
            ), literalSymbols = true)
        }

        val builtinLayout: List<List<KeyDef>> = listOf(
            listOf(
                defaultLetter("Q"),
                defaultLetter("W"),
                defaultLetter("E"),
                defaultLetter("R"),
                defaultLetter("T"),
                defaultLetter("Y"),
                defaultLetter("U"),
                defaultLetter("I"),
                defaultLetter("O"),
                defaultLetter("P")
            ),
            listOf(
                defaultLetter("A"),
                defaultLetter("S"),
                defaultLetter("D"),
                defaultLetter("F"),
                defaultLetter("G"),
                defaultLetter("H"),
                defaultLetter("J"),
                defaultLetter("K"),
                defaultLetter("L")
            ),
            listOf(
                CapsKey(),
                defaultLetter("Z"),
                defaultLetter("X"),
                defaultLetter("C"),
                defaultLetter("V"),
                defaultLetter("B"),
                defaultLetter("N"),
                defaultLetter("M"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("?123", ""),
                CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative, "，"),
                LanguageKey(),
                SpaceKey(),
                SymbolKey("。", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView? by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView? by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView? by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView? by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`?.img?.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space?.mainText?.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(
                content = if (action.raw) action.content else transformPopupPreview(action.content)
            )
            is PopupAction.PreviewUpdateAction -> action.copy(
                content = if (action.raw) action.content else transformPopupPreview(action.content)
            )
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        // 布局中可能没有 caps 键（如自定义成 separator/隔音符，或用户删除了 caps），需判空
        caps?.img?.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            if (it.def !is KeyDef.Appearance.AltText) return
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = if (it.def.rawAltText) it.def.altText else transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                // rawText=true 的键（SymbolKey）跳过标点映射，保持 WYSIWYG
                if (it.def.rawText) return@forEach
                it.mainText.text = it.def.displayText.let { str ->
                    if (str.isEmpty() || str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}