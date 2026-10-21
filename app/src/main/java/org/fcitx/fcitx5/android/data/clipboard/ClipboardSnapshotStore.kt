/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import java.io.File
import java.security.MessageDigest

/** 全量持久快照及历史。所有读写与本地操作共用同一锁和 Room 事务。 */
object ClipboardSnapshotStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    data class Capture(val packet: ClipboardSyncPacket, val fingerprint: String)

    suspend fun snapshot(): ClipboardSyncPacket = capture().packet
    suspend fun capture(): Capture = ClipboardManager.withSyncLock {
        ClipboardManager.enableSyncRetention()
        // Redact before constructing either entries or history, so expired plaintext never leaves the device.
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        ClipboardOplog.withTransaction(ClipboardManager.syncDatabase()) {
            val groups = ClipboardManager.syncGroupDao().getAllIncludingAll()
            val entries = ClipboardManager.syncDao().getAllIncludingDeleted()
            val byId = groups.associateBy { it.id }
            val outgoing = entries.map { e -> toSync(e, byId[e.groupId]) }
            ClipboardOplog.appendIncomingBatch(outgoing.map(::archiveOp) + groups.map { archiveOp(toSync(it)) })
            val history = ClipboardOplog.readAll().map { op ->
                val payload = op.entry?.let { p ->
                    // Old on-device journal rows reference private image files, never remote absolute paths.
                    p.copy(imagePath = null, imageData = p.imageData ?: p.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }?.let(::imageFileToBase64))
                }
                SyncRevision(ClipboardOplog.revisionId(op), op.syncId, op.ts, op.deviceId, op.opType, payload, op.group)
            }
            val packet = ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId,
                entries = outgoing, groups = groups.map(::toSync), history = history)
            Capture(packet, fingerprintUnlocked())
        }
    }

    suspend fun fingerprint(): String = ClipboardManager.withSyncLock { fingerprintUnlocked() }
    internal suspend fun fingerprintUnlocked(): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); md.update(bytes)
        }
        ClipboardManager.syncGroupDao().getAllIncludingAll().sortedBy { it.syncId }.forEach { add(it.toString()) }
        ClipboardManager.syncDao().getAllIncludingDeleted().sortedBy { it.syncId }.forEach { e ->
            add(e.copy(hasBeenSynced = true, syncExposurePending = false).toString()); val f = e.imagePath?.let(::File)
            add("${f?.isFile}:${f?.length()}:${f?.lastModified()}")
        }
        ClipboardOplog.readAll().sortedBy { ClipboardOplog.revisionId(it) }.forEach { add(ClipboardOplog.revisionId(it)) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun missingImageIds(): List<String> = ClipboardManager.withSyncLock {
        ClipboardManager.syncDao().getAllIncludingDeleted().filter {
            !it.deleted && it.type.startsWith("image/") && it.imagePath?.let { p -> File(p).isFile } != true
        }.map { it.syncId }
    }

    /** For change reporting only: no image encoding, history traversal, or archive writes. */
    suspend fun metadataSnapshot(): ClipboardSyncPacket = ClipboardManager.withSyncLock {
        metadataSnapshotUnlocked()
    }

    suspend fun metadataCapture(): Capture = ClipboardManager.withSyncLock {
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        val packet = metadataSnapshotUnlocked()
        Capture(packet, fingerprintUnlocked())
    }

    internal suspend fun metadataSnapshotUnlocked(): ClipboardSyncPacket =
        ClipboardOplog.withTransaction(ClipboardManager.syncDatabase()) {
            val groups = ClipboardManager.syncGroupDao().getAllIncludingAll()
            val byId = groups.associateBy { it.id }
            ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId, groups = groups.map(::toSync),
                entries = ClipboardManager.syncDao().getAllIncludingDeleted().map { toSync(it, byId[it.groupId], false) })
        }

    private fun toSync(e: ClipboardEntry, group: ClipboardGroup?, includeImage: Boolean = true): SyncEntry {
        val image = if (includeImage && !e.deleted && e.type.startsWith("image/") && e.imagePath?.let { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile } == true)
            imageFileToBase64(e.imagePath) ?: error("图片读取失败，未确认同步完成") else null
        return SyncEntry(syncId = e.syncId, text = e.text, pinned = e.pinned, timestamp = e.timestamp,
            type = e.type, sensitive = e.sensitive, deleted = e.deleted,
            updatedAt = e.updatedAt, deviceId = e.lastModifiedBy.ifBlank { ClipboardOplog.deviceId },
            imageData = image, groupSyncId = e.groupSyncId.ifBlank { group?.syncId.orEmpty() }, sortOrder = e.sortOrder,
            groupName = group?.name.orEmpty())
    }
    private fun toSync(g: ClipboardGroup) = SyncGroup(g.syncId, g.name, g.sortOrder, g.updatedAt, g.deleted, g.deviceId)
    private fun archiveOp(e: SyncEntry) = ClipboardOplog.archiveOp(e.syncId, e.updatedAt, e.deviceId,
        entry = ClipboardOplog.EntryOp(text = e.text, pinned = e.pinned, timestamp = e.timestamp,
            type = e.type, sensitive = e.sensitive, deleted = e.deleted, groupName = e.groupName,
            groupSyncId = e.groupSyncId, sortOrder = e.sortOrder, imageData = e.imageData))
    private fun archiveOp(g: SyncGroup) = ClipboardOplog.archiveOp(g.syncId, g.updatedAt, g.deviceId,
        group = ClipboardOplog.GroupOp(g.name, g.sortOrder))

    suspend fun merge(packet: ClipboardSyncPacket): Int = ClipboardManager.withSyncLock {
        mergeUnlocked(packet)
    }

    suspend fun mergeWithStats(packet: ClipboardSyncPacket): Pair<Int, ClipboardSyncStats> = ClipboardManager.withSyncLock {
        val before = metadataSnapshotUnlocked()
        val applied = mergeUnlocked(packet)
        applied to ClipboardSyncSummary.summarize(before, packet, metadataSnapshotUnlocked())
    }

    private suspend fun mergeUnlocked(packet: ClipboardSyncPacket): Int {
        // Run before every sync merge.  Keep only metadata tombstones for expired rows;
        // an old remote inventory/history must not resurrect their cleared plaintext.
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        require(packet.protocolVersion == 2) { "同步协议不兼容，请升级双方应用" }
        require(packet.entries.all { it.syncId.isNotBlank() } && packet.groups.all { it.syncId.isNotBlank() }) { "同步数据缺少稳定标识" }
        require(packet.entries.map { it.syncId }.distinct().size == packet.entries.size && packet.groups.map { it.syncId }.distinct().size == packet.groups.size) { "同步包包含重复标识" }
        var changed = 0
        ClipboardOplog.withTransaction(ClipboardManager.syncDatabase()) {
            val gd = ClipboardManager.syncGroupDao(); val ed = ClipboardManager.syncDao()
            // Every local tombstone is a redaction barrier, not only rows past retention.
            // Also include tombstones in this packet before filtering its history, so an
            // old revision cannot ride alongside a freshly received delete.
            val tombstones = mutableMapOf<String, Long>()
            ed.getAllIncludingDeleted().filter { it.deleted && it.syncId.isNotBlank() }
                .forEach { tombstones[it.syncId] = maxOf(tombstones[it.syncId] ?: 0L, it.updatedAt) }
            ClipboardOplog.readAll().filter { it.entry?.deleted == true && it.syncId.isNotBlank() }
                .forEach { tombstones[it.syncId] = maxOf(tombstones[it.syncId] ?: 0L, it.ts) }
            packet.entries.filter { it.deleted }.forEach {
                tombstones[it.syncId] = maxOf(tombstones[it.syncId] ?: 0L, it.updatedAt)
            }
            packet.history.filter { it.entry?.deleted == true }.forEach {
                tombstones[it.syncId] = maxOf(tombstones[it.syncId] ?: 0L, it.ts)
            }
            val remoteIds = (packet.entries.map { it.syncId } + packet.history.filter { it.entry != null }.map { it.syncId }).toSet()
            val historicalImagePaths = ClipboardOplog.readAll().groupBy { it.syncId }
                .mapValues { (_, ops) -> ops.mapNotNull { it.entry?.imagePath }.toSet() }
            // One durable clock advance per packet, not one blocking preference commit per row.
            val remoteMax = maxOf(packet.entries.maxOfOrNull { it.updatedAt } ?: 0L,
                packet.groups.maxOfOrNull { it.updatedAt } ?: 0L, packet.history.maxOfOrNull { it.ts } ?: 0L)
            if (remoteMax > 0L) ClipboardManager.observeRemoteTime(remoteMax)
            // Capture both winners; the journal recovery batch commits with the visible rows.
            // File publication follows the Room commit and is retried before the next sync.
            val journal = mutableListOf<ClipboardOplog.Op>()
            packet.history.forEach { h ->
                val deleteTs = tombstones[h.syncId]
                val entry = h.entry?.let { p ->
                    if (deleteTs != null && h.ts <= deleteTs) p.copy(text = "", pinned = false, imagePath = null, imageData = null) else p
                }
                val seq = h.id.substringAfterLast(':').toLongOrNull() ?: error("无效历史版本标识")
                require(seq >= 0 && h.id == "${h.deviceId}:$seq") { "历史版本标识不匹配" }
                journal += ClipboardOplog.Op(seq, h.deviceId, h.opType, h.syncId, h.ts,
                    entry?.copy(imagePath = null), h.group)
            }
            packet.groups.sortedBy { it.syncId }.forEach { r ->
                val local = gd.getBySyncId(r.syncId)
                journal += archiveOp(r); local?.let { journal += archiveOp(toSync(it)) }
                val decision = local == null || compare(r.updatedAt, r.deviceId, json.encodeToString(r),
                    local.updatedAt, local.deviceId, json.encodeToString(toSync(local))) > 0
                if (decision) {
                    gd.upsert(ClipboardGroup(id = local?.id ?: 0, name = r.name, sortOrder = r.sortOrder,
                        syncId = r.syncId, updatedAt = r.updatedAt, deleted = r.deleted, deviceId = r.deviceId))
                    changed++
                }
            }
            packet.entries.sortedBy { it.syncId }.forEach { r ->
                val deleteTs = tombstones[r.syncId]
                if (!r.deleted && deleteTs != null && r.updatedAt <= deleteTs) return@forEach
                val local = ed.getBySyncId(r.syncId)
                val localGroup = local?.let { gd.get(it.groupId) }
                val localWire = local?.let { toSync(it, localGroup) }
                val journalR = if (r.deleted) r.copy(text = "", pinned = false, imageData = null) else r
                journal += archiveOp(journalR); localWire?.let { journal += archiveOp(it) }
                val cmp = if (localWire == null) 1 else compare(r.updatedAt, r.deviceId, semantic(r),
                    localWire.updatedAt, localWire.deviceId, semantic(localWire))
                val repair = local != null && ClipboardSyncRules.canRepairMissingImage(cmp, r.deleted,
                    r.type.startsWith("image/"), local.imagePath?.let { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile } == true, !r.imageData.isNullOrBlank())
                if (cmp <= 0) {
                    if (repair) {
                        val path = imageBase64ToFile(r.imageData, r.type) ?: error("图片补传写入失败")
                        ed.updateImagePath(local!!.id, path); changed++
                    }
                    return@forEach
                }
                val group = r.groupSyncId.takeIf { it.isNotBlank() }?.let { gd.getBySyncId(it) }
                // Peer ordinary deletes retain this device's payload for seven-day recovery.
                // Deleted bytes are never imported from the wire; force-delete is local-only.
                val path = if (r.deleted) local?.imagePath?.takeIf {
                    ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile
                } else if (r.type.startsWith("image/")) {
                    if (!r.imageData.isNullOrBlank()) imageBase64ToFile(r.imageData, r.type) ?: error("图片写入失败，未确认同步完成")
                    else local?.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }
                } else local?.imagePath // Keep source for explicit undo/history; never send deleted image bytes.
                ed.upsert(ClipboardEntry(id = local?.id ?: 0, text = if (r.deleted) local?.text.orEmpty() else r.text,
                    pinned = if (r.deleted) local?.pinned == true else r.pinned, timestamp = r.timestamp,
                    type = r.type, deleted = r.deleted, sensitive = r.sensitive, imagePath = path,
                    groupId = group?.takeUnless { it.deleted }?.id ?: 0, groupSyncId = r.groupSyncId,
                    syncId = r.syncId, updatedAt = r.updatedAt, sortOrder = r.sortOrder, lastModifiedBy = r.deviceId,
                    hasBeenSynced = r.syncId in remoteIds))
                changed++
            }
            // A received historical revision proves the identity exists on the peer too,
            // even if the delta did not repeat its current row or our newer row won.
            remoteIds.forEach { ed.markDelivered(it) }
            // Visible group linkage is derived from stable identity. A tombstone cannot hide entries forever.
            val groupsBySyncId = gd.getAllIncludingAll().associateBy { it.syncId }
            ed.getAllIncludingDeleted().forEach { entry ->
                val resolved = groupsBySyncId[entry.groupSyncId]?.takeUnless { it.deleted }?.id ?: 0
                if (entry.groupId != resolved) ed.setGroup(entry.id, resolved, entry.groupSyncId)
            }
            ClipboardOplog.appendIncomingBatch(journal)
        }
        return changed
    }
    private fun semantic(e: SyncEntry): String = json.encodeToString(e.copy(imageData = null, groupName = ""))
    private fun compare(at: Long, ad: String, av: String, bt: Long, bd: String, bv: String): Int =
        ClipboardSyncRules.compareVersion(at, ad, av, bt, bd, bv)
}
