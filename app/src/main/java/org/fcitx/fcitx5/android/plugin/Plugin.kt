/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

/**
 * 剪贴板插件契约层 v1。
 *
 * 设计约定（改动前必读）：
 * - 只加字段（带默认值）/ 只加新接口，不改已有签名与字段语义；
 * - 契约类型一律纯 Kotlin（零 Android 依赖），保证可 JVM 单测、可 JSON 序列化给脚本层；
 * - hook = 小接口；插件按 manifest.hooks 声明能力，管线路由 filterIsInstance；
 * - 任何新 hook（PanelRender/CommitObserve）在此补小接口即可，老插件零改动。
 */

/** 插件唯一根接口：manifest 必须实现，能力按 hook 分接口实现 */
interface Plugin {
    val manifest: PluginManifest
}

/** 一期实现的能力接口：粘贴管线（Dispatchers.Default 上调用） */
interface PasteTransformHook {
    /** 返回 NotHandled 则原文传递；抛异常/超时由管线兜底，插件无需自我防御超时 */
    suspend fun onPaste(ctx: PasteContext, text: String): PasteResult
}

/** 二期预留：采集入库前改写（与 AIDL IClipboardEntryTransformer 同位，进程内版） */
interface CaptureTransformHook {
    suspend fun onCapture(ctx: CaptureContext, text: String): PasteResult
}

/** 三期预留：面板条目渲染定制 */
interface PanelRenderHook

/** 三期预留：上屏观察（只读，给统计类插件） */
interface CommitObserveHook

/** Hook 枚举：minApiVersion 表示承载该 hook 需要的最低插件 API 版本 */
enum class PluginHook(val minApiVersion: Int) {
    PasteTransform(1),
    CaptureTransform(1),
    PanelRender(2),
    CommitObserve(2),
}
