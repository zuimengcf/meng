/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.content.Context
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 长按弹出选择器（popup preset）可视化编辑的数据内核（纯逻辑，不依赖 UI）。
 *
 * 键盘长按某键会弹出一个候选符号选择器，其内容来自内置 [PopupPreset]（硬编码在
 * Kotlin 源码，例如长按 `.` 弹出 `, ? ! : ; ...`）。本内核让用户可修改 / 新增：
 *
 * 「中间层，不改原文件」：
 * - 默认内容仍由内置 [PopupPreset] 提供（不碰源码）；
 * - 用户编辑的内容写入独立的用户覆盖层 JSON：`data/longpress_preset.json`
 *   （存于 app 外部私有目录，更新 App 不会被覆盖）。
 * - 运行时 [getFor] 优先返回用户覆盖，没有覆盖的键回退内置默认 —— 这就是「中间层」。
 */
object LongPressPresetEditor {

    /** 单个长按键的候选列表。key 为长按的主键（如 "."），items 为弹窗候选。 */
    data class PresetRow(
        val key: String,
        /** 默认候选（内置 PopupPreset） */
        val defaultItems: List<String>,
        /** 用户覆盖后的候选（null 表示未覆盖，沿用默认） */
        val overrideItems: List<String>? = null,
    ) {
        val currentItems: List<String> get() = overrideItems ?: defaultItems
        val isOverridden: Boolean get() = overrideItems != null
    }

    private const val FILE_NAME = "longpress_preset.json"

    /** 用户覆盖层文件路径。 */
    fun presetFile(context: Context = appContext): File {
        val dataDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "data")
        return File(dataDir, FILE_NAME)
    }

    /** 读取用户覆盖层 JSON：key → items 列表。 */
    private fun loadOverrides(context: Context): Map<String, List<String>> {
        val f = presetFile(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val obj = JSONObject(f.readText())
            val map = HashMap<String, List<String>>()
            obj.keys().forEach { k ->
                val arr = obj.optJSONArray(k)
                if (arr != null) {
                    map[k] = List(arr.length()) { i -> arr.optString(i) }
                }
            }
            map
        }.getOrElse {
            Timber.e(it, "Failed to parse longpress preset overrides")
            emptyMap()
        }
    }

    /** 持久化用户覆盖层 JSON。 */
    private fun saveOverrides(context: Context, overrides: Map<String, List<String>>): Boolean {
        return runCatching {
            val f = presetFile(context)
            f.parentFile?.mkdirs()
            // 若文件存在但不可写（如被 shell 创建），先删除再重建
            if (f.exists() && !f.canWrite()) f.delete()
            val obj = JSONObject()
            overrides.forEach { (k, v) ->
                val arr = org.json.JSONArray()
                v.forEach { arr.put(it) }
                obj.put(k, arr)
            }
            f.writeText(obj.toString())
            true
        }.getOrElse {
            Timber.e(it, "Failed to save longpress preset overrides")
            false
        }
    }

    /** 所有可编辑行：内置默认 + 用户覆盖（合并所有键）。 */
    fun loadRows(context: Context): List<PresetRow> {
        val overrides = loadOverrides(context)
        val allKeys = (PopupPreset.keys + overrides.keys).distinct().sorted()
        return allKeys.map { k ->
            PresetRow(
                key = k,
                defaultItems = PopupPreset[k]?.toList() ?: emptyList(),
                overrideItems = overrides[k],
            )
        }
    }

    /** 运行时查询：某键长按弹出的候选，优先用户覆盖。 */
    fun getFor(context: Context, label: String): Array<String>? {
        val overrides = loadOverrides(context)
        // 精确匹配
        val exact = overrides[label]
        if (exact != null && exact.isNotEmpty()) return exact.toTypedArray()
        // 大小写回退：运行时 label 可能被 TextKeyboard 转成大写（transformAlphabet），
        // 而用户在编辑器保存用的是小写主键。这里回退查大小写变体，保证映射层命中。
        if (label.length == 1) {
            val alt = if (label[0].isUpperCase()) label.lowercase() else label.uppercase()
            val fallback = overrides[alt]
            if (fallback != null && fallback.isNotEmpty()) return fallback.toTypedArray()
        }
        return PopupPreset[label]
    }

    /** 保存某键的候选（覆盖或新增）。items 为空则视为删除覆盖，回退默认。 */
    fun save(context: Context, key: String, items: List<String>): Boolean {
        val overrides = loadOverrides(context).toMutableMap()
        val cleaned = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            overrides.remove(key)
        } else {
            overrides[key] = cleaned
        }
        return saveOverrides(context, overrides)
    }

    /** 移除某键的用户覆盖（回退默认）。 */
    fun removeOverride(context: Context, key: String): Boolean {
        val overrides = loadOverrides(context).toMutableMap()
        overrides.remove(key)
        return saveOverrides(context, overrides)
    }
}
