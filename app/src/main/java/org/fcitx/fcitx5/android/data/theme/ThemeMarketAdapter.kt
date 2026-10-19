/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi

/**
 * 梦 · 主题商店列表 Adapter。
 * 每行展示主题缩略图、名称与「第 N 号主题」说明，右侧按钮在未安装时显示「安装」，已安装时显示「应用」。
 *
 * 缩略图与应用主题页共用 [ThemeThumbnailUi.setTheme] 同一渲染路径，保证预览效果一致。
 */
class ThemeMarketAdapter(
    private val onInstall: (ThemeMarket.ThemeMarketItem) -> Unit,
    private val onApply: (ThemeMarket.ThemeMarketItem) -> Unit,
) : RecyclerView.Adapter<ThemeMarketAdapter.VH>() {

    private val items = mutableListOf<ThemeMarket.ThemeMarketItem>()
    private val installed = mutableSetOf<String>()
    private val busyIds = mutableSetOf<String>()
    /** themeName → Theme，供缩略图渲染（与主题页同一套 ThemeThumbnailUi） */
    private var thumbThemes = mutableMapOf<String, Theme>()

    fun submitList(list: List<ThemeMarket.ThemeMarketItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setInstalled(ids: Set<String>) {
        installed.clear()
        installed.addAll(ids)
        notifyDataSetChanged()
    }

    fun setBusy(id: String, busy: Boolean) {
        if (busy) busyIds.add(id) else busyIds.remove(id)
        notifyDataSetChanged()
    }

    /** 预生成缩略图 Theme（由 ThemeManager 各主题得出，交给 [ThemeThumbnailUi] 渲染） */
    fun setThumbThemes(map: Map<String, Theme>) {
        thumbThemes = map.toMutableMap()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_theme_market, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbContainer: ViewGroup = itemView.findViewById(R.id.theme_market_thumb)
        private val name: TextView = itemView.findViewById(R.id.theme_market_name)
        private val desc: TextView = itemView.findViewById(R.id.theme_market_desc)
        private val action: Button = itemView.findViewById(R.id.theme_market_action)
        private var thumbUi: ThemeThumbnailUi? = null

        fun bind(item: ThemeMarket.ThemeMarketItem) {
            val ctx = itemView.context
            name.text = item.name
            desc.text = ctx.getString(R.string.theme_market_desc, item.id, item.index)

            // 与应用主题页同一渲染接口：ThemeThumbnailUi.setTheme
            val theme = thumbThemes[item.themeName]
            var ui = thumbUi
            if (ui == null) {
                ui = ThemeThumbnailUi(ctx)
                thumbContainer.addView(ui.root,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                thumbContainer.clipChildren = true
                thumbContainer.clipToPadding = true
                thumbUi = ui
            }
            ui.root.let { r ->
                r.visibility = if (theme != null) View.VISIBLE else View.GONE
                if (theme != null) ui.setTheme(theme)
            }

            val isInstalled = installed.contains(item.id)
            val isBusy = busyIds.contains(item.id)

            action.isEnabled = !isBusy
            action.text = when {
                isBusy -> ctx.getString(R.string.theme_market_installing)
                isInstalled -> ctx.getString(R.string.theme_market_apply)
                else -> ctx.getString(R.string.theme_market_install)
            }
            action.setOnClickListener {
                if (isInstalled) onApply(item) else onInstall(item)
            }
        }
    }
}