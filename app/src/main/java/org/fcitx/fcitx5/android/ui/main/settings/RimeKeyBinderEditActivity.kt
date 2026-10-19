/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.market.PunctuatorEditor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp

/**
 * 万象 punctuator（标点表）可视化编辑器。
 *
 * 把「标点键 → 全角形态值 / 半角形态值」逐行展示为可编辑列表：
 * - 每行 = 一个标点（如 `.`、`,`），点击编辑 full_shape 与 half_shape 两个值。
 * - 保存：只把用户改过的键写回中间层 custom/wanxiang.custom.yaml
 *   （不改原文件 build/wanxiang.schema.yaml），然后 FcitxDaemon.restartFcitx()
 *   触发 rime 重新部署，真正改变输入行为。
 */
class RimeKeyBinderEditActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: BindingAdapter
    private var rows = mutableListOf<PunctuatorEditor.PunctRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rime_key_binder_editor)
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rkb_topbar)) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = s.top)
            insets
        }

        findViewById<View>(R.id.rkb_back).setOnClickListener { finish() }
        findViewById<View>(R.id.rkb_save).setOnClickListener { saveAndReload() }

        recycler = findViewById(R.id.rkb_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = BindingAdapter { index, row -> showEditDialog(index, row) }
        recycler.adapter = adapter

        load()
    }

    /** 从 build/wanxiang.schema.yaml 读取当前标点表。 */
    private fun load() {
        rows = PunctuatorEditor.loadPunctuators(this).toMutableList()
        adapter.submitList(rows)
        // 未安装万象提示
        if (rows.isEmpty()) {
            findViewById<TextView>(R.id.rkb_hint).text =
                getString(R.string.rime_key_binder_not_installed)
        }
    }

    private fun showEditDialog(index: Int, row: PunctuatorEditor.PunctRow) {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        // 标点键（只读展示，一般不改键名，但允许）
        val fKey = EditText(ctx).also {
            it.hint = getString(R.string.rime_key_binder_accept_hint)
            it.setText(row.key)
            it.setPadding(dp(4), dp(6), dp(4), dp(6))
            layout.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // 全角形态值
        layout.addView(label(R.string.rime_punct_full))
        val fFull = EditText(ctx).also {
            it.hint = getString(R.string.rime_punct_full_hint)
            it.setText(PunctuatorEditor.summarize(row.fullEdited ?: row.fullValue))
            it.setPadding(dp(4), dp(6), dp(4), dp(6))
            layout.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // 半角形态值
        layout.addView(label(R.string.rime_punct_half))
        val fHalf = EditText(ctx).also {
            it.hint = getString(R.string.rime_punct_half_hint)
            it.setText(PunctuatorEditor.summarize(row.halfEdited ?: row.halfValue))
            it.setPadding(dp(4), dp(6), dp(4), dp(6))
            layout.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.rime_key_binder_edit) + " · " + row.key)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val key = fKey.text.toString().trim()
                if (key.isEmpty()) return@setPositiveButton
                val fullRaw = fFull.text.toString().trim()
                val halfRaw = fHalf.text.toString().trim()
                val fullNew = PunctuatorEditor.parseEditedValue(fullRaw)
                val halfNew = PunctuatorEditor.parseEditedValue(halfRaw)
                // 只有与原值不同才标记为已编辑（写入中间层）
                val fullEdited = if (PunctuatorEditor.valueEquals(fullNew, row.fullValue)) null else fullNew
                val halfEdited = if (PunctuatorEditor.valueEquals(halfNew, row.halfValue)) null else halfNew
                rows[index] = row.copy(
                    key = key,
                    fullEdited = fullEdited,
                    halfEdited = halfEdited,
                )
                adapter.submitList(rows)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(res: Int): TextView = TextView(this).apply {
        text = getString(res)
        setTextSize(14f)
        setTextColor(0xFF666666.toInt())
        setPadding(dp(0), dp(12), dp(0), dp(4))
    }

    /** 保存写回 custom yaml（中间层，不改原文件），并重启 fcitx 使其立即生效。 */
    private fun saveAndReload() {
        val path = PunctuatorEditor.savePunctuators(this, rows)
        if (path == null) {
            toast(R.string.rime_key_binder_save_failed)
            return
        }
        runCatching { FcitxDaemon.restartFcitx() }
        toast(R.string.rime_key_binder_saved)
    }

    class BindingAdapter(
        private val onClick: (Int, PunctuatorEditor.PunctRow) -> Unit,
    ) : RecyclerView.Adapter<BindingAdapter.Holder>() {

        private val items = mutableListOf<PunctuatorEditor.PunctRow>()

        fun submitList(list: List<PunctuatorEditor.PunctRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val accept: TextView = v.findViewById(R.id.rkb_accept)
            val action: TextView = v.findViewById(R.id.rkb_action)
            val when_: TextView = v.findViewById(R.id.rkb_when)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rime_key_binding, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.accept.text = row.key
            holder.action.text = "${row.fullSummary}  |  ${row.halfSummary}"
            holder.when_.text = holder.itemView.context.getString(R.string.rime_punct_summary)
            holder.itemView.setOnClickListener {
                onClick(position, row)
            }
        }
    }
}