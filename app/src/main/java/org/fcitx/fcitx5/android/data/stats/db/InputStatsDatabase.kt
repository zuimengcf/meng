/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.stats.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
    }
}
