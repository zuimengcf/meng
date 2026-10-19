/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.smart

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import splitties.dimensions.dp
import timber.log.Timber

/**
 * 「梦」输入统计展示页：日 / 周 / 月 / 全部 + 字数统计 + TOP 常用词 + 连续性联想数据。
 */
class InputStatsFragment : Fragment() {

    private var bodyViews: List<TextView> = emptyList()
    private var refreshJob: Job? = null
    private var lastData: StatsData? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16))
        }
        // Build the six sections once. Loading must never blank or append to the page.
        bodyViews = listOf(
            R.string.smart_stats_today, R.string.smart_stats_week,
            R.string.smart_stats_month, R.string.smart_stats_all,
            R.string.smart_stats_top_words, R.string.smart_stats_assoc
        ).map { title ->
            root.addView(TextView(ctx).apply {
                setText(title)
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
                setPadding(0, ctx.dp(8), 0, ctx.dp(4))
            })
            TextView(ctx).apply {
                setText(R.string.smart_stats_loading)
                textSize = 14f
                setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                setPadding(0, 0, 0, ctx.dp(12))
                root.addView(this)
            }
        }
        return ScrollView(ctx).apply { addView(root) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Restore the previous snapshot immediately if this fragment's view was recreated.
        lastData?.let(::render)
    }

    override fun onResume() {
        super.onResume()
        // onViewCreated + onResume used to launch two competing initial loads.
        refresh()
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        bodyViews = emptyList()
        super.onDestroyView()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    StatsData(
                        today = InputStatsManager.todayStats(),
                        week = InputStatsManager.weekStats(),
                        month = InputStatsManager.monthStats(),
                        all = InputStatsManager.dailySummary(),
                        topWords = InputStatsManager.topWords(30),
                        assocCount = InputStatsManager.assocCount()
                    )
                }
                lastData = data
                render(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load input statistics")
                // A failed refresh must not erase a previously displayed snapshot.
                if (lastData == null) {
                    bodyViews.forEach { it.setText(R.string.smart_stats_load_failed) }
                }
            }
        }
    }

    private data class StatsData(
        val today: InputDailyStats?,
        val week: List<InputDailyStats>,
        val month: List<InputDailyStats>,
        val all: List<InputDailyStats>,
        val topWords: List<FreqWord>,
        val assocCount: Long
    ) {
        private fun total(list: List<InputDailyStats>) = list.sumOf { it.totalChars }
        private fun chinese(list: List<InputDailyStats>) = list.sumOf { it.chineseChars }
        private fun english(list: List<InputDailyStats>) = list.sumOf { it.englishWords }
        private fun digits(list: List<InputDailyStats>) = list.sumOf { it.digits }
        private fun punct(list: List<InputDailyStats>) = list.sumOf { it.punctuation }

        val weekTotal: Long get() = total(week)
        val weekChinese: Long get() = chinese(week)
        val weekEnglish: Long get() = english(week)
        val weekDigits: Long get() = digits(week)
        val weekPunct: Long get() = punct(week)

        val monthTotal: Long get() = total(month)
        val monthChinese: Long get() = chinese(month)
        val monthEnglish: Long get() = english(month)
        val monthDigits: Long get() = digits(month)
        val monthPunct: Long get() = punct(month)

        val allTotal: Long get() = total(all)
        val allChinese: Long get() = chinese(all)
        val allEnglish: Long get() = english(all)
        val allDigits: Long get() = digits(all)
        val allPunct: Long get() = punct(all)
    }

    private fun render(data: StatsData) {
        val ctx = requireContext()

        val bodies = bodyViews.iterator()
        fun section(body: String) {
            val target = bodies.next()
            // Avoid unnecessary text/layout updates on an unchanged resume refresh.
            if (target.text.toString() != body) target.text = body
        }

        // 今日
        val todayBody = if (data.today == null) ctx.getString(R.string.smart_stats_empty)
        else ctx.getString(
            R.string.smart_stats_today_body,
            data.today.chineseChars, data.today.englishWords, data.today.digits,
            data.today.punctuation, data.today.totalChars
        )
        section(todayBody)

        // 本周
        section(
            ctx.getString(
                R.string.smart_stats_range_body,
                data.weekChinese, data.weekEnglish, data.weekDigits, data.weekPunct, data.weekTotal
            )
        )

        // 本月
        section(
            ctx.getString(
                R.string.smart_stats_range_body,
                data.monthChinese, data.monthEnglish, data.monthDigits, data.monthPunct, data.monthTotal
            )
        )

        // 全部
        section(
            ctx.getString(
                R.string.smart_stats_range_body,
                data.allChinese, data.allEnglish, data.allDigits, data.allPunct, data.allTotal
            )
        )

        // TOP 常用词
        if (data.topWords.isEmpty()) {
            section(ctx.getString(R.string.smart_stats_empty))
        } else {
            val lines = data.topWords.withIndex().joinToString("\n") { (i, w) ->
                "${i + 1}. ${w.word}（${w.count}）"
            }
            section(lines)
        }

        // 连续性联想数据（功能 2 预测的数据来源）
        section(
            ctx.getString(R.string.smart_stats_assoc_body, data.assocCount)
        )
    }
}