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

@Dao
interface ClipboardJournalOutboxDao {
    @Insert
    suspend fun insert(batch: ClipboardJournalOutbox): Long

    @Query("SELECT * FROM clipboard_journal_outbox ORDER BY id")
    suspend fun pending(): List<ClipboardJournalOutbox>

    @Query("DELETE FROM clipboard_journal_outbox WHERE id = :id")
    suspend fun acknowledge(id: Long)
}
