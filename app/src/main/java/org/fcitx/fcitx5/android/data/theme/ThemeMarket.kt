/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

/**
 * 梦 · 主题商店
 *
 * 内置一个「内置主题库」（打包进 assets），用户可从商店列表中一键安装并应用。
 * 当前内置 01 号主题「梦」：透明深色 + 背景图。
 */
object ThemeMarket {

    /** 商店条目：一个可安装的主题 */
    data class ThemeMarketItem(
        val id: String,
        val name: String,
        val index: Int,
        /** assets 内相对路径，如 theme_store/meng_default.zip */
        val assetZip: String,
        /** 安装后主题在 [ThemeManager] 里的内部名（见 zip 内 json 的 name 字段） */
        val themeName: String,
    )

    /**
     * 所有上架的基础主题（离线打包在 assets，无需网络）。
     * 后续可在 [ThemeMarketItem.assetZip] 指向网络下载时再扩展在线源。
     */
    val catalog: List<ThemeMarketItem> = listOf(
        ThemeMarketItem(
            id = "meng_default",
            name = "梦",
            index = 1,
            assetZip = "theme_store/meng_default.zip",
            themeName = "MengDefault",
        )
    )

    /** 标记「首启默认主题」是否已初始化，避免每次启动覆盖用户选择 */
    private const val KEY_FIRST_RUN_DONE = "theme_market_first_run_done"

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences("theme_market", Context.MODE_PRIVATE)

    fun isFirstRunDone(): Boolean = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)

    fun setFirstRunDone() {
        prefs.edit { putBoolean(KEY_FIRST_RUN_DONE, true) }
    }

    /**
     * 从 assets 解压并安装主题 zip。
     * 复用 [ThemeFilesManager.importTheme]，成功后把主题加入 [ThemeManager] 并刷新列表。
     * @return 安装得到的已应用主题
     */
    fun installFromAsset(item: ThemeMarketItem): Result<Theme.Custom> =
        runCatching {
            val ctx = appContext
            val input = ctx.assets.open(item.assetZip)
            val (_, theme, _) = ThemeFilesManager.importTheme(input).getOrThrow()
            // 让内存列表感知新主题（同时写入文件；若同名列已存在则覆盖）
            ThemeManager.saveTheme(theme)
            ThemeManager.refreshThemes()
            theme
        }

    /**
     * 应用主题：若用户未开启「跟随系统日夜」，直接设为普通模式主题。
     * 若开启了跟随系统，则同步写入亮/暗模式主题（当前主题为深色带背景图，两种模式一致）。
     */
    fun applyTheme(theme: Theme) {
        if (ThemeManager.prefs.followSystemDayNightTheme.getValue()) {
            // 跟随系统开启：把亮/暗两档都设成该主题，保证无论深浅都生效
            ThemeManager.prefs.lightModeTheme.setValue(theme)
            ThemeManager.prefs.darkModeTheme.setValue(theme)
        } else {
            ThemeManager.setNormalModeTheme(theme)
        }
    }

    /**
     * 首次启动时执行：
     * 1. 若自定义主题尚未安装，自动从 assets 安装默认主题「梦」。
     * 2. 将「梦」设为默认主题（关闭跟随系统，使用普通模式主题）。
     * 仅执行一次，之后用户可自由更换。
     */
    fun ensureDefaultThemeOnFirstRun() {
        if (isFirstRunDone()) return
        try {
            val defaultItem = catalog.firstOrNull() ?: return
            // 已安装则无需重复导入
            val installed = ThemeManager.getTheme(defaultItem.themeName) as? Theme.Custom
            val theme = installed ?: installFromAsset(defaultItem).getOrNull() ?: run {
                Timber.w("ThemeMarket: fail to install default theme from asset")
                return
            }
            // 设为默认：关闭跟随系统并使用普通模式主题，这样无论系统深浅都默认「梦」
            ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
            ThemeManager.setNormalModeTheme(theme)
            setFirstRunDone()
            Timber.i("ThemeMarket: default theme '%s' installed & applied", theme.name)
        } catch (t: Throwable) {
            Timber.w(t, "ThemeMarket: ensureDefaultThemeOnFirstRun failed")
        }
    }
}