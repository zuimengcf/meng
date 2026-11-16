/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync core (io.localsync.core.SyncCollection).
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

/**
 * 业务层为每种要同步的数据实现一个集合适配器。key 在所有设备上表示同一条逻辑记录。
 */
interface SyncCollection {
    val name: String
    val policy: ConflictPolicy get() = ConflictPolicy.DELETE_WINS

    fun snapshot(): Map<String, Units>

    /** 写入合并后的完整记录；false = 业务层暂不能落地（记为影子，之后每次扫描重试）。 */
    fun upsert(key: String, units: Units): Boolean

    fun delete(key: String)

    /**
     * meng 扩展：记录自带的版本时间（毫秒）。非 null 时，本地变更操作的 HLC 以它为 wall
     * （并保证严格大于该记录已有的时钟），使引擎的先后与业务层「较新者胜」一致；
     * null 时用本机 HLC tick（localsync 原行为）。
     */
    fun versionWall(units: Units): Long? = null

    /**
     * meng 扩展：本地扫描发现某条记录不见了时怎么办。
     * 业务层自己做软删除/保留期回收时，物理消失不代表用户删除，返回 IGNORE 即可。
     */
    fun onMissing(key: String, last: Units): MissingPolicy = MissingPolicy.PROPAGATE_DELETE
}

enum class MissingPolicy { PROPAGATE_DELETE, IGNORE }

/**
 * 支持增量扫描的集合：token 相同则认为内容未变，只对变化的 key 调 [load] 并哈希。
 */
interface IncrementalCollection : SyncCollection {
    fun tokens(): Map<String, String>
    fun load(keys: Collection<String>): Map<String, Units>
    override fun snapshot(): Map<String, Units> = load(tokens().keys)
}
