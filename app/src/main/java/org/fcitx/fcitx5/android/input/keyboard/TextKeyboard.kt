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
import org.fcitx.fcitx5.android.input.keyboard.config.AlphabetPopupCandidates
import org.fcitx.fcitx5.android.input.keyboard.config.KeyboardPunctuationMode
import org.fcitx.fcitx5.android.market.LongPressPresetEditor
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme,
    /** 可选：显式指定布局（供布局编辑器做「真实主题渲染」的预览）。null 时用 [Layout]（磁盘/内置）。 */
    keyLayoutOverride: List<List<KeyDef>>? = null,
    val layoutName: String = Name,
    initialChinesePunctuation: Boolean = layoutName != EnglishName
) : BaseKeyboard(context, theme, keyLayoutOverride ?: layoutFor(layoutName)) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        const val EnglishName = "English"

        fun layoutFor(name: String): List<List<KeyDef>> =
            KeyboardLayoutLoader.loadOrNull(name) ?: if (name == EnglishName) englishBuiltinLayout else builtinLayout

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
                SeparatorKey(0.1f, KeyDef.Appearance.Variant.Alternative),
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
                CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative, ","),
                LanguageKey(),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )

        // 两套独立布局：中文原位为分词，英文原位为大小写。
        val englishBuiltinLayout: List<List<KeyDef>> = builtinLayout.map { row ->
            row.map { if (it is SeparatorKey) CapsKey() else it }
        }
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

    // 編輯器預設預覽中文；實際鍵盤由引擎subMode即時更新。
    private var chinesePunctuation = initialChinesePunctuation
    private fun modePunctuation(text: String) = KeyboardPunctuationMode.display(text, chinesePunctuation)

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    init { updatePunctuationKeys() }

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
            is KeyAction.CommitAction -> {
                // 彈窗候選已按當前模式生成；直接鍵/滑動在這裡用同一策略提交。
                if (source == KeyActionListener.Source.Keyboard && action.modeAwarePunctuation) {
                    transformed = action.copy(text = modePunctuation(action.text))
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
        updatePunctuationKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`?.img?.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        val chinese = KeyboardPunctuationMode.isChinese(
            ime.languageCode, ime.addon, ime.uniqueName,
            ime.subMode.icon, ime.subMode.label, ime.subMode.name, ime.icon
        )
        if (chinese != chinesePunctuation) {
            chinesePunctuation = chinese
            // 取消舊模式彈窗，避免按住候選時切換模式後提交舊內容。
            textKeys.forEach { super.onPopupAction(PopupAction.DismissAction(it.id)) }
        }
        updatePunctuationKeys()
        updateCapsButtonIcon()
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

    private fun previewContent(viewId: Int, content: String, raw: Boolean): String {
        val appearance = findViewById<KeyView>(viewId)?.def as? KeyDef.Appearance.Text
        // 隔音符是引擎按鍵，不是中文引號，不參與模式轉換。
        if (appearance?.modeAwarePunctuation == true && (raw || content.none { it.isLetter() })) {
            return ChineseKeyboardSymbols.display(appearance.displayText, content, chinesePunctuation)
        }
        return if (raw) content else transformPopupPreview(content)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(
                content = previewContent(action.viewId, action.content, action.raw)
            )
            is PopupAction.PreviewUpdateAction -> action.copy(
                content = previewContent(action.viewId, action.content, action.raw)
            )
            is PopupAction.ShowKeyboardAction -> {
                val items = when (val keyboard = action.keyboard) {
                    is KeyDef.Popup.Keyboard.Explicit -> keyboard.items.toList()
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = keyboard.label.let {
                            if (it.length == 1 && it[0].isLetter()) transformAlphabet(it) else it
                        }
                        LongPressPresetEditor.getFor(context, label)?.toList()
                    }
                }
                val appearance = findViewById<KeyView>(action.viewId)?.def as? KeyDef.Appearance.Text
                val key = appearance?.displayText.orEmpty()
                val candidates = if (appearance is KeyDef.Appearance.AltText)
                    AlphabetPopupCandidates.resolve(key, items.orEmpty(), capsState != CapsState.None)
                else items.orEmpty()
                if (candidates.isEmpty()) action else action.copy(
                    keyboard = KeyDef.Popup.Keyboard.Explicit(
                        ChineseKeyboardSymbols.displayedCandidates(
                            key, candidates, chinesePunctuation
                        ).toTypedArray()
                    )
                )
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
                it.altText.text = if (it.def.modeAwarePunctuation) ChineseKeyboardSymbols.display(it.def.displayText, it.def.altText, chinesePunctuation)
                    else if (it.def.rawAltText) it.def.altText else transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                if (it.def.modeAwarePunctuation) {
                    it.mainText.text = modePunctuation(it.def.displayText)
                    return@forEach
                }
                if (it.def.rawText) return@forEach
                it.mainText.text = it.def.displayText.let { str ->
                    if (str.isEmpty() || str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}