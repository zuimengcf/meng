/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMarket
import org.fcitx.fcitx5.android.data.theme.ThemeMarketAdapter
import org.fcitx.fcitx5.android.utils.toast

/**
 * 梦 · 主题商店
 *
 * 展示可安装的主题（当前为离线内置库），支持一键安装并应用。
 */
class ThemeMarketFragment : Fragment() {

    private lateinit var adapter: ThemeMarketAdapter
    private lateinit var emptyView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_theme_market, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        emptyView = view.findViewById(R.id.theme_market_empty)
        val list = view.findViewById<RecyclerView>(R.id.theme_market_list)
        list.layoutManager = LinearLayoutManager(requireContext())

        adapter = ThemeMarketAdapter(
            onInstall = { item -> installAndApply(item) },
            onApply = { item -> applyItem(item) },
        )
        list.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = ThemeMarket.catalog
        adapter.submitList(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val installedIds = items.filter { isInstalled(it) }.map { it.id }.toSet()
        adapter.setInstalled(installedIds)
        adapter.setThumbThemes(buildThumbs())
    }

    private fun isInstalled(item: ThemeMarket.ThemeMarketItem): Boolean =
        ThemeManager.getTheme(item.themeName) != null

    /** 为商店条目生成缩略图主题对象（与应用主题页同一渲染路径，见 [buildThumbs]） */
    private fun buildThumbs(): Map<String, Theme> {
        val map = mutableMapOf<String, Theme>()
        ThemeMarket.catalog.forEach { item ->
            val theme = ThemeManager.getTheme(item.themeName)
                ?: ThemeManager.BuiltinThemes.firstOrNull { it.name == item.themeName }
            if (theme != null) {
                map[item.themeName] = theme
            }
        }
        return map
    }

    private fun installAndApply(item: ThemeMarket.ThemeMarketItem) {
        val ctx = requireContext()
        adapter.setBusy(item.id, true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ThemeMarket.installFromAsset(item)
            }
            adapter.setBusy(item.id, false)
            result.onSuccess { theme ->
                ThemeMarket.applyTheme(theme)
                ctx.toast(ctx.getString(R.string.theme_market_install_ok, theme.name))
            }.onFailure {
                ctx.toast(R.string.theme_market_install_fail)
            }
            refresh()
        }
    }

    private fun applyItem(item: ThemeMarket.ThemeMarketItem) {
        val ctx = requireContext()
        val theme = ThemeManager.getTheme(item.name)
        if (theme != null) {
            ThemeMarket.applyTheme(theme)
            ctx.toast(ctx.getString(R.string.theme_market_apply_ok, theme.name))
        } else {
            installAndApply(item)
        }
    }
}