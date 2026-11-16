/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * 心跳用的摘要缓存（纯 Kotlin，可在 JVM 单测）。
 *
 * 摘要只在数据代数 [generation] 变化或超过 [maxAgeMs] 时重算；超龄兜底覆盖
 * 不经过数据库的变化（例如图片文件被外部替换）。
 * 只用于心跳的"是否需要交换"判断；预览、全量覆盖等需要精确比对的路径仍直接重算。
 */
class ClipboardSyncFingerprintCache(private val maxAgeMs: Long = 30_000L) {
    private data class Entry(val generation: Long, val at: Long, val value: String)
    @Volatile private var entry: Entry? = null

    /** 有效缓存：代数相同且未超龄。 */
    fun fresh(generation: Long, now: Long): String? =
        entry?.takeIf { it.generation == generation && now - it.at in 0 until maxAgeMs }?.value

    /** 任意缓存值（可能过期），仅在同步锁被长时间占用时作为心跳回答。 */
    fun any(): String? = entry?.value

    fun put(generation: Long, now: Long, value: String) { entry = Entry(generation, now, value) }

    fun clear() { entry = null }
}
