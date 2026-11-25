/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin
import android.content.Context
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.json.JSONArray
import org.json.JSONObject
/**
 * 宿主统一基础组件渲染器：把面板型脚本插件（action=panel, ui=frame）产出的
 * 声明式 UI 树 JSON 翻译成 View，并把点按项经 [commit] 回调上屏。
 *
 * 这是「宿主只开通用通道、UI 由插件自绘」的宿主侧一半：
 * - UI 树由插件脚本 renderPanel() 产出（见 ScriptPlugin.panelModel）；
 * - 本渲染器只实现一套极简、与所有面板通用的原语，不含任何艺术字/符号专用逻辑；
 * - 完全自由绘制（ui:"free"）需另行申请许可，本轮不开放。
 *
 * 支持的基础组件（v1）：
 *   {type:"label", text, size?}
 *   {type:"grid", cols?, cells:[{text, commit?}]}   // commit 缺省 = text；点按上屏 commit
 *   {type:"tabs", tabs:[{label, body:<node>}]}       // 多分类：上排标签 + 下方 body
 *
 * v1.1 新增：grid cell 支持 {image, commit}（贴纸包用）。
 *   - image：插件目录内图片文件名（png/jpg/webp/gif），渲染成缩略图格子；
 *   - 点按时 [commit] 收到 IMAGE_COMMIT_PREFIX + 绝对路径，由宿主窗口层分流到
 *     commitImage（图片直插）而非文本上屏；插件侧不用关心分流细节。
 */
class PluginPanelRenderer(
    private val ctx: Context,
    private val theme: Theme,
    private val commit: (String) -> Unit,
    /** 可选：点按前的展示预求值（把 {count}/{time} 等占位符渲染成可见文本，仅显示不改 commit） */
    private val preview: ((String) -> String)? = null,
    /** 可选：插件安装目录（贴纸包等图片格子用；null 则图片格子显示占位框） */
    private val pluginDir: String? = null,
) {
    private val cellHeightPx = dp(44)
    private val tabHeightPx = dp(40)

    /** 渲染整棵 UI 树；非法/空模型返回 null（调用方退化为空面板）。 */
    fun render(model: String): View? = runCatching {
        val node = JSONObject(model)
        val view = renderNode(node)
        // 顶层 grid/label 内容超高时包滚动（tabs 内部已处理，避免双重滚动）；
        // 这是宿主通用能力，任何插件超长列表都能滚。
        if (node.optString("type", "label") != "tabs") {
            ScrollView(ctx).apply {
                setBackgroundColor(theme.keyboardColor)
                addView(view)
            }
        } else {
            view
        }
    }.getOrNull()

    private fun renderNode(node: JSONObject): View {
        return when (node.optString("type", "label")) {
            "grid" -> renderGrid(node)
            "tabs" -> renderTabs(node)
            else -> renderLabel(node)
        }
    }

    private fun renderLabel(node: JSONObject): View {
        val tv = TextView(ctx)
        tv.text = node.optString("text", "")
        tv.textSize = node.optDouble("size", 17.0).toFloat()
        tv.setTextColor(theme.keyTextColor)
        // label 也铺统一浮层背景：避免与 grid/tabs 混排时颜色不一致
        tv.setBackgroundColor(theme.keyboardColor)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        return tv
    }

    private fun renderGrid(node: JSONObject): View {
        val cells = node.optJSONArray("cells") ?: JSONArray()
        val cols = node.optInt("cols", 6).coerceIn(1, 12)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyboardColor)
        }
        val rows = (cells.length() + cols - 1) / cols
        for (r in 0 until rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(theme.keyboardColor)
            }
            for (c in 0 until cols) {
                val idx = r * cols + c
                // 单列/长文本格子自适应行高：minHeight 保底，内容超高自动撑开（完整句子不被裁）
                val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    height = if (cols == 1) LinearLayout.LayoutParams.WRAP_CONTENT else cellHeightPx
                }
                if (idx >= cells.length()) {
                    val empty = TextView(ctx)
                    row.addView(empty, tp)
                    continue
                }
                val cell = cells.getJSONObject(idx)
                // v1.1 图片格子：贴纸包用。image=插件目录内文件名，渲染缩略图，
                // 点按提交 IMAGE_COMMIT_PREFIX + 绝对路径（宿主窗口层分流到 commitImage）。
                val imageName = cell.optString("image", "")
                if (imageName.isNotEmpty()) {
                    val iv = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        setOnClickListener { commit(IMAGE_COMMIT_PREFIX + imageName) }
                        // 后台加载图片失败也不影响布局（缩略图缺省显示空框）
                        if (pluginDir != null) {
                            val path = java.io.File(pluginDir, imageName)
                            if (path.exists()) {
                                // 主线程同步解码小图（面板已限尺寸）；大图解码慢的话考虑后续换异步
                                val bmp = BitmapFactory.decodeFile(path.absolutePath)
                                if (bmp != null) setImageBitmap(bmp)
                            }
                        }
                    }
                    row.addView(iv, tp)
                    continue
                }
                val text = cell.optString("text", "")
                val commitText = cell.optString("commit", text)
                // 展示文本：有 preview 回调则预求值（如 {count}→12），让用户一眼看到真实句子
                val displayText = preview?.invoke(text) ?: text
                val tv = TextView(ctx).apply {
                    this.text = displayText
                    textSize = if (cols == 1) 18f else 22f
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    setTextColor(theme.keyTextColor)
                    if (cols == 1) minHeight = cellHeightPx // 单列保底行高（View 属性，LayoutParams 上没有）
                    setOnClickListener { commit(commitText) }
                }
                row.addView(tv, tp)
            }
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return root
    }

    private fun renderTabs(node: JSONObject, embedBody: Boolean = false): View {
        val tabs = node.optJSONArray("tabs") ?: JSONArray()
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyboardColor)
        }
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.keyboardColor)
        }
        // 正文滚动容器：tabBar 固定顶部，body 超高时滚动（宿主通用能力）。
        // 嵌套（二级分类）时不包 ScrollView——由外层一级 tabs 的 bodyScroll 统一滚动，
        // 避免两级滚动容器嵌套导致滚动冲突。
        val bodyScroll = if (embedBody) null else ScrollView(ctx).apply {
            isFillViewport = false
            setBackgroundColor(theme.keyboardColor)
        }
        val bodyContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyboardColor)
        }
        bodyScroll?.addView(bodyContainer)
        val bodies = mutableListOf<View>()
        val tabViews = mutableListOf<TextView>()
        for (i in 0 until tabs.length()) {
            val t = tabs.getJSONObject(i)
            val label = t.optString("label", "")
            val body = renderBody(t.optJSONObject("body") ?: JSONObject())
            body.visibility = if (i == 0) View.VISIBLE else View.GONE
            bodies.add(body)
            bodyContainer.addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            val idx = i
            val tabTv = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setOnClickListener {
                    bodies.forEachIndexed { j, b -> b.visibility = if (j == idx) View.VISIBLE else View.GONE }
                    // 当前 tab 高亮（激活色背景 + 主色文字），其余回退；一眼看出所在分区
                    tabViews.forEachIndexed { j, tv ->
                        if (j == idx) {
                            tv.setTextColor(theme.genericActiveForegroundColor)
                            tv.setBackgroundColor(theme.genericActiveBackgroundColor)
                        } else {
                            tv.setTextColor(theme.keyTextColor)
                            tv.setBackgroundColor(theme.keyboardColor)
                        }
                    }
                }
                // 初始：第一个 tab 高亮
                if (i == 0) {
                    setTextColor(theme.genericActiveForegroundColor)
                    setBackgroundColor(theme.genericActiveBackgroundColor)
                } else {
                    setTextColor(theme.keyTextColor)
                    setBackgroundColor(theme.keyboardColor)
                }
            }
            tabViews.add(tabTv)
            val tp = with(LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)) { weight = 1f; this }
            tabBar.addView(tabTv, tp)
        }
        outer.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, tabHeightPx
        ))
        // body 整体放入滚动容器（tabBar 固定，正文超高可滚）；嵌套时直接放 outer
        if (bodyScroll != null) {
            outer.addView(bodyScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        } else {
            outer.addView(bodyContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return outer
    }

    /** tabs 的 body 支持 label/grid 顶层；嵌套 tabs（二级分类）也支持，避免过深时仍可展开。 */
    private fun renderBody(node: JSONObject): View {
        return when (node.optString("type", "label")) {
            "grid" -> renderGrid(node)
            "tabs" -> renderTabs(node, embedBody = true)
            else -> renderLabel(node)
        }
    }
    private fun dp(value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /**
         * 点按图片格子的 commit 前缀：IMAGE_COMMIT_PREFIX + 图片文件名。
         * 渲染器透传文件名，窗口层拼出插件目录绝对路径并分流到 commitImage（图片直插）。
         */
        const val IMAGE_COMMIT_PREFIX = "\uE001IMAGE\uE001"
    }
}