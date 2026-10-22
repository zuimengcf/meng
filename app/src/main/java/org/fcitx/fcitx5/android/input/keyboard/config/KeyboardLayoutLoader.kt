/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import android.util.AtomicFile
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


/** 当前用户JSON优先；无效文件只回退、不覆写。保存以AtomicFile避免半写入。 */
object KeyboardLayoutLoader {
    private const val CONFIG_SUBDIR = "usr/share/fcitx5/keyboard"
    @Volatile var revision: Long = 0L
        private set

    fun decodeConfig(text: String): KeyboardLayoutConfig = KeyboardLayoutCodec.decode(text)
    fun encodeConfig(config: KeyboardLayoutConfig): String = KeyboardLayoutCodec.encode(config)

    fun defaultConfig(layoutName: String): KeyboardLayoutConfig? = runCatching {
        requireLayoutName(layoutName)
        appContext.assets.open("$CONFIG_SUBDIR/$layoutName-layout.json")
            .bufferedReader().use { decodeConfig(it.readText()) }
    }.onFailure { Timber.w(it, "Cannot load default keyboard layout: $layoutName") }.getOrNull()

    fun configFile(layoutName: String): File? {
        requireLayoutName(layoutName)
        val base = appContext.getExternalFilesDir(null) ?: return null
        return File(base, "$CONFIG_SUBDIR/$layoutName-layout.json")
    }

    fun configFilePath(layoutName: String): String? = configFile(layoutName)?.absolutePath

    fun loadOrNull(layoutName: String): List<List<KeyDef>>? = loadConfig(layoutName)?.let(::toKeyDefs)

    fun loadConfig(layoutName: String): KeyboardLayoutConfig? {
        val file = configFile(layoutName)
        if (file != null && (file.exists() || File(file.path + ".bak").exists())) {
            val custom = runCatching {
                AtomicFile(file).openRead().use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (buffer.size() <= KeyboardLayoutCodec.MAX_BYTES) {
                        val count = input.read(chunk, 0, minOf(chunk.size, KeyboardLayoutCodec.MAX_BYTES + 1 - buffer.size()))
                        if (count < 0) break
                        buffer.write(chunk, 0, count)
                    }
                    val bytes = buffer.toByteArray()
                    require(bytes.size <= KeyboardLayoutCodec.MAX_BYTES) { "布局文件过大" }
                    decodeConfig(bytes.toString(Charsets.UTF_8))
                }
            }.onFailure { Timber.w(it, "Invalid keyboard layout; keeping file untouched") }.getOrNull()
            if (custom != null) {
                // 只升级与旧出厂模板完全一致的副本，真正自定义的布局不改动。
                val defaults = defaultConfig(layoutName)
                val legacy = if (layoutName == "Text" && defaults != null)
                    ChineseKeyboardSymbols.legacyTextTemplate(defaults) else null
                return when {
                    custom == legacy -> defaults ?: custom
                    (layoutName == "Text" || layoutName == "English") && defaults != null -> ChineseKeyboardSymbols.upgradeTextTemplate(custom, defaults)
                    else -> custom
                }
            }
        }
        val builtin = defaultConfig(layoutName) ?: return null
        if (file != null && !file.exists()) saveConfig(layoutName, builtin)
        return builtin
    }

    fun saveConfig(layoutName: String, config: KeyboardLayoutConfig): Boolean {
        val file = configFile(layoutName) ?: return false
        return runCatching {
            val bytes = encodeConfig(config).toByteArray(Charsets.UTF_8)
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            revision++
            true
        }.onFailure { Timber.w(it, "Cannot save keyboard layout: $layoutName") }.getOrDefault(false)
    }

    private fun requireLayoutName(name: String) {
        require(name == "Text" || name == "English" || name == "Number") { "未知布局名称" }
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
            is KeySpec.Letter -> {
                val items = ChineseKeyboardSymbols.resolve(char, alt, popup)
                AlphabetKey(
                    char, items.firstOrNull().orEmpty(), variant.variant(),
                    popup = buildList<KeyDef.Popup> {
                        add(KeyDef.Popup.AltPreview(char, items.firstOrNull().orEmpty(), rawAlternative = true))
                        if (items.isNotEmpty()) add(KeyDef.Popup.Keyboard.Explicit(items.toTypedArray()))
                    }.toTypedArray(),
                    literalSymbols = true
                )
            }
            is KeySpec.Symbol -> SymbolKey(char, width, variant.variant())
            is KeySpec.Comma -> CommaKey(width, variant.variant(), char)
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