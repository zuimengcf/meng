/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(clipboardEntry: ClipboardEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ClipboardEntry)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET pinned=:pinned WHERE id=:id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(id: Int, text: String)

    /** 跨设备同步收到图片二进制时，为缺失图片的条目补写本地图片路径。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET imagePath=:imagePath WHERE id=:id")
    suspend fun updateImagePath(id: Int, imagePath: String)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTime(id: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun itemCount(): Int

    /** 按跨设备同步主键定位条目（局域网合并用；多设备各自 id 不同，须以 syncId 定位）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE syncId=:syncId AND syncId<>'' LIMIT 1")
    suspend fun getBySyncId(syncId: String): ClipboardEntry?

    /** 更新某条目的 syncId 与最终变更时间。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET syncId=:syncId, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setSyncInfo(id: Int, syncId: String, updatedAt: Long)

    /** 更新某条目的变更时间（同步导出、编辑、分组等所用）。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET updatedAt=:updatedAt WHERE id=:id")
    suspend fun touchUpdatedAt(id: Int, updatedAt: Long)

    /** 记录某条数据最后被哪台设备修改（多设备同步区分用）。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET lastModifiedBy=:deviceId WHERE id=:id")
    suspend fun setLastModifiedBy(id: Int, deviceId: String)

    /** 读取本机所有非删除条目（含 deleted=0/1，供同步导出完整列表）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME}")
    suspend fun getAllIncludingDeleted(): List<ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id AND deleted=0 LIMIT 1")
    suspend fun get(id: Int): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE rowId=:rowId AND deleted=0 LIMIT 1")
    suspend fun get(rowId: Long): ClipboardEntry?

    @Query("SELECT EXISTS(SELECT 1 FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0)")
    suspend fun haveUnpinned(): Boolean

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun getAllUnpinned(): List<ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC")
    fun allEntries(): PagingSource<Int, ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun getAll(): List<ClipboardEntry>

    /** 全量读取未删除条目，与键盘面板 allEntries() 完全一致的排序：置顶优先，其次时间倒序。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC")
    suspend fun getAllSorted(): List<ClipboardEntry>

    /** 按时间倒序读取（最新在上，不做置顶优先）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY timestamp DESC")
    suspend fun getAllByTimeDesc(): List<ClipboardEntry>

    /** 按时间正序读取（最旧在上）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY timestamp ASC")
    suspend fun getAllByTimeAsc(): List<ClipboardEntry>

    /** 按更新时间倒序读取（最近变更/最近操作过的在顶）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY updatedAt DESC, timestamp DESC")
    suspend fun getAllByUpdatedAtDesc(): List<ClipboardEntry>

    /** 按文本字典序读取（忽略大小写，其次时间倒序）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY text COLLATE NOCASE ASC, timestamp DESC")
    suspend fun getAllByTextAsc(): List<ClipboardEntry>

    /** 图片优先（图片在最前，其余按时间倒序）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY (type LIKE 'image/%') DESC, timestamp DESC")
    suspend fun getAllByImageFirst(): List<ClipboardEntry>

    /** 纯文本优先（文本在最前，图片靠后，其余按时间倒序）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY (type LIKE 'image/%') ASC, timestamp DESC")
    suspend fun getAllByTextFirst(): List<ClipboardEntry>

    /** 按自定义拖拽顺序 sortOrder（越大越靠前，其次时间倒序）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY sortOrder DESC, timestamp DESC")
    suspend fun getAllBySortOrder(): List<ClipboardEntry>

    /** 批量更新条目排序位置（拖动排序用）。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET sortOrder=:sortOrder WHERE id=:id")
    suspend fun updateSortOrder(id: Int, sortOrder: Long)

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND deleted=0 LIMIT 1")
    suspend fun find(text: String, sensitive: Boolean = false): ClipboardEntry?

    /** 供导入使用：按 id 覆盖式写入一条条目（用于恢复完整记录） */
    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id")
    suspend fun deleteById(id: Int)

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun findAllIds(): IntArray

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun findUnpinnedIds(): IntArray

    /** 查找超上限应被自动清理的未置顶未删除条目（timestamp 早于 cutoff）。 */
    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0 AND timestamp<:cutoff")
    suspend fun findUnpinnedOlderThan(cutoff: Long): IntArray

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 WHERE id in (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    /** 批量更新多条目的变更时间（删除/恢复墓碑用，保证删除在合并中“较新者胜”）。 */
    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET updatedAt=:updatedAt WHERE id in (:ids)")
    suspend fun touchUpdatedAt(ids: IntArray, updatedAt: Long)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET DELETED=1 WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun markUnpinnedAsDeletedEarlierThan(timestamp: Long)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=0 WHERE id in (:ids) AND deleted=1")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1")
    suspend fun realDelete()

    /** 物理删除“已删除”的墓碑。带 syncId 的墓碑须保留（供同步传播删除，避免对端复活），故只清无 syncId 的本地残留。 */
    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1 AND syncId=''")
    suspend fun realDeleteLocalOnly()

    /** 读取全部带 syncId 的删除墓碑（多设备确认后按水位线清理用）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1 AND syncId<>''")
    suspend fun getDeletedTombstones(): List<ClipboardEntry>

    /** 批量物理删除指定条目（墓碑按设备确认后清理用）。 */
    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    /* ===== 搜索（额外配置，快速寻找） ===== */

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND text LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    fun search(query: String): PagingSource<Int, ClipboardEntry>

    /** 搜索并返回完整列表（供全屏搜索结果页直接展示）。 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND text LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    suspend fun searchAll(query: String): List<ClipboardEntry>

    /* ===== 分组（额外配置，快速寻找） ===== */

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET groupId=:groupId, groupSyncId=:groupSyncId WHERE id=:id")
    suspend fun setGroup(id: Int, groupId: Int, groupSyncId: String = "")

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET groupSyncId=:groupSyncId WHERE id=:id")
    suspend fun setGroupSyncId(id: Int, groupSyncId: String)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET groupId=0, groupSyncId='' WHERE groupId=:groupId")
    suspend fun clearGroup(groupId: Int)

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND groupId=:groupId ORDER BY pinned DESC, timestamp DESC")
    fun entriesInGroup(groupId: Int): PagingSource<Int, ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND groupId=0 ORDER BY pinned DESC, timestamp DESC")
    fun ungroupedEntries(): PagingSource<Int, ClipboardEntry>

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND groupId=:groupId")
    suspend fun countInGroup(groupId: Int): Int

    /** 搜索限定在某分组内 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND groupId=:groupId AND text LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    fun searchInGroup(groupId: Int, query: String): PagingSource<Int, ClipboardEntry>

    /** 在未分组中搜索 */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND groupId=0 AND text LIKE '%' || :query || '%' ORDER BY pinned DESC, timestamp DESC")
    fun searchUngrouped(query: String): PagingSource<Int, ClipboardEntry>
}
