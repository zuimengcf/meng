/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 剪切板数据的导入/导出 —— 统一以 **zip** 为载体。
 *
 * ## 载体结构
 * 导出为一个 zip，内含两部分：
 * - `databases/clbdb` ：本机 Room 剪切板数据库文件本体（含全部条目 + 分组表 + 图片路径引用）。
 * - `metadata.json`   ：来源标记（packageName / versionName / exportTime），复用用户数据包名机制。
 *
 * ## 导入
 * 只读解析 zip 里的 `databases/clbdb`，读出条目与分组，再逐条合并写入本机库
 * （保留本机数据，已存在条目自动跳过）。
 *
 * 兼容性：小企鹅（fcitx5-android）用户数据备份的 `databases/clbdb` 同样可被本导入解析
 * （小企鹅旧库无 groupId/分组表时分组为空，仅导入条目）。
 */
object ClipboardExportManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private const val DB_PATH = "databases/clbdb"
    private const val META_FILE = "metadata.json"

    /** 来源标记（复用用户数据的包名机制） */
    @Serializable
    data class ClipboardMetadata(
        val packageName: String,
        val versionName: String,
        val exportTime: Long,
    )

    /**
     * 导出本机剪切板数据库文件为 zip。
     * [dbFile] 为 Room 生成的 `databases/clbdb` 文件路径。
     * @return 当前未删除的条目数。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun export(dbFile: File, packageName: String, versionName: String, dest: OutputStream): Int {
        ZipOutputStream(dest.buffered()).use { zip ->
            // 1. 数据库文件本体（含条目 + 分组表 + 图片路径引用）
            zip.putNextEntry(ZipEntry(DB_PATH))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            // 2. 来源标记
            zip.putNextEntry(ZipEntry(META_FILE))
            json.encodeToStream(
                ClipboardMetadata(packageName, versionName, System.currentTimeMillis()),
                zip
            )
            zip.closeEntry()
        }
        val count = countNonDeleted(dbFile)
        Timber.d("Exported clipboard db '$DB_PATH' ($count entries) as db-file zip")
        return count
    }

    /** 从 zip 只读解析 `databases/clbdb`，返回 (条目, 分组)。小企鹅旧库无 groupId/分组表时分组为空。 */
    @OptIn(ExperimentalSerializationApi::class)
    fun importFromDb(src: InputStream): Pair<List<ClipboardEntry>, List<ClipboardGroup>>? = runCatching {
        var dbBytes: ByteArray? = null
        ZipInputStream(src.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == DB_PATH) dbBytes = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val bytes = dbBytes ?: return@runCatching null
        val tmp = File.createTempFile("clbdb_import", ".db")
        try {
            tmp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                Pair(readEntries(db), readGroups(db))
            } finally {
                db.close()
            }
        } finally {
            tmp.delete()
        }
    }.getOrElse {
        Timber.w("Failed to parse clipboard db from zip (%s)", it.javaClass.simpleName)
        null
    }

    private fun readEntries(db: SQLiteDatabase): List<ClipboardEntry> {
        val list = ArrayList<ClipboardEntry>()
        db.rawQuery("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0", null).use { c ->
            val idCol = c.getColumnIndex("id")
            val textCol = c.getColumnIndex("text")
            val pinnedCol = c.getColumnIndex("pinned")
            val tsCol = c.getColumnIndex("timestamp")
            val typeCol = c.getColumnIndex("type")
            val sensitiveCol = c.getColumnIndex("sensitive")
            val imagePathCol = c.getColumnIndex("imagePath")
            val groupIdCol = c.getColumnIndex("groupId") // 小企鹅旧库可能无此列 -> -1
            while (c.moveToNext()) {
                list += ClipboardEntry(
                    id = c.getInt(idCol),
                    text = c.getString(textCol),
                    pinned = pinnedCol >= 0 && c.getInt(pinnedCol) != 0,
                    timestamp = c.getLong(tsCol),
                    type = if (typeCol >= 0) c.getString(typeCol) else "text/plain",
                    sensitive = sensitiveCol >= 0 && c.getInt(sensitiveCol) != 0,
                    imagePath = if (imagePathCol >= 0 && !c.isNull(imagePathCol)) c.getString(imagePathCol) else null,
                    groupId = if (groupIdCol >= 0) c.getInt(groupIdCol) else 0,
                )
            }
        }
        return list
    }

    private fun readGroups(db: SQLiteDatabase): List<ClipboardGroup> = runCatching {
        // 小企鹅旧库可能没有 clipboard_group 表（分组是梦新增），此时返回空列表，不阻断条目导入。
        db.rawQuery("SELECT * FROM ${ClipboardGroup.TABLE_NAME}", null).use { c ->
            val idCol = c.getColumnIndex("id")
            val nameCol = c.getColumnIndex("name")
            val sortCol = c.getColumnIndex("sortOrder")
            val out = ArrayList<ClipboardGroup>()
            while (c.moveToNext()) {
                out += ClipboardGroup(
                    id = c.getInt(idCol),
                    name = c.getString(nameCol),
                    sortOrder = if (sortCol >= 0) c.getInt(sortCol) else 0,
                )
            }
            out
        }
    }.getOrElse {
        Timber.w("clipboard_group table absent or unreadable, importing entries only (%s)", it.javaClass.simpleName)
        emptyList()
    }

    /** 统计某 db 文件中未删除的条目数（用于导出反馈）。 */
    fun countNonDeleted(dbFile: File): Int = runCatching {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        } finally {
            db.close()
        }
    }.getOrElse {
        Timber.w("Failed to count clipboard entries in db (%s)", it.javaClass.simpleName)
        0
    }
}
