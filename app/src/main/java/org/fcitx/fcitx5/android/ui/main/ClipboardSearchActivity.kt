/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.ActivityClipboardSearchBinding
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.MessagePhraseCipher
import org.fcitx.fcitx5.android.utils.inputMethodManager

/**
 * 獨立的剪貼簿搜尋輸入 Activity。
 *
 * 除原有搜尋模式外，也提供短語密鑰編輯模式。兩者共用這個有正常系統輸入連線的
 * EditText，避免在輸入法視窗內直接建立不相容的 AppCompat Dialog。
 */
class ClipboardSearchActivity : Activity() {

    private lateinit var binding: ActivityClipboardSearchBinding
    private var cancelled = false
    private var cipherKeyMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureMode()

        binding.clipboardSearchCancel.setOnClickListener {
            if (cipherKeyMode) {
                resetCipherKey()
            } else {
                cancelled = true
                ClipboardSearchResult.keyword = ""
                finish()
            }
        }
        binding.clipboardSearchOk.setOnClickListener {
            if (cipherKeyMode) saveCipherKey() else doSearch()
        }
        binding.clipboardSearchText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                if (cipherKeyMode) saveCipherKey() else doSearch()
                true
            } else false
        }
        inputMethodManager.showSoftInput(binding.clipboardSearchText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelled = false
        configureMode()
        inputMethodManager.showSoftInput(binding.clipboardSearchText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun configureMode() {
        cipherKeyMode = intent.getStringExtra(EXTRA_MODE) == MODE_CIPHER_KEY
        binding.clipboardSearchText.apply {
            if (cipherKeyMode) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = EditorInfo.IME_ACTION_DONE
                hint = getString(R.string.message_phrase_cipher_key_hint)
                setText(AppPrefs.getInstance().internal.messagePhraseCipherKey.getValue())
                setSelection(text.length)
            } else {
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                hint = getString(R.string.clipboard_search_hint)
                setText(ClipboardSearchResult.keyword)
                setSelection(text.length)
            }
        }
        if (cipherKeyMode) {
            title = getString(R.string.message_phrase_cipher_key_title)
            binding.clipboardSearchCancel.setText(R.string.message_phrase_cipher_reset_key)
            binding.clipboardSearchOk.setText(R.string.save)
        } else {
            title = getString(R.string.clipboard_search)
            binding.clipboardSearchCancel.setText(R.string.clipboard_search_cancel)
            binding.clipboardSearchOk.setText(R.string.clipboard_search)
        }
    }

    private fun saveCipherKey() {
        val key = binding.clipboardSearchText.text.toString()
        if (key.isEmpty()) {
            binding.clipboardSearchText.error = getString(R.string.message_phrase_cipher_key_empty)
            return
        }
        AppPrefs.getInstance().internal.messagePhraseCipherKey.setValue(key)
        Toast.makeText(this, R.string.message_phrase_cipher_key_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetCipherKey() {
        AppPrefs.getInstance().internal.messagePhraseCipherKey.setValue(MessagePhraseCipher.DEFAULT_KEY)
        Toast.makeText(this, R.string.message_phrase_cipher_key_reset, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun doSearch() {
        ClipboardSearchResult.keyword = binding.clipboardSearchText.text.toString().trim()
        finish()
        // 搜尋後重新展示剪貼簿窗口：列表切換為搜尋篩選結果。
        FcitxInputMethodService.instance?.showClipboardWindow()
    }

    override fun onStop() {
        super.onStop()
        if (cipherKeyMode) return
        // 使用者按返回/切走也視為確認已輸入內容；點「取消」除外。
        if (!cancelled) {
            ClipboardSearchResult.keyword = binding.clipboardSearchText.text.toString().trim()
        }
        // 搜尋模式關閉時回到剪貼簿窗口（取消=全部，搜尋=篩選結果）。
        FcitxInputMethodService.instance?.showClipboardWindow()
    }

    companion object {
        const val EXTRA_MODE = "clipboard_search_mode"
        const val MODE_CIPHER_KEY = "cipher_key"
    }
}
