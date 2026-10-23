/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 外部化键盘布局配置（可自定义）。
 *
 * 结构：
 * - [rows]：每行是一个按键 id 列表（按键 id 即 [keys] 的 key），自左向右排列，行为在运行期逐行渲染。
 * - [keys]：按键 id -> 按键规格定义表。每种规格对应一种渲染外观与行为。
 *
 * 默认配置放在 assets 下；用户副本位于应用外部文件目录，支持设置页导入导出；
 * 运行期优先读取数据目录里的副本（可被用户直接编辑），读取失败则回退到内置原始布局。
 *
 * 支持的按键规格见 [KeySpec]。
 */
@Serializable
data class KeyboardLayoutConfig(
    /** 键盘行，每行自左向右对应一个按键 id。 */
    val rows: List<List<String>> = emptyList(),
    /** 按键 id -> 按键规格。 */
    val keys: Map<String, KeySpec> = emptyMap(),
)

/** 单个按键的规格定义。 */
@Serializable
sealed class KeySpec {

    /** 字母键（QWERTY 主键）：主字符 + 可选上滑符号和长按候选。对应 `AlphabetKey`（宽度固定 0.1f）。 */
    @Serializable
    @SerialName("letter")
    data class Letter(
        /** 显示与提交的字符（如 `Q`）。 */
        val char: String,
        /** 上滑/次级符号（如数字 `1`）。 */
        val alt: String? = null,
        /** 变体：normal / alt / alternative / accent。 */
        val variant: String = "normal",
        /** 额外长按候选；null 使用旧版 fallback，[] 表示无额外候选。 */
        val popup: List<String>? = null,
        /** 字母候选相对副符号列的偏移：-1 左邻，+1 右邻。 */
        @SerialName("popup_letter_offset")
        val popupLetterOffset: Int = AlphabetPopupCandidates.defaultLetterOffset(char),
        /** 整体 popup 横向偏移，单位为候选格宽。 */
        @SerialName("popup_shift_x")
        val popupShiftX: Int = 0,
    ) : KeySpec()

    /** 通用符号/文本键：按字面提交文本。对应 `SymbolKey`。 */
    @Serializable
    @SerialName("symbol")
    data class Symbol(
        val char: String,
        val width: Float = 0.1f,
        val variant: String = "normal",
    ) : KeySpec()

    /** 逗号键：带 Emoji/快捷短语/Unicode 弹出菜单。对应 `CommaKey`。 */
    @Serializable
    @SerialName("comma")
    data class Comma(
        val width: Float = 0.1f,
        val variant: String = "alternative",
        val char: String = ",",
    ) : KeySpec()

    /** 数字键盘键：提交系统数字/符号 keycode。对应 `NumPadKey`。 */
    @Serializable
    @SerialName("numpad")
    data class NumPad(
        val label: String,
        /** 符号键码的十六进制字符串（如 `0xffb1`），或数字字符 `0`-`9`。 */
        val code: String,
        val width: Float = 0.1f,
        val variant: String = "normal",
        /** 文本大小，单位 sp。 */
        @SerialName("text_size")
        val textSize: Float = 16f,
    ) : KeySpec()

    /** 大写切换键。对应 `CapsKey`。 */
    @Serializable
    @SerialName("caps")
    data object Caps : KeySpec()

    /** 分词/隔音符键（rime 拼音用 `'` 分隔 yu/an 等，防止误拼）。对应 `SeparatorKey`。 */
    @Serializable
    @SerialName("separator")
    data object Separator : KeySpec()

    /** 退格键。对应 `BackspaceKey`。 */
    @Serializable
    @SerialName("backspace")
    data class Backspace(
        val width: Float = 0.15f,
        val variant: String = "alternative",
    ) : KeySpec()

    /** 语言/输入法切换键。对应 `LanguageKey`。 */
    @Serializable
    @SerialName("lang")
    data object Lang : KeySpec()

    /** 空格键。对应 `SpaceKey`。 */
    @Serializable
    @SerialName("space")
    data object Space : KeySpec()

    /** 迷你空格键（数字键盘用）。对应 `MiniSpaceKey`。 */
    @Serializable
    @SerialName("mini_space")
    data object MiniSpace : KeySpec()

    /** 回车键。对应 `ReturnKey`。 */
    @Serializable
    @SerialName("return")
    data class Return(
        val width: Float = 0.15f,
    ) : KeySpec()

    /** 键盘切换键（如 `?123` / `ABC`）。对应 `LayoutSwitchKey`。 */
    @Serializable
    @SerialName("layout_switch")
    data class LayoutSwitch(
        val label: String,
        /** 目标键盘名；空串表示符号键盘（按记忆恢复）。 */
        val to: String = "",
        val width: Float = 0.15f,
        val variant: String = "alternative",
    ) : KeySpec()
}
