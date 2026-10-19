/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAdapter
import org.fcitx.fcitx5.android.utils.formatDateTime

/**
 * 全屏剪切板管理页使用的卡片式列表 Adapter。
 * 使用独立 XML 布局（item_clipboard_manage），不依赖 ClipboardEntryUi / CustomGestureView。
 * 每条卡片：内容 + 日期 + 置顶标记 + 分享 / 移到分组 / 删除 / 拖动排序手柄。
 */
class ClipboardManageAdapter(
    private val ctx: Context,
    private val maskSensitive: Boolean,
    private val onClick: (ClipboardEntry) -> Unit,
    private val onShare: (ClipboardEntry) -> Unit,
    private val onDelete: (ClipboardEntry) -> Unit,
    private val onMoveToGroup: (ClipboardEntry) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<ClipboardManageAdapter.VH>() {

    private val items = mutableListOf<ClipboardEntry>()

    fun submit(list: List<ClipboardEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 拖动排序结束后重新装载（顺序由外部持久化后回调）。 */
    fun swap(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun entryAt(position: Int): ClipboardEntry? = items.getOrNull(position)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.item_clipboard_text)
        val image: ImageView = itemView.findViewById(R.id.item_clipboard_image)
        val date: TextView = itemView.findViewById(R.id.item_clipboard_date)
        val pin: ImageView = itemView.findViewById(R.id.item_clipboard_pin)
        val share: ImageView = itemView.findViewById(R.id.item_clipboard_share)
        val folder: ImageView = itemView.findViewById(R.id.item_clipboard_folder)
        val delete: ImageView = itemView.findViewById(R.id.item_clipboard_delete)
        val drag: ImageView = itemView.findViewById(R.id.item_clipboard_drag)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clipboard_manage, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val isImage = entry.type.startsWith("image/") && entry.imagePath != null
        if (isImage) {
            holder.text.visibility = View.GONE
            holder.image.visibility = View.VISIBLE
            holder.image.setImageURI(Uri.fromFile(java.io.File(entry.imagePath!!)))
        } else {
            holder.text.visibility = View.VISIBLE
            holder.image.visibility = View.GONE
            holder.text.text = ClipboardAdapter.excerptText(
                entry.text, entry.sensitive && maskSensitive
            )
        }
        holder.date.text = formatDateTime(entry.timestamp)
        holder.pin.visibility = if (entry.pinned) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(entry) }
        holder.share.setOnClickListener { onShare(entry) }
        holder.folder.setOnClickListener { onMoveToGroup(entry) }
        holder.delete.setOnClickListener { onDelete(entry) }
        holder.drag.setOnClickListener { }
        holder.drag.setOnLongClickListener {
            onStartDrag(holder)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}