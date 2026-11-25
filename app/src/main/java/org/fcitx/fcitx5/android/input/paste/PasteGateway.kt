/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.paste

import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.plugin.PluginPipeline
import org.fcitx.fcitx5.android.plugin.PluginHost
import org.fcitx.fcitx5.android.plugin.EntryMeta
import org.fcitx.fcitx5.android.plugin.PasteContext
import org.fcitx.fcitx5.android.plugin.PasteSource
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/**
 * 粘贴网关：输入法进程内唯一门控 + 管线调度 + 目标校验 + 上屏。
 *
 * 门控顺序（写死）：管线无可用插件 → entry.sensitive → 密码框 → 超长(>64K)放行 →
 * Default 线程跑管线(≤150ms) → 主线程校验 ic/fieldId 未变 → commitPaste。
 * 任何失败路径都上屏原文。
 */
object PasteGateway {

    private const val MAX_TEXT = 64 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun paste(source: PasteSource, text: String, entry: ClipboardEntry?) {
        val service = currentService() ?: return
        // 无可用插件：直接原文上屏（管线根本不启动，零开销）
        if (PluginHost.activePastePlugins().isEmpty()) {
            service.commitPaste(text)
            return
        }
        // 门控：sensitive 条目 / 密码框 / 超长文本 不进管线
        if (entry?.sensitive == true) {
            service.commitPaste(text)
            return
        }
        if (text.length > MAX_TEXT) {
            service.commitPaste(text)
            return
        }
        val ic = service.currentInputConnection ?: run { service.commitPaste(text); return }
        val fieldId = service.currentInputEditorInfo?.fieldId ?: -1
        val flags = service.capabilityFlags()
        if (flags.has(CapabilityFlag.Password)) {
            service.commitPaste(text)
            return
        }
        val targetPackage: String? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            service.currentInputEditorInfo?.packageName
        } else null
        val ctx = PasteContext(
            source = source,
            targetPackage = targetPackage,
            isUrlField = flags.has(CapabilityFlag.Url),
            isEmailField = flags.has(CapabilityFlag.Email),
            entryMeta = entry?.let { EntryMeta(id = it.id, pinned = it.pinned, groupId = it.groupId, type = it.type, timestamp = it.timestamp) },
        )
        scope.launch {
            val plugins = PluginHost.activePastePlugins() // 快照：运行中启停不影响当次
            val outcome = PluginPipeline.process(plugins, ctx, text)
            val finalText = outcome.text
            // 单次生效消费：管线跑完，把所有标记「⚡单次」的设置项写回 false（下次粘贴自动关闭）
            // 宿主通用能力，不写死插件逻辑；标记由状态区快速切换菜单写入。
            plugins.map { it.manifest.id }
                .distinct()
                .forEach { pid -> PluginHost.consumeOnceModes(pid) }
            // 回主线程：校验目标未变再上屏
            mainHandler.post {
                val s = currentService()
                if (s == null || s.currentInputConnection !== ic ||
                    (s.currentInputEditorInfo?.fieldId ?: -1) != fieldId
                ) {
                    return@post // 输入框已切换：放弃（宁可不粘不错屏）
                }
                s.commitPaste(finalText)
            }
        }
    }

    // ---- service 获取桥 ----

    @Volatile private var serviceRef: FcitxInputMethodService? = null

    /** 由 FcitxInputMethodService onCreate/onStartInput 时登记 */
    fun attach(service: FcitxInputMethodService) {
        serviceRef = service
    }

    fun detach(service: FcitxInputMethodService) {
        if (serviceRef === service) serviceRef = null
    }

    private fun currentService(): FcitxInputMethodService? = serviceRef

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}