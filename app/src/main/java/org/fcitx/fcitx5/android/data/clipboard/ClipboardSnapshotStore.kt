/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/** 全量持久快照及历史。所有读写与本地操作共用同一锁和 Room 事务。 */
object ClipboardSnapshotStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    /**
     * @property blobs 仅 blob 模式：快照里每个图片哈希对应的本地字节来源（文件或日志内联数据的引用）。
     */
    data class Capture(val packet: ClipboardSyncPacket, val fingerprint: String,
        val blobs: Map<String, ClipboardImageBlobs.Source> = emptyMap())

    suspend fun snapshot(): ClipboardSyncPacket = capture().packet

    /**
     * @param blobMode 对端协商了 blob/1：条目与历史里的图片只给 SHA-256，不做 base64。
     *   归档到历史日志的版本仍按旧格式（含内联字节）计算 id，因此历史版本 id 与旧版设备一致。
     */
    suspend fun capture(blobMode: Boolean = false): Capture = ClipboardManager.withSyncLock {
        ClipboardManager.enableSyncRetention()
        // Redact before constructing either entries or history, so expired plaintext never leaves the device.
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        ClipboardOplog.withTransaction(ClipboardManager.syncDatabase()) {
            val groups = ClipboardManager.syncGroupDao().getAllIncludingAll()
            val entries = ClipboardManager.syncDao().getAllIncludingDeleted()
            val byId = groups.associateBy { it.id }
            val blobs = LinkedHashMap<String, ClipboardImageBlobs.Source>()
            val outgoing = if (!blobMode) entries.map { e -> toSync(e, byId[e.groupId]) }.also { list ->
                ClipboardOplog.appendIncomingBatch(list.map(::archiveOp) + groups.map { archiveOp(toSync(it)) })
            } else entries.map { e -> toSyncBlob(e, byId[e.groupId], blobs) }.also { list ->
                archiveBlobEntries(list, blobs)
                ClipboardOplog.appendIncomingBatch(groups.map { archiveOp(toSync(it)) })
            }
            val history = ClipboardOplog.readAll().map { op ->
                val id = ClipboardOplog.revisionId(op)
                val payload = op.entry?.let { p -> if (blobMode) historyBlob(id, p, blobs) else
                    // Old on-device journal rows reference private image files, never remote absolute paths.
                    p.copy(imagePath = null, imageData = p.imageData ?: p.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) && File(it).isFile }?.let(::imageFileToBase64))
                }
                SyncRevision(id, op.syncId, op.ts, op.deviceId, op.opType, payload, op.group)
            }
            val packet = ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId,
                entries = outgoing, groups = groups.map(::toSync), history = history)
            Capture(packet, fingerprintUnlocked(), blobs)
        }
    }

    internal fun imagesDir(): File? = appContext.getExternalFilesDir(null)?.let { File(it, "clipboard_images") }

    private fun privateImage(path: String?): File? =
        path?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) }?.let(::File)?.takeIf { it.isFile }

    private fun toSyncBlob(e: ClipboardEntry, group: ClipboardGroup?, blobs: MutableMap<String, ClipboardImageBlobs.Source>): SyncEntry {
        val base = toSync(e, group, includeImage = false)
        if (e.deleted || !e.type.startsWith("image/")) return base
        val file = privateImage(e.imagePath) ?: return base
        val sha = ClipboardImageBlobs.sha256OfFile(file) ?: error("图片读取失败，未确认同步完成")
        blobs.putIfAbsent(sha, ClipboardImageBlobs.Source.FromFile(file))
        return base.copy(imageSha256 = sha)
    }

    private fun historyBlob(id: String, p: ClipboardOplog.EntryOp, blobs: MutableMap<String, ClipboardImageBlobs.Source>): ClipboardOplog.EntryOp {
        val inline = p.imageData?.takeIf { it.isNotBlank() }
        val sha: String? = if (inline != null) ClipboardImageBlobs.sha256OfBase64(inline, id)?.also {
            blobs.putIfAbsent(it, ClipboardImageBlobs.Source.Inline(inline))
        } else privateImage(p.imagePath)?.let { f ->
            ClipboardImageBlobs.sha256OfFile(f)?.also { blobs.putIfAbsent(it, ClipboardImageBlobs.Source.FromFile(f)) }
        }
        return p.copy(imagePath = null, imageData = null, imageSha256 = sha)
    }

    /** 已归档过的（条目元数据 + 图片哈希）；命中则不必为算历史 id 而重新 base64 整张图。 */
    private val archivedBlobKeys = object : java.util.LinkedHashMap<String, Unit>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 8192
    }

    /** 与旧格式产生完全相同的归档版本：图片条目逐张临时还原字节、算 id、立即释放。 */
    private fun archiveBlobEntries(list: List<SyncEntry>, blobs: Map<String, ClipboardImageBlobs.Source>) {
        val plain = ArrayList<ClipboardOplog.Op>()
        list.forEach { e ->
            val sha = e.imageSha256
            if (sha == null) { plain += archiveOp(e); return@forEach }
            val key = ClipboardImageBlobs.sha256Hex(json.encodeToString(e).toByteArray(Charsets.UTF_8))
            if (synchronized(archivedBlobKeys) { archivedBlobKeys.containsKey(key) }) return@forEach
            val data = blobs[sha]?.let(ClipboardImageBlobs::readBase64) ?: error("图片读取失败，未确认同步完成")
            ClipboardOplog.appendIncomingBatch(listOf(archiveOp(e.copy(imageData = data, imageSha256 = null))))
            synchronized(archivedBlobKeys) { archivedBlobKeys[key] = Unit }
        }
        ClipboardOplog.appendIncomingBatch(plain)
    }

    /** 把 blob 形式还原为旧格式：按哈希从本地图片读回字节、清掉哈希字段。用于上传与合并前。 */
    fun hydrate(packet: ClipboardSyncPacket, blobs: Map<String, ClipboardImageBlobs.Source> = emptyMap(),
        dropUnresolvedHistory: Boolean = false): ClipboardSyncPacket {
        if (packet.entries.none { it.imageSha256 != null } && packet.history.none { it.entry?.imageSha256 != null }) return packet
        val dir = imagesDir()
        fun bytes(sha: String): String? = blobs[sha]?.let(ClipboardImageBlobs::readBase64)
            ?: ClipboardImageBlobs.find(dir, sha)?.let { ClipboardImageBlobs.readBase64(ClipboardImageBlobs.Source.FromFile(it)) }
        val entries = packet.entries.map { e ->
            val sha = e.imageSha256 ?: return@map e
            e.copy(imageData = e.imageData ?: if (e.deleted) null else bytes(sha), imageSha256 = null)
        }
        val history = packet.history.mapNotNull { h ->
            val p = h.entry ?: return@mapNotNull h
            val sha = p.imageSha256 ?: return@mapNotNull h
            val data = p.imageData ?: bytes(sha)
            // 历史版本 id 由含字节的内容算出；缺字节的副本不能落盘，否则会生成重复版本。下次同步再补。
            if (data == null && dropUnresolvedHistory) return@mapNotNull null
            h.copy(entry = p.copy(imageData = data, imageSha256 = null))
        }
        return packet.copy(entries = entries, history = history)
    }

    private val heartbeatCache = ClipboardSyncFingerprintCache()

    /**
     * 心跳摘要。数据未变时直接返回缓存，不读库、不扫 oplog、不等同步锁；
     * 同步锁被长时间占用（大合并进行中）且已有旧值时返回旧值，下一次心跳再判断。
     * 预览/全量覆盖仍用 [fingerprintUnlocked] 精确重算，不受缓存影响。
     */
    suspend fun fingerprint(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        heartbeatCache.fresh(ClipboardSyncSignal.generation, now)?.let { return it }
        if (ClipboardManager.isSyncBusy) heartbeatCache.any()?.let { return it }
        return ClipboardManager.withSyncLock {
            // Read the generation before scanning: a write racing the scan bumps it and forces a recompute.
            val generation = ClipboardSyncSignal.generation
            fingerprintUnlocked().also { heartbeatCache.put(generation, android.os.SystemClock.elapsedRealtime(), it) }
        }
    }
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

    /**
     * 只为历史版本拉来的图片：字节已内联进日志，文件本身没有条目引用，删掉以免占空间。
     * 被任何条目或日志路径引用的文件（同哈希去重后共用）一律保留。
     */
    suspend fun releaseUnreferencedBlobs(files: Collection<File>) = ClipboardManager.withSyncLock {
        val referenced = HashSet<String>()
        ClipboardManager.syncDao().getAllIncludingDeleted().forEach { e -> e.imagePath?.let { referenced += File(it).absolutePath } }
        ClipboardOplog.readAll().forEach { op -> op.entry?.imagePath?.let { referenced += File(it).absolutePath } }
        files.filter { it.absolutePath !in referenced }.forEach { runCatching { it.delete() } }
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

    /**
     * engine/1：把 localsync 引擎已经裁决好的记录写进 Room。不再按 updatedAt 比较（引擎的 HLC 已决定胜者），
     * 但沿用旧路径的一切副作用：图片落盘、分组解析、历史归档、墓碑屏障、送达标记、心跳摘要失效。
     * 调用方必须已持有 [ClipboardManager.withSyncLock]。
     */
    internal suspend fun applyEngineUnlocked(packet: ClipboardSyncPacket): Pair<Int, ClipboardSyncStats> {
        val before = metadataSnapshotUnlocked()
        val applied = mergeUnlocked(packet, authoritative = true)
        return applied to ClipboardSyncSummary.summarize(before, packet, metadataSnapshotUnlocked())
    }

    /** engine/1 扫描用：条目的元数据形式（不含图片字节，图片以 [sha] 表示）。 */
    internal fun engineEntry(e: ClipboardEntry, group: ClipboardGroup?, sha: String?): SyncEntry =
        toSync(e, group, includeImage = false).copy(imageSha256 = sha)

    internal fun engineGroup(g: ClipboardGroup): SyncGroup = toSync(g)

    private suspend fun mergeUnlocked(wire: ClipboardSyncPacket, authoritative: Boolean = false): Int {
        // blob/1：图片字节已由 blob_get 预先落盘，这里还原成旧格式后走原有合并逻辑，规则完全不变。
        val hydrated = hydrate(wire, dropUnresolvedHistory = true)
        // 超长文本（> CursorWindow）写进 Room 后就再也读不出来、之后所有同步都会失败：旧协议下直接不导入。
        // 不改写成截断版本，避免与对端按 updatedAt 比较时来回覆盖对端的完整原文。
        val oversized = hydrated.entries.count { !it.deleted && ClipboardSizeLimits.isOversized(it.text) }
        if (oversized > 0) Timber.w("clipboard merge skipped %d oversized entries", oversized)
        val packet = if (oversized == 0) hydrated
            else hydrated.copy(entries = hydrated.entries.filter { it.deleted || !ClipboardSizeLimits.isOversized(it.text) })
        // Run before every sync merge.  Keep only metadata tombstones for expired rows;
        // an old remote inventory/history must not resurrect their cleared plaintext.
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        require(packet.protocolVersion == 2) { "同步协议不兼容，请升级双方应用" }
        val wall = System.currentTimeMillis()
        require(packet.entries.all { ClipboardSyncRules.isPlausibleVersionTime(it.updatedAt, wall) } &&
            packet.groups.all { ClipboardSyncRules.isPlausibleVersionTime(it.updatedAt, wall) } &&
            packet.history.all { ClipboardSyncRules.isPlausibleVersionTime(it.ts, wall) }) {
            "对端设备时钟异常（版本时间超前一年以上），已拒绝本次同步，请校准该设备时间"
        }
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
                val decision = local == null || if (authoritative) json.encodeToString(r) != json.encodeToString(toSync(local))
                    else compare(r.updatedAt, r.deviceId, json.encodeToString(r),
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
                val cmp = if (localWire == null) 1
                    else if (authoritative) (if (engineSemantic(r) == engineSemantic(localWire)) 0 else 1)
                    else compare(r.updatedAt, r.deviceId, semantic(r),
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
        ClipboardSyncSignal.invalidate()
        return changed
    }
    /** 引擎形式：已删除条目的正文/置顶不参与比较（引擎从不传它们）。 */
    private fun engineSemantic(e: SyncEntry): String = semantic(if (e.deleted) e.copy(text = "", pinned = false) else e)
    private fun semantic(e: SyncEntry): String = json.encodeToString(e.copy(imageData = null, imageSha256 = null, groupName = ""))
    private fun compare(at: Long, ad: String, av: String, bt: Long, bd: String, bv: String): Int =
        ClipboardSyncRules.compareVersion(at, ad, av, bt, bd, bv)
}
