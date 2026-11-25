/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

import kotlinx.coroutines.withTimeoutOrNull

/** 管线结果：出口恒有文本（失败/超时=原文） */
data class PipelineOutcome(
    val text: String,
    /** 实际改动文本的插件 id 链（供「已净化」提示与还原） */
    val transformedBy: List<String> = emptyList(),
    val timedOut: Boolean = false,
)

/**
 * 粘贴管线：链式执行全部 PasteTransformHook 插件。
 *
 * 语义（框架层强制，写死）：
 * - 单插件超时 100ms / 整管线 150ms；
 * - 插件异常或超时 = 该步回退（用上一步输出继续），失败计数交由 Host 记熔断；
 * - 插件按 priority 降序、同 priority 按注册序；
 * - 本类零 Android 依赖，可 JVM 单测。
 */
object PluginPipeline {

    private const val PLUGIN_TIMEOUT_MS = 100L
    private const val PIPELINE_TIMEOUT_MS = 150L
    private const val TRIP_THRESHOLD = 3

    suspend fun process(
        plugins: List<Plugin>,
        ctx: PasteContext,
        text: String,
        onPluginFailure: (pluginId: String, error: Throwable) -> Unit = { _, _ -> },
    ): PipelineOutcome {
        val active = plugins.filterIsInstance<PasteTransformHook>()
        if (active.isEmpty()) return PipelineOutcome(text)
        // hook 对象 → 原 Plugin 对象（取 manifest.id）
        val manifestOf = HashMap<PasteTransformHook, Plugin>()
        plugins.forEach { p -> if (p is PasteTransformHook) manifestOf[p] = p }
        val outcome = withTimeoutOrNull(PIPELINE_TIMEOUT_MS) {
            var acc = text
            val chain = mutableListOf<String>()
            for (plugin in active) {
                if (acc.isEmpty()) break
                val started = System.nanoTime()
                val id = manifestOf[plugin]?.manifest?.id ?: plugin.javaClass.simpleName ?: "plugin"
                try {
                    val r = withTimeoutOrNull(PLUGIN_TIMEOUT_MS) {
                        plugin.onPaste(ctx, acc)
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    recordStats(id, elapsedMs)
                    when (r) {
                        is PasteResult.Text -> {
                            if (r.text != acc) chain.add(id)
                            acc = r.text
                        }
                        else -> Unit // NotHandled 或单插件超时：原文继续
                    }
                } catch (e: Throwable) {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    recordStats(id, elapsedMs, e)
                    onPluginFailure(id, e)
                    // 异常：上一步输出继续，不中断管线
                }
            }
            PipelineOutcome(acc, chain)
        }
        return outcome ?: PipelineOutcome(text, timedOut = true)
    }

    // ---- 运行时统计（供熔断与设置页）----

    private val statsMap = HashMap<String, PluginRuntimeStats>()
    private val statsLock = Any()

    fun statsOf(pluginId: String): PluginRuntimeStats =
        synchronized(statsLock) { statsMap[pluginId] ?: PluginRuntimeStats() }

    fun resetStats(pluginId: String) = synchronized(statsLock) { statsMap.remove(pluginId) }

    /** 连续失败达到阈值时由 Host 查询并置 Tripped */
    fun shouldTrip(pluginId: String): Boolean =
        statsOf(pluginId).consecutiveFailures >= TRIP_THRESHOLD

    private fun recordStats(pluginId: String, elapsedMs: Long, error: Throwable? = null) {
        synchronized(statsLock) {
            val s = statsMap[pluginId] ?: PluginRuntimeStats()
            statsMap[pluginId] = s.copy(
                calls = s.calls + 1,
                failures = s.failures + if (error != null) 1 else 0,
                consecutiveFailures = if (error != null) s.consecutiveFailures + 1 else 0,
                totalMillis = s.totalMillis + elapsedMs,
                lastError = error?.toString()?.take(120),
            )
        }
    }
}