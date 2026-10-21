/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng for Android Contributors
 */
package org.fcitx.fcitx5.android.input.calculator

import java.math.BigDecimal
import java.math.RoundingMode

/** 全局共享的计算器引擎实例（按键分发喂入、候选栏读取展示）。 */
object CalculatorEngineHolder {
    val engine = CalculatorEngine()
}

/**
 * 「梦」数字键盘计算器引擎（纯 Kotlin，零 native 依赖）。
 *
 * 设计取向（方案 B）：数字/运算符照常上屏，引擎**旁路追踪**用户敲入的
 * 表达式，在候选栏给出两个候选——① 完整算式（如 `3+3=`）② 答案（如 `6`）；
 * 点选答案即把结果上屏并**结束本次计算**（不会重复追加）。
 *
 * 状态机：只累积原始输入 `rawInput`（数字、运算符、小数点、可选的末尾 `=`），
 * 计算时对 rawInput 求值。支持 + - * / 与括号、优先级、小数点，用
 * [BigDecimal] 保证精度（避免 0.3/0.1=2.999…）。
 */
class CalculatorEngine {

    /** 用户原始输入串，如 `1+1`、`44-70+35-70`、`3+3=`。 */
    private var rawInput = ""

    /** true 表示本次计算已完成（点选答案上屏后），下一次按键开启新一轮。 */
    private var finished = false

    /** 追加一个字符（数字/运算符/小数点/等号）。返回是否可展示计算器候选。 */
    fun handle(char: Char): Boolean {
        // 已完成的一次计算：新输入开启新一轮
        if (finished) {
            rawInput = ""
            finished = false
        }
        if (char.isDigit() || char == '.' ||
            char == '+' || char == '-' || char == '*' || char == '/' ||
            char == '(' || char == ')' || char == '='
        ) {
            // 已输入 '=' 后继续输入数字，视为新一轮（等号后重建）
            if (rawInput.endsWith('=')) rawInput = ""
            rawInput += char
        }
        return isActive()
    }

    /** 退格：删掉最后一个字符。返回是否仍处于计算器活动态。 */
    fun handleDelete(): Boolean {
        if (rawInput.isNotEmpty()) rawInput = rawInput.dropLast(1)
        if (rawInput.isEmpty()) finished = false
        return isActive()
    }

    fun clear() {
        rawInput = ""
        finished = false
    }

    /** 点选答案上屏后调用：结束本次计算，下次按键开启新一轮。 */
    fun finish() {
        rawInput = ""
        finished = true
    }

    /** 是否已累积了可求值的表达式（至少含一个数字和一个运算符）。 */
    fun isActive(): Boolean =
        rawInput.isNotEmpty() && rawInput.any { it.isDigit() } &&
            rawInput.any { it in "+-*/" }

    /** 原始输入是否以 `=` 结尾（用户已敲等号）。 */
    fun expressionEndsWithEqual(): Boolean = rawInput.endsWith('=')

    /** 候选①完整算式（含末尾 `=`）；无活跃式返回 null。 */
    fun expressionText(): String? =
        if (isActive()) rawInput else null

    /** 候选②答案（纯数字，无括号无等号前缀）；无法求值返回 null。 */
    fun result(): String? {
        val expr = rawInput.removeSuffix("=")
        if (expr.isEmpty() || !expr.any { it.isDigit() }) return null
        return evaluate(expr)
    }

    /** 完整原始表达式（供调试）。 */
    fun expression(): String = rawInput

    companion object {
        /** 数字键盘 KP 码点 → 计算器字符。非计算器键返回 null。 */
        fun charFromSym(sym: Int): Char? = when (sym) {
            0xffaa -> '*'
            0xffab -> '+'
            0xffac -> ','      // KP 逗号（当作普通标点，不参与计算）
            0xffad -> '-'
            0xffae -> '.'
            0xffaf -> '/'
            0xffb0 -> '0'
            0xffb1 -> '1'
            0xffb2 -> '2'
            0xffb3 -> '3'
            0xffb4 -> '4'
            0xffb5 -> '5'
            0xffb6 -> '6'
            0xffb7 -> '7'
            0xffb8 -> '8'
            0xffb9 -> '9'
            0xffbd -> '='
            else -> null
        }
    }

    /**
     * 表达式求值（递归下降）。支持 + - * / 与括号、优先级、小数点。
     * 用 BigDecimal 保证精度；无限小数精确到 10 位显示。
     */
    private fun evaluate(expression: String): String? {
        if (expression.isEmpty() || expression.any { it.isWhitespace() }) return null
        // 仅允许合法字符
        if (expression.any { ch ->
                ch.isDigit() || ch == '.' || ch == '+' || ch == '-' ||
                    ch == '*' || ch == '/' || ch == '(' || ch == ')'
            }.not()) return null
        // 连续运算符（非括号、非首字符减号）视为非法
        if (Regex("[+\\-*/]{2,}").containsMatchIn(expression.replace("(-", "("))) return null
        return try {
            val parser = Parser(expression)
            val value = parser.parseExpression()
            if (!parser.atEnd()) return null
            // BigDecimal 结果始终有限（除零在 parser 中已拦截抛出）
            formatBigDecimal(value.stripTrailingZeros())
        } catch (_: Exception) {
            null
        }
    }

    /** 整数去 .0，小数最多 10 位。 */
    private fun formatBigDecimal(value: BigDecimal): String {
        if (value.scale() <= 0) return value.toPlainString()
        val rounded = value.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        if (rounded.scale() <= 0) return rounded.toPlainString()
        return rounded.toPlainString()
    }

    /** 递归下降表达式解析器（BigDecimal）。 */
    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; value = value.add(parseTerm()) }
                    '-' -> { pos++; value = value.subtract(parseTerm()) }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): BigDecimal {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; value = value.multiply(parseFactor()) }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("div by zero")
                        value = try {
                            value.divide(divisor)
                        } catch (_: ArithmeticException) {
                            value.divide(divisor, 10, RoundingMode.HALF_UP)
                        }
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): BigDecimal {
            var sign = 1
            while (peek() == '+' || peek() == '-') {
                if (peek() == '-') sign = -sign
                pos++
            }
            var value: BigDecimal
            if (peek() == '(') {
                pos++
                value = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                pos++
            } else {
                value = parseNumber()
            }
            return if (sign < 0) value.negate() else value
        }

        private fun parseNumber(): BigDecimal {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("expected number")
            return s.substring(start, pos).toBigDecimalOrNull()
                ?: throw IllegalArgumentException("bad number")
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
    }
}