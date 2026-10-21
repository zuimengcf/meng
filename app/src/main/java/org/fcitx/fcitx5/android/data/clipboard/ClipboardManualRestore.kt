/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.util.Base64
import android.util.Base64InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Explicit user restore never runs network retention, text deduplication or pre-import cleanup. */
internal object ClipboardManualRestore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun merge(imported: ClipboardExportManager.ImportedPacket): Int = ClipboardManager.withSyncLock {
        val imageDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "clipboard_images")
        val changed = mergeInto(ClipboardManager.syncDatabase(), imported.packet, imported.imageFiles, imageDir,
            appendHistory = ClipboardOplog::appendIncomingBatch, observeTime = { ClipboardManager.observeRemoteTime(it) })
        // Advance only after all rows and history are durable. No retention/GC is triggered here.
        ClipboardManager.mergeWatermarks(imported.packet.watermarks, durable = true)
        changed
    }

    /** Replace the visible collection with an authenticated full snapshot. Older/shared local rows
     * become normal tombstones, while provably local-only rows are physically removed by the same
     * deletion policy used by the UI. The destination fingerprint makes preview/confirm atomic. */
    suspend fun overwrite(packet: ClipboardSyncPacket, expectedFingerprint: String): Int =
        ClipboardDelivery.withLock {
            ClipboardManager.withSyncLock {
                ClipboardExportManager.validatePacket(packet)
                require(packet.deviceId.isNotBlank()) { "全量快照缺少来源设备标识" }
                require(ClipboardSnapshotStore.fingerprintUnlocked() == expectedFingerprint) {
                    "目标设备内容在预览后有变化，请重新预览再覆盖"
                }
                val database = ClipboardManager.syncDatabase()
                val incomingEntryIds = packet.entries.mapTo(HashSet()) { it.syncId }
                val incomingGroupIds = packet.groups.mapTo(HashSet()) { it.syncId }
                val groupIds = packet.groups.associateBy { it.syncId }
                require(packet.entries.all { row ->
                    row.groupSyncId.isBlank() || row.groupSyncId in groupIds
                }) { "全量快照引用了不存在或已删除的分组" }
                require(packet.entries.none { !it.deleted && it.type.startsWith("image/") && it.imageData.isNullOrBlank() }) {
                    "源设备有图片文件缺失；本次全量覆盖已取消，先在有原图的设备补齐图片再试"
                }

                val current = ClipboardSnapshotStore.metadataSnapshotUnlocked()
                val greatest = maxOf(
                    current.entries.maxOfOrNull { it.updatedAt } ?: 0L,
                    current.groups.maxOfOrNull { it.updatedAt } ?: 0L,
                    current.history.maxOfOrNull { it.ts } ?: 0L,
                    packet.entries.maxOfOrNull { it.updatedAt } ?: 0L,
                    packet.groups.maxOfOrNull { it.updatedAt } ?: 0L,
                    packet.history.maxOfOrNull { it.ts } ?: 0L,
                )
                require(greatest < Long.MAX_VALUE - 1L) { "同步版本时钟已达上限，不能全量覆盖" }
                val deletionTime = ClipboardManager.observeRemoteTime(greatest)
                val replacementTime = ClipboardManager.observeRemoteTime(deletionTime)
                val replaceTarget = suspend {
                    val entries = ClipboardManager.syncDao().getAllIncludingDeleted()
                    entries.filter { it.syncId !in incomingEntryIds && !it.deleted }
                        .forEach { ClipboardManager.deleteEntryUnlocked(it, deletionTime) }

                    ClipboardManager.syncGroupDao().getAllIncludingAll()
                        .filter { it.syncId !in incomingGroupIds && !it.deleted }.forEach { oldGroup ->
                        val stable = oldGroup.syncId.ifEmpty { ClipboardOplog.newSyncId() }
                        val deleted = oldGroup.copy(syncId = stable, deleted = true, updatedAt = deletionTime,
                            deviceId = ClipboardOplog.deviceId)
                        ClipboardManager.syncGroupDao().upsert(deleted)
                        ClipboardOplog.append(ClipboardOplog.OP_GROUP_DELETE, stable,
                            group = ClipboardOplog.GroupOp(oldGroup.name, oldGroup.sortOrder), ts = deletionTime)
                    }
                }
                val replacement = packet.copy(
                    entries = packet.entries.map { it.copy(updatedAt = replacementTime, deviceId = packet.deviceId) },
                    groups = packet.groups.map { it.copy(updatedAt = replacementTime, deviceId = packet.deviceId) }
                )
                val imageDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "clipboard_images")
                val changed = mergeInto(database, replacement, emptyMap(), imageDir, ClipboardOplog::appendIncomingBatch,
                    observeTime = { ClipboardManager.observeRemoteTime(it) }, markShared = true,
                    beforeMerge = replaceTarget)
                ClipboardManager.mergeWatermarks(packet.watermarks, durable = true)
                ClipboardManager.clearLastEntryAndRefreshCount()
                changed
            }
        }

    /** Injectable storage boundary also used by isolated Room instrumentation tests. */
    internal suspend fun mergeInto(database: ClipboardDatabase, packet: ClipboardSyncPacket,
        imageFiles: Map<String, File>, imageDirectory: File, appendHistory: (List<ClipboardOplog.Op>) -> Unit,
        observeTime: (Long) -> Unit = {},
        retainCollection: () -> Unit = ClipboardManager::enableSyncRetention,
        markShared: Boolean = false, beforeMerge: suspend () -> Unit = {}): Int {
        ClipboardExportManager.validatePacket(packet)
        // A durable, fail-closed setting must precede every row/image write. It deliberately
        // remains enabled after a failed restore: retaining extra data is safer than trimming.
        retainCollection()
        val materialized = HashMap<String, String>()
        fun image(key: String, embedded: String?, type: String): String? = materialized[key] ?: run {
            val source = imageFiles[key]
            val input = when {
                source != null -> source.inputStream()
                !embedded.isNullOrBlank() -> Base64InputStream(embedded.byteInputStream(), Base64.DEFAULT)
                else -> null
            }
            input?.let { ClipboardBackupImages.store(it, imageDirectory, type) }?.also { materialized[key] = it }
        }
        var changed = 0
        ClipboardOplog.withTransaction(database) {
            val gd = database.clipboardGroupDao(); val ed = database.clipboardDao()
            beforeMerge()
            val journal = ArrayList<ClipboardOplog.Op>()
            packet.history.forEach { h ->
                val payload = h.entry?.let { p -> p.copy(imagePath = image("h:${h.id}", p.imageData, p.type), imageData = null) }
                journal += ClipboardOplog.Op(h.id.substringAfterLast(':').toLong(), h.deviceId, h.opType, h.syncId, h.ts, payload, h.group)
            }
            val maxTime = maxOf(packet.entries.maxOfOrNull { it.updatedAt } ?: 0L,
                packet.groups.maxOfOrNull { it.updatedAt } ?: 0L, packet.history.maxOfOrNull { it.ts } ?: 0L)
            if (maxTime > 0) observeTime(maxTime)
            packet.groups.sortedBy { it.syncId }.forEach { incoming ->
                val local = gd.getBySyncId(incoming.syncId)
                journal += groupHistory(incoming)
                local?.let { journal += groupHistory(groupWire(it)) }
                if (local == null || compare(incoming.updatedAt, incoming.deviceId, json.encodeToString(incoming),
                        local.updatedAt, local.deviceId, json.encodeToString(groupWire(local))) > 0) {
                    gd.upsert(ClipboardGroup(id = local?.id ?: 0, name = incoming.name, sortOrder = incoming.sortOrder,
                        syncId = incoming.syncId, updatedAt = incoming.updatedAt, deleted = incoming.deleted, deviceId = incoming.deviceId))
                    changed++
                }
            }
            packet.entries.sortedBy { it.syncId }.forEach { incoming ->
                val local = ed.getBySyncId(incoming.syncId)
                val localGroup = local?.let { gd.get(it.groupId) }
                val localWire = local?.let { entryWire(it, localGroup) }
                val incomingImage = if (incoming.type.startsWith("image/")) image("e:${incoming.syncId}", incoming.imageData, incoming.type) else null
                journal += entryHistory(incoming, incomingImage)
                if (local != null && localWire != null) journal += entryHistory(localWire, local.imagePath)
                val comparison = if (localWire == null) 1 else compare(incoming.updatedAt, incoming.deviceId, semantic(incoming),
                    localWire.updatedAt, localWire.deviceId, semantic(localWire))
                val localImage = local?.imagePath?.takeIf {
                    val file = File(it).canonicalFile
                    file.isFile && (file.path.startsWith(imageDirectory.canonicalPath + File.separator) ||
                        ClipboardPurge.isPrivateClipboardImage(it))
                }
                if (comparison <= 0) {
                    // Repair even a deleted row's missing image, without changing its version or resurrecting it.
                    if (comparison == 0 && localImage == null && incomingImage != null) {
                        ed.updateImagePath(local!!.id, incomingImage); changed++
                    }
                    return@forEach
                }
                val path = if (incoming.deleted) localImage else incomingImage ?: localImage
                require(incoming.deleted || !incoming.type.startsWith("image/") || path != null) {
                    "备份未包含图片原文件，无法无损恢复；请在原设备重新导出剪贴板备份"
                }
                val group = incoming.groupSyncId.takeIf { it.isNotBlank() }?.let { gd.getBySyncId(it) }
                val retainLocalPayload = markShared && incoming.deleted
                ed.upsert(ClipboardEntry(id = local?.id ?: 0,
                    text = if (retainLocalPayload) local?.text.orEmpty() else incoming.text,
                    pinned = if (retainLocalPayload) local?.pinned == true else incoming.pinned,
                    timestamp = incoming.timestamp, type = incoming.type, sensitive = incoming.sensitive,
                    deleted = incoming.deleted, imagePath = path, groupId = group?.takeUnless { it.deleted }?.id ?: 0,
                    groupSyncId = incoming.groupSyncId, syncId = incoming.syncId, updatedAt = incoming.updatedAt,
                    sortOrder = incoming.sortOrder, lastModifiedBy = incoming.deviceId,
                    hasBeenSynced = markShared || local?.hasBeenSynced == true,
                    syncExposurePending = if (markShared) false else local?.syncExposurePending == true))
                changed++
            }
            val groups = gd.getAllIncludingAll().associateBy { it.syncId }
            ed.getAllIncludingDeleted().forEach { row ->
                val resolved = groups[row.groupSyncId]?.takeUnless { it.deleted }?.id ?: 0
                if (row.groupId != resolved) ed.setGroup(row.id, resolved, row.groupSyncId)
            }
            appendHistory(journal)
        }
        return changed
    }

    private fun groupWire(g: ClipboardGroup) = SyncGroup(g.syncId, g.name, g.sortOrder, g.updatedAt, g.deleted, g.deviceId)
    private fun entryWire(e: ClipboardEntry, g: ClipboardGroup?) = SyncEntry(syncId = e.syncId, text = e.text,
        pinned = e.pinned, timestamp = e.timestamp, type = e.type, sensitive = e.sensitive, deleted = e.deleted,
        groupName = g?.name.orEmpty(), updatedAt = e.updatedAt, deviceId = e.lastModifiedBy,
        groupSyncId = e.groupSyncId.ifBlank { g?.syncId.orEmpty() }, sortOrder = e.sortOrder)
    private fun entryHistory(e: SyncEntry, path: String?) = ClipboardOplog.archiveOp(e.syncId, e.updatedAt, e.deviceId,
        entry = ClipboardOplog.EntryOp(text = e.text, pinned = e.pinned, timestamp = e.timestamp, type = e.type,
            sensitive = e.sensitive, deleted = e.deleted, groupName = e.groupName, imagePath = path,
            groupSyncId = e.groupSyncId, sortOrder = e.sortOrder))
    private fun groupHistory(g: SyncGroup) = ClipboardOplog.archiveOp(g.syncId, g.updatedAt, g.deviceId,
        group = ClipboardOplog.GroupOp(g.name, g.sortOrder))
    private fun semantic(e: SyncEntry) = json.encodeToString(e.copy(imageData = null, groupName = ""))
    private fun compare(at: Long, ad: String, av: String, bt: Long, bd: String, bv: String) =
        ClipboardSyncRules.compareVersion(at, ad, av, bt, bd, bv)
}

/** Content-addressed, streaming and atomically published; never trusts an incoming path. */
internal object ClipboardBackupImages {
    fun store(input: InputStream, directory: File, type: String): String = input.use { storeOpen(it, directory, type) }

    private fun storeOpen(input: InputStream, directory: File, type: String): String {
        require(directory.isDirectory || directory.mkdirs()) { "无法创建剪贴板图片目录" }
        val temporary = File.createTempFile("restore-", ".tmp", directory)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            input.use { source -> FileOutputStream(temporary).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = source.read(buffer); if (n < 0) break; out.write(buffer, 0, n); md.update(buffer, 0, n); size += n }
                out.fd.sync()
            } }
            require(size > 0) { "备份图片为空" }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            val ext = when { type.contains("png") -> "png"; type.contains("gif") -> "gif"; type.contains("webp") -> "webp";
                type.contains("jpeg") || type.contains("jpg") -> "jpg"; else -> "bin" }
            val target = File(directory, "$hash.$ext")
            val existingDigest = if (target.isFile) target.inputStream().use { source ->
                val existing = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { val n = source.read(buffer); if (n < 0) break; existing.update(buffer, 0, n) }
                existing.digest().joinToString("") { "%02x".format(it) }
            } else null
            if (existingDigest != hash) check(temporary.renameTo(target)) { "图片恢复写入失败" }
            return target.absolutePath
        } finally { temporary.delete() }
    }
}
