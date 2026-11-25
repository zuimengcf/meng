/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

import java.io.File

/**
 * 外部数据插件：从 files/plugins/<id>/ 目录加载的 .meng 独立安装插件。
 *
 * 目录结构（.meng 安装包解包产物）：
 *   files/plugins/<id>/manifest.json   插件声明（id/name/version/action/settings/menus...）
 *   files/plugins/<id>/rules.json      action=clean：URL 追踪参数规则
 *   files/plugins/<id>/pinyin.json     action=annotate：汉字→拼音表（可选，键=汉字，值=拼音）
 *
 * 单实例：同 id 同时只有一个（版本升级 = 覆盖目录文件）。
 *
 * 三种粘贴变换动作（manifest.action）：
 * - clean    URL 追踪参数净化（默认，与 rules.json 配合）
 * - extract  链接提取：从混有 emoji/汉字/干扰符的文本中提取纯链接
 * - annotate 拼音标注：开启状态下给粘贴文本中的汉字自动注音（字(拼音)），朗读/分享更友好
 */
class DataPlugin private constructor(
    override val manifest: PluginManifest,
    private val rules: TrackerRules?,
    private val pinyinTable: Map<String, String>?,
) : Plugin, PasteTransformHook {

    override suspend fun onPaste(ctx: PasteContext, text: String): PasteResult {
        return when (manifest.action) {
            "extract" -> extractLinks(text)
            "annotate" -> annotatePinyin(text)
            else -> cleanUrls(text)
        }
    }

    // ===== action=clean：URL 追踪参数净化 =====
    private fun cleanUrls(text: String): PasteResult {
        // 快速预检：无 URL 迹象直接跳过（零开销）
        if ("http" !in text) return PasteResult.NotHandled
        val r = rules ?: return PasteResult.NotHandled
        // 合并用户自定义规则（插件菜单「自定义规则」中追加的参数）
        val custom = PluginHost.customRules(manifest.id)
        // preserveFragment=false 时连 # 片段一起删（部分站把追踪塞 fragment）
        val keepFrag = PluginHost.settingOf(manifest.id, "preserveFragment", "true") == "true"
        var changed = false
        val out = TrackerRules.URL_REGEX.replace(text) { m ->
            val cleaned = if (custom.isEmpty()) r.clean(m.value)
            else cleanWithCustom(m.value, r, custom)
            val final = if (keepFrag) cleaned else cleanFragment(cleaned)
            if (final != m.value) {
                changed = true
                final
            } else m.value
        }
        return if (changed) PasteResult.Text(out) else PasteResult.NotHandled
    }

    /** preserveFragment=false：去掉 URL 的 #fragment 部分 */
    private fun cleanFragment(url: String): String {
        val idx = url.indexOf('#')
        return if (idx < 0) url else url.substring(0, idx)
    }

    private fun cleanWithCustom(url: String, r: TrackerRules, custom: Set<String>): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val query = url.substring(qIdx + 1)
        val fragIdx = query.indexOf('#')
        val frag = if (fragIdx >= 0) query.substring(fragIdx) else ""
        val params = (if (fragIdx >= 0) query.substring(0, fragIdx) else query)
            .split('&').filter { it.isNotEmpty() }
        val kept = params.filter { p ->
            val key = p.substringBefore('=')
            !r.isTracker(key) && key !in custom
        }
        if (kept.size == params.size) return url
        val q = kept.joinToString("&")
        return base + (if (q.isNotEmpty()) "?$q" else "") + frag
    }

    // ===== action=extract：链接提取 =====
    // 发送者用 emoji/汉字/零宽字符等防爬虫，复制后原文可能是：
    //   "🔗【点✨击 進入】hxxps://ex𝑎mple.com/path?x=1👉或复制此链接到浏览器"
    // 策略：先整体找 http(s):// 链接；若找不到（防爬改写），再做防爬修复后提取。
    private fun extractLinks(text: String): PasteResult {
        val direct = extractFromText(text)
        if (direct != null) return direct
        // 防爬修复（可通过设置关闭）：常见混淆字符替换后重试
        val repair = PluginHost.settingOf(manifest.id, "repair", "true") == "true"
        if (repair) {
            val repaired = repairObfuscatedText(text)
            if (repaired != text) {
                val retry = extractFromText(repaired)
                if (retry != null) return retry
            }
        }
        return PasteResult.NotHandled
    }

    private fun extractFromText(text: String): PasteResult {
        val matches = TrackerRules.URL_REGEX.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return PasteResult.NotHandled
        // 剔除 URL 段里的干扰汉字（反爬混淆字），得到纯净可用的链接
        val cleaned = matches.map { stripHanFromUrl(it) }
        // 文本里有链接：全部提取，多链接换行拼接
        val joined = cleaned.joinToString("\n")
        // 已是纯链接列表就不动（避免重复变换）
        if (joined == text) return PasteResult.NotHandled
        return PasteResult.Text(joined)
    }

    /** 剔除链接 URL 段里的干扰汉字：baid测u→baidu、c时om→com */
    private fun stripHanFromUrl(url: String): String {
        val sb = StringBuilder(url.length)
        var i = 0
        while (i < url.length) {
            val cp = url.codePointAt(i)
            if (!isHan(cp)) sb.append(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** 修复防爬混淆：hxxps→https、𝑎→a 等同类字符、零宽字符清除 */
    private fun repairObfuscatedText(text: String): String {
        var t = text
        // 零宽与不可见字符
        t = t.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "")
            .replace("\uFEFF", "").replace("\u2060", "")
        // 常见协议伪装
        t = t.replace("hxxps://", "https://", ignoreCase = true)
            .replace("hxxp://", "http://", ignoreCase = true)
        // 数学/风格化字母 → ASCII（防爬常用变体）
        t = t.replace("𝑎", "a").replace("𝑏", "b").replace("𝑐", "c")
            .replace("𝑑", "d").replace("𝑒", "e").replace("𝑓", "f")
            .replace("𝑔", "g").replace("𝑕", "h").replace("𝑖", "i")
            .replace("𝑗", "j").replace("𝑘", "k").replace("𝑙", "l")
            .replace("𝑚", "m").replace("𝑛", "n").replace("𝑜", "o")
            .replace("𝑝", "p").replace("𝑞", "q").replace("𝑟", "r")
            .replace("𝑠", "s").replace("𝑡", "t").replace("𝑢", "u")
            .replace("𝑣", "v").replace("𝑤", "w").replace("𝑥", "x")
            .replace("𝑦", "y").replace("𝑧", "z")
        return t
    }

    // ===== action=annotate：拼音标注 =====
    // 开启状态自动给普通文本中的汉字注音：字(拼音)。非汉字字符原样保留。
    // 关键约束：不污染 URL —— 把文本按 URL 正则切成「URL 段 / 非 URL 段」，
    // 只注音非 URL 段（避免 baike.baid测u.c时om/ 这类反爬中文链接被注音破坏）。
    // 设置项：tone=带声调（龘(dá)）或无声调（龘(da)）；onlyRare=只注生僻字
    private fun annotatePinyin(text: String): PasteResult {
        val table = pinyinTable ?: return PasteResult.NotHandled
        if (text.isEmpty()) return PasteResult.NotHandled
        val tone = PluginHost.settingOf(manifest.id, "tone", "true") == "true"
        val onlyRare = PluginHost.settingOf(manifest.id, "onlyRare", "false") == "true"
        val sb = StringBuilder(text.length + 32)
        var changed = false
        // 按 URL 切分：URL 段不动，非 URL 段逐字注音
        var cursor = 0
        for (m in TrackerRules.URL_REGEX.findAll(text)) {
            // 注音 URL 之前的普通文本
            changed = annotateSegment(sb, table, text, cursor, m.range.first, tone, onlyRare) || changed
            // URL 段原样保留（不进注解）
            sb.append(m.value)
            cursor = m.range.last + 1
        }
        // 注音尾部剩余普通文本
        changed = annotateSegment(sb, table, text, cursor, text.length, tone, onlyRare) || changed
        return if (changed) PasteResult.Text(sb.toString()) else PasteResult.NotHandled
    }

    /** 对 [start,end) 文本逐字注音，返回值是否有改动 */
    private fun annotateSegment(
        sb: StringBuilder,
        table: Map<String, String>,
        text: String,
        start: Int,
        end: Int,
        tone: Boolean,
        onlyRare: Boolean,
    ): Boolean {
        var changed = false
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            val chStr = String(Character.toChars(cp))
            if (isHan(cp)) {
                val py = table[chStr]
                if (py != null) {
                    // onlyRare：只注非常用字（>0x9FFF 视为非常用），常用字跳过
                    if (onlyRare && cp < 0xA000) {
                        sb.append(chStr)
                    } else {
                        sb.append(chStr).append('(').append(if (tone) py else stripTone(py)).append(')')
                        changed = true
                    }
                } else sb.append(chStr)
            } else sb.append(chStr)
            i += Character.charCount(cp)
        }
        return changed
    }

    /** 拼音去声调符号：dá→da、ǚ→v 等（tone=false 时用） */
    private fun stripTone(py: String): String {
        val sb = StringBuilder(py.length)
        py.forEach { c ->
            sb.append(when (c) {
                'ā', 'á', 'ǎ', 'à' -> 'a'
                'ē', 'é', 'ě', 'è' -> 'e'
                'ī', 'í', 'ǐ', 'ì' -> 'i'
                'ō', 'ó', 'ǒ', 'ò' -> 'o'
                'ū', 'ú', 'ǔ', 'ù' -> 'u'
                'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü' -> 'v'
                'ń', 'ň', 'ǹ', 'ñ' -> 'n'
                'Ā', 'Á', 'Ǎ', 'À' -> 'A'
                'Ē', 'É', 'Ě', 'È' -> 'E'
                'Ī', 'Í', 'Ǐ', 'Ì' -> 'I'
                'Ō', 'Ó', 'Ǒ', 'Ò' -> 'O'
                'Ū', 'Ú', 'Ǔ', 'Ù' -> 'U'
                'Ǖ', 'Ǘ', 'Ǚ', 'Ǜ', 'Ü' -> 'V'
                else -> c
            })
        }
        return sb.toString()
    }

    private fun isHan(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.OTHER_LETTER.toInt() && (
            (cp in 0x3400..0x4DBF) ||   // 扩展 A
            (cp in 0x4E00..0x9FFF) ||    // 基本
            (cp in 0xF900..0xFAFF) ||    // 兼容
            (cp in 0x20000..0x2A6DF) ||  // 扩展 B
            (cp in 0x2A700..0x2EBEF) ||  // 扩展 C-F
            (cp in 0x30000..0x323AF)     // 扩展 G
            )
    }

    companion object {
        /**
         * 尝试从目录加载插件；目录不完整/解析失败返回 null（汇报由外部负责）。
         */
        fun from(dir: File): DataPlugin? {
            if (!dir.isDirectory) return null
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) return null
            val manifest = MengPackage.parseManifest(mf.readText()) ?: return null
            // 只支持数据插件（脚本插件二期）
            if (manifest.type != "data") return null
            var rules: TrackerRules? = null
            var pinyin: Map<String, String>? = null
            when (manifest.action) {
                "extract" -> Unit // 链接提取无需数据文件
                "annotate" -> {
                    val pf = File(dir, "pinyin.json")
                    if (!pf.exists()) return null
                    pinyin = parsePinyinTable(pf.readText()) ?: return null
                }
                else -> { // clean
                    val rulesFile = File(dir, "rules.json")
                    if (!rulesFile.exists()) return null
                    rules = TrackerRules.parse(rulesFile.readText()) ?: return null
                }
            }
            return DataPlugin(manifest, rules, pinyin)
        }

        private fun parsePinyinTable(json: String): Map<String, String>? = runCatching {
            val obj = org.json.JSONObject(json)
            val map = HashMap<String, String>(obj.length() * 2)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        }.getOrNull()
    }
}