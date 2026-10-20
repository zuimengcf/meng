/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * 「梦」输入统计数据库（独立于 fcitx/剪切板，不参与用户数据导出覆盖，保护用户配置）。
 * 功能 1 输入统计的地基；后续联想/纠错的常用词数据也可复用本库。
 */
@Database(
    entities = [
        InputDailyStats::class,
        FreqWord::class,
        InputSession::class,
        WordAssoc::class,
        PinyinCorrect::class
    ],
    version = 3,
    exportSchema = false
)
abstract class InputStatsDatabase : RoomDatabase() {
    abstract fun statsDao(): InputStatsDao

    companion object {
        const val DB_NAME = "meng_stats"

        /** 当前数据库 schema 版本（与 [Database] 注解的 version 保持一致）。 */
        const val CURRENT_VERSION = 3

        /** Keep corrupt stats files for recovery; never use the platform delete-on-corruption callback. */
        val PRESERVING_CORRUPTION_FACTORY: SupportSQLiteOpenHelper.Factory =
            PreservingCorruptionFactory(FrameworkSQLiteOpenHelperFactory())

        /** Additive migrations only; never destroy user learning data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS word_assoc (
                        `key` TEXT NOT NULL, `prev` TEXT NOT NULL, `next` TEXT NOT NULL,
                        `count` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS pinyin_correct (
                        `key` TEXT NOT NULL, `wrong` TEXT NOT NULL, `correct` TEXT NOT NULL,
                        `type` INTEGER NOT NULL, `count` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`)
                    )
                """.trimIndent())
            }
        }
    }

    private class PreservingCorruptionFactory(
        private val delegate: SupportSQLiteOpenHelper.Factory
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val original = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    original.onDowngrade(db, oldVersion, newVersion)
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)

                // Do not delegate: the default callback deletes the corrupt database files.
                override fun onCorruption(db: SupportSQLiteDatabase) = Unit
            }
            val rebuilt = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .build()
            return delegate.create(rebuilt)
        }
    }
}
