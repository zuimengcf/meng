/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.input.keyboard.AlphabetKey
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.CapsKey
import org.fcitx.fcitx5.android.input.keyboard.CommaKey
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LanguageKey
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.MiniSpaceKey
import org.fcitx.fcitx5.android.input.keyboard.NumPadKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SeparatorKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.SymbolKey
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 键盘布局配置加载器。
 *
 * 把 [KeyboardLayoutConfig]（JSON）转换成 BaseKeyboard 需要的 `List<List<KeyDef>>`。
 * 加载顺序：
 * 1. 优先读应用数据目录下的 `files/usr/share/fcitx5/keyboard/<layoutName>-layout.json`（用户可编辑）。
 * 2. 读取失败/文件不存在时，回退到内置的默认配置 [editorDefaultOf]，保证键盘始终能用。
 *
 * `layoutName` 为键盘名（`Text` / `Number`）。
 */
object KeyboardLayoutLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val CONFIG_SUBDIR = "usr/share/fcitx5/keyboard"

    /**
     * 加载指定键盘的布局。返回 null 表示读取失败（调用方回退到代码内置布局）。
     *
     * 查找顺序：
     * 1. 用户可编辑的外部文件 `getExternalFilesDir(null)/usr/share/fcitx5/keyboard/<layoutName>-layout.json`
     *    —— 用户通过文件管理器 / SAF 编辑后重启键盘即可生效（真正的自定义入口）。
     * 2. 应用 assets 内置的同路径 JSON —— 作为「外部化默认配置」的出厂版本。
     */
    fun loadOrNull(layoutName: String): List<List<KeyDef>>? {
        val raw = readConfigText(layoutName) ?: return null
        return runCatching {
            val config = json.decodeFromString<KeyboardLayoutConfig>(raw)
            toKeyDefs(config)
        }.onFailure {
            Timber.w(it, "Failed to parse keyboard layout config: $layoutName")
        }.getOrNull()
    }

    private fun readConfigText(layoutName: String): String? {
        // 1. 用户可编辑的外部副本优先
        val extFile = configFile(layoutName)
        if (extFile != null && extFile.exists()) {
            return runCatching { extFile.readText() }.onFailure {
                Timber.w(it, "Failed to read external keyboard config: ${extFile.path}")
            }.getOrNull()
        }
        // 2. assets 内置的出厂配置：首次读取时一并导出到外部目录，供用户编辑
        val builtin = runCatching {
            appContext.assets.open("$CONFIG_SUBDIR/$layoutName-layout.json")
                .bufferedReader().use { it.readText() }
        }.onFailure {
            Timber.v("No builtin keyboard config for $layoutName")
        }.getOrNull() ?: return null
        if (extFile != null && !extFile.exists()) {
            exportBuiltinToExternal(layoutName, builtin, extFile)
        }
        return builtin
    }

    /** 把出厂默认配置落盘到用户可编辑的外部目录，使其真正可自定义。 */
    private fun exportBuiltinToExternal(layoutName: String, content: String, extFile: File) {
        runCatching {
            extFile.parentFile?.mkdirs()
            extFile.writeText(content)
            Timber.d("Exported builtin keyboard layout to ${extFile.path}")
        }.onFailure {
            Timber.w(it, "Failed to export builtin keyboard layout to ${extFile.path}")
        }
    }

    /** 应用外部（用户可编辑）配置文件路径。 */
    fun configFile(layoutName: String): File? {
        val base = appContext.getExternalFilesDir(null) ?: return null
        return File(base, "$CONFIG_SUBDIR/$layoutName-layout.json")
    }

    /** 外部配置文件的实际绝对路径（供设置页提示用户可编辑的位置）。 */
    fun configFilePath(layoutName: String): String? = configFile(layoutName)?.absolutePath

    /**
     * 加载当前生效的完整布局配置（供 UI 编辑器读取）。
     * 优先读外部用户文件；否则回退到 assets 内置并返回其解析结果。
     * 返回 null 表示无可用配置。
     */
    fun loadConfig(layoutName: String): KeyboardLayoutConfig? {
        val raw = readConfigText(layoutName) ?: return null
        return runCatching {
            json.decodeFromString<KeyboardLayoutConfig>(raw)
        }.onFailure {
            Timber.w(it, "Failed to parse keyboard layout config: $layoutName")
        }.getOrNull()
    }

    /**
     * 把配置写入外部用户可编辑文件（即运行时加载优先读取的位置）。
     * 返回是否写成功。
     */
    fun saveConfig(layoutName: String, config: KeyboardLayoutConfig): Boolean {
        val file = configFile(layoutName) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(config))
            Timber.d("Saved keyboard layout $layoutName to ${file.path}")
            true
        }.onFailure {
            Timber.w(it, "Failed to save keyboard layout $layoutName to ${file.path}")
        }.getOrDefault(false)
    }

    /** 把配置结构转换成 KeyDef 布局。 */
    fun toKeyDefs(config: KeyboardLayoutConfig): List<List<KeyDef>> {
        val defs = HashMap<String, KeyDef?>()
        config.keys.forEach { (id, spec) ->
            defs[id] = spec.toKeyDef()
        }
        return config.rows.map { row -> row.mapNotNull { defs[it] } }
    }

    private fun KeySpec.toKeyDef(): KeyDef? {
        return when (this) {
            is KeySpec.Letter -> AlphabetKey(
                char, alt ?: "", variant.variant()
            )
            is KeySpec.Symbol -> SymbolKey(char, width, variant.variant())
            is KeySpec.Comma -> CommaKey(width, variant.variant())
            is KeySpec.NumPad -> NumPadKey(
                label,
                code.padSym(),
                textSize,
                width,
                variant.variant()
            )
            is KeySpec.Caps -> CapsKey()
            is KeySpec.Separator -> SeparatorKey(0.1f, KeyDef.Appearance.Variant.Alternative)
            is KeySpec.Backspace -> BackspaceKey(width, variant.variant())
            is KeySpec.Lang -> LanguageKey()
            is KeySpec.Space -> SpaceKey()
            is KeySpec.MiniSpace -> MiniSpaceKey()
            is KeySpec.Return -> ReturnKey(width)
            is KeySpec.LayoutSwitch -> LayoutSwitchKey(label, to, width, variant.variant())
        }
    }

    /** 解析数字键盘按键的符号键码：接受十六进制串 `0xffb1` 或数字字符 `0`-`9`。 */
    private fun String.padSym(): Int {
        val s = trim()
        if (s.length == 1 && s[0] in '0'..'9') {
            return FcitxKeyMapping.FcitxKey_KP_0 + (s[0] - '0')
        }
        return s.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0
    }

    private fun String.variant(): KeyDef.Appearance.Variant = when (lowercase()) {
        "alt" -> KeyDef.Appearance.Variant.AltForeground
        "alternative" -> KeyDef.Appearance.Variant.Alternative
        "accent" -> KeyDef.Appearance.Variant.Accent
        else -> KeyDef.Appearance.Variant.Normal
    }
}