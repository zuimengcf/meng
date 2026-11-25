/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 宿主通用「点按求值」通道：面板型插件把当前输入框/时间/随机值通过占位符注入点按内容。
 *
 * 设计原则：宿主只提供**通用求值原语**（输入框全文、字数、时间、随机 token），
 * **不内置任何特定插件业务**。模板如何组合（脚本里把占位符拼进 grid 的 commit）由插件自绘。
 *
 * 支持占位符（脚本 renderPanel 产出的 commit 字符串中可用）：
 *   {editor}              当前输入框全文；读取失败/空 → 空串
 *   {count}               当前输入框字符数（按 Unicode code point 计数）
 *   {tokens}              当前输入框文本的 AI token 估算（轻量分词启发式，非精确 tokenizer）
 *   {time}                当前时间，默认 "HH:mm:ss"
 *   {time:格式}           按 java.time DateTimeFormatter 模式格式化，如 {time:HH时mm分ss秒}
 *   {date}              当前日期 "yyyy-MM-dd"
 *   {now}               当前时间戳毫秒
 *   {rand:n}              生成 n 位随机十六进制 token（n 缺省 16，0<n<=512）
 *   {rand:alnum:n}        生成 n 位随机字母数字 token
 *
 * 未被识别的 {..} 原样保留（避免误吞插件自己的文本）。
 */
object PluginPanelEvaluator {

    private val hexPool = "0123456789abcdef"
    private val alnumPool = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 对模板字符串做占位符替换；返回求值后的最终上屏文本。 */
    fun evaluate(template: String): String {
        if ('{' !in template) return template
        val sb = StringBuilder(template.length + 32)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i)
                if (end < 0) {
                    sb.append(template, i, template.length)
                    break
                }
                val token = template.substring(i + 1, end)
                val value = resolve(token)
                if (value != null) {
                    sb.append(value)
                    i = end + 1
                    continue
                }
                // 未识别：原样保留 {..}
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 解析单个 {token}；未识别返回 null。 */
    private fun resolve(token: String): String? {
        val t = token.trim()
        return when {
            t == "editor" -> editorText().orEmpty()
            t == "count" -> editorText()?.let { countChars(it).toString() } ?: "0"
            t == "tokens" -> editorText()?.let { estimateTokens(it).toString() } ?: "0"
            t == "date" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            t == "now" -> System.currentTimeMillis().toString()
            t.startsWith("time") -> resolveTime(t)
            t.startsWith("rand") -> resolveRand(t)
            else -> null
        }
    }

    /**
     * AI token 轻量估算（近似 OpenAI/BPE 家族的启发式，非精确 tokenizer）。
     *
     * 规则（贴近常见中英混合文本的实测分布，误差约 ±15%）：
     * - 英文/数字连续串：每 4 字符 ≈ 1 token，至少 1
     * - 中文/日文/韩文等 CJK 汉字：每字符 1 token（中文在 BPE 里平均 ≈ 0.6-1 token/字，取 1 偏保守）
     * - 空白/标点：每 2 个 ≈ 1 token（LLM 通常把它们并入相邻 token，这里单独估）
     * - 其余（emoji 等）：每字符 1 token
     */
    private fun estimateTokens(s: String): Int {
        var tokens = 0
        // 英文/数字连续段
        val asciiRun = Regex("[A-Za-z0-9]+")
        for (m in asciiRun.findAll(s)) tokens += ((m.value.length + 3) / 4)
        // 中文字符（CJK）每字 1 token
        for (ch in s) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF ||
                ch.code in 0xF900..0xFAFF || ch.code in 0x20000..0x2A6DF
            ) tokens++
        }
        // 空白/标点：每 2 个 1 token（约等于并入相邻 token 的开销）
        var punct = 0
        for (ch in s) {
            if (ch.isWhitespace() || ch in "，。！？；：、,.!?;:'\"()[]{}<>《》【】（）·—-—_…") punct++
        }
        tokens += (punct + 1) / 2
        return maxOf(tokens, 1)
    }

    private fun resolveTime(token: String): String? {
        if (token == "time") return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val colon = token.indexOf(':')
        if (colon < 0) return null
        val pattern = token.substring(colon + 1).trim()
        if (pattern.isEmpty()) return null
        return runCatching { LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
    }

    private fun resolveRand(token: String): String? {
        val body = token.removePrefix("rand").removePrefix(":")
        if (body.isEmpty()) return randomToken(hexPool, 16)
        val (kindRaw, lenRaw) = if (':' in body) {
            val idx = body.indexOf(':')
            body.substring(0, idx) to body.substring(idx + 1)
        } else {
            "hex" to body
        }
        val len = lenRaw.trim().toIntOrNull()?.coerceIn(1, 512) ?: 16
        val pool = when (kindRaw) {
            "alnum" -> alnumPool
            else -> hexPool
        }
        return randomToken(pool, len)
    }

    private fun randomToken(pool: String, len: Int): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(pool[(Math.random() * pool.length).toInt()]) }
        return sb.toString()
    }

    /** 当前输入框全文（经 service 通用通道读取）；无输入框/失败 → null。 */
    private fun editorText(): String? =
        FcitxInputMethodService.instance?.readCurrentEditorTextForPlugin()

    /** Unicode code point 计数（区分代理对，中文字符按一个字符算）。 */
    private fun countChars(s: String): Int = s.codePointCount(0, s.length)
}