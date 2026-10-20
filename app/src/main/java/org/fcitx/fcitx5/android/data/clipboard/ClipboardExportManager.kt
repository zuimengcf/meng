/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.validateZipEntryName
import org.fcitx.fcitx5.android.utils.writeBackupEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Manual backup is separate from network retention: images are streamed ZIP members. */
object ClipboardExportManager {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private const val SNAPSHOT = "clipboard-snapshot.json"
    private const val DB_PATH = "databases/clbdb"
    private const val META_FILE = "metadata.json"
    private const val FORMAT = 3
    private val knownPackages = setOf("com.meng.ime", "com.meng.ime.debug",
        "org.fcitx.fcitx5.android", "org.fcitx.fcitx5.android.debug")

    @Serializable data class ClipboardMetadata(val packageName: String, val versionName: String,
        val exportTime: Long, val format: Int = FORMAT)
    @Serializable data class Archive(val metadata: ClipboardMetadata, val packet: ClipboardSyncPacket,
        val imageRefs: Map<String, String> = emptyMap())
    data class ImportedPacket(val packet: ClipboardSyncPacket, val imageFiles: Map<String, File>,
        val temporaryDir: File) : AutoCloseable {
        override fun close() { temporaryDir.deleteRecursively() }
    }

    /** The provider is opened once per key, consumed and closed before the next image. */
    @OptIn(ExperimentalSerializationApi::class)
    fun export(packet: ClipboardSyncPacket, packageName: String, versionName: String, dest: OutputStream,
        imageProvider: (String) -> InputStream? = { null }): Int {
        validatePacket(packet)
        val metadata = ClipboardMetadata(packageName, versionName, System.currentTimeMillis())
        val refs = linkedMapOf<String, String>()
        ZipOutputStream(dest.buffered()).use { zip ->
            val keys = packet.entries.filter { it.type.startsWith("image/") }.map { "e:${it.syncId}" } +
                packet.history.filter { it.entry?.type?.startsWith("image/") == true }.map { "h:${it.id}" }
            keys.distinct().forEach { key ->
                imageProvider(key)?.let { input ->
                    val name = "images/${digest(key.byteInputStream())}.bin"
                    zip.writeBackupEntry(name, input)
                    refs[key] = name
                }
            }
            zip.putNextEntry(java.util.zip.ZipEntry(SNAPSHOT))
            json.encodeToStream(Archive.serializer(), Archive(metadata, packet, refs), zip)
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(META_FILE))
            json.encodeToStream(ClipboardMetadata.serializer(), metadata, zip)
            zip.closeEntry()
        }
        return packet.entries.count { !it.deleted }
    }

    /** Metadata-only compatibility helper. Image files require [importArchive] and its lifetime. */
    fun importPacket(src: InputStream): ClipboardSyncPacket = importArchive(src).use { it.packet }

    @OptIn(ExperimentalSerializationApi::class)
    fun importArchive(src: InputStream): ImportedPacket {
        val root = File.createTempFile("clipboard-import-", ".stage")
        check(root.delete() && root.mkdir()) { "无法创建剪贴板导入临时目录" }
        try {
            // Shared extractor rejects traversal, drive paths, NULs and duplicate members; streams to disk.
            ZipInputStream(src.buffered()).use { it.extract(root) }
            val snapshot = File(root, SNAPSHOT)
            if (snapshot.isFile) {
                val archive = try { snapshot.inputStream().use { json.decodeFromStream<Archive>(it) } }
                    catch (e: Exception) { throw IllegalArgumentException("剪贴板备份快照损坏或格式不兼容", e) }
                require(archive.metadata.format == FORMAT) { "剪贴板备份版本不兼容：${archive.metadata.format}" }
                validatePackage(archive.metadata.packageName)
                validatePacket(archive.packet)
                val refs = archive.imageRefs.mapValues { (key, name) ->
                    require(key in imageKeys(archive.packet)) { "备份图片引用没有对应记录：$key" }
                    val safe = validateZipEntryName(name)
                    require(safe.startsWith("images/")) { "备份图片引用不在 images 目录" }
                    File(root, safe).also { require(it.isFile) { "备份缺少声明的图片文件：$name" } }
                }
                return ImportedPacket(archive.packet, refs, root)
            }
            val dbFile = File(root, DB_PATH)
            require(dbFile.isFile) { "不是可识别的剪贴板备份；完整设置备份请使用“导入用户数据”，剪贴板备份请使用“导入剪切板”" }
            val metadataFile = File(root, META_FILE)
            if (metadataFile.isFile) {
                val metadata = json.parseToJsonElement(metadataFile.readText()).jsonObject
                metadata["packageName"]?.jsonPrimitive?.content?.let(::validatePackage)
            }
            return readLegacy(root, dbFile)
        } catch (t: Throwable) { root.deleteRecursively(); throw t }
    }

    private fun validatePackage(source: String) {
        require(source in knownPackages) { "备份来源标识不兼容：$source。支持梦输入法及小企鹅兼容备份；请检查文件与导入入口" }
    }

    internal fun validatePacket(packet: ClipboardSyncPacket) {
        require(packet.protocolVersion == 2) { "剪贴板快照协议不兼容" }
        require(packet.entries.all { it.syncId.isNotBlank() } && packet.groups.all { it.syncId.isNotBlank() }) { "备份缺少同步身份" }
        require(packet.entries.map { it.syncId }.distinct().size == packet.entries.size &&
            packet.groups.map { it.syncId }.distinct().size == packet.groups.size) { "备份含有重复同步身份" }
        require(packet.history.map { it.id }.distinct().size == packet.history.size) { "备份含有重复历史版本" }
        packet.history.forEach { h ->
            val seq = h.id.substringAfterLast(':').toLongOrNull()
            require(seq != null && seq >= 0 && h.id == "${h.deviceId}:$seq" && h.syncId.isNotBlank() &&
                ((h.entry == null) != (h.group == null))) { "备份含有无效历史版本" }
        }
    }
    private fun imageKeys(packet: ClipboardSyncPacket): Set<String> =
        (packet.entries.map { "e:${it.syncId}" } + packet.history.map { "h:${it.id}" }).toSet()

    private fun readLegacy(root: File, dbFile: File): ImportedPacket {
        val imageRefs = linkedMapOf<String, File>()
        val packet = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                require(c.moveToFirst() && c.getInt(0) == 0) { "旧备份 WAL 无法完整恢复" }
            }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                require(c.moveToFirst() && c.getString(0) == "ok") { "剪贴板备份数据库校验失败" }
            }
            val namespace = digest(dbFile.inputStream()) // Include recovered WAL state in legacy identity.
            val groups = readGroups(db).map { g -> g.copy(syncId = g.syncId.ifBlank { "legacy:$namespace:group:${g.id}" }) }
            val byId = groups.associateBy { it.id }
            val entries = readEntries(db).map { row ->
                val stable = row.syncId.ifBlank { "legacy:$namespace:entry:${row.id}" }
                row.imagePath?.takeIf { row.type.startsWith("image/") }?.let { path ->
                    findLegacyImage(root, path)?.let { imageRefs["e:$stable"] = it }
                }
                val group = byId[row.groupId]
                SyncEntry(syncId = stable, text = row.text, pinned = row.pinned, timestamp = row.timestamp,
                    type = row.type, sensitive = row.sensitive, deleted = row.deleted,
                    groupName = group?.name.orEmpty(), updatedAt = row.updatedAt.takeIf { it > 0 } ?: row.timestamp,
                    deviceId = row.lastModifiedBy, groupSyncId = row.groupSyncId.ifBlank { group?.syncId.orEmpty() },
                    sortOrder = row.sortOrder)
            }
            val historyFile = File(root, "clipboard_oplog")
            val history = if (historyFile.isFile) historyFile.useLines { lines -> lines.filter { it.isNotBlank() }.map { line ->
                val op = ClipboardOplog.parse(line) ?: error("备份中的剪贴板操作日志损坏")
                val id = ClipboardOplog.revisionId(op)
                op.entry?.imagePath?.let { path -> findLegacyImage(root, path)?.let { imageRefs["h:$id"] = it } }
                SyncRevision(id, op.syncId, op.ts, op.deviceId, op.opType, op.entry?.copy(imagePath = null), op.group)
            }.toList() } else emptyList()
            ClipboardSyncPacket(entries = entries, groups = groups.map { SyncGroup(it.syncId, it.name, it.sortOrder, it.updatedAt, it.deleted, it.deviceId) }, history = history)
        }
        validatePacket(packet)
        return ImportedPacket(packet, imageRefs, root)
    }

    private fun findLegacyImage(root: File, oldPath: String): File? {
        // Never open an absolute path from the archive on the receiving device.
        val name = oldPath.replace('\\', '/').substringAfterLast('/')
        require(name.isNotBlank() && name !in setOf(".", "..")) { "旧备份图片路径无效" }
        val candidates = listOf("external/clipboard_images", "clipboard_images", "files/clipboard_images")
            .map { File(root, "$it/$name") }.filter { it.isFile }
        if (candidates.size > 1) require(candidates.map { digest(it.inputStream()) }.distinct().size == 1) { "旧备份存在同名但内容不同的图片：$name" }
        return candidates.firstOrNull()
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
    private fun readGroups(db: SQLiteDatabase): List<ClipboardGroup> {
        if (!hasTable(db, ClipboardGroup.TABLE_NAME)) return emptyList()
        return db.rawQuery("SELECT * FROM ${ClipboardGroup.TABLE_NAME}", null).use { c ->
            val out = ArrayList<ClipboardGroup>()
            fun str(n: String, default: String = ""): String { val i = c.getColumnIndex(n); return if (i < 0 || c.isNull(i)) default else c.getString(i) }
            fun num(n: String): Long { val i = c.getColumnIndex(n); return if (i < 0) 0 else c.getLong(i) }
            while (c.moveToNext()) out += ClipboardGroup(id = num("id").toInt(), name = str("name"),
                sortOrder = num("sortOrder").toInt(), syncId = str("syncId"), updatedAt = num("updatedAt"),
                deleted = num("deleted") != 0L, deviceId = str("deviceId"))
            out
        }
    }
    private fun readEntries(db: SQLiteDatabase): List<ClipboardEntry> = db.rawQuery("SELECT * FROM ${ClipboardEntry.TABLE_NAME}", null).use { c ->
        require(c.getColumnIndex("id") >= 0 && c.getColumnIndex("text") >= 0 && c.getColumnIndex("timestamp") >= 0) { "备份数据库不含兼容的剪贴板表结构" }
        val out = ArrayList<ClipboardEntry>()
        fun str(n: String, default: String = ""): String { val i = c.getColumnIndex(n); return if (i < 0 || c.isNull(i)) default else c.getString(i) }
        fun num(n: String): Long { val i = c.getColumnIndex(n); return if (i < 0) 0 else c.getLong(i) }
        while (c.moveToNext()) out += ClipboardEntry(id = num("id").toInt(), text = str("text"), pinned = num("pinned") != 0L,
            timestamp = num("timestamp"), type = str("type", "text/plain"), deleted = num("deleted") != 0L,
            sensitive = num("sensitive") != 0L, imagePath = str("imagePath").ifBlank { null }, groupId = num("groupId").toInt(),
            groupSyncId = str("groupSyncId"), syncId = str("syncId"), updatedAt = num("updatedAt"), sortOrder = num("sortOrder"), lastModifiedBy = str("lastModifiedBy"))
        out
    }
    private fun digest(input: InputStream): String = input.use {
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = it.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
