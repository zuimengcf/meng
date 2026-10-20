/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.utils.timestamp

@Entity(tableName = ClipboardEntry.TABLE_NAME)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = ClipDescription.MIMETYPE_TEXT_PLAIN)
    val type: String = ClipDescription.MIMETYPE_TEXT_PLAIN,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false,
    /** 图片条目的内部路径（type 以 image/ 开头时有值），null 表示纯文本 */
    val imagePath: String? = null
    ,
    /** 所属分组 id（额外配置，仅用于快速寻找；null/0 表示未分组，行为与原版一致） */
    @ColumnInfo(defaultValue = "0")
    val groupId: Int = 0,
    @ColumnInfo(defaultValue = "")
    val groupSyncId: String = "",
    /** 跨设备稳定的同步主键（局域网同步用，仅本机业务；不影响小企鹅兼容）。 */
    @ColumnInfo(defaultValue = "")
    val syncId: String = "",
    /** 数据最近一次变更时间（毫秒，同步合并「较新者胜」用）。 */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
    /** 用户手动排序位置（越大越靠前；0=未手动排，按时间倒序）。 */
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Long = 0,
    /** 最后修改该条目的设备 id（多设备同步区分用；空表示本机/未知）。 */
    @ColumnInfo(defaultValue = "")
    val lastModifiedBy: String = "",
    /** Unknown/legacy/imported rows default to shared; only freshly captured rows opt out. */
    @ColumnInfo(defaultValue = "1")
    val hasBeenSynced: Boolean = true,
    /** Bytes may have reached a peer, but no durable authenticated receipt has arrived. */
    @ColumnInfo(defaultValue = "0")
    val syncExposurePending: Boolean = false,
) {
    companion object {
        const val BULLET = "•"

        const val TABLE_NAME = "clipboard"

        private val IS_SENSITIVE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }

        fun fromClipData(
            clipData: ClipData,
            transformer: ((String) -> String)? = null
        ): ClipboardEntry? {
            val desc = clipData.description
            // TODO: handle multiple items (when does this happen?)
            val item = clipData.getItemAt(0) ?: return null
            val str = item.text?.toString() ?: return null
            // 若 MIME 为图片但 item.text 非空（浏览器复制图片带 URL），不应作为文本条目存入；
            // 图片采集由 captureImageEntry 负责，此处直接返回 null 避免存成链接。
            if (desc.hasMimeType("image/*") || (0 until desc.mimeTypeCount).any {
                    desc.getMimeType(it)?.startsWith("image/") == true
                }) return null
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                desc.extras?.getBoolean(IS_SENSITIVE) ?: false
            } else {
                false
            }
            return ClipboardEntry(
                text = if (transformer != null) transformer(str) else str,
                timestamp = clipData.timestamp(),
                type = desc.getMimeType(0),
                sensitive = sensitive
            )
        }
    }
}