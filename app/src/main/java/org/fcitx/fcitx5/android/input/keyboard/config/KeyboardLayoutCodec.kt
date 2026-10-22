/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard.config

import kotlinx.serialization.json.Json

/** 不依赖Android的布局校验和序列化，导入失败不会改动已保存的布局。 */
object KeyboardLayoutCodec {
    const val MAX_BYTES = 1024 * 1024
    /** popup只存额外候选；alt是独立的隐含首项，不占此上限。 */
    const val MAX_POPUP_EXTRAS = 32
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun decode(text: String): KeyboardLayoutConfig {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "布局文件不能超过1 MiB" }
        val config = json.decodeFromString<KeyboardLayoutConfig>(text)
        validate(config)
        return canonicalize(config)
    }

    fun encode(config: KeyboardLayoutConfig): String {
        validate(config)
        return json.encodeToString(canonicalize(config)) + "\n"
    }

    /** 将长按候选规范化为不包含重复 alt 的额外候选；null 保留旧版 fallback 语义。 */
    fun canonicalize(config: KeyboardLayoutConfig): KeyboardLayoutConfig = config.copy(
        keys = config.keys.mapValues { (_, key) ->
            if (key is KeySpec.Letter && key.popup != null) {
                key.copy(
                    popup = key.popup.filter { it != key.alt }.distinct(),
                    popupLetterOffset = key.popupLetterOffset,
                    popupShiftX = key.popupShiftX,
                )
            } else {
                key
            }
        }
    )

    fun validate(config: KeyboardLayoutConfig) {
        require(config.rows.size in 1..12 && config.rows.all { it.size in 1..32 }) { "布局需要1–12行，每行1–32个按键" }
        require(config.keys.size in 1..256) { "按键数量需要在1–256之间" }
        val used = config.rows.flatten()
        require(used.all { config.keys.containsKey(it) }) { "行中引用了不存在的按键" }
        require(used.distinct().size == used.size) { "同一个按键ID不能重复使用" }
        config.keys.forEach { (id, key) ->
            require(id.isNotBlank() && id.length <= 80) { "按键ID不能为空或超过80字符" }
            when (key) {
                is KeySpec.Letter -> {
                    require(key.char.length == 1 && (key.char[0] in 'a'..'z' || key.char[0] in 'A'..'Z')) { "字母键必须是单个英文字母" }
                    key.alt?.takeIf { it.isNotEmpty() }?.let(::text)
                    variant(key.variant)
                    require(key.popupLetterOffset == -1 || key.popupLetterOffset == 1) {
                        "按键$id 的popupLetterOffset必须为-1或1"
                    }
                    require(key.popupShiftX in -2..2) { "按键$id 的popupShiftX必须在-2–2之间" }
                    key.popup?.let { items ->
                        // []合法，运行时仍由resolve把alt作为首项；旧格式含alt由canonicalize去重。
                        require(items.size in 0..MAX_POPUP_EXTRAS) {
                            "按键$id 的popup允许0–32项额外候选，alt不计入项数"
                        }
                        items.forEach(::text)
                    }
                }
                is KeySpec.Symbol -> { text(key.char); width(key.width); variant(key.variant) }
                is KeySpec.Comma -> { text(key.char); width(key.width); variant(key.variant) }
                is KeySpec.NumPad -> {
                    text(key.label); width(key.width); variant(key.variant)
                    require(key.textSize.isFinite() && key.textSize in 8f..72f) { "字号需要在8–72之间" }
                    val code = key.code.removePrefix("0x").removePrefix("0X")
                    require(key.code.matches(Regex("[0-9]")) || code.toIntOrNull(16)?.let { it in 1..0x1fffffff } == true) { "数字键码无效" }
                }
                is KeySpec.Backspace -> { width(key.width); variant(key.variant) }
                is KeySpec.Return -> width(key.width)
                is KeySpec.LayoutSwitch -> {
                    text(key.label); width(key.width); variant(key.variant)
                    require(key.to in setOf("", "Text", "English", "Number", "Symbol", "Emoji", "Emoticon")) { "未知目标键盘" }
                }
                else -> Unit
            }
        }
        // 这些类型使用固定viewId，重复会导致操作命中错误。
        val fixed = used.map { config.keys.getValue(it) }.filter {
            it is KeySpec.Caps || it is KeySpec.Backspace || it is KeySpec.Lang ||
                it is KeySpec.Space || it is KeySpec.MiniSpace || it is KeySpec.Return
        }.map { it.javaClass }
        require(fixed.distinct().size == fixed.size) { "功能键类型不能重复" }
    }

    private fun text(value: String) {
        require(value.isNotEmpty() && value.length <= 32 && value.none { it.isISOControl() }) { "按键文本或候选必须为1–32字符，不能含控制字符" }
    }
    private fun width(value: Float) {
        require(value.isFinite() && value in 0f..1f) { "按键宽度需要在0–1之间" }
    }
    private fun variant(value: String) {
        require(value.lowercase() in setOf("normal", "alt", "alternative", "accent")) { "未知按键外观" }
    }
}
