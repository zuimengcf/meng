/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin
import android.content.Context
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
/**
 * 插件加载器：来源注册 → 校验纳管 → 状态机 → 快照分发。
 *
 * 插件全部来自 files/plugins/<id>/（.meng 独立安装包解包目录），不内置任何插件。
 * 校验规则（加载即拒，运行不炸）：id 冲突 / apiVersion 高于宿主 / hooks 声明
 * 与实现接口不符 → Incompatible 展示，不抛异常。
 */
object PluginHost {
    private val sources = CopyOnWriteArrayList<PluginSource>()
    /** 全部纳管插件（含 Incompatible），顺序=注册序 */
    private val all = CopyOnWriteArrayList<Plugin>()
    /** id → 来源类型 缓存，避免每次 snapshot 全量重扫磁盘（sourceKindOf 用） */
    private val sourceKindCache = HashMap<String, PluginSourceKind>()
    @Volatile private var initialized = false
    /** 首次访问时初始化（PasteGateway 首次使用时惰性初始化） */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        registerSource(ExternalSource)
    }
    fun registerSource(source: PluginSource) {
        if (sources.any { it.kind == source.kind }) return
        sources.add(source)
        reloadFrom(source)
    }
    /** 目录变化（安装/卸载插件后）调用来重扫 */
    @Synchronized
    fun invalidate() {
        // 释放旧插件持有的 QuickJS 引擎（大数据常驻需显式回收）
        all.forEach { plugin ->
            if (plugin is ScriptPlugin) plugin.closeEngine()
        }
        all.clear()
        sourceKindCache.clear()
        sources.forEach { reloadFrom(it) }
    }
    private fun reloadFrom(source: PluginSource) {
        val seen = HashSet<String>()
        for (plugin in source.discover()) {
            val m = plugin.manifest
            if (!seen.add(m.id) || all.any { it.manifest.id == m.id }) continue // id 冲突拒载
            all.add(plugin)
            sourceKindCache[m.id] = source.kind
            // 大数据脚本插件（pinyin/data 数 MB）首次加载耗时可能数秒，
            // 后台预热引擎，避免首次粘贴被管线 100ms 超时打断。
            if (plugin is ScriptPlugin && plugin.isHeavy()) plugin.warmUp()
        }
    }
    /** 管线执行前快照：运行中启停不影响当次执行 */
    fun snapshot(): List<PluginInfo> {
        ensureInitialized()
        return all.map { plugin ->
            val m = plugin.manifest
            val state = when {
                !compatible(plugin) -> PluginState.Incompatible
                !userEnabled(m) -> PluginState.Disabled
                PluginPipeline.shouldTrip(m.id) -> PluginState.Tripped
                else -> PluginState.Enabled
            }
            PluginInfo(
                manifest = m,
                state = state,
                stats = PluginPipeline.statsOf(m.id),
                source = sourceKindOf(m.id),
            )
        }
    }
    /** 管线取可用插件：Enabled 且声明了 PasteTransform 且实现正确 */
    fun activePastePlugins(): List<Plugin> {
        ensureInitialized()
        return all.filter { p ->
            val m = p.manifest
            compatible(p) && userEnabled(m) && !PluginPipeline.shouldTrip(m.id) &&
                PluginHook.PasteTransform in m.hooks && p is PasteTransformHook
        }.sortedByDescending { it.manifest.priority }
    }
    /**【.meng 安装】安装成功后重扫 */
    fun installMeng(uri: android.net.Uri): MengPackage.InstallResult {
        ensureInitialized()
        val r = MengPackage.install(uri, appContext)
        if (r.success) invalidate()
        return r
    }

    /**
     * 【zip 容器安装】一个 zip 内可含一个或多个 .meng 插件，逐个按现有流程安装。
     *
     * 兼容策略（按内容判断，不依赖 mime）：
     * - 外层 zip 内含 .meng 条目 → 批量容器，逐个安装，返回每个的独立结果；
     * - 无 .meng 条目（即选中的就是单个 .meng 包本身）→ 按单包安装。
     * 每个子包仍走 MengPackage.installFromFile 校验 + 落盘；任一成功都会触发重扫。
     */
    data class ZipInstallResult(
        val results: List<MengPackage.InstallResult>,
        val okCount: Int = results.count { it.success },
    )

    fun installMengOrZip(uri: android.net.Uri): ZipInstallResult {
        ensureInitialized()
        // 先拷贝到本地缓存文件，避免流式 zip 多次读取同一 uri 的兼容问题
        val tmp = File(appContext.cacheDir, "mengzip_${System.currentTimeMillis()}.zip")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return ZipInstallResult(emptyList())
        } catch (e: Exception) {
            return ZipInstallResult(listOf(MengPackage.InstallResult(false, error = "unreadable: ${e.message}")))
        }
        var changed = false
        val results = runCatching {
            val innerMengs = ZipFile(tmp).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".meng", ignoreCase = true) }
                    .filter { !it.name.contains("..") && !it.name.startsWith("/") } // 防路径穿越
                    .toList()
            }
            if (innerMengs.isEmpty()) {
                // 没有 .meng 条目：选中物本身就是单个 .meng 包，直接按单包安装
                val r = MengPackage.installFromFile(tmp, appContext)
                if (r.success) changed = true
                listOf(r)
            } else {
                ZipFile(tmp).use { zip ->
                    innerMengs.map { entry ->
                        val inner = File(
                            appContext.cacheDir,
                            "inner_${System.currentTimeMillis()}_${entry.name.replace('/', '_')}"
                        )
                        try {
                            zip.getInputStream(entry).use { i -> inner.outputStream().use { o -> i.copyTo(o) } }
                            val r = MengPackage.installFromFile(inner, appContext)
                            if (r.success) changed = true
                            r
                        } catch (e: Exception) {
                            MengPackage.InstallResult(false, error = e.message)
                        } finally {
                            inner.delete()
                        }
                    }
                }
            }
        }.getOrElse { e ->
            listOf(MengPackage.InstallResult(false, error = "invalid zip: ${e.message}"))
        }
        tmp.delete()
        if (changed) invalidate()
        return ZipInstallResult(results)
    }

    /** 兼容别名：批量容器安装 */
    fun installMengZip(uri: android.net.Uri): ZipInstallResult = installMengOrZip(uri)
    /**【.meng 卸载】删除插件目录，恢复未装状态 */
    fun uninstall(pluginId: String): Boolean {
        ensureInitialized()
        val dir = File(appContext.filesDir, "plugins/$pluginId")
        if (!dir.exists()) return false
        prefEdit().remove(pluginKey(pluginId)).commit()
        PluginPipeline.resetStats(pluginId)
        return runCatching { dir.deleteRecursively() }.getOrDefault(false).also {
            if (it) invalidate()
        }
    }

    /**【首次安装默认关闭】新装插件（官方/第三方一视同仁）默认 enabled=false，
     *  用户在插件列表手动开启后才参与粘贴管线/面板。升级（目录已存在）不受影响，
     *  保留用户既有开关状态。由 MengPackage.installFromFile 在落盘成功后调用。 */
    fun markNewInstall(pluginId: String) {
        prefEdit().putBoolean(pluginKey(pluginId), false).commit()
    }
    fun setEnabled(pluginId: String, enabled: Boolean) {
        prefEdit().putBoolean(pluginKey(pluginId), enabled).commit()
        if (enabled) PluginPipeline.resetStats(pluginId) // 重新启用时清熔断
    }
    fun resetBreaker(pluginId: String) {
        PluginPipeline.resetStats(pluginId)
    }
    fun isEnabled(pluginId: String): Boolean {
        ensureInitialized()
        val plugin = all.firstOrNull { it.manifest.id == pluginId } ?: return false
        return userEnabled(plugin.manifest) && !PluginPipeline.shouldTrip(pluginId)
    }

    /** 取面板型脚本插件的自绘 UI 模型（action=panel 且 ui=frame）；无则 null */
    fun panelModelOf(pluginId: String, query: String? = null, ctxJson: String? = null): String? {
        ensureInitialized()
        val plugin = all.firstOrNull { it.manifest.id == pluginId } ?: return null
        if (plugin is ScriptPlugin) return plugin.panelModel(query, ctxJson)
        return null
    }

    /** 宿主侧词典搜索（dict.txt 数据不注入脚本，避免超限）；无词典返回 null */
    fun searchDictOf(pluginId: String, query: String, maxResults: Int = 20): String? {
        ensureInitialized()
        val plugin = all.firstOrNull { it.manifest.id == pluginId } ?: return null
        if (plugin is ScriptPlugin) return plugin.searchDict(query, maxResults)
        return null
    }

    // ===== 插件菜单数据：自定义规则（插件注册菜单，宿主存储）=====
    private val customRulesCache = HashMap<String, Set<String>>()
    /** 读取插件自定义参数（黑名单追加项），无则空集 */
    @Synchronized
    fun customRules(pluginId: String): Set<String> =
        customRulesCache.getOrPut(pluginId) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(customKey(pluginId), emptySet()) ?: emptySet()
        }
    /** 整体替换自定义规则（详情页编辑保存） */
    @Synchronized
    fun setCustomRules(pluginId: String, rules: Set<String>) {
        val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefEdit().putStringSet(customKey(pluginId), cleaned).commit()
        customRulesCache[pluginId] = cleaned
    }
    /** 追加单条自定义规则 */
    @Synchronized
    fun addCustomRule(pluginId: String, rule: String) {
        val cur = customRules(pluginId).toMutableSet()
        cur.add(rule.trim())
        setCustomRules(pluginId, cur)
    }
    private fun customKey(pluginId: String) = "plugin_${pluginId}_custom_rules"

    // ===== 插件自定义设置：宿主存储，运行时注入（升级不丢配置）=====
    /** 读取插件设置值；无值时返回 manifest 声明的 defaultValue */
    fun settingOf(pluginId: String, key: String, defaultValue: String): String {
        val stored = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(settingKey(pluginId, key), null)
        return stored ?: defaultValue
    }
    /** 写入插件设置值 */
    fun setSetting(pluginId: String, key: String, value: String) {
        prefEdit().putString(settingKey(pluginId, key), value).commit()
    }

    // ===== 插件自定义链接镜像映射（A→B）：设置页编辑，运行时注入 JS =====
    /** 读取插件自定义镜像映射（源域→目标域）；无则为空 Map */
    @Synchronized
    fun customMirror(pluginId: String): Map<String, String> =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(mirrorKey(pluginId), emptySet())
            ?.mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else (line.substring(0, idx).trim() to line.substring(idx + 1).trim())
            }?.toMap() ?: emptyMap()
    /** 整体替换自定义镜像映射（每行 "源=目标"） */
    @Synchronized
    fun setCustomMirror(pluginId: String, map: Map<String, String>) {
        val cleaned = map.mapNotNull { (from, to) ->
            val f = from.trim(); val t = to.trim()
            if (f.isEmpty() || t.isEmpty()) null else "$f=$t"
        }.toSet()
        prefEdit().putStringSet(mirrorKey(pluginId), cleaned).commit()
    }
    private fun mirrorKey(pluginId: String) = "plugin_${pluginId}_custom_mirror"
    private fun settingKey(pluginId: String, key: String) = "plugin_${pluginId}_setting_${key}"

    // ===== 插件注册菜单的 toggle 开关值（插件 manifest 注册，宿主存储）=====
    /** 读取插件 toggle 菜单当前开关值；无则用 manifest 声明的 defaultValue */
    fun menuSwitchValue(pluginId: String, menu: PluginMenu): Boolean {
        val pref = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = menuKey(pluginId, menu.id)
        return if (pref.contains(key)) pref.getBoolean(key, menu.defaultValue) else menu.defaultValue
    }
    /** 写入插件 toggle 菜单开关值 */
    fun setMenuSwitchValue(pluginId: String, menu: PluginMenu, value: Boolean) {
        prefEdit().putBoolean(menuKey(pluginId, menu.id), value).commit()
    }
    private fun menuKey(pluginId: String, menuId: String) = "plugin_${pluginId}_menu_${menuId}"

    // ===== 插件设置项「单次生效」标记（宿主通用能力，插件零改动）=====
    // 「单次」是独立于常开开关的一次性授权：开启时记下开关原值并临时置 true，
    // 下一次粘贴管线消费后恢复原值。与「常开」开关互不干扰：
    // 常开开关照常两态切换；单次只在下一次粘贴生效一次，随后开关回到原状态。
    // key 统一为 "plugin_<id>_setting_once_<settingKey>"，
    // 原值备份存 "plugin_<id>_setting_once_prev_<settingKey>"。

    /** 读取插件设置项是否为「单次」待消费状态 */
    fun isOnceMode(pluginId: String, settingKey: String): Boolean =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(onceKey(pluginId, settingKey), false)

    /** 把插件设置项置为「单次」：仅打标记（不改变总开关值）。
     *  总开关（常开）是独立总开关，开着就一直开着；单次是叠加授权——
     *  勾单次只标记「待触发一次」，运行时由 injectSettings 强制生效一次，
     *  用完 consumeOnceModes 清标记，总开关值保持原样（开回开、关回关）。 */
    fun enableOnceMode(pluginId: String, settingKey: String, defaultValue: String = "false") {
        prefEdit()
            .putBoolean(onceKey(pluginId, settingKey), true)
            .commit()
    }

    /** 取消「单次」标记（一次性授权撤销），总开关值不动。
     *  单次是额外赋权，撤销只清标记，不触碰总开关。 */
    fun clearOnceMode(pluginId: String, settingKey: String, defaultValue: String = "false") {
        val pref = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (pref.getBoolean(onceKey(pluginId, settingKey), false)) {
            prefEdit()
                .remove(onceKey(pluginId, settingKey))
                .commit()
        }
    }

    /** 单个设置项的一次性消费：清单次标记（一次性授权结束）。
     *  总开关值全程不动（单次是额外赋权，不触碰总开关）——用完总开关保持原样，
     *  开回开、关回关。快捷面板回普通「开/关」两态。 */
    fun consumeOnceMode(pluginId: String, settingKey: String, defaultValue: String = "false") {
        val pref = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!pref.getBoolean(onceKey(pluginId, settingKey), false)) return
        prefEdit()
            .remove(onceKey(pluginId, settingKey))
            .commit()
    }

    /** 管线消费：把所有「单次」设置项清标记（一次性授权结束）。
     *  总开关值全程不动（单次是额外赋权，用完总开关保持原样，开回开、关回关）；
     *  下次要用需重新勾选/重新点快捷「⚡单次」。
     *  返回被消费的 key 列表（供 UI 提示）。 */
    @Synchronized
    fun consumeOnceModes(pluginId: String): List<String> {
        val m = all.firstOrNull { it.manifest.id == pluginId }?.manifest ?: return emptyList()
        val consumed = mutableListOf<String>()
        val editor = prefEdit()
        for (ps in m.settings) {
            val k = onceKey(pluginId, ps.key)
            if (appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(k, false)) {
                // 清标记（一次性授权结束）；总开关值不动
                editor.remove(k)
                consumed.add(ps.key)
            }
        }
        if (consumed.isNotEmpty()) editor.commit()
        return consumed
    }

    private fun onceKey(pluginId: String, settingKey: String) = "plugin_${pluginId}_setting_once_${settingKey}"
    private fun oncePrevKey(pluginId: String, settingKey: String) = "plugin_${pluginId}_setting_once_prev_${settingKey}"
    private fun compatible(plugin: Plugin): Boolean {
        val m = plugin.manifest
        if (m.apiVersion > PLUGIN_API_VERSION) return false
        // hooks 声明与实现接口一致性校验：声明了却未实现 → 拒载
        if (PluginHook.PasteTransform in m.hooks && plugin !is PasteTransformHook) return false
        if (PluginHook.CaptureTransform in m.hooks && plugin !is CaptureTransformHook) return false
        if (PluginHook.PanelRender in m.hooks && plugin !is PanelRenderHook) return false
        if (PluginHook.CommitObserve in m.hooks && plugin !is CommitObserveHook) return false
        return true
    }
    private fun userEnabled(m: PluginManifest): Boolean =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(pluginKey(m.id), true)
    private fun prefEdit() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
    private fun pluginKey(pluginId: String) = "plugin_${pluginId}_enabled"
    private fun sourceKindOf(pluginId: String): PluginSourceKind {
        return sourceKindCache[pluginId] ?: PluginSourceKind.External
    }
    private val PREFS_NAME = "input_method_plugins"

    // ---- 一期来源：独立安装包目录 files/plugins/<id>/ ----
    object ExternalSource : PluginSource {
        override val kind = PluginSourceKind.External
        override fun discover(): List<Plugin> {
            val root = File(appContext.filesDir, "plugins")
            if (!root.exists()) return emptyList()
            return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
                // script 插件（QuickJS）优先；否则 data 插件（规则净化）
                ScriptPlugin.from(dir) ?: DataPlugin.from(dir)
            } ?: emptyList()
        }
    }
}