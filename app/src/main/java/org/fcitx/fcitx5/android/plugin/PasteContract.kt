/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

/**
 * 管线数据契约。演进约定：只加字段（带默认值），不改已有字段语义。
 * 注意：sensitive 条目由 PasteGateway 门控拦截，根本不会进管线——契约里
 * 不存在 sensitive 字段是刻意设计，不要往 ctx 里补。
 */

enum class PasteSource { Panel, Suggestion, EditButton, HwCtrlV }

data class PasteContext(
    val source: PasteSource,
    val targetPackage: String? = null,
    val isUrlField: Boolean = false,
    val isEmailField: Boolean = false,
    val isMultiline: Boolean = false,
    /** 条目元数据（不含文本本体）；非历史条目（如直接读系统剪贴板）为 null */
    val entryMeta: EntryMeta? = null,
)

/** 二期 CaptureTransformHook 的上下文（一期仅定型占位） */
data class CaptureContext(
    val targetPackage: String? = null,
)

/** 历史条目元数据：字段名与 ClipboardEntry 对齐，仅取渲染/统计所需 */
data class EntryMeta(
    val id: Int,
    val pinned: Boolean = false,
    val groupId: Int = 0,
    val type: String = "text/plain",
    val timestamp: Long = 0L,
)

sealed class PasteResult {
    /**
     * 改写文本。transformedBy 由管线自动维护（记录改动链），插件实现不要手填。
     */
    data class Text(val text: String) : PasteResult()
    object NotHandled : PasteResult()
}
