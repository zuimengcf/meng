/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

/**
 * 插件 Manifest 与状态机。
 */

data class PluginManifest(
    /** 全局唯一 id（"url-purifier"），冲突拒载 */
    val id: String,
    val name: String,
    val version: Int,
    /** 版本号原始字符串（插件 manifest 里写的，如 "1.1.111"）；纯数字时与 version 数值一致 */
    val versionText: String = "",
    /** 当前插件 API 版本；高于宿主支持版本 → Incompatible 拒载（不崩溃） */
    val apiVersion: Int = 1,
    /** 越大越先执行；相同按注册序。内建建议 100 档 */
    val priority: Int = 100,
    val hooks: List<PluginHook> = listOf(PluginHook.PasteTransform),
    /** 插件类型：data=规则/数据插件（.meng 包内 rules.json，一期）；script=脚本（二期 QuickJS）；code=原生（三期） */
    val type: String = "data",
    /** 粘贴变换动作：clean=URL净化（默认）；extract=链接提取；annotate=拼音标注；panel=自绘面板 */
    val action: String = "clean",
    /**
     * 面板渲染方式（仅 action=panel 有效）：
     * "frame" = 宿主统一基础组件（label/grid/tabs），默认，所有面板通用；
     * "free"  = 完全自由绘制，需另行申请许可（本轮不开放，宿主直接拒绝渲染）。
     *
     * ⚠️ panel 型插件应显式声明 hooks:[]（而非 PasteTransform），
     * 否则会被粘贴管线当成粘贴变换插件去执行 onPaste ——
     * 若坚持默认 PasteTransform，脚本实现必须实现 transform 并返回原文。
     */
    val ui: String = "frame",
    /** 插件注册的菜单项（详情页展示）：如自定义规则列表 */
    val menus: List<PluginMenu> = emptyList(),
    /** 插件自定义设置项（详情页展示+宿主存储，运行时注入插件逻辑） */
    val settings: List<PluginSetting> = emptyList(),
    /**
     * 插件图标（可选）：宿主内置 drawable 名称（如 "text_format"、"language"）。
     * 未声明或未知名称 → 宿主回退默认扩展图标。UI 侧按此映射 R.drawable。
     */
    val icon: String? = null,
    /**
     * 词典搜索能力声明（仅带 dict_*.txt 的插件有效，如本地词典）：
     *   defSearch = true → 宿主搜索时把释义列也纳入匹配（输入释义关键词反查词条）。
     * 宿主只提供通用能力，具体搜索行为由各插件自行声明，避免宿主写死业务逻辑。
     */
    val dictDefSearch: Boolean = false,
)

/**
 * 插件注册的菜单项：详情页按此渲染入口
 *
 * 语言包全部由插件注册（title/editorTitle/hint），宿主只做通用渲染，不特判菜单 id。
 * 菜单 + 存储槽位（宿主为插件提供键值/行列表存储）：
 *   lines 行列表编辑器（每行一项，存 customRules）
 *   kv    键值编辑器（每行 key=value，存 customMirror）
 */
data class PluginMenu(
    /** 菜单唯一 id（插件内），如 "custom-rules" */
    val id: String,
    /** 菜单标题（插件注册的语言包） */
    val title: String,
    /** 旧类型兼容：text=点击打开编辑器；toggle=布尔开关项 */
    val type: String = "text",
    /** 编辑语义：lines=行列表；kv=键值映射；null=不可编辑（仅 toggle 或占位） */
    val editType: String? = null,
    /** 编辑器对话框标题（插件注册，缺省用 title） */
    val editorTitle: String? = null,
    /** 编辑器输入框提示（插件注册的语言包） */
    val hint: String? = null,
    /** type=toggle 时的默认值 */
    val defaultValue: Boolean = false,
)

/** 插件自定义设置项：插件自带设置，宿主存储其值，运行时注入插件 */
data class PluginSetting(
    /** 设置键（插件内唯一），如 "tone" */
    val key: String,
    /** 显示名，如 "标注声调" */
    val label: String,
    /** 设置类型：switch=布尔开关；select=单选；text=文本 */
    val type: String = "switch",
    /** select 的候选项 */
    val options: List<String> = emptyList(),
    /** 默认值（字符串存储：switch 为 "true"/"false"；select/text 为字符串） */
    val defaultValue: String = "false",
    /** 是否出现在状态区「快速切换」菜单（仅 type=switch 有效） */
    val quick: Boolean = false,
)

enum class PluginState {
    Enabled,          // 运行中
    Disabled,         // 用户关闭
    Tripped,          // 熔断：连续失败 ≥ 3，等手动重置
    Incompatible,     // apiVersion 不符，只读展示
}

/** 运行时统计：熔断依据 + 设置页展示 */
data class PluginRuntimeStats(
    val calls: Long = 0,
    val failures: Long = 0,
    val consecutiveFailures: Int = 0,
    val totalMillis: Long = 0,
    val lastError: String? = null,
)

/** UI 只读快照（不可变）：热更新安全，运行中启停不影响当次管线执行 */
data class PluginInfo(
    val manifest: PluginManifest,
    val state: PluginState,
    val stats: PluginRuntimeStats,
    val source: PluginSourceKind,
)
enum class PluginSourceKind { External, Script, Aidl }
/** 插件来源抽象：一期 ExternalSource（.meng 独立安装包）；二期 ScriptSource；三期 AidlSource */
interface PluginSource {
    val kind: PluginSourceKind
    /** 发现期（启动/重载时）调用；Host 校验 manifest 后纳管 */
    fun discover(): List<Plugin>
}
/** 当前宿主支持的插件 API 版本（与 PluginHook.minApiVersion 对齐递增） */
const val PLUGIN_API_VERSION = 1