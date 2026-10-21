/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.smart

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.stats.InputStatsManager
import org.fcitx.fcitx5.android.data.stats.db.FreqWord
import org.fcitx.fcitx5.android.data.stats.db.InputDailyStats
import org.fcitx.fcitx5.android.data.stats.db.WordAssoc
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

/** 「梦」输入统计展示页：日 / 周 / 月 / 全部 + 可管理的常用词和联想条目。 */
class InputStatsFragment : Fragment() {
    private var bodyViews: List<TextView> = emptyList()
    private var refreshJob: Job? = null
    private var lastData: StatsData? = null
    private var wordRange = WordRange.ALL
    private var wordQuery = ""
    private var assocQuery = ""
    private var wordRows: LinearLayout? = null
    private var assocRows: LinearLayout? = null
    private var outcomeView: TextView? = null
    private var wordLimitView: TextView? = null
    private var assocLimitView: TextView? = null
    private var wordLoadMoreView: TextView? = null
    private var wordPageSize = 30
    private var wordLoaded = 0

    /** 联想只在搜索时展示；未搜索时为空，避免一次性加载全量。 */
    private var assocSearched = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(16)) }
        bodyViews = listOf(
            R.string.smart_stats_today, R.string.smart_stats_week,
            R.string.smart_stats_month, R.string.smart_stats_all
        ).map { title ->
            root.addView(TextView(ctx).apply {
                setText(title); setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
                setPadding(0, ctx.dp(8), 0, ctx.dp(4))
            })
            TextView(ctx).apply {
                setText(R.string.smart_stats_loading); textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                setPadding(0, 0, 0, ctx.dp(12)); root.addView(this)
            }
        }
        outcomeView = TextView(ctx).apply {
            setText(R.string.smart_stats_loading); textSize = 14f
            setPadding(0, 0, 0, ctx.dp(12)); root.addView(this)
        }
        root.addView(searchHeader(ctx, R.string.smart_stats_top_words, R.string.smart_stats_search_words,
            query = { wordQuery }) { wordQuery = it; wordLoaded = 0; refresh() })
        root.addView(rangeBar(ctx))
        root.addView(TextView(ctx).apply { text = "按最近活跃时间筛选；括号为累计次数，不是区间内次数。" })
        root.addView(pageSizeBar(ctx))
        wordRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        wordLimitView = TextView(ctx).apply { setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray)); root.addView(this) }
        wordLoadMoreView = TextView(ctx).apply {
            text = "加载更多"; setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            setPadding(0, ctx.dp(8), 0, ctx.dp(4)); setOnClickListener { loadMoreWords() }
            root.addView(this)
        }
        root.addView(searchHeader(ctx, R.string.smart_stats_assoc, R.string.smart_stats_search_assoc,
            query = { assocQuery }) { assocQuery = it; assocSearched = true; refresh() })
        assocRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        assocLimitView = TextView(ctx).apply { setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray)); root.addView(this) }
        // 每次创建页面都默认折叠；展开不代表确认删除，仍保留原有确认流程。
        val dangerActions = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(Button(ctx).apply {
            text = "危险操作 ▸"
            setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_red_dark))
            contentDescription = "展开危险操作"
            setOnClickListener {
                val expanded = dangerActions.visibility != View.VISIBLE
                dangerActions.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "危险操作 ▾" else "危险操作 ▸"
                contentDescription = if (expanded) "收起危险操作" else "展开危险操作"
            }
        })
        root.addView(dangerActions)
        dangerActions.addView(TextView(ctx).apply {
            text = "以下操作会删除数据，不可恢复，请谨慎使用。"
            setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray)); textSize = 13f
            setPadding(0, 0, 0, ctx.dp(8))
        })
        dangerActions.addView(Button(ctx).apply {
            setText("清空全部常用词"); setOnClickListener {
                confirmScopedClear("危险操作：清空全部常用词？忽略搜索和日期筛选，不影响日统计、联想和纠错学习。此操作不可撤销！", action = { InputStatsManager.clearWords() })
            }
        })
        dangerActions.addView(Button(ctx).apply {
            setText("清空全部学习联想"); setOnClickListener {
                confirmScopedClear("危险操作：清空全部学习联想？忽略搜索，不影响日统计、常用词或纠错学习；内置联想词表不在此删除。此操作不可撤销！", action = { InputStatsManager.clearAssociations() })
            }
        })
        dangerActions.addView(Button(ctx).apply {
            setText(R.string.smart_stats_clear_all); setOnClickListener { confirmClearAll() }
        })
        return ScrollView(ctx).apply { addView(root) }
    }

    private fun rangeBar(ctx: android.content.Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        WordRange.values().forEach { range ->
            addView(Button(ctx).apply {
                setText(range.label)
                setOnClickListener { wordRange = range; wordLoaded = 0; refresh() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** 每页条数下拉选择：30 / 50 / 100，默认 30。 */
    private fun pageSizeBar(ctx: android.content.Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(ctx).apply {
            text = "每页条数"; textSize = 15f; setPadding(0, 0, ctx.dp(8), 0)
        })
        val spinner = Spinner(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, listOf("30 条", "50 条", "100 条")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.setSelection(listOf(30, 50, 100).indexOf(wordPageSize).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val size = listOf(30, 50, 100)[position]
                if (size != wordPageSize) { wordPageSize = size; wordLoaded = 0; refresh() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 标题在左、搜索按钮独立靠右；前景色随浅色 / 深色主题切换。 */
    private fun searchHeader(ctx: android.content.Context, title: Int, hint: Int, query: () -> String, changed: (String) -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        val foregroundColor = ctx.styledColor(android.R.attr.textColorPrimary)
        addView(TextView(ctx).apply {
            setText(title)
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
            setTextColor(foregroundColor)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = ctx.dp(12)
        })
        addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_baseline_search_24)
            imageTintList = ColorStateList.valueOf(foregroundColor)
            contentDescription = getString(hint)
            val backgroundAttrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = backgroundAttrs.getDrawable(0)
            backgroundAttrs.recycle()
            setPadding(ctx.dp(12))
            setOnClickListener {
                val input = EditText(ctx).apply {
                    setHint(hint); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true)
                    setText(query()); setSelection(text.length)
                }
                val inputContainer = LinearLayout(ctx).apply {
                    setPadding(ctx.dp(24), 0, ctx.dp(24), 0)
                    addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                AlertDialog.Builder(ctx)
                    .setTitle(hint)
                    .setView(inputContainer)
                    .setPositiveButton("搜索") { _, _ -> changed(input.text.toString().trim()) }
                    .setNeutralButton("清除搜索") { _, _ -> changed("") }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { super.onViewCreated(view, savedInstanceState); lastData?.let(::render) }
    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroyView() { refreshJob?.cancel(); refreshJob = null; bodyViews = emptyList(); wordRows = null; assocRows = null; outcomeView = null; wordLimitView = null; assocLimitView = null; wordLoadMoreView = null; super.onDestroyView() }

    private fun refresh(appendWords: Boolean = false) {
        refreshJob?.cancel()
        // Capture UI state before switching dispatchers. Only completed loads advance the offset.
        val wordQueryNow = wordQuery.trim()
        val assocQueryNow = assocQuery.trim()
        val fromMillis = wordRange.fromMillis()
        val pageSize = wordPageSize
        val previousWords = if (appendWords) lastData?.topWords.orEmpty() else emptyList()
        val offset = previousWords.size
        val limit = if (appendWords) pageSize else maxOf(pageSize, wordLoaded)
        val searchAssoc = assocSearched && assocQueryNow.isNotEmpty()
        wordLoadMoreView?.isEnabled = false
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    // Fetch one extra row to distinguish a full last page from a page with more data.
                    val words = if (wordQueryNow.isEmpty()) InputStatsManager.topWordsSince(fromMillis, limit + 1, offset)
                        else InputStatsManager.searchWords(wordQueryNow, fromMillis, limit + 1, offset)
                    StatsData(InputStatsManager.todayStats(), InputStatsManager.weekStats(), InputStatsManager.monthStats(),
                        InputStatsManager.dailySummary(),
                        previousWords + words.take(limit),
                        // 联想仅搜索时加载，最多 pageSize 条；未搜索时为空列表。
                        if (searchAssoc) InputStatsManager.searchAssoc(assocQueryNow, pageSize) else emptyList(),
                        InputStatsManager.commitOutcomeCounts(), wordQueryNow.isNotEmpty(), searchAssoc,
                        words.size > limit)
                }
                wordLoaded = data.topWords.size
                wordLoadMoreView?.isEnabled = true
                lastData = data; render(data)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                wordLoadMoreView?.isEnabled = true
                Timber.w(e, "Failed to load input statistics")
                if (lastData == null) bodyViews.forEach { it.setText(R.string.smart_stats_load_failed) }
            }
        }
    }

    /** 点击「加载更多」追加下一页常用词。 */
    private fun loadMoreWords() {
        if (refreshJob?.isActive == true || wordLoaded == 0 || lastData?.hasMoreWords != true) return
        refresh(appendWords = true)
    }

    private data class StatsData(val today: InputDailyStats?, val week: List<InputDailyStats>, val month: List<InputDailyStats>, val all: List<InputDailyStats>, val topWords: List<FreqWord>, val assocs: List<WordAssoc>, val outcomes: InputStatsManager.CommitOutcomeCounts, val wordSearch: Boolean, val assocSearch: Boolean, val hasMoreWords: Boolean) {
        private fun total(x: List<InputDailyStats>) = x.sumOf { it.totalChars }
        private fun chinese(x: List<InputDailyStats>) = x.sumOf { it.chineseChars }
        private fun english(x: List<InputDailyStats>) = x.sumOf { it.englishWords }
        private fun digits(x: List<InputDailyStats>) = x.sumOf { it.digits }
        private fun punct(x: List<InputDailyStats>) = x.sumOf { it.punctuation }
        val weekTotal get() = total(week); val weekChinese get() = chinese(week); val weekEnglish get() = english(week); val weekDigits get() = digits(week); val weekPunct get() = punct(week)
        val monthTotal get() = total(month); val monthChinese get() = chinese(month); val monthEnglish get() = english(month); val monthDigits get() = digits(month); val monthPunct get() = punct(month)
        val allTotal get() = total(all); val allChinese get() = chinese(all); val allEnglish get() = english(all); val allDigits get() = digits(all); val allPunct get() = punct(all)
    }

    private fun render(data: StatsData) {
        val ctx = requireContext(); val bodies = bodyViews.iterator()
        fun section(text: String) { if (bodies.hasNext()) { val target = bodies.next(); if (target.text.toString() != text) target.text = text } }
        section(data.today?.let { ctx.getString(R.string.smart_stats_today_body, it.chineseChars, it.englishWords, it.digits, it.punctuation, it.totalChars) } ?: ctx.getString(R.string.smart_stats_empty))
        section(ctx.getString(R.string.smart_stats_range_body, data.weekChinese, data.weekEnglish, data.weekDigits, data.weekPunct, data.weekTotal))
        section(ctx.getString(R.string.smart_stats_range_body, data.monthChinese, data.monthEnglish, data.monthDigits, data.monthPunct, data.monthTotal))
        section(ctx.getString(R.string.smart_stats_range_body, data.allChinese, data.allEnglish, data.allDigits, data.allPunct, data.allTotal))
        outcomeView?.text = ctx.getString(R.string.smart_stats_commit_outcomes, data.outcomes.confirmed, data.outcomes.unverified, data.outcomes.rejected)
        renderRows(data)
    }

    private fun renderRows(data: StatsData? = lastData) {
        val ctx = context ?: return; val snapshot = data ?: return
        wordRows?.removeAllViews(); assocRows?.removeAllViews()
        val q = wordQuery.trim()
        snapshot.topWords.filter { q.isEmpty() || it.word.contains(q, true) }.forEach { word ->
            wordRows?.addView(managedRow(ctx, "${word.word}（${word.count}）") { confirmDelete(ctx.getString(R.string.smart_stats_delete_word_confirm, word.word)) { InputStatsManager.deleteWord(word.word) } })
        }
        if (wordRows?.childCount == 0) wordRows?.addView(emptyText(ctx))
        // 是否还有下一页以本次查询的额外一条为准，不以累计显示条数判断。
        wordLoadMoreView?.visibility = if (snapshot.hasMoreWords) View.VISIBLE else View.GONE
        val aq = assocQuery.trim()
        snapshot.assocs.filter { aq.isEmpty() || "${it.prev} ${it.next}".contains(aq, true) }.forEach { assoc ->
            assocRows?.addView(managedRow(ctx, "${assoc.prev} → ${assoc.next}（${assoc.count}）") { confirmDelete(ctx.getString(R.string.smart_stats_delete_assoc_confirm, assoc.prev, assoc.next)) { InputStatsManager.deleteAssoc(assoc.key) } })
        }
        if (assocRows?.childCount == 0) assocRows?.addView(emptyText(ctx))
        assocLimitView?.text = if (snapshot.assocSearch && snapshot.assocs.size >= wordPageSize) ctx.getString(R.string.smart_stats_search_limit) else ""
    }

    private fun managedRow(ctx: android.content.Context, label: String, delete: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, ctx.dp(4))
        addView(TextView(ctx).apply { text = label; textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(ctx).apply { setText(R.string.smart_stats_delete); setOnClickListener { delete() } })
    }
    private fun emptyText(ctx: android.content.Context) = TextView(ctx).apply { setText(R.string.smart_stats_empty); setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray)) }
    private fun confirmDelete(message: String, action: suspend () -> Boolean) {
        AlertDialog.Builder(requireContext()).setMessage(message).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.smart_stats_delete) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                refreshJob?.cancel()
                wordLoadMoreView?.isEnabled = false
                val result = runCatching { withContext(Dispatchers.IO) { action() } }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (result.isFailure) android.widget.Toast.makeText(requireContext(), "操作未完全完成，请检查列表后重试", android.widget.Toast.LENGTH_SHORT).show()
                // Deletion shifts database offsets: restart at the first page, even after partial failure.
                wordLoaded = 0
                refresh()
            }
        }.show()
    }
    private fun confirmScopedClear(message: String, action: suspend () -> Unit, strong: Boolean = false) {
        val ctx = requireContext()
        val builder = AlertDialog.Builder(ctx)
            .setTitle("危险操作")
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        // 最危险的操作要求二次确认（输入固定短语）。
        if (strong) {
            val input = EditText(ctx).apply { hint = "输入「确认清空」以继续"; inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
            val pad = ctx.dp(20)
            input.setPadding(pad, ctx.dp(8), pad, 0)
            builder.setView(input)
            builder.setPositiveButton("我确认，不可恢复") { _, _ ->
                if (input.text.toString().trim() != "确认清空") {
                    Toast.makeText(ctx, "未输入确认短语，已取消清空", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                doClear(action)
            }
        } else {
            builder.setPositiveButton("我确认，不可恢复") { _, _ -> doClear(action) }
        }
        builder.show()
    }
    private fun doClear(action: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            refreshJob?.cancel()
            wordLoadMoreView?.isEnabled = false
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isFailure) Toast.makeText(requireContext(), "清空未完全完成，请刷新检查", Toast.LENGTH_SHORT).show()
            wordLoaded = 0
            lastData = null; refresh()
        }
    }
    private fun confirmClearAll() {
        confirmScopedClear(getString(R.string.smart_stats_clear_confirm) + "\n\n此操作不可撤销！", action = { InputStatsManager.clearStatsAndAssoc() }, strong = true)
    }

    private enum class WordRange(val label: String) {
        TODAY("本日"), WEEK("本周"), MONTH("本月"), ALL("全部");
        fun fromMillis(): Long = when (this) {
            TODAY -> LocalDate.now(); WEEK -> LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); MONTH -> LocalDate.now().withDayOfMonth(1); ALL -> null
        }?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
    }

    private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }
}
