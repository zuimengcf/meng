/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.plugin.PluginHost
import org.fcitx.fcitx5.android.plugin.PluginPanelRenderer
import org.mechdancer.dependency.manager.must

/**
 * 宿主通用面板窗口：为 action=panel 的脚本插件打开一个面板。
 *
 * 「宿主只开通道、UI 由插件自绘」的窗口侧：
 * - 面板的 UI 树由插件脚本 renderPanel() 产出（ScriptPlugin.panelModel），
 *   宿主**不内置任何艺术字/特殊符号逻辑**；
 * - 本窗口用 [PluginPanelRenderer] 把 UI 树翻译成 View，
 *   点按格子经 [CommonKeyActionListener] 以 CommitAction 上屏；
 * - 词典类插件（dict.txt 高达 53MB）搜索在后台线程执行，避免主线程卡顿/OOM，
 *   先显示「搜索中…」，完成后主线程替换内容。
 * - 非 essential 窗口，可随时 attachWindow(实例) 打开。
 *
 * [pluginId] 用于定位插件目录与面板模型；[windowManager.attachWindow(this)]
 * 由调用方（更多面板的插件入口）触发。
 */
class PluginPanelWindow(
    private val pluginId: String,
) : InputWindow.ExtendedInputWindow<PluginPanelWindow>() {

    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override val showTitle = false
    override val title: String = ""
    override fun enterAnimation(lastWindow: InputWindow): Transition? = null
    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    override fun onCreateView(): android.view.View {
        // 先渲染「搜索中…」占位，词典搜索/脚本渲染在后台线程完成
        val container = FrameLayout(context)
        container.addView(TextView(context).apply {
            text = "搜索中…"
            setTextColor(theme.keyTextColor)
            gravity = android.view.Gravity.CENTER
        })
        loadPanelAsync(container)
        return container
    }

    private fun loadPanelAsync(container: FrameLayout) {
        // 词典类面板：用当前输入框全文作为查询词（主线程只读一次输入框，很快）
        val query = FcitxInputMethodService.instance?.readCurrentEditorTextForPlugin()
        scope.launch {
            // 后台线程：宿主侧词典搜索 + 脚本 renderPanel（53MB dict 流式扫描，不碰主线程）
            val model = withContext(Dispatchers.Default) {
                PluginHost.panelModelOf(pluginId, query)
            }
            val rendered: android.view.View = if (model == null || model.isBlank()) {
                // 面板模型缺失：给出可读提示而非静默空面板（脚本 renderPanel 失败/超时/未定义）
                TextView(context).apply {
                    text = "面板渲染失败"
                    setTextColor(theme.keyTextColor)
                    gravity = android.view.Gravity.CENTER
                }
            } else {
                // 插件安装目录：贴纸包等图片格子在此目录下找图（渲染器拼接绝对路径）
                val pluginDir = java.io.File(
                    context.filesDir, "plugins/$pluginId"
                ).takeIf { it.isDirectory }?.absolutePath
                val renderer = PluginPanelRenderer(context, theme,
                    commit = { raw ->
                        // 通用导出通道：EXPORT 前缀 + JSON 参数（任意插件可用，非 text-to-file 专属）
                        if (raw.startsWith(EXPORT_PREFIX)) {
                            exportVia(raw.removePrefix(EXPORT_PREFIX))
                            return@PluginPanelRenderer
                        }
                        // 图片格子：IMAGE 前缀 → 拼插件目录绝对路径 → commitImage 图片直插
                        if (raw.startsWith(PluginPanelRenderer.IMAGE_COMMIT_PREFIX)) {
                            val imageName = raw.removePrefix(PluginPanelRenderer.IMAGE_COMMIT_PREFIX)
                            // 路径穿越防护：文件名不允许带路径分隔符
                            if (imageName.contains('/') || imageName.contains('\\') ||
                                imageName.contains("..")) {
                                return@PluginPanelRenderer
                            }
                            val absPath = java.io.File(
                                java.io.File(context.filesDir, "plugins/$pluginId"), imageName
                            )
                            val ok = FcitxInputMethodService.instance?.commitImage(
                                absPath.absolutePath, ""
                            ) == true
                            if (!ok) {
                                // 图片直插失败（目标不支持）：按插件设置决定是否弹系统分享面板
                                val shareFallback = PluginHost.settingOf(pluginId, "shareFallback", "true") == "true"
                                if (!shareFallback) {
                                    android.widget.Toast.makeText(
                                        context, R.string.plugin_image_unsupported, Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    try {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", absPath
                                        )
                                        // 按扩展名给精确 MIME：接收方识别为原格式，避免泛型 image/* 触发转码
                                        val realMime = when (absPath.extension.lowercase()) {
                                            "png" -> "image/png"
                                            "gif" -> "image/gif"
                                            "webp" -> "image/webp"
                                            "jpg", "jpeg" -> "image/jpeg"
                                            else -> "image/*"
                                        }
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = realMime
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(intent, absPath.name)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context, R.string.plugin_image_unsupported, Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        } else {
                            // 通用点按求值：先解析宿主占位符（{count}/{time}/{editor}/{rand}/{tokens}...）
                            // 再经通用按键监听上屏，与符号/emoji 面板同一提交通道。
                            val text = PluginPanelEvaluator.evaluate(raw)
                            if (text.startsWith(REPLACE_PREFIX)) {
                                // 完整替换输入框全文（词典点按场景：避免"插入"导致词条重复）
                                val replacement = text.removePrefix(REPLACE_PREFIX)
                                val ok = FcitxInputMethodService.instance?.replaceCurrentEditorTextForPlugin(replacement) == true
                                if (!ok) {
                                    // 替换失败（无全文/敏感框）：降级为普通插入
                                    commonKeyActionListener.listener.onKeyAction(
                                        KeyAction.CommitAction(replacement), KeyActionListener.Source.Keyboard
                                    )
                                }
                            } else {
                                commonKeyActionListener.listener.onKeyAction(
                                    KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard
                                )
                            }
                        }
                    },
                    preview = { raw ->
                        // 展示预求值：格子里直接显示真实短语（如 {count}→12），
                        // 仅用于显示，点按仍提交原始 commit 模板
                        PluginPanelEvaluator.evaluate(raw)
                    },
                    pluginDir = pluginDir,
                )
                renderer.render(model)
                    ?: TextView(context).apply {
                        text = "面板内容无效"
                        setTextColor(theme.keyTextColor)
                        gravity = android.view.Gravity.CENTER
                    }
            }
            container.removeAllViews()
            container.addView(rendered)
        }
    }

    override fun onAttached() {
    }

    override fun onDetached() {
    }

    /** 通用文本导出通道：任意插件经 EXPORT_PREFIX + JSON 调用。
     *  JSON 参数：{"format":"txt|md|html", "name?": "可选文件名（不含扩展名）"}
     *  读输入框全文（空则系统剪贴板最新文本），写 files/documents/ 并弹系统分享。 */
    private fun exportVia(jsonParams: String) {
        // 解析 JSON 参数：{"format":"txt|md|html", "name?":"可选文件名"}
        val parsed = runCatching {
            val o = org.json.JSONObject(jsonParams)
            o.optString("format", "txt").lowercase() to o.optString("name", "")
        }.getOrDefault("txt" to "")
        val format = parsed.first
        val name = parsed.second.takeIf { it.isNotBlank() }
        // 输入框优先；空则读系统剪贴板最新文本（复制内容常为待导出源）
        val source = FcitxInputMethodService.instance?.readCurrentEditorTextForPlugin()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
            ?: run {
                Toast.makeText(context, R.string.plugin_text_to_file_empty, Toast.LENGTH_SHORT).show()
                return
            }
        // 扩展名 + MIME + 文件名前缀
        val (ext, mime, defPrefix) = when (format) {
            "md" -> Triple("md", "text/markdown", "笔记")
            "html" -> Triple("html", "text/html", "页面")
            else -> Triple("txt", "text/plain", "文本")
        }
        val dir = java.io.File(context.filesDir, "documents").apply { mkdirs() }
        val base = (name?.takeIf { it.isNotBlank() } ?: defPrefix)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") // 文件名非法字符清洗
            .take(60)
        val file = java.io.File(dir, "${base}_${System.currentTimeMillis()}.$ext")
        // 内容按格式包装（html 套最小文档骨架，md/txt 原文）
        val content = when (format) {
            "html" -> "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"></head>\n<body>\n${source}\n</body>\n</html>\n"
            else -> source
        }
        runCatching { file.writeText(content, Charsets.UTF_8) }
            .onFailure {
                Toast.makeText(context, R.string.plugin_text_to_file_failed, Toast.LENGTH_SHORT).show()
                return
            }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, file.name)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            file.delete()
            Toast.makeText(context, R.string.plugin_text_to_file_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** 点按 commit 前缀：以它开头的点按 = 完整替换输入框全文（词典词条点按用） */
        const val REPLACE_PREFIX = "\uE000REPLACE\uE000"
        /** 点按 commit 前缀：以它开头 = 通用导出（后面跟 JSON 参数，任意插件可用） */
        const val EXPORT_PREFIX = "\uE000EXPORT\uE000"
    }
}