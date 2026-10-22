/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import org.fcitx.fcitx5.android.core.InputMethodEntry

/** 中文 Text 与英文 English 独立实例、独立 JSON；切模式只切键盘，不改写配置。 */
object TextKeyboardLayouts {
    fun isText(name: String): Boolean = name == "Text" || name == "English"

    fun forInputMethod(ime: InputMethodEntry): String = if (KeyboardPunctuationMode.isChinese(
        ime.languageCode, ime.addon, ime.uniqueName,
        ime.subMode.icon, ime.subMode.label, ime.subMode.name, ime.icon
    )) "Text" else "English"

    /** Text/ABC 是逻辑文字键盘返回入口，数字与符号页不跟随中英文模式跳走。 */
    fun resolveTarget(requested: String, activeText: String): String =
        if (isText(requested)) activeText else requested
}
