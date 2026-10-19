/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClipboardGroupDao {

    @Insert
    suspend fun insert(group: ClipboardGroup): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: ClipboardGroup)

    @Query("SELECT * FROM ${ClipboardGroup.TABLE_NAME} WHERE deleted=0 ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<ClipboardGroup>

    @Query("SELECT * FROM ${ClipboardGroup.TABLE_NAME} WHERE id=:id LIMIT 1")
    suspend fun get(id: Int): ClipboardGroup?

    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET name=:name WHERE id=:id")
    suspend fun rename(id: Int, name: String)

    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET sortOrder=:sortOrder WHERE id=:id")
    suspend fun updateSortOrder(id: Int, sortOrder: Int)

    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET deleted=1, updatedAt=:updatedAt, deviceId=:deviceId WHERE id=:id")
    suspend fun markDeleted(id: Int, updatedAt: Long, deviceId: String)

    /** 按跨设备同步主键定位分组（局域网合并用）。 */
    @Query("SELECT * FROM ${ClipboardGroup.TABLE_NAME} WHERE syncId=:syncId AND syncId<>'' LIMIT 1")
    suspend fun getBySyncId(syncId: String): ClipboardGroup?

    /** 更新分组 syncId 与最终变更时间。 */
    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET syncId=:syncId, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setSyncInfo(id: Int, syncId: String, updatedAt: Long)

    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET syncId=:syncId, updatedAt=:updatedAt, deviceId=:deviceId WHERE id=:id")
    suspend fun setSyncInfo(id: Int, syncId: String, updatedAt: Long, deviceId: String)

    @Query("UPDATE ${ClipboardGroup.TABLE_NAME} SET deleted=1, updatedAt=:updatedAt, deviceId=:deviceId WHERE id=:id")
    suspend fun delete(id: Int, updatedAt: Long, deviceId: String)

    /** 读取全部分组（含本机与同步来的）。 */
    @Query("SELECT * FROM ${ClipboardGroup.TABLE_NAME} ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllIncludingAll(): List<ClipboardGroup>

    /** 按组名查找分组（同步合并时以组名去重）。 */
    @Query("SELECT * FROM ${ClipboardGroup.TABLE_NAME} WHERE name=:name LIMIT 1")
    suspend fun getByName(name: String): ClipboardGroup?
}
