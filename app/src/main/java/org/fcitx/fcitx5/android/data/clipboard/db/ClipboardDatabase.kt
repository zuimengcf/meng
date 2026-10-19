/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ClipboardEntry::class, ClipboardGroup::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
    ]
)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao
    abstract fun clipboardGroupDao(): ClipboardGroupDao

    companion object {
        const val DB_NAME = "clbdb"

        /** 8→9：新增 lastModifiedBy 列（记录每条数据最后被哪台设备修改，多设备同步区分用）。 */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ${ClipboardEntry.TABLE_NAME} ADD COLUMN groupSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ${ClipboardGroup.TABLE_NAME} ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ${ClipboardGroup.TABLE_NAME} ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ${ClipboardEntry.TABLE_NAME} ADD COLUMN lastModifiedBy TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}