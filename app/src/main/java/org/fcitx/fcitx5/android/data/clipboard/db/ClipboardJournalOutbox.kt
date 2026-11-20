/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** A committed journal batch remains here until the file journal has been fsynced. */
@Entity(tableName = "clipboard_journal_outbox")
data class ClipboardJournalOutbox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: String,
    @ColumnInfo(defaultValue = "'append'") val action: String = "append",
)

/** 只含小字段的出盒索引行：大批次的 payload 按片读取，绝不整行进 CursorWindow。 */
data class ClipboardJournalOutboxIndex(val id: Long, val action: String, val size: Long)

@Dao
interface ClipboardJournalOutboxDao {
    @Insert
    suspend fun insert(batch: ClipboardJournalOutbox): Long

    @Query("SELECT * FROM clipboard_journal_outbox ORDER BY id")
    suspend fun pending(): List<ClipboardJournalOutbox>

    /**
     * Android 的 CursorWindow 单行上限约 2 MB：一次合并里带图片 base64 或超长文本的批次写得进去、
     * 却用 [pending] 读不出来（SQLiteBlobTooBigException），而每次拿同步锁都会先回放出盒，
     * 于是之后所有同步和剪贴板记录都会失败。回放改用这两个查询：先取索引，再按片拼出 payload。
     */
    @Query("SELECT id, action, length(payload) AS size FROM clipboard_journal_outbox ORDER BY id")
    suspend fun pendingIndex(): List<ClipboardJournalOutboxIndex>

    /** [start] 从 1 开始，按字符计（SQLite substr 不会切断多字节字符）。 */
    @Query("SELECT substr(payload, :start, :count) FROM clipboard_journal_outbox WHERE id = :id")
    suspend fun payloadSlice(id: Long, start: Long, count: Int): String?

    @Query("DELETE FROM clipboard_journal_outbox WHERE id = :id")
    suspend fun acknowledge(id: Long)
}
