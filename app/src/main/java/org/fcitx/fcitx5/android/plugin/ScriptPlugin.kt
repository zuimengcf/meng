/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

import org.fcitx.fcitx5.android.script.qjs.QjsEngine
import org.fcitx.fcitx5.android.script.qjs.QjsException
import org.json.JSONObject
import timber.log.Timber
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * JS 脚本插件：从 files/plugins/<id>/plugin.js 加载，用 QuickJS-ng 沙箱执行。
 *
 * 引擎增强（大数据场景）：
 * - **实例缓存**：每个 ScriptPlugin 懒创建并复用 QjsEngine（不再每次新建），
 *   load 一次把「数据全局变量 + 插件脚本」常驻内存，后续 call 复用上下文；
 * - **数据全局注入**：rules.json/pinyin.json/data.json 拼接为 JS 源码前缀
 *   `var __RULES__=...; var __PINYIN__=...; var __DATA__=...;` 随 load 一次解析，
 *   脚本直接从全局变量读取，避免每次 call 传大 JSON（受 input 限制 2MB 且慢）；
 * - **串行化**：QjsEngine 非线程安全，所有 call 经 synchronized 串行；
 * - **关闭**：[closeEngine] 在插件重扫/卸载时释放 native 内存。
 *
 * 脚本约定（引擎层强制，见 meng-qjs README）：
 * - 只标准 ECMAScript，无 std/os/文件/网络/定时器/宿主函数；
 * - 定义全局函数  transform(text: string, ctx: object): string ，必须返回字符串；
 * - 异常统一 QjsException（SCRIPT/TIMEOUT/INTERRUPTED/OOM/BAD_RESULT/...），
 *   任何异常由管线兜底上屏原文。
 *
 * 资源限制（QjsEngine）：128MB 内存、256KB 栈、64MB source、2MB 输入、8MB 输出；
 * 单次求值 [SCRIPT_CALL_TIMEOUT_MS]。连续 3 次 TIMEOUT/OOM 熔断停用，
 * 由 PluginPipeline/Host 统计。
 */
class ScriptPlugin private constructor(
    override val manifest: PluginManifest,
    private val scriptSource: String,
    /** 可选 rules.json 数据（拼接为全局 __RULES__；null 则不含） */
    private val rulesJson: String?,
    /** 可选 pinyin.json 数据（拼接为全局 __PINYIN__；null 则不含） */
    private val pinyinJson: String?,
    /** 可选 data.json 转换数据（拼接为全局 __DATA__；null 则不含） */
    private val dataJson: String?,
    /** 可选词典分类文件（dict.txt / dict_*.txt，宿主侧流式按需查询，不整读进堆；空则无词典） */
    private val dictFiles: List<File>,
) : Plugin, PasteTransformHook {

    /** 懒创建且复用的 QuickJS 引擎：数据随 load 一次常驻 */
    @Volatile
    private var engine: QjsEngine? = null
    @Volatile
    private var disposed = false
    private val engineLock = Any()

    /** 获取（必要时创建）引擎：load 数据全局变量 + 脚本。
     *  插件已销毁（closeEngine 后）不再创建新实例，抛 IllegalStateException 由调用方兜底。 */
    private fun engine(): QjsEngine {
        engine?.let { return it }
        synchronized(engineLock) {
            if (disposed) throw IllegalStateException("plugin ${manifest.id} disposed")
            engine?.let { return it }
            val qjs = QjsEngine()
            qjs.load(buildSource(), "${manifest.id}/plugin.js", SCRIPT_LOAD_TIMEOUT_MS)
            engine = qjs
            return qjs
        }
    }

    /** 拼接数据头（全局变量）+ 插件脚本，作为一次 load 的完整 source */
    private fun buildSource(): String {
        val sb = StringBuilder(scriptSource.length + (rulesJson?.length ?: 0) +
            (pinyinJson?.length ?: 0) + (dataJson?.length ?: 0) + 256)
        if (rulesJson != null) {
            sb.append("var __RULES__=").append(rulesJson).append(";\n")
        }
        if (pinyinJson != null) {
            sb.append("var __PINYIN__=").append(pinyinJson).append(";\n")
        }
        if (dataJson != null) {
            sb.append("var __DATA__=").append(dataJson).append(";\n")
        }
        sb.append(scriptSource)
        // 面板插件（action=panel）的 renderPanel 返回 JS 对象，而引擎 call 只接受字符串
        // 返回值（native 层 "result is not a string"）。统一包一层自动 JSON.stringify，
        // 宿主 PluginPanelRenderer.render(model: String) 恰好消费 JSON 字符串，插件脚本零改动。
        if (manifest.action == "panel") {
            sb.append(
                "\nif (typeof __meng_panel_wrap__ === 'undefined') {\n" +
                "  var __meng_orig_renderPanel__ = renderPanel;\n" +
                "  renderPanel = function(query, ctx) {\n" +
                "    var r = __meng_orig_renderPanel__(query, ctx);\n" +
                "    if (typeof r === 'string') return r;\n" +
                "    return JSON.stringify(r);\n" +
                "  };\n" +
                "}\n"
            )
        }
        return sb.toString()
    }

    /** 释放引擎与词典 db（插件重扫/卸载时调用）；幂等。
     *  并发安全：与 call 持有的锁（引擎实例）先串行，再在 engineLock 内交换引用。
     *  call 用 synchronized(qjs) 锁引擎实例本身，close 先拿同一把实例锁再关闭，
     *  保证不会在 call 执行中途销毁引擎（QuickJS 非线程安全，否则 use-after-free 崩溃）。 */
    fun closeEngine() {
        val e = synchronized(engineLock) {
            disposed = true
            val cur = engine
            engine = null
            cur
        } ?: run { closeDictDb(); return }
        synchronized(e) {
            runCatching { e.close() }
        }
        closeDictDb()
    }

    /** 是否大数据插件（数据源码合计超 256KB，需首次预热避免管线超时） */
    fun isHeavy(): Boolean {
        val d = dataJson?.length ?: 0
        val p = pinyinJson?.length ?: 0
        val r = rulesJson?.length ?: 0
        val dict = dictFiles.sumOf { it.length() }
        return d + p + r + dict > 256 * 1024
    }

    /**
     * 预热：后台线程加载引擎（大数据 load 可达数秒），避免首次粘贴时
     * 被管线 100ms 超时打断（首次调用回退原文，预热后第二次即正常）。
     * 幂等：引擎已存在则无操作。
     * 注意：dict.txt 不在预热读取——53MB 词典按需流式查询时才会读，
     * 避免启动/扫描期任何大对象入堆。
     */
    fun warmUp() {
        if (engine != null) return
        Thread({
            try {
                if (engine == null) engine()
            } catch (e: Throwable) {
                Timber.w("ScriptPlugin ${manifest.id} warmUp failed: ${e.message}")
            }
        }, "plugin-warmup-${manifest.id}").apply {
            isDaemon = true
            start()
        }
    }

    override suspend fun onPaste(ctx: PasteContext, text: String): PasteResult {
        // ctxJson：只给只读字段 + 插件自身声明设置 + 自定义镜像（小数据），
        // 大数据（rules/pinyin/data）走全局变量，不随 call 传递。
        val ctxJson = buildCtxJson(ctx)
        return try {
            val qjs = engine()
            val out = synchronized(qjs) {
                qjs.call(ENTRY_FUNCTION, text, ctxJson, SCRIPT_CALL_TIMEOUT_MS)
            }
            // 返回字符串 == text 则未改动，走 NotHandled 避免污染
            if (out == text) PasteResult.NotHandled else PasteResult.Text(out)
        } catch (e: QjsException) {
            // 任何脚本异常/超时/过大 → 上屏原文，由管线统计熔断
            Timber.w("ScriptPlugin ${manifest.id} failed: ${e.kind}, ${e.message}")
            PasteResult.NotHandled
        } catch (e: Throwable) {
            Timber.w(e, "ScriptPlugin ${manifest.id} host error")
            PasteResult.NotHandled
        }
    }

    private fun buildCtxJson(ctx: PasteContext): String {
        val o = JSONObject()
        o.put("isUrlField", ctx.isUrlField)
        o.put("isEmailField", ctx.isEmailField)
        o.put("isMultiline", ctx.isMultiline)
        if (ctx.targetPackage != null) o.put("targetPackage", ctx.targetPackage)
        // 注入插件自身声明的设置（宿主存储值；未设置时用 manifest 默认值）
        if (manifest.settings.isNotEmpty()) {
            val s = JSONObject()
            for (ps in manifest.settings) {
                s.put(ps.key, PluginHost.settingOf(manifest.id, ps.key, ps.defaultValue))
            }
            // 单次授权（额外赋权）：标记为 once 的设置项运行时强制置 true，
            // 这一次粘贴生效一次；粘贴后 PasteGateway 的 consumeOnceModes 清标记。
            val once = JSONObject()
            for (ps in manifest.settings) {
                if (PluginHost.isOnceMode(manifest.id, ps.key)) {
                    s.put(ps.key, "true") // 运行时强制开启这一次（额外赋权，不改存储）
                    once.put(ps.key, true)
                }
            }
            if (once.length() > 0) s.put("_once", once)
            o.put("settings", s)
        }
        // 注入用户自定义链接镜像映射（A→B），覆盖/追加内置 mirrorRules
        val customMirror = PluginHost.customMirror(manifest.id)
        if (customMirror.isNotEmpty()) {
            o.put("customMirror", JSONObject(customMirror))
        }
        return o.toString()
    }

    /**
     * 【面板自绘出口】action=panel 的脚本插件调用宿主通用面板窗口时，产出声明式 UI 树 JSON。
     *
     * 脚本约定：定义全局函数  renderPanel(query: string, ctx: object): object 返回一段用
     * **宿主统一基础组件**（frame 模式）描述的 UI 树；宿主通用渲染器翻译成 View 并上屏。
     * 非 panel 插件返回 null。
     *
     * [query] 可选查询词（由宿主面板打开时注入，如当前输入框全文或词典搜索结果），
     * 脚本可据此生成动态内容。
     * [ctxJson] 附加上下文 JSON（当前仅面板插件自行输出 UI 树时使用，宿主不注入额外键）。
     *
     * 基础组件（v1，宿主通用，不含任何艺术字专用逻辑）：
     *   {type:"label", text, size?}
     *   {type:"grid", cols?, cells:[{text, commit?}]}      // commit 缺省 = text；点按上屏 commit
     *   {type:"tabs", tabs:[{label, body:<node>}]}          // 多分类：上排标签 + 下方 body
     *
     * 完全自由绘制属于预留许可（manifest 声明 ui:"free"），本轮不开放，返回 null。
     */
    fun panelModel(query: String? = null, ctxJson: String? = null): String? {
        if (manifest.action != "panel") return null
        if (manifest.ui == "free") return null // 自由绘制需另行申请许可，见 PluginStatus.kt
        return try {
            val qjs = engine()
            // 未显式传 ctxJson 时，自动注入 {query, results, settings}：
            // results 来自宿主侧词典搜索（无 dict 则为空数组），
            // settings 为插件自身声明的设置值（宿主存储；未设置用 manifest 默认值），
            // 脚本 renderPanel(query, ctx) 可直接渲染搜索结果/按设置报价。
            val effCtx = ctxJson ?: runCatching {
                org.json.JSONObject()
                    .put("query", query ?: "")
                    .put("results", org.json.JSONArray(searchDict(query ?: "")))
                    .also { o -> injectSettings(o) }
                    .toString()
            }.getOrDefault("{}")
            synchronized(qjs) {
                qjs.call(PANEL_FUNCTION, query ?: "", effCtx, PANEL_CALL_TIMEOUT_MS)
            }.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.w("ScriptPlugin ${manifest.id} renderPanel failed: ${e.message}")
            null
        }
    }

    /** 把插件自身声明的设置注入 ctx JSON（与粘贴通道 buildCtxJson 同源逻辑）。 */
    private fun injectSettings(o: org.json.JSONObject) {
        if (manifest.settings.isEmpty()) return
        val s = org.json.JSONObject()
        for (ps in manifest.settings) {
            s.put(ps.key, PluginHost.settingOf(manifest.id, ps.key, ps.defaultValue))
        }
        // 单次授权：标记为 once 的设置项运行时强制置 true（额外赋权生效这一次，不改存储），
        // 并附上 "_once":true 供 JS 显示「⚡单次」状态。粘贴后 consumeOnceModes 清标记。
        val once = org.json.JSONObject()
        for (ps in manifest.settings) {
            if (PluginHost.isOnceMode(manifest.id, ps.key)) {
                s.put(ps.key, "true") // 运行时强制开启这一次（额外赋权）
                once.put(ps.key, true)
            }
        }
        if (once.length() > 0) s.put("_once", once)
        o.put("settings", s)
    }

    /**
     * 【宿主侧词典搜索】dict.txt 首次查询时懒构建为 SQLite（dict.db）缓存，
     * 之后全部走索引点查/前缀查，毫秒级；不再每次全扫 108 万行。
     *
     * 命中策略（按序，够了就停）：
     *  1. 精确点查：word = ?（主键 B-tree）
     *  2. 前缀匹配：word LIKE 'q%'（走索引，ASCII 大小写不敏感，英文 zz/ZZ 都能中）
     *  3. 子串兜底：word LIKE '%q%'（全扫但 LIMIT 提前停，仅短 query 用）
     *  4. 释义反查（插件 manifest 声明 dict.defSearch=true 时启用）：
     *     def LIKE '%q%' 且释义含中文（输入释义关键词也能找到词条；
     *     纯拼音释义被排除，避免拼音串刷屏）
     * 长文本（>8 字符，如输入框全文）：最大正向匹配——逐位置尝试 1..6 长度点查，
     * 收集输入框里出现的词条（O(文本长×6) 点查，毫秒级）。
     *
     * 返回 JSON 数组 [{word, def}]。query 为空 → 空数组。无 dict.txt → null。
     */
    fun searchDict(query: String, maxResults: Int = 20): String? {
        if (dictFiles.isEmpty()) return null
        if (query.isBlank()) return "[]"
        val db = ensureDictDb() ?: return "[]"
        val q = query.trim()
        return runCatching {
            val out = org.json.JSONArray()
            val seen = java.util.HashSet<String>()
            // 长文本（输入框全文）：最大正向匹配找词条
            if (q.length > 8) {
                scanTextMatches(db, q, out, seen, maxResults)
                return@runCatching out.toString()
            }
            // 短 query：精确 → 前缀 → 子串 → 释义反查
            addExact(db, q, out, seen, maxResults)
            if (out.length() < maxResults) addPrefix(db, q, out, seen, maxResults)
            if (out.length() < maxResults && q.length <= 6) {
                addSubstring(db, q, out, seen, maxResults)
            }
            if (out.length() < maxResults && manifest.dictDefSearch) {
                addDefReverse(db, q, out, seen, maxResults)
            }
            out.toString()
        }.getOrNull() ?: "[]"
    }

    // ===== 词典 SQLite 缓存（懒构建一次，之后直开）=====

    @Volatile
    private var dictDb: SQLiteDatabase? = null
    @Volatile
    private var dictDbBuilding = false
    private val dictDbLock = Any()

    private fun ensureDictDb(): SQLiteDatabase? {
        if (dictFiles.isEmpty()) return null
        dictDb?.let { return it }
        synchronized(dictDbLock) {
            dictDb?.let { return it }
            val dbFile = File(dictFiles[0].parentFile, "dict.db")
            // 缓存失效：任一 dict_*.txt 比 dict.db 新（如插件升级带新词条）→ 删旧重建，
            // 否则用户更新插件后旧缓存仍被直开，新词条查不到。
            if (dbFile.exists() && dictFiles.any { it.lastModified() > dbFile.lastModified() }) {
                runCatching { dictDb?.close() }
                dictDb = null
                dbFile.delete()
            }
            if (dbFile.exists() && dbFile.length() > 0) {
                return runCatching {
                    SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                        .also { dictDb = it }
                }.getOrNull()
            }
            if (dictDbBuilding) return null // 另一线程构建中，稍后重试
            dictDbBuilding = true
            return try {
                buildDictDb(dictFiles, dbFile)
            } catch (e: Exception) {
                Timber.w("ScriptPlugin ${manifest.id} buildDictDb failed: ${e.message}")
                null
            } finally {
                dictDbBuilding = false
            }
        }
    }

    /** 从全部 dict_*.txt 流式构建 dict.db（TEXT 列 + 主键 B-tree，事务批量插入） */
    private fun buildDictDb(srcs: List<File>, dst: File): SQLiteDatabase? {
        Timber.i("ScriptPlugin ${manifest.id} building dict.db from ${srcs.size} file(s)...")
        val write = SQLiteDatabase.openOrCreateDatabase(dst, null)
        try {
            write.execSQL("CREATE TABLE IF NOT EXISTS dict(word TEXT PRIMARY KEY, def TEXT) WITHOUT ROWID")
            val stmt = write.compileStatement("INSERT OR IGNORE INTO dict VALUES (?,?)")
            var count = 0
            write.beginTransaction()
            try {
                for (src in srcs) {
                    src.bufferedReader(Charsets.UTF_8).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isEmpty()) continue
                            val tab = line.indexOf('\t')
                            if (tab <= 0) continue
                            stmt.bindString(1, line.substring(0, tab))
                            stmt.bindString(2, line.substring(tab + 1))
                            stmt.executeInsert()
                            count++
                            if (count % 5000 == 0) {
                                write.setTransactionSuccessful()
                                write.endTransaction()
                                write.beginTransaction()
                            }
                        }
                    }
                }
                write.setTransactionSuccessful()
            } finally {
                write.endTransaction()
            }
            stmt.close()
            write.close()
            Timber.i("ScriptPlugin ${manifest.id} dict.db built: $count rows")
        } catch (e: Exception) {
            write.close()
            dst.delete()
            throw e
        }
        dictDb?.let { return it }
        return runCatching {
            SQLiteDatabase.openDatabase(dst.path, null, SQLiteDatabase.OPEN_READONLY)
                .also { dictDb = it }
        }.getOrNull()
    }

    fun closeDictDb() {
        synchronized(dictDbLock) {
            dictDb?.close()
            dictDb = null
        }
    }

    /** 精确点查 */
    private fun addExact(
        db: SQLiteDatabase, q: String,
        out: org.json.JSONArray, seen: java.util.HashSet<String>, max: Int,
    ) {
        if (out.length() >= max) return
        db.rawQuery("SELECT word, def FROM dict WHERE word = ?", arrayOf(q)).use { c ->
            if (c.moveToFirst() && !seen.contains(c.getString(0))) {
                seen.add(c.getString(0))
                out.put(org.json.JSONObject().put("word", c.getString(0)).put("def", c.getString(1)))
            }
        }
    }

    /** 前缀匹配（LIKE 走索引，ASCII 大小写不敏感） */
    private fun addPrefix(
        db: SQLiteDatabase, q: String,
        out: org.json.JSONArray, seen: java.util.HashSet<String>, max: Int,
    ) {
        if (out.length() >= max) return
        val pat = escapeLike(q) + "%"
        db.rawQuery("SELECT word, def FROM dict WHERE word LIKE ? ESCAPE '\\' LIMIT ?",
            arrayOf(pat, max.toString())).use { c ->
            while (c.moveToNext() && out.length() < max) {
                val w = c.getString(0)
                if (seen.add(w)) {
                    out.put(org.json.JSONObject().put("word", w).put("def", c.getString(1)))
                }
            }
        }
    }

    /** 子串兜底（仅短 query；全扫但 LIMIT 提前停） */
    private fun addSubstring(
        db: SQLiteDatabase, q: String,
        out: org.json.JSONArray, seen: java.util.HashSet<String>, max: Int,
    ) {
        if (out.length() >= max) return
        val pat = "%" + escapeLike(q) + "%"
        db.rawQuery("SELECT word, def FROM dict WHERE word LIKE ? ESCAPE '\\' LIMIT ?",
            arrayOf(pat, max.toString())).use { c ->
            while (c.moveToNext() && out.length() < max) {
                val w = c.getString(0)
                if (seen.add(w)) {
                    out.put(org.json.JSONObject().put("word", w).put("def", c.getString(1)))
                }
            }
        }
    }

    /** 释义反查：def LIKE '%q%'，仅收含中文的释义（排除纯拼音），全扫 LIMIT 提前停 */
    private fun addDefReverse(
        db: SQLiteDatabase, q: String,
        out: org.json.JSONArray, seen: java.util.HashSet<String>, max: Int,
    ) {
        if (out.length() >= max) return
        val pat = "%" + escapeLike(q) + "%"
        // 逐行过滤：跳过纯拼音/纯 ASCII 释义（frost 词库大量词条 def 仅为拼音串），
        // 放宽扫描上限为 max*5 行，凑满 max 条含中文释义即停
        db.rawQuery("SELECT word, def FROM dict WHERE def LIKE ? ESCAPE '\\'", arrayOf(pat)).use { c ->
            var scanned = 0
            while (c.moveToNext() && out.length() < max && scanned < max * 5) {
                scanned++
                val w = c.getString(0)
                val d = c.getString(1) ?: ""
                // 跳过纯拼音/纯 ASCII 释义（frost 词库大量词条 def 仅为拼音串）
                val hasChinese = d.any { it.code >= 0x4E00 && it.code <= 0x9FFF }
                if (!hasChinese) continue
                if (seen.add(w)) {
                    out.put(org.json.JSONObject().put("word", w).put("def", d))
                }
            }
        }
    }

    /** 长文本最大正向匹配：逐位置尝试 1..6 长度点查，收集输入框中出现的词条 */
    private fun scanTextMatches(
        db: SQLiteDatabase, text: String,
        out: org.json.JSONArray, seen: java.util.HashSet<String>, max: Int,
    ) {
        val n = text.length
        // 限制扫描长度：全文过长只取前 300 字符，避免点查过多
        val limit = minOf(n, 300)
        var i = 0
        while (i < limit && out.length() < max) {
            var matched = false
            val maxLen = minOf(6, limit - i)
            var l = maxLen
            while (l >= 1 && !matched) {
                val sub = text.substring(i, i + l)
                if (!seen.contains(sub)) {
                    db.rawQuery("SELECT word, def FROM dict WHERE word = ?", arrayOf(sub)).use { c ->
                        if (c.moveToFirst()) {
                            val w = c.getString(0)
                            if (seen.add(w)) {
                                out.put(org.json.JSONObject().put("word", w).put("def", c.getString(1)))
                            }
                            matched = true
                        }
                    }
                }
                l--
            }
            i += if (matched) l + 1 else 1
        }
    }

    /** LIKE 模式转义：% _ \ 前缀加反斜杠 */
    private fun escapeLike(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (ch in s) {
            when (ch) {
                '%', '_', '\\' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        // 数据全局变量随源码一次 load：687KB pinyin + 1.19MB data 解析耗时较长，
        // 首次加载放宽到 8s（仅一次，后续 call 复用引擎不再 load）。
        private const val SCRIPT_LOAD_TIMEOUT_MS = 8000
        private const val SCRIPT_CALL_TIMEOUT_MS = 50
        private const val PANEL_CALL_TIMEOUT_MS = 50
        /** 脚本出口函数名（固定约定，见 meng-qjs README） */
        const val ENTRY_FUNCTION = "transform"
        /** 面板自绘出口函数名（action=panel 时宿主调用） */
        const val PANEL_FUNCTION = "renderPanel"

        /** 从目录加载脚本插件；缺 manifest.json 或 plugin.js、type!=script 返回 null */
        fun from(dir: File): ScriptPlugin? {
            if (!dir.isDirectory) return null
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) return null
            val manifest = MengPackage.parseManifest(mf.readText()) ?: return null
            if (manifest.type != "script") return null
            val script = File(dir, "plugin.js")
            if (!script.exists() || script.length() == 0L) return null
            val rulesFile = File(dir, "rules.json")
            val rulesText = if (rulesFile.exists()) rulesFile.readText() else null
            val pinyinFile = File(dir, "pinyin.json")
            val pinyinText = if (pinyinFile.exists()) pinyinFile.readText() else null
            val dataFile = File(dir, "data.json")
            val dataText = if (dataFile.exists()) dataFile.readText() else null
            // 词典分类文件不整读：ScriptPlugin 只持 File 引用，宿主侧按需查询。
            // 支持 dict.txt + dict_*.txt 多分类文件（用户「分类储存，不单文件」需求），
            // 排序保证构建顺序稳定（dict.txt 优先，其余按名序）。
            val dictFiles = dir.listFiles { f -> f.isFile && f.name.matches(Regex("^dict[^/]*\\.txt$")) }
                ?.sortedBy { it.name } ?: emptyList()
            return ScriptPlugin(
                manifest = manifest,
                scriptSource = script.readText(),
                rulesJson = rulesText,
                pinyinJson = pinyinText,
                dataJson = dataText,
                dictFiles = dictFiles,
            )
        }
    }
}