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
 * Rime key_binder 可视化编辑的数据内核（纯逻辑，不依赖 UI）。
 *
 * 职责：
 * - 从「最终形态」`build/wanxiang.schema.yaml` 读取 key_binder.bindings 数组，
 *   还原成可编辑的行 [KeyBindingRow]。
 * - 把用户的修改写回安全的用户覆盖层 `custom/wanxiang.custom.yaml`
 *   （作者预留、更新方案不会覆盖的位置），用 rime 的 `patch:` 语法
 *   覆盖 `key_binder/bindings` 为用户的版本。
 *
 * 为什么写 custom 而不是 build？
 * build/ 是 rime 部署生成的临时产物，会被重新部署覆盖；custom/wanxiang.custom.yaml
 * 才是作者预留的用户自定义层（见 custom/patch方法论.md），权重最高，且更新方案不被覆盖。
 */
object RimeKeyBinder {

    /** 每个 key_binder 绑定行。send/toggle/select 三选一。 */
    data class KeyBindingRow(
        val accept: String,
        val when_: String?,          // rime 的 when 字段（`when` 是 Kotlin 关键字）
        val send: String? = null,
        val toggle: String? = null,
        val select: String? = null,
        val sendSequence: String? = null,
        val match: String? = null,
    ) {
        val actionSummary: String
            get() = when {
                send != null -> "发送 ${send}"
                toggle != null -> "切换 ${toggle}"
                select != null -> "选择 ${select}"
                sendSequence != null -> "发送序列"
                else -> "（行为）"
            }

        val whenSummary: String get() = when_ ?: "always"
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

    private fun loadYaml(text: String): Map<String, Any> {
        val options = LoaderOptions()
        options.isAllowDuplicateKeys = false
        val yaml = Yaml(SafeConstructor(options))
        val loaded = yaml.load<Any?>(text)
        @Suppress("UNCHECKED_CAST")
        return (loaded as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
    }

    /**
     * 读取当前「最终形态」的 key_binder.bindings，返回可编辑行列表。
     * 读取失败（缺文件/无该段）返回空列表，调用方提示未安装万象。
     */
    fun loadBindings(context: Context): List<KeyBindingRow> {
        val dir = rimeDataDir(context)
        val build = File(dir, BUILD_SCHEMA)
        if (!build.exists()) {
            Timber.w("No build schema: ${build.path}")
            return emptyList()
        }
        return runCatching {
            val root = loadYaml(build.readText())
            val kb = root["key_binder"] as? Map<*, *> ?: return emptyList()
            val bindings = kb["bindings"] as? List<*> ?: return emptyList()
            bindings.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                KeyBindingRow(
                    accept = m["accept"]?.toString() ?: return@mapNotNull null,
                    when_ = m["when"]?.toString(),
                    send = m["send"]?.toString(),
                    toggle = m["toggle"]?.toString(),
                    select = m["select"]?.toString(),
                    sendSequence = m["send_sequence"]?.toString(),
                    match = m["match"]?.toString(),
                )
            }
        }.getOrElse {
            Timber.w(it, "Failed to parse key_binder from ${build.path}")
            emptyList()
        }
    }

    /**
     * 把用户编辑后的行写回 custom/wanxiang.custom.yaml。
     *
     * 采用「文本级替换」策略：保留原 custom 文件里除 `key_binder/bindings` 外的
     * 所有内容（候选数、双拼分支、简码等用户原有设置），只把其中代表
     * `key_binder/bindings` 的块替换为编辑后的版本。若原文件没有该块，则追加。
     *
     * @return 改后的文件绝对路径；失败返回 null。
     */
    fun saveBindings(context: Context, rows: List<KeyBindingRow>): String? {
        return runCatching {
            val customFile = rimeCustomFile(context)
            customFile.parentFile?.mkdirs()
            val original = if (customFile.exists()) customFile.readText() else null
            customFile.writeText(replaceKeyBinderBlock(original, rows))
            Timber.d("Saved rime key_binder to ${customFile.path}")
            customFile.absolutePath
        }.getOrElse {
            Timber.e(it, "Failed to save rime key_binder")
            null
        }
    }

    /** rime custom 文件完整路径。 */
    fun rimeCustomFile(context: Context): File = File(rimeDataDir(context), CUSTOM_FILE)

    /** 当前 custom 文件全文（供编辑器预览/展示原始内容）。 */
    fun readCustomContent(context: Context): String? {
        val f = rimeCustomFile(context)
        return if (f.exists()) f.readText() else null
    }

    /**
     * 文本级替换 custom 内容里的 key_binder/bindings 块。
     * 若匹配不到已有块，则在文件末尾（patch: 顶层之下）追加新的 pad…
     */
    private fun replaceKeyBinderBlock(original: String?, rows: List<KeyBindingRow>): String {
        val s = original ?: ""
        // 尝试匹配已有的 key_binder/bindings 块：该行及其后所有缩进更深的内容
        val re = Regex("""(?m)^( *)key_binder/bindings:\n(?:(?:[ \t]+.*\n?)*)""")
        val match = re.find(s)
        return if (match != null) {
            // 用匹配到的缩进构造新块，替换旧块
            val indent = match.groupValues[1]
            val newBlock = buildKeyBinderBlockYaml(rows, indent)
            s.replaceRange(match.range, newBlock)
        } else {
            // 无现有块：追加（若无 patch: 顶层，则补一个）
            val base = s.trimEnd()
            val sb = StringBuilder()
            if (base.isNotBlank()) sb.append(base).append("\n")
            if (!Regex("""(?m)^patch:\s*$""").containsMatchIn(base)) {
                sb.append("patch:\n")
            }
            sb.append(buildKeyBinderBlockYaml(rows, "  "))
            sb.toString()
        }
    }

    /** 生成缩进下的 key_binder/bindings 块文本。[indent] 为 key_binder/bindings 行的缩进。 */
    private fun buildKeyBinderBlockYaml(rows: List<KeyBindingRow>, indent: String): String {
        val keyIndent = indent
        val itemIndent = indent + "  "
        val sb = StringBuilder()
        sb.append(keyIndent).append("key_binder/bindings:\n")
        rows.forEach { row ->
            sb.append(itemIndent).append("- {")
            val parts = mutableListOf<String>()
            parts.add("accept: ${quoteIfNeeded(row.accept)}")
            row.when_?.let { parts.add("when: ${quoteIfNeeded(it)}") }
            row.send?.let { parts.add("send: ${quoteIfNeeded(it)}") }
            row.toggle?.let { parts.add("toggle: ${quoteIfNeeded(it)}") }
            row.select?.let { parts.add("select: ${quoteIfNeeded(it)}") }
            row.sendSequence?.let { parts.add("send_sequence: ${quoteIfNeeded(it)}") }
            row.match?.let { parts.add("match: ${quoteIfNeeded(it)}") }
            sb.append(parts.joinToString(", "))
            sb.append("}\n")
        }
        return sb.toString()
    }

    /** 供编辑器写入时预览生成的 custom yaml 全文。 */
    fun previewCustomYaml(context: Context, rows: List<KeyBindingRow>): String {
        val original = readCustomContent(context)
        return replaceKeyBinderBlock(original, rows)
    }

    private fun quoteIfNeeded(v: String): String {
        // 含特殊字符（冒号/空格/大括号/引号/反斜杠）的字段加双引号，与 rime 惯例一致
        val needs = v.any { it == ':' || it == ' ' || it == '{' || it == '}' ||
            it == '\'' || it == '"' || it == '\\' }
        return if (needs) "\"${v.replace("\"", "\\\"")}\"" else v
    }
}