/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 剪切板分组（额外配置，仅用于快速寻找，不参与 fcitx 数据兼容）。
 *
 * 分组信息只保存在本地 Room 库中，不写入导出 JSON，因此不影响与小企鹅
 * （fcitx5-android）的导入/导出互操作。
 */
@Entity(tableName = ClipboardGroup.TABLE_NAME)
data class ClipboardGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    /** 展示排序（越小越靠前）。 */
    val sortOrder: Int = 0,
    /** 跨设备稳定的同步主键（局域网同步用，仅本机业务）。 */
    @ColumnInfo(defaultValue = "")
    val syncId: String = "",
    /** 分组最近一次变更时间（毫秒，同步合并「较新者胜」用）。 */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "")
    val deviceId: String = "",
) {
    companion object {
        const val TABLE_NAME = "clipboard_group"
    }
}