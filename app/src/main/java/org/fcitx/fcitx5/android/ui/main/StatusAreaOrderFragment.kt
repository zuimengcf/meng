/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.status.StatusAreaOrder
import splitties.dimensions.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「更多」面板排序页：长按拖动调整顺序，影响输入法「更多」面板的全部条目
 * （静态功能 + 插件 + fcitx 引擎状态）。
 * 拖动结束即时保存；顶部「恢复默认」一键还原。
 */
class StatusAreaOrderFragment : Fragment() {

    private val items = mutableListOf<StatusAreaEntry>()
    private lateinit var adapter: OrderAdapter

    private fun themeColor(attr: Int): Int {
        val a = requireContext().obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0xFF333333.toInt())
        a.recycle()
        return c
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val fgColor = themeColor(android.R.attr.textColorPrimary)
        val subColor = themeColor(android.R.attr.textColorSecondary)
        adapter = OrderAdapter(items) { fgColor }

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 顶部说明 + 恢复默认
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(4))
        }
        val hint = TextView(ctx).apply {
            text = "长按拖动调整顺序，影响输入法「更多」面板"
            textSize = 13f
            setTextColor(subColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resetBtn = TextView(ctx).apply {
            text = "恢复默认"
            textSize = 14f
            setPadding(ctx.dp(8), ctx.dp(4), 0, ctx.dp(4))
            setOnClickListener {
                StatusAreaOrder.reset(ctx)
                reload()
                Toast.makeText(ctx, "已恢复默认顺序", Toast.LENGTH_SHORT).show()
            }
        }
        bar.addView(hint)
        bar.addView(resetBtn)
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 拖拽列表
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this@StatusAreaOrderFragment.adapter.let { adapter = it }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val e = items.removeAt(from)
                items.add(to, e)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.alpha = 0.55f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                // 拖动结束：保存当前顺序（后台写盘）
                val labels = items.map { it.label }
                lifecycleScope.launch(Dispatchers.IO) {
                    StatusAreaOrder.save(requireContext(), labels)
                }
            }
        }).attachToRecyclerView(list)

        // 条目构建含 fcitx 原生调用，后台加载
        reload()
        return root
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                StatusAreaOrder.allEntries(requireContext())
            }
            items.clear()
            items.addAll(fresh)
            adapter.notifyDataSetChanged()
        }
    }

    private class OrderAdapter(
        private val items: List<StatusAreaEntry>,
        private val labelColor: () -> Int
    ) : RecyclerView.Adapter<OrderAdapter.Holder>() {

        class Holder(val root: LinearLayout, val icon: TextView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
                background = runCatching {
                    val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    val d = a.getDrawable(0)
                    a.recycle()
                    d
                }.getOrNull()
            }
            val icon = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, ctx.dp(16), 0)
            }
            val label = TextView(ctx).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            root.addView(icon)
            root.addView(label)
            return Holder(root, icon, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.label.text = entry.label
            holder.label.setTextColor(labelColor())
            val ctx = holder.root.context
            val d = entry.icon.takeIf { it != 0 }?.let { ContextCompat.getDrawable(ctx, it) }
            d?.setTint(holder.label.currentTextColor)
            holder.icon.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
        }

        override fun getItemCount() = items.size
    }
}