/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng for Android Contributors
 */
package org.fcitx.fcitx5.android.input.calculator

/** 只跟踪当前非敏感输入框的连续数字键盘输入；不拦截实际按键上屏。 */
class CalculatorSession(private val engine: CalculatorEngine) {
    private var enabled = false

    fun startInput(allowed: Boolean) {
        clear()
        enabled = allowed
    }

    fun endInput() {
        clear()
        enabled = false
    }

    fun handleSym(sym: Int) {
        if (!enabled) {
            clear()
            return
        }
        when (sym) {
            0xff08 -> engine.handleDelete() // 纯数字阶段也必须同步退格
            else -> {
                val char = CalculatorEngine.charFromSym(sym)
                if (char == null) clear() else engine.handle(char)
            }
        }
    }

    /** 输入边界（布局切换、外部光标移动、粘贴等），包括尚无运算符的输入。 */
    fun clear() = engine.clear()
}
