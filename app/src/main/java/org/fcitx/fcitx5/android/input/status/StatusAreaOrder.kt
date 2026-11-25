/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.plugin.PluginHost
import org.fcitx.fcitx5.android.plugin.PluginState
import org.fcitx.fcitx5.android.ui.main.iconRes

/**
 * 「更多」面板手动排序：共享顺序存储 + 默认条目构建。
 *
 * 输入法侧（StatusAreaWindow）与主应用排序页（StatusAreaOrderFragment）
 * 读写同一份顺序（SharedPreferences），排序结果影响整个「更多」面板：
 * 静态功能条目 + 插件条目 + fcitx 引擎条目统一按保存顺序排列。
 * 排序键用条目 label；未记录顺序的新条目按默认顺序追加在已排序条目之后。
 */
object StatusAreaOrder {
    private const val PREFS = "status_area_order"
    private const val KEY = "order"
    private const val SEP = "\u0001"

    fun savedOrder(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()

    fun save(ctx: Context, labels: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, labels.joinToString(SEP)).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** 按保存的顺序排序条目；未记录的条目按原相对顺序排在所有已记录之后 */
    fun applyOrder(ctx: Context, entries: Array<StatusAreaEntry>): Array<StatusAreaEntry> {
        val order = savedOrder(ctx)
        if (order.isEmpty()) return entries
        val rank = order.withIndex().associate { it.value to it.index }
        val base = order.size
        return entries
            .mapIndexed { i, e -> e to (rank[e.label] ?: (base + i)) }
            .sortedBy { it.second }
            .map { it.first }
            .toTypedArray()
    }

    /** 「更多」面板的宿主静态条目（默认顺序） */
    fun defaultEntries(ctx: Context): Array<StatusAreaEntry> = arrayOf(
        StatusAreaEntry.Android(
            ctx.getString(R.string.keyboard_layout_position),
            R.drawable.ic_baseline_drag_handle_24,
            StatusAreaEntry.Android.Type.KeyboardPosition
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.theme),
            R.drawable.ic_baseline_palette_24,
            StatusAreaEntry.Android.Type.ThemeList
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.input_method_options),
            R.drawable.ic_baseline_language_24,
            StatusAreaEntry.Android.Type.InputMethod
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.reload_config),
            R.drawable.ic_baseline_sync_24,
            StatusAreaEntry.Android.Type.ReloadConfig
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.virtual_keyboard),
            R.drawable.ic_baseline_keyboard_24,
            StatusAreaEntry.Android.Type.Keyboard
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.message_phrase_cipher),
            R.drawable.ic_baseline_code_24,
            StatusAreaEntry.Android.Type.MessagePhraseCipher
        ),
        StatusAreaEntry.Android(
            ctx.getString(R.string.tts_audio_send),
            R.drawable.ic_baseline_keyboard_voice_24,
            StatusAreaEntry.Android.Type.TtsAudio
        )
    )

    /** 已安装插件条目（active 反映启用状态），顺序=注册序 */
    fun pluginEntries(): Array<StatusAreaEntry> =
        PluginHost.snapshot().map { info ->
            StatusAreaEntry.Android(
                info.manifest.name,
                info.manifest.iconRes(),
                StatusAreaEntry.Android.Type.Plugin,
                info.state == PluginState.Enabled,
                pluginId = info.manifest.id
            )
        }.toTypedArray()

    /** fcitx 引擎状态条目（主应用侧读取；无连接/异常时为空，不影响排序其余条目） */
    fun fcitxEntries(): Array<StatusAreaEntry> {
        val conn = FcitxDaemon.getFirstConnectionOrNull() ?: return emptyArray()
        val actions: Array<Action> = runCatching {
            conn.runImmediately { statusArea() }
        }.getOrDefault(emptyArray())
        return actions.map { StatusAreaEntry.fromAction(it) }.toTypedArray()
    }

    /** 排序页展示的全量条目（默认顺序 = 静态 + 插件 + 引擎） */
    fun allEntries(ctx: Context): Array<StatusAreaEntry> =
        defaultEntries(ctx) + pluginEntries() + fcitxEntries()
}
