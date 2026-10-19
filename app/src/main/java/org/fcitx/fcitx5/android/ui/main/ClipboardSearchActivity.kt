/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import org.fcitx.fcitx5.android.databinding.ActivityClipboardSearchBinding
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.inputMethodManager

/**
 * 剪切板搜索输入窗口。
 *
 * 剪切板窗口（输入法内嵌）无法直接弹出系统软键盘输入（键盘即输入法自身），
 * 因此点击剪切板搜索时启动本独立输入 Activity：用户在其中输入搜索关键词，
 * 点「搜索」或回车后把结果写回静态 holder（[ClipboardSearchResult]），并返回剪切板窗口。
 *
 * 与 [ClipboardEditActivity] 保持一致的弹窗体验（顶部输入框 + 底部系统样式按钮）。
 */
class ClipboardSearchActivity : Activity() {

    private lateinit var binding: ActivityClipboardSearchBinding
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardSearchBinding.inflate(layoutInflater).apply {
            clipboardSearchText.setText(ClipboardSearchResult.keyword)
            clipboardSearchCancel.setOnClickListener {
                cancelled = true
                ClipboardSearchResult.keyword = ""
                finish()
            }
            clipboardSearchOk.setOnClickListener { doSearch() }
            clipboardSearchText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch()
                    true
                } else false
            }
        }
        setContentView(binding.root)
        inputMethodManager.showSoftInput(binding.clipboardSearchText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun doSearch() {
        ClipboardSearchResult.keyword = binding.clipboardSearchText.text.toString().trim()
        finish()
        // 搜索后重新展示剪切板窗口：列表变成搜索过滤后的结果（而非回到键盘输入状态）
        FcitxInputMethodService.instance?.showClipboardWindow()
    }

    override fun onStop() {
        super.onStop()
        // 用户按返回/切走也视为确认当前已输入内容，保证搜索尽量不丢
        // （点「取消」除外：清空关键词，回到全部）
        if (!cancelled) {
            ClipboardSearchResult.keyword = binding.clipboardSearchText.text.toString().trim()
        }
        // 无论何种方式关闭搜索窗口，都回到剪切板窗口（取消=全部，搜索=过滤结果）
        FcitxInputMethodService.instance?.showClipboardWindow()
    }
}