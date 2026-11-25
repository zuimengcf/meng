/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * .meng 插件包：zip 容器，用于独立更新插件（不需重装主程序）。
 *
 * 包内结构（v1）：
 *   manifest.json  {"id","name","version","apiVersion","priority","hooks","type"}
 *   rules.json     插件数据（type="data" 时必备；当前仅剪贴板净化使用）
 *
 * 安装位置：files/plugins/<id>/（应用私有目录）
 * 安装语义：- 版本号必须 >= 已装版本（拒绝降级）
 *           - apiVersion 不能高于宿主支持版本（PLUGIN_API_VERSION）
 *           - 包内条目白名单校验（防路径穿越）
 */
object MengPackage {
    const val ENTRY_MANIFEST = "manifest.json"
    private val ALLOWED_ENTRIES = setOf(
        "manifest.json", "rules.json", "pinyin.json", "plugin.js",
        "data.json", "dict.txt", "README.txt", "README.md", "LICENSE", "LICENSE.txt",
    )
    // 词典分类文件：dict.txt / dict_cn.txt / dict_en.txt / dict_hot.txt / dict_acg.txt ...
    // 宿主支持多个分类词典文件（用户「分类储存，不单文件」需求），全部并入统一索引。
    private val DICT_ENTRY = Regex("^dict[^/]*\\.txt$")
    // 贴纸等图片资产（贴纸包插件用）：png/jpg/jpeg/webp/gif，平铺在包根目录
    private val IMAGE_ENTRY = Regex("^[A-Za-z0-9_-]+\\.(png|jpg|jpeg|webp|gif)$")
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]+$")

    // 解压体积上限（防 zip 炸弹：压缩比可虚标 entry.size，实际按声明值累计拦截；
    // 大词典插件 53MB 需放行，故上限取宽松值）
    private const val MAX_ENTRY_SIZE = 256L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 512L * 1024 * 1024

    data class InstallResult(
        val success: Boolean,
        val pluginId: String? = null,
        val version: Int? = null,
        val error: String? = null,
        /** 内容指纹校验结果：命中官方表 → true（包未被修改） */
        val official: Boolean = false,
    )

    /** 从文件选择器 Uri 安装 .meng 包（先拷临时文件再解析，避免流式 zip 兼容问题） */
    fun install(uri: Uri, context: Context): InstallResult {
        val tmp = File(context.cacheDir, "meng_${System.currentTimeMillis()}.meng")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return InstallResult(false, error = "unreadable")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            return installFromFile(tmp, context)
        } catch (e: Exception) {
            return InstallResult(false, error = e.message)
        } finally {
            tmp.delete()
        }
    }

    /** 从本地文件安装（解析 + 校验 + 原子替换） */
    fun installFromFile(file: File, context: Context): InstallResult {
        val manifestJson: String
        val rulesJson: String?
        val pinyinJson: String?
        val pluginJs: String?
        val dataJson: String?
        // 词典分类文件（dict.txt / dict_*.txt）：只标记条目名，不整读进堆（流式拷贝落盘）
        val dictEntries = mutableListOf<String>()
        // 图片资产（贴纸包插件）：png/jpg/webp/gif，流式拷贝落盘
        val imageEntries = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val e = zip.entries()
                var totalSize = 0L
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    val name = entry.name
                    // 允许 dict_*.txt 分类文件（用户「分类储存」需求）+ 图片资产（贴纸包）+ 既有白名单
                    val allowed = name in ALLOWED_ENTRIES || DICT_ENTRY.matches(name) || IMAGE_ENTRY.matches(name)
                    if (!allowed || name.contains("..") || name.startsWith("/")) {
                        return InstallResult(false, error = "bad entry: $name")
                    }
                    if (!entry.isDirectory) {
                        // zip 炸弹防护：单条目与累计声明体积超限拒装
                        if (entry.size > MAX_ENTRY_SIZE) {
                            return InstallResult(false, error = "entry too large: $name")
                        }
                        totalSize += entry.size
                        if (totalSize > MAX_TOTAL_SIZE) {
                            return InstallResult(false, error = "package too large")
                        }
                    }
                    if (DICT_ENTRY.matches(name)) dictEntries.add(name)
                    if (IMAGE_ENTRY.matches(name)) imageEntries.add(name)
                }
                val mEntry = zip.getEntry(ENTRY_MANIFEST)
                    ?: return InstallResult(false, error = "missing manifest.json")
                manifestJson = zip.getInputStream(mEntry).bufferedReader().use { it.readText() }
                val rEntry = zip.getEntry("rules.json")
                rulesJson = rEntry?.let { zip.getInputStream(it).bufferedReader().use { it.readText() } }
                val pEntry = zip.getEntry("pinyin.json")
                pinyinJson = pEntry?.let { zip.getInputStream(it).bufferedReader().use { it.readText() } }
                val jsEntry = zip.getEntry("plugin.js")
                pluginJs = jsEntry?.let { zip.getInputStream(it).bufferedReader().use { it.readText() } }
                val dEntry = zip.getEntry("data.json")
                dataJson = dEntry?.let { zip.getInputStream(it).bufferedReader().use { it.readText() } }
            }
        } catch (e: Exception) {
            return InstallResult(false, error = "not a valid .meng package")
        }
        val manifest = parseManifest(manifestJson)
            ?: return InstallResult(false, error = "invalid manifest.json")
        // 官方指纹校验：重算包内容指纹比对内置表，命中=官方（未被修改）
        val fingerprint = fingerprintOf(file)
        val official = fingerprint != null &&
            OfficialFingerprints.isOfficial(manifest.id, manifest.version, fingerprint)
        if (manifest.version < 1) return InstallResult(false, error = "bad version")
        if (manifest.apiVersion > PLUGIN_API_VERSION) {
            return InstallResult(false, error = "apiVersion ${manifest.apiVersion} > host ${PLUGIN_API_VERSION}")
        }
        // 按 type 校验数据文件
        if (manifest.type == "script") {
            if (pluginJs == null || pluginJs.isBlank()) {
                return InstallResult(false, error = "missing plugin.js")
            }
        } else if (manifest.type == "data") {
            when (manifest.action) {
                "annotate" -> if (pinyinJson == null) return InstallResult(false, error = "missing pinyin.json")
                "extract" -> Unit
                else -> if (rulesJson == null) return InstallResult(false, error = "missing rules.json")
            }
        }
        // 拒绝降级
        val dir = File(context.filesDir, "plugins/${manifest.id}")
        val existingMf = File(dir, ENTRY_MANIFEST)
        if (existingMf.exists()) {
            val old = parseManifest(existingMf.readText())
            if (old != null && manifest.version < old.version) {
                return InstallResult(false, error = "downgrade blocked: ${old.version} -> ${manifest.version}")
            }
        }
        // 首次安装（目录不存在）→ 默认关闭，用户在插件列表手动开启后才参与管线。
        // 官方/第三方一视同仁：插件能力包括读输入框全文等，首次装机不静默生效。
        val firstInstall = !dir.exists()
        // 原子安装：先写临时目录再整体替换
        val tmpDir = File(context.cacheDir, "meng_install_${manifest.id}_${System.currentTimeMillis()}")
        try {
            tmpDir.mkdirs()
            File(tmpDir, ENTRY_MANIFEST).writeText(manifestJson)
            if (rulesJson != null) File(tmpDir, "rules.json").writeText(rulesJson)
            if (pinyinJson != null) File(tmpDir, "pinyin.json").writeText(pinyinJson)
            if (pluginJs != null) File(tmpDir, "plugin.js").writeText(pluginJs)
            if (dataJson != null) File(tmpDir, "data.json").writeText(dataJson)
            if (dictEntries.isNotEmpty()) {
                // 所有 dict_*.txt 分类文件流式拷贝（不整读进堆）：从 zip 直接写到临时目录
                ZipFile(file).use { zip ->
                    for (name in dictEntries) {
                        val dicEntry = zip.getEntry(name) ?: continue
                        zip.getInputStream(dicEntry).use { input ->
                            File(tmpDir, name).outputStream().use { output ->
                                copyBounded(input, output)
                            }
                        }
                    }
                }
            }
            if (imageEntries.isNotEmpty()) {
                // 贴纸图片资产流式拷贝落盘（渲染器缩略图 / commitImage 直插都从插件目录读）
                ZipFile(file).use { zip ->
                    for (name in imageEntries) {
                        val imgEntry = zip.getEntry(name) ?: continue
                        zip.getInputStream(imgEntry).use { input ->
                            File(tmpDir, name).outputStream().use { output ->
                                copyBounded(input, output)
                            }
                        }
                    }
                }
            }
            dir.parentFile?.mkdirs()
            dir.deleteRecursively()
            if (!tmpDir.renameTo(dir)) {
                dir.mkdirs()
                File(tmpDir, ENTRY_MANIFEST).copyTo(File(dir, ENTRY_MANIFEST), overwrite = true)
                if (rulesJson != null) {
                    File(tmpDir, "rules.json").copyTo(File(dir, "rules.json"), overwrite = true)
                }
                if (pinyinJson != null) {
                    File(tmpDir, "pinyin.json").copyTo(File(dir, "pinyin.json"), overwrite = true)
                }
                if (pluginJs != null) {
                    File(tmpDir, "plugin.js").copyTo(File(dir, "plugin.js"), overwrite = true)
                }
                if (dataJson != null) {
                    File(tmpDir, "data.json").copyTo(File(dir, "data.json"), overwrite = true)
                }
                for (name in dictEntries) {
                    File(tmpDir, name).copyTo(File(dir, name), overwrite = true)
                }
                for (name in imageEntries) {
                    File(tmpDir, name).copyTo(File(dir, name), overwrite = true)
                }
            }
        } catch (e: Exception) {
            return InstallResult(false, error = e.message)
        } finally {
            tmpDir.deleteRecursively()
        }
        if (firstInstall) {
            // 首次安装：写 enabled=false，管线/面板在用户手动开启前不生效
            PluginHost.markNewInstall(manifest.id)
        }
        return InstallResult(true, pluginId = manifest.id, version = manifest.version, official = official)
    }

    /**
     * 流式拷贝并按实际字节数封顶（防 zip 炸弹虚报：中央目录的 entry.size 可伪造，
     * 此处以真实读到的字节计数，超上限即抛异常终止安装，已写的临时目录由调用方清理）。
     */
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            written += n
            if (written > MAX_ENTRY_SIZE) throw java.io.IOException("entry too large (actual)")
            output.write(buf, 0, n)
        }
    }

    /**
     * 包内容指纹（与 scripts/meng_pack.py 完全一致）：
     * zip 条目按名字典序，每条 "name:sha256hex(content)\n" 拼接成字符串，
     * 整体字符串 UTF-8 字节的 sha256（hex）。与压缩参数/时间戳无关；读包失败返回 null。
     */
    fun fingerprintOf(file: File): String? = runCatching {
        val sb = StringBuilder()
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .sorted()
                .toList()
            for (name in names) {
                val entryMd = java.security.MessageDigest.getInstance("SHA-256")
                val entry = zip.getEntry(name) ?: continue
                zip.getInputStream(entry).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        entryMd.update(buf, 0, n)
                    }
                }
                sb.append(name).append(':')
                    .append(entryMd.digest().joinToString("") { "%02x".format(it) })
                    .append('\n')
            }
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(sb.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 解析插件版本号：支持整数（7）与多段点分字符串（"1.1.111"）两种写法。
     * 多段版本编码为 int：每段最多 3 位十进制，不足 3 段左对齐补零（权重递减）：
     *   "1.1.111" → 1*10^6 + 1*10^3 + 111 = 1001111
     *   "1.2"     → 1*10^6 + 2*10^3 + 0   = 1002000
     *   纯整数 → 原值。
     * 段数超 3、段值超 999、负数或解析失败 → null（调用方拒绝安装）。
     * 比较规则：编码值大小即版本新旧（高位段权重高）。
     */
    fun parsePluginVersion(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it.takeIf { v -> v >= 1 } }
        val segs = s.split('.', '-', '+')
            .map { it.trim() }
        if (segs.isEmpty() || segs.size > 3 || segs.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val nums = segs.map { it.toInt() }
        if (nums.any { it > 999 } || nums[0] == 0 && nums.size > 1) return null
        var code = 0
        for (i in 0 until 3) {
            code = code * 1000 + nums.getOrElse(i) { 0 }
        }
        return code.takeIf { it >= 1 }
    }

    fun parseManifest(json: String): PluginManifest? = runCatching {
        val o = JSONObject(json)
        val id = o.getString("id")
        if (!SAFE_ID.matches(id)) return null
        // version 支持整数（7）或多段字符串（"1.1.111"）；解析失败视为非法 manifest
        val rawVer = if (o.has("version")) {
            val v = o.get("version")
            if (v is Number) v.toString() else v.toString()
        } else null
        val verInt = parsePluginVersion(rawVer) ?: return null
        val verText = rawVer?.trim().orEmpty()
        PluginManifest(
            id = id,
            name = o.optString("name", id),
            version = verInt,
            versionText = verText,
            apiVersion = o.optInt("apiVersion", 1),
            priority = o.optInt("priority", 100),
            type = o.optString("type", "data"),
            hooks = if (o.has("hooks")) {
                o.optJSONArray("hooks")?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            PluginHook.entries.firstOrNull { it.name.equals(arr.getString(i), true) }
                                ?.let { add(it) }
                        }
                    }
                } ?: listOf(PluginHook.PasteTransform)
            } else {
                // 未显式声明 hooks 时保持向后兼容：默认粘贴变换
                listOf(PluginHook.PasteTransform)
            },
            action = o.optString("action", "clean"),
            ui = o.optString("ui", "frame"),
            menus = o.optJSONArray("menus")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val mo = arr.optJSONObject(i) ?: continue
                        val mid = mo.optString("id")
                        if (mid.isEmpty()) continue
                        add(
                            PluginMenu(
                                id = mid,
                                title = mo.optString("title", mid),
                                type = mo.optString("type", "text"),
                                editType = mo.optString("editType", null).ifEmpty { null },
                                editorTitle = mo.optString("editorTitle", null).ifEmpty { null },
                                hint = mo.optString("hint", null).ifEmpty { null },
                                defaultValue = mo.optBoolean("defaultValue", false),
                            )
                        )
                    }
                }
            } ?: emptyList(),
            settings = o.optJSONArray("settings")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val so = arr.optJSONObject(i) ?: continue
                        val key = so.optString("key")
                        if (key.isEmpty()) continue
                        add(
                            PluginSetting(
                                key = key,
                                label = so.optString("label", key),
                                type = so.optString("type", "switch"),
                                options = so.optJSONArray("options")?.let { oa ->
                                    buildList {
                                        for (j in 0 until oa.length()) add(oa.getString(j))
                                    }
                                } ?: emptyList(),
                                defaultValue = so.optString("defaultValue", "false"),
                                quick = so.optBoolean("quick", false),
                            )
                        )
                    }
                }
            } ?: emptyList(),
            icon = o.optString("icon", null).ifEmpty { null },
            // 词典能力声明：dict:{"defSearch":true} → 释义列纳入宿主搜索（通用能力，插件自声明）
            dictDefSearch = o.optJSONObject("dict")?.optBoolean("defSearch", false) ?: false,
        )
    }.getOrNull()
}