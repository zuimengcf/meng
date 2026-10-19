/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.content.Context
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import timber.log.Timber
import java.io.File

/**
 * Rime punctuator（标点表）可视化编辑的数据内核（纯逻辑，不依赖 UI）。
 *
 * 用户要的是「中间层，不改原文件」：
 * - 从「最终形态」`build/wanxiang.schema.yaml` 读取 `punctuator.full_shape` / `half_shape`
 *   两张标点映射表，还原成可编辑的行 [PunctRow]。
 * - 修改后【只把用户改过的键】写回安全的用户覆盖层 `custom/wanxiang.custom.yaml`
 *   （作者预留、更新方案不会覆盖的位置），用 rime 的 `patch:` 语法生成
 *   `punctuator/full_shape` 与 `punctuator/half_shape` 覆盖块。
 *   没改的键不写，继续沿用 build 原值 —— 这就是「中间层」：不污染原文件，改动精准生效。
 *
 * 为什么写 custom 而不写 build？
 * build/ 是 rime 部署生成的临时产物，会被重新部署覆盖；custom/wanxiang.custom.yaml
 * 才是作者预留的用户自定义层（见 custom/patch方法论.md），权重最高，且更新方案不被覆盖。
 */
object PunctuatorEditor {

    /** 单个标点的完整值：full_shape 与 half_shape 两种形态下的映射。 */
    data class PunctRow(
        /** 标点键，如 '.' 或 ','（full/half 共用同一键名）。 */
        val key: String,
        /** 全角形态值（build 原值），如 '.' → commit "。" */
        val fullValue: Any?,
        /** 半角形态值（build 原值），如 '.' → "。"（一些方案 half_shape 也转全角） */
        val halfValue: Any?,
        /** 用户编辑后的全角值（null 表示未改） */
        val fullEdited: Any? = null,
        /** 用户编辑后的半角值（null 表示未改） */
        val halfEdited: Any? = null,
    ) {
        /** 用户是否改过全角 */
        val isFullEdited: Boolean get() = fullEdited != null
        /** 用户是否改过半角 */
        val isHalfEdited: Boolean get() = halfEdited != null

        /** 展示用：全角形态当前值文本 */
        val fullSummary: String get() = summarize(fullEdited ?: fullValue)
        /** 展示用：半角形态当前值文本 */
        val halfSummary: String get() = summarize(halfEdited ?: halfValue)
    }

    private const val SCHEMA_NAME = "wanxiang"
    private const val BUILD_SCHEMA = "build/$SCHEMA_NAME.schema.yaml"
    private const val CUSTOM_FILE = "custom/$SCHEMA_NAME.custom.yaml"

    /** rime 数据根目录（与 SchemaMarketManager.rimeDataDir 一致）。 */
    fun rimeDataDir(context: Context): File {
        val dataDir = File(
            (context.getExternalFilesDir(null) ?: context.filesDir),
            "data"
        )
        return File(dataDir, "rime")
    }

    /** rime custom 文件完整路径。 */
    fun rimeCustomFile(context: Context): File = File(rimeDataDir(context), CUSTOM_FILE)

    /** 指定 schema 的 custom 文件完整路径（用于对所有方案通杀写入）。 */
    fun rimeCustomFile(context: Context, schemaName: String): File =
        File(rimeDataDir(context), "custom/$schemaName.custom.yaml")

    private fun loadYaml(text: String): Map<String, Any> {
        val options = LoaderOptions()
        options.isAllowDuplicateKeys = false
        val yaml = Yaml(SafeConstructor(options))
        val loaded = yaml.load<Any?>(text)
        @Suppress("UNCHECKED_CAST")
        return (loaded as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
    }

    /** 展示用：把结构化 yaml 值转成简洁文本（供列表/编辑框显示）。 */
    fun summarize(v: Any?): String {
        if (v == null) return ""
        return when (v) {
            is Map<*, *> -> {
                // {commit: "。"} → 显示 commit 的值；{pair: [...]} → 显示 pair 列表
                val commit = v["commit"]
                if (commit != null) return summarize(commit)
                val pair = v["pair"]
                if (pair is List<*>) return pair.joinToString("/") { summarize(it) }
                v.entries.joinToString(", ") { "${it.key}: ${summarize(it.value)}" }
            }
            is List<*> -> v.joinToString(", ") { summarize(it) }
            else -> v.toString()
        }
    }

    /** 判断两个 yaml 值是否等价（用于决定是否写入中间层）。 */
    fun valueEquals(a: Any?, b: Any?): Boolean = valueKey(a) == valueKey(b)

    /** 把 yaml 值规整成稳定可比较的字符串键（用于判断是否与原值相同）。 */
    private fun valueKey(v: Any?): String {
        if (v == null) return ""
        return when (v) {
            is Map<*, *> -> {
                // {commit: "。"} / {pair: ["“","”"]} 等
                val entries = v.entries.sortedBy { it.key.toString() }
                    .joinToString("|") { "${it.key}:${valueKey(it.value)}" }
                "{${entries}}"
            }
            is List<*> -> "[" + v.joinToString(",") { valueKey(it) } + "]"
            else -> v.toString()
        }
    }

    /**
     * 读取当前「最终形态」的 punctuator 表，返回可编辑行列表。
     * 只统计 full_shape 与 half_shape 同时出现、且属于常见键盘标点的键，
     * 读取失败（缺文件/无该段）返回空列表。
     */
    fun loadPunctuators(context: Context): List<PunctRow> {
        val dir = rimeDataDir(context)
        val build = File(dir, BUILD_SCHEMA)
        if (!build.exists()) {
            Timber.w("No build schema: ${build.path}")
            return emptyList()
        }
        return runCatching {
            val root = loadYaml(build.readText())
            val punct = root["punctuator"] as? Map<*, *> ?: return emptyList()
            val full = punct["full_shape"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val half = punct["half_shape"] as? Map<*, *> ?: emptyMap<Any, Any>()
            // 并集键：优先展示两形态都存在的标点键
            val keys = (full.keys + half.keys)
                .map { it.toString() }
                .distinct()
                .filter { it.length <= 2 }  // 排除多字符的组合触发
            keys.map { k ->
                PunctRow(
                    key = k,
                    fullValue = full[k],
                    halfValue = half[k],
                )
            }
        }.getOrElse {
            Timber.w(it, "Failed to parse punctuator from ${build.path}")
            emptyList()
        }
    }

    /**
     * 把用户编辑后的标点写回 custom。
     * 只写入与 build 原值不同的键（中间层），文本级替换 custom 里的
     * `punctuator/full_shape` 与 `punctuator/half_shape` 覆盖块；若没有则追加。
     *
     * 【通杀所有输入方案】不再只写 wanxiang 一个方案，而是遍历 build/ 下所有
     * `*.schema.yaml`，为每个方案生成/更新其 `<schemaName>.custom.yaml`，写入同一份
     * 「用户改过的键」覆盖块。没改过的键不写，各方案保留自己的 build 原值 ——
     * 既保留万象等未修改配置，又让改动对所有输入方案统一生效。
     *
     * @return 改后的 custom 文件绝对路径列表；全部失败返回 null。
     */
    fun savePunctuators(context: Context, rows: List<PunctRow>): String? {
        return runCatching {
            val rime = rimeDataDir(context)
            val buildDir = File(rime, "build")
            val schemas = if (buildDir.exists()) {
                buildDir.listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }
                    ?.mapNotNull { f -> f.name.removeSuffix(".schema.yaml").takeIf { it.isNotBlank() } }
                    ?: emptyList()
            } else emptyList()
            // 兜底：至少写回主方案 wanxiang（build 目录缺失时）
            val targets = if (schemas.isEmpty()) listOf(SCHEMA_NAME) else schemas
            val written = mutableListOf<String>()
            for (schema in targets.distinct()) {
                val customFile = rimeCustomFile(context, schema)
                customFile.parentFile?.mkdirs()
                val original = if (customFile.exists()) customFile.readText() else null
                customFile.writeText(replacePunctuatorBlock(original, rows))
                Timber.d("Saved rime punctuator to ${customFile.path}")
                written.add(customFile.absolutePath)
            }
            written.firstOrNull()
        }.getOrElse {
            Timber.e(it, "Failed to save rime punctuator")
            null
        }
    }

    /** 当前 custom 文件全文（供编辑器预览/展示原始内容）。 */
    fun readCustomContent(context: Context): String? {
        val f = rimeCustomFile(context)
        return if (f.exists()) f.readText() else null
    }

    /** 供编辑器写入时预览生成的 custom yaml 全文。 */
    fun previewCustomYaml(context: Context, rows: List<PunctRow>): String {
        val original = readCustomContent(context)
        return replacePunctuatorBlock(original, rows)
    }

    /** 生成单个形态（full_shape/half_shape）的覆盖块文本。 */
    private fun buildShapeBlock(
        shapeName: String,
        indent: String,
        rows: List<PunctRow>,
        pick: (PunctRow) -> Pair<String, Any?>?,
    ): String {
        val sb = StringBuilder()
        sb.append(indent).append("$shapeName:\n")
        rows.forEach { row ->
            val sel = pick(row) ?: return@forEach
            val (key, value) = sel
            sb.append(indent).append("  ").append(quoteKey(key)).append(": ")
                .append(yamlValue(value)).append("\n")
        }
        return sb.toString()
    }

    /**
     * 文本级替换 custom 内容里的 punctuator 覆盖块。
     * - 若已存在 `punctuator:` 顶层块，则在其下替换 full_shape / half_shape 子块
     *   （保留该块其它已有内容），避免重复累积。
     * - 若无则生成新的完整 punctuator 块追加到 patch: 顶层之下。
     */
    private fun replacePunctuatorBlock(original: String?, rows: List<PunctRow>): String {
        val s = original ?: ""
        // 只保留用户改过的键
        val fullRows = rows.filter { it.isFullEdited }
        val halfRows = rows.filter { it.isHalfEdited }

        val indent = "  " // patch 子项的缩进

        // 1) 已有 punctuator 顶层块：替换其中的 full_shape / half_shape 子块
        val punctBlock = Regex("""(?m)^( *)punctuator:\n((?:[ \t]+.*\n?)*)""").find(s)
        if (punctBlock != null) {
            val blockIndent = punctBlock.groupValues[1]
            var inner = punctBlock.groupValues[2]
            val subIndent = blockIndent + "  "
            // 替换/重建 full_shape 子块
            inner = replaceSubShape(inner, "full_shape", subIndent, fullRows) { r ->
                if (r.isFullEdited) r.key to r.fullEdited else null
            }
            // 替换/重建 half_shape 子块
            inner = replaceSubShape(inner, "half_shape", subIndent, halfRows) { r ->
                if (r.isHalfEdited) r.key to r.halfEdited else null
            }
            val newBlock = blockIndent + "punctuator:\n" + inner
            return s.replaceRange(punctBlock.range, newBlock)
        }

        // 2) 无 punctuator 块：生成完整块追加
        val sb = StringBuilder()
        if (s.isNotBlank()) sb.append(s.trimEnd()).append("\n")
        if (!Regex("""(?m)^patch:\s*$""").containsMatchIn(s)) {
            sb.append("patch:\n")
        }
        sb.append(indent).append("punctuator:\n")
        if (fullRows.isNotEmpty()) {
            sb.append(buildShapeBlock("full_shape", indent + "  ", fullRows) { r ->
                if (r.isFullEdited) r.key to r.fullEdited else null
            })
        }
        if (halfRows.isNotEmpty()) {
            sb.append(buildShapeBlock("half_shape", indent + "  ", halfRows) { r ->
                if (r.isHalfEdited) r.key to r.halfEdited else null
            })
        }
        return sb.toString()
    }

    /**
     * 在已有 punctuator 块的内部文本中，替换或重建某个 shape 子块。
     * 若 shape 子块已存在则整块替换；否则追加到块末尾。
     */
    private fun replaceSubShape(
        inner: String,
        shapeName: String,
        subIndent: String,
        rows: List<PunctRow>,
        pick: (PunctRow) -> Pair<String, Any?>?,
    ): String {
        val shapeRe = Regex("""(?m)^${Regex.escape(subIndent)}${shapeName}:\n((?:[ \t]+.*\n?)*)""")
        val shapeMatch = shapeRe.find(inner)
        val newShape = if (rows.isEmpty()) {
            // 无用户修改时，直接移除该子块（连同其内容行）
            ""
        } else {
            buildShapeBlock(shapeName, subIndent, rows, pick)
        }
        return if (shapeMatch != null) {
            inner.replaceRange(shapeMatch.range, newShape)
        } else {
            inner + newShape
        }
    }

    private fun quoteKey(k: String): String {
        // 标点键常为 . , ; : 等单字符，或是 rime 保留字符；统一加引号最稳妥，
        // 避免裸键被 YAML/rime 误解析（如 `.`、`:`、`-`、`?`）。
        val needs = k.any { it.isWhitespace() || it == ':' || it == '{' || it == '}' ||
            it == ',' || it == '[' || it == ']' || it == '"' || it == '\'' || it == '.' ||
            it == '-' || it == '?' || it == '#' }
        return if (needs) "\"${k.replace("\"", "\\\"")}\"" else k
    }

    /** 生成 yaml 值文本：支持纯字符串、{commit/pair}、列表。 */
    private fun yamlValue(v: Any?): String {
        if (v == null) return "null"
        return when (v) {
            is String -> quoteScalar(v)
            is Map<*, *> -> {
                // {commit: "。"} 或 {pair: [...]}
                val entries = v.entries.map { (k, value) ->
                    "${k}: ${yamlValue(value)}"
                }.joinToString(", ")
                "{ $entries }"
            }
            is List<*> -> v.joinToString(", ", "[", "]") { yamlValue(it) }
            is Boolean, is Number -> v.toString()
            else -> quoteScalar(v.toString())
        }
    }

    private fun quoteScalar(v: String): String {
        val needs = v.any { it.isWhitespace() || it == ':' || it == '{' || it == '}' ||
            it == ',' || it == '[' || it == ']' || it == '"' || it == '\'' || it == '\\' }
        return if (needs) "\"${v.replace("\"", "\\\"")}\"" else v
    }

    /** 把用户输入的文本解析成 yaml 值对象（字符串、commit 包装、pair、列表）。 */
    fun parseEditedValue(raw: String): Any? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        // 尝试解析为结构化 yaml（{...} 或 [...]）
        if (t.startsWith("{") || t.startsWith("[")) {
            return runCatching {
                val yaml = Yaml(SafeConstructor(LoaderOptions()))
                yaml.load<Any?>(t)
            }.getOrNull()
        }
        return t
    }
}
