/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

/**
 * 剪切板搜索关键词的进程内中转站。
 *
 * 剪切板窗口（输入法内嵌 View）没有 Activity 生命周期，无法用 startActivityForResult
 * 接收返回结果；因此搜索关键词通过本静态对象在「搜索输入 Activity」与「剪切板窗口」之间传递。
 */
object ClipboardSearchResult {
    /** 当前搜索关键词（空串 = 不搜索）。由 [ClipboardSearchActivity] 写入、剪切板窗口读取。 */
    @Volatile
    var keyword: String = ""
}
