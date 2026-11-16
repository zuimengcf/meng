/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync core (io.localsync.core.Hlc).
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

import java.util.Locale

/**
 * 混合逻辑时钟（HLC）：wall 物理毫秒 + 同毫秒计数 + 设备 id 平局裁决，全序。
 * 编码为定长 "%016x%08x@node"，字符串字典序 == 时钟先后。
 */
data class Hlc(val wall: Long, val counter: Int, val node: String) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        if (wall != other.wall) return wall.compareTo(other.wall)
        if (counter != other.counter) return counter.compareTo(other.counter)
        return node.compareTo(other.node)
    }

    fun encode(): String = String.format(Locale.ROOT, "%016x%08x@%s", wall, counter, node)

    override fun toString(): String = encode()

    /** 严格大于 this、属于 [owner] 的最小时钟；计数饱和时进位到 wall+1（饱和加法）。 */
    fun successor(owner: String): Hlc =
        if (counter == Int.MAX_VALUE) Hlc(satAdd(wall, 1), 0, owner) else Hlc(wall, counter + 1, owner)

    companion object {
        fun decode(s: String): Hlc {
            val at = s.indexOf('@')
            require(at == 24 && s.length > 25) { "bad hlc: $s" }
            val wall = java.lang.Long.parseUnsignedLong(s.substring(0, 16), 16)
            require(wall >= 0) { "bad hlc wall: $s" }
            return Hlc(wall, s.substring(16, 24).toLong(16).toInt(), s.substring(25))
        }

        fun cmp(a: String?, b: String?): Int = when {
            a == null && b == null -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }

        fun max(a: String?, b: String?): String? = if (cmp(a, b) >= 0) a else b

        fun satAdd(a: Long, b: Long): Long {
            val r = a + b
            return if (((a xor r) and (b xor r)) < 0) (if (b > 0) Long.MAX_VALUE else Long.MIN_VALUE) else r
        }
    }
}

/**
 * HLC 时钟源，线程安全。
 * @param maxForwardDriftMs 远端时钟超过本机物理时间多少毫秒视为错乱（整批拒收）。
 */
class HlcClock(
    private val node: String,
    initial: Hlc? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxForwardDriftMs: Long = 24L * 3600 * 1000,
) {
    private var last: Hlc = initial ?: Hlc(0, 0, node)

    @Synchronized
    fun tick(): Hlc {
        val pt = now()
        last = if (pt > last.wall) Hlc(pt, 0, node) else last.successor(node)
        return last
    }

    fun isTooFarAhead(remote: Hlc): Boolean = Hlc.satAdd(now(), maxForwardDriftMs) < remote.wall

    /** 吸收远端/本地版本时钟；超前过多时拒绝（返回 false），不把本机时钟带偏。 */
    @Synchronized
    fun receive(remote: Hlc): Boolean {
        val pt = now()
        if (isTooFarAhead(remote)) return false
        val wall = maxOf(pt, last.wall, remote.wall)
        last = when {
            wall == last.wall && wall == remote.wall -> Hlc(wall, maxOf(last.counter, remote.counter), node).successor(node)
            wall == last.wall -> last.successor(node)
            wall == remote.wall -> Hlc(wall, remote.counter, node).successor(node)
            else -> Hlc(wall, 0, node)
        }
        return true
    }

    @Synchronized
    fun current(): Hlc = last
}
