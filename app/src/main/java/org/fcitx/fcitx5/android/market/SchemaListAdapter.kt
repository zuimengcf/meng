/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R

/**
 * 方案商店列表的 Adapter。展示可安装方案及状态。
 *
 * 每个方案提供两个动作：
 * - 「下载 / 更新」：弹出该方案的下载链接（官方源 + 国内镜像）供用户自行下载；
 *   已安装且有新版本时显示「更新」，否则显示「下载」。
 * - 「导入」：通过系统文件选择器选中本地下好的 zip 安装包。
 * - 已安装时「下载」按钮变为「卸载」。
 */
class SchemaListAdapter(
    private val onShowLinks: (SchemaSource) -> Unit,
    private val onImport: (SchemaSource) -> Unit,
    private val onUninstall: ((SchemaSource) -> Unit)? = null,
) : RecyclerView.Adapter<SchemaListAdapter.VH>() {

    private val items = mutableListOf<SchemaSource>()
    private val installedVersions = mutableMapOf<String, String>()
    private val busy = mutableMapOf<String, Boolean>()

    /** 提交方案列表 */
    fun submitList(list: List<SchemaSource>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 更新已安装版本表 */
    fun notifyInstalled(versions: Map<String, String>) {
        installedVersions.clear()
        installedVersions.putAll(versions)
        notifyDataSetChanged()
    }

    /** 更新进行中的导入状态 */
    fun notifyBusy(p: Map<String, Boolean>) {
        busy.clear()
        busy.putAll(p)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schema_market, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.schema_name)
        private val desc: TextView = itemView.findViewById(R.id.schema_desc)
        private val latency: TextView = itemView.findViewById(R.id.schema_latency)
        private val importBtn: Button = itemView.findViewById(R.id.schema_import)
        private val actionBtn: Button = itemView.findViewById(R.id.schema_action)

        fun bind(source: SchemaSource) {
            val ctx = itemView.context
            name.text = source.displayName

            val installed = installedVersions[source.schemaId]
            val hasUpdate = installed != null && installed != source.version
            // 显式展示已装版本与安装状态：已装显示「已安装 vX」，未装显示「未安装」，
            // 有更新时追加「可更新至 vY」，而不是让用户仅凭按钮文字猜测。
            val statusLine = when {
                installed == null -> ctx.getString(R.string.schema_not_installed)
                hasUpdate -> ctx.getString(
                    R.string.schema_update_available,
                    source.version
                )
                else -> ""
            }
            var descText = ctx.getString(
                R.string.schema_market_desc,
                source.schemaId,
                source.version,
            )
            if (installed != null) {
                descText += ctx.getString(R.string.schema_installed, installed)
            }
            if (statusLine.isNotEmpty()) {
                descText = descText.trim() + "\n" + statusLine
            }
            desc.text = descText

            // 展示最优镜像的连通状态与延迟（直观呈现自动探测结果）
            val best = SchemaMarketManager.pickBestMirror(source)
            val bestMirror = source.mirrors.firstOrNull { it.url == best }
            if (bestMirror != null && bestMirror.status != MirrorStatus.UNKNOWN) {
                val statusText = ctx.getString(when (bestMirror.status) {
                    MirrorStatus.OK -> R.string.schema_mirror_status_ok
                    MirrorStatus.SLOW -> R.string.schema_mirror_status_slow
                    MirrorStatus.FAIL -> R.string.schema_mirror_status_fail
                    MirrorStatus.UNKNOWN -> R.string.schema_mirror_status_unknown
                })
                val latencyText = bestMirror.latencyMs?.let {
                    ctx.getString(R.string.schema_latency_ms, it)
                } ?: ""
                latency.text = ctx.getString(R.string.schema_best_mirror, statusText, " · $latencyText")
                latency.visibility = View.VISIBLE
            } else {
                latency.visibility = View.GONE
            }

            val isImporting = busy[source.schemaId] == true

            // 「导入」按钮：导入中则禁用并显示文案
            importBtn.isEnabled = !isImporting
            importBtn.text = if (isImporting) ctx.getString(R.string.schema_importing)
            else ctx.getString(R.string.schema_import)
            importBtn.setOnClickListener { onImport(source) }

            // 「下载 / 更新 / 卸载」按钮
            actionBtn.isEnabled = !isImporting
            actionBtn.text = when {
                hasUpdate -> ctx.getString(R.string.schema_download_update)
                installed != null -> ctx.getString(R.string.schema_uninstall)
                else -> ctx.getString(R.string.schema_download)
            }
            actionBtn.setOnClickListener {
                if (installed != null && !hasUpdate) {
                    // 已安装且无更新 -> 卸载
                    onUninstall?.invoke(source)
                } else {
                    // 未安装 或 有更新 -> 弹出下载链接/更新指引
                    onShowLinks(source)
                }
            }
        }
    }
}