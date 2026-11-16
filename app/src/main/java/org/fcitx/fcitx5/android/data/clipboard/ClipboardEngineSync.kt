/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardGroup
import org.fcitx.fcitx5.android.data.clipboard.engine.ConflictPolicy
import org.fcitx.fcitx5.android.data.clipboard.engine.IncrementalCollection
import org.fcitx.fcitx5.android.data.clipboard.engine.MissingPolicy
import org.fcitx.fcitx5.android.data.clipboard.engine.SqliteSyncStore
import org.fcitx.fcitx5.android.data.clipboard.engine.SyncEngine
import org.fcitx.fcitx5.android.data.clipboard.engine.Units
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * engine/1：以 localsync core（HLC + 版本向量 + 操作日志 + 对端确认）为核心的剪贴板同步。
 *
 * - Room 仍是唯一的业务数据；引擎状态放在独立的 SQLite 文件里，不做 Room 迁移。
 * - 引擎通过「扫描 Room」得到本地变更（增量 token，只哈希变化的行），所以旧快照协议、
 *   全量覆盖、本地编辑写进 Room 的任何改动，都会在下一次扫描时变成引擎操作——两套协议自然桥接。
 * - 引擎裁决后的写入走 [ClipboardSnapshotStore.applyEngineUnlocked]，保留图片落盘、历史归档、墓碑屏障等旧行为。
 * - 所有入口都在 [ClipboardManager.withSyncLock] 内执行，与旧合并路径严格串行。
 */
object ClipboardEngineSync {
    private const val IMAGE_CHUNK = 8
    private const val ENTRY_CHUNK = 500

    private class Pending {
        val entries = LinkedHashMap<String, SyncEntry>()
        val groups = LinkedHashMap<String, SyncGroup>()
        val missingBlobs = LinkedHashSet<String>()
    }

    @Volatile private var pending = Pending()
    @Volatile private var entryRows: List<ClipboardEntry> = emptyList()
    @Volatile private var groupRows: List<ClipboardGroup> = emptyList()
    private val knownBlobs = ConcurrentHashMap<String, File>()

    private val entries = object : IncrementalCollection {
        override val name = ClipboardEngineMapping.ENTRIES
        override val policy = ConflictPolicy.UPDATE_REVIVES
        override fun tokens(): Map<String, String> {
            val groups = groupRows.associateBy { it.id }
            return entryRows.filter { it.syncId.isNotBlank() }.associate { it.syncId to entryToken(it, groups[it.groupId]) }
        }
        override fun load(keys: Collection<String>): Map<String, Units> {
            val wanted = keys.toHashSet()
            val groups = groupRows.associateBy { it.id }
            return entryRows.filter { it.syncId in wanted }.associate { row ->
                row.syncId to ClipboardEngineMapping.entryUnits(ClipboardSnapshotStore.engineEntry(row, groups[row.groupId], imageSha(row)))
            }
        }
        override fun upsert(key: String, units: Units): Boolean {
            val e = ClipboardEngineMapping.entryFromUnits(key, units)
            val sha = e.imageSha256
            if (sha != null && blobFile(sha) == null) { pending.missingBlobs += sha; return false }
            pending.entries[key] = e
            return true
        }
        override fun delete(key: String) = Unit // 引擎层从不产生删除（软删除是 unit 里的 deleted 字段）
        override fun versionWall(units: Units) = ClipboardEngineMapping.versionWall(units)
        override fun onMissing(key: String, last: Units) = MissingPolicy.IGNORE
    }

    private val groups = object : IncrementalCollection {
        override val name = ClipboardEngineMapping.GROUPS
        override val policy = ConflictPolicy.UPDATE_REVIVES
        override fun tokens(): Map<String, String> =
            groupRows.filter { it.syncId.isNotBlank() }.associate { it.syncId to sha1(ClipboardSnapshotStore.engineGroup(it).toString()) }
        override fun load(keys: Collection<String>): Map<String, Units> {
            val wanted = keys.toHashSet()
            return groupRows.filter { it.syncId in wanted }.associate { it.syncId to ClipboardEngineMapping.groupUnits(ClipboardSnapshotStore.engineGroup(it)) }
        }
        override fun upsert(key: String, units: Units): Boolean { pending.groups[key] = ClipboardEngineMapping.groupFromUnits(key, units); return true }
        override fun delete(key: String) = Unit
        override fun versionWall(units: Units) = ClipboardEngineMapping.versionWall(units)
        override fun onMissing(key: String, last: Units) = MissingPolicy.IGNORE
    }

    val engine: SyncEngine by lazy {
        SyncEngine(SqliteSyncStore(appContext), listOf(groups, entries), ClipboardOplog.deviceId,
            logger = { m, t -> Timber.w(t, "clipboard engine: %s", m) },
            maxForwardDriftMs = ClipboardSyncRules.MAX_REMOTE_FUTURE_MS)
    }

    data class Outcome(val applied: Int, val stats: ClipboardSyncStats?, val missingBlobs: Set<String>, val deliveredIds: Set<String>)

    /** 把 Room 的当前内容扫描成引擎操作，并落地之前缺图片的影子记录。 */
    suspend fun scan(): Outcome = ClipboardManager.withSyncLock { scanUnlocked() }

    private suspend fun scanUnlocked(): Outcome {
        ClipboardManager.enableSyncRetention()
        ClipboardPurge.purgeExpiredDeletedUnlocked()
        loadRows()
        pending = Pending()
        engine.scan()
        return flushUnlocked()
    }

    /** 合并远端操作：先整批校验（格式、HLC 漂移），任何一条不合格整批拒收、版本向量不动。 */
    suspend fun applyRemote(ops: List<ClipboardEngineWireOp>): Outcome = ClipboardManager.withSyncLock {
        val converted = ops.map(ClipboardEngineMapping::fromWire)
        val wall = System.currentTimeMillis()
        converted.forEach { op ->
            op.units?.let { u -> ClipboardEngineMapping.versionWall(u)?.let { t ->
                require(ClipboardSyncRules.isPlausibleVersionTime(t, wall)) { "对端设备时钟异常（版本时间超前一年以上），已整批拒收，请校准该设备时间" }
            } }
        }
        // 先把本机未扫描的改动记成操作，避免远端写入覆盖掉还没进日志的本地编辑。
        loadRows(); pending = Pending(); engine.scan(); val local = flushUnlocked()
        pending = Pending()
        val r = engine.applyRemote(converted)
        val out = flushUnlocked()
        Outcome(r.applied, out.stats ?: local.stats, out.missingBlobs, out.deliveredIds)
    }

    private suspend fun loadRows() {
        groupRows = ClipboardManager.syncGroupDao().getAllIncludingAll()
        entryRows = ClipboardManager.syncDao().getAllIncludingDeleted()
    }

    /** 把引擎接受的写入分块落进 Room；失败的记录退回影子，绝不让 Room 与引擎状态悄悄分叉。 */
    private suspend fun flushUnlocked(): Outcome {
        val p = pending
        pending = Pending()
        val groupList = p.groups.values.toList()
        val entryList = p.entries.values.toList()
        var applied = 0
        var stats: ClipboardSyncStats? = null
        if (groupList.isNotEmpty() || entryList.isNotEmpty()) {
            val chunks = ArrayList<List<SyncEntry>>()
            var cur = ArrayList<SyncEntry>(); var images = 0
            entryList.forEach { e ->
                if (cur.size >= ENTRY_CHUNK || (e.imageSha256 != null && images >= IMAGE_CHUNK)) { chunks += cur; cur = ArrayList(); images = 0 }
                cur += e; if (e.imageSha256 != null) images++
            }
            if (cur.isNotEmpty() || chunks.isEmpty()) chunks += cur
            var done = 0
            try {
                chunks.forEachIndexed { i, chunk ->
                    val hydrated = chunk.map { e ->
                        val sha = e.imageSha256 ?: return@map e
                        val data = blobFile(sha)?.let { ClipboardImageBlobs.readBase64(ClipboardImageBlobs.Source.FromFile(it)) }
                            ?: error("图片在落地前消失")
                        e.copy(imageData = data, imageSha256 = null)
                    }
                    val packet = ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId, entries = hydrated,
                        groups = if (i == 0) groupList else emptyList())
                    val (n, s) = ClipboardSnapshotStore.applyEngineUnlocked(packet)
                    applied += n; stats = merge(stats, s); done++
                }
            } catch (e: Exception) {
                val failed = chunks.drop(done).flatten().map { it.syncId }
                engine.markShadow(ClipboardEngineMapping.ENTRIES, failed)
                if (done == 0) engine.markShadow(ClipboardEngineMapping.GROUPS, groupList.map { it.syncId })
                throw e
            }
            if (applied > 0) ClipboardSyncSignal.invalidate()
        }
        val missing = LinkedHashSet(p.missingBlobs)
        engine.shadowValues(ClipboardEngineMapping.ENTRIES).values.forEach { u ->
            ClipboardEngineMapping.imageHash(u)?.takeIf { blobFile(it) == null }?.let { missing += it }
        }
        return Outcome(applied, stats, missing, entryList.map { it.syncId }.toSet())
    }

    private fun merge(a: ClipboardSyncStats?, b: ClipboardSyncStats): ClipboardSyncStats = if (a == null) b else ClipboardSyncStats(
        a.added + b.added, a.mergedExisting + b.mergedExisting, a.updated + b.updated, a.deleted + b.deleted, b.totalAfter,
        a.addedDetails + b.addedDetails, a.updatedDetails + b.updatedDetails, a.deletedDetails + b.deletedDetails)

    // ------------------------------------------------------------------ 图片

    private fun imageSha(row: ClipboardEntry): String? {
        if (row.deleted || !row.type.startsWith("image/")) return null
        val file = row.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) }?.let(::File)?.takeIf { it.isFile } ?: return null
        val sha = ClipboardImageBlobs.sha256OfFile(file) ?: error("图片读取失败，未确认同步完成")
        knownBlobs[sha] = file
        return sha
    }

    /** 本机上内容等于 [sha] 的图片文件（每次都校验哈希，文件被替换就作废）。 */
    fun blobFile(sha: String): File? {
        knownBlobs[sha]?.let { f -> if (ClipboardImageBlobs.sha256OfFile(f) == sha) return f else knownBlobs.remove(sha) }
        return ClipboardImageBlobs.find(ClipboardSnapshotStore.imagesDir(), sha)?.also { knownBlobs[sha] = it }
    }

    /** 本机未删除条目正在使用的、内容等于 [sha] 的图片；用于跨会话补拉。 */
    suspend fun liveImage(sha: String): File? = ClipboardManager.withSyncLock {
        ClipboardManager.syncDao().getAllIncludingDeleted().asSequence()
            .filter { !it.deleted && it.type.startsWith("image/") }
            .mapNotNull { row -> row.imagePath?.takeIf { ClipboardPurge.isPrivateClipboardImage(it) }?.let(::File)?.takeIf { it.isFile } }
            .firstOrNull { ClipboardImageBlobs.sha256OfFile(it) == sha }
    }

    fun collectGarbage() = runCatching { engine.collectGarbage() }.onFailure { Timber.w(it, "clipboard engine gc failed") }

    /** 收到对端补传的图片：只接受引擎正在等的哈希，校验后落盘，再扫描一次让影子记录落地。 */
    suspend fun acceptBlob(sha: String, bytes: ByteArray): Outcome = ClipboardManager.withSyncLock {
        val waiting = engine.shadowValues(ClipboardEngineMapping.ENTRIES).values
            .firstOrNull { ClipboardEngineMapping.imageHash(it) == sha }
            ?: throw SecurityException("未请求的图片")
        val type = ClipboardEngineMapping.entryFromUnits("_", waiting).type
        val dir = ClipboardSnapshotStore.imagesDir() ?: error("无法访问图片目录")
        knownBlobs[sha] = ClipboardImageBlobs.store(dir, sha, bytes, type)
        scanUnlocked()
    }

    fun storeFetchedBlob(sha: String, bytes: ByteArray, mimeType: String): File {
        val dir = ClipboardSnapshotStore.imagesDir() ?: error("无法访问图片目录")
        return ClipboardImageBlobs.store(dir, sha, bytes, mimeType).also { knownBlobs[sha] = it }
    }

    /** 影子记录在等的图片：哈希 -> MIME。 */
    fun waitingBlobs(): Map<String, String> = engine.shadowValues(ClipboardEngineMapping.ENTRIES).values.mapNotNull { u ->
        ClipboardEngineMapping.imageHash(u)?.takeIf { blobFile(it) == null }?.let { it to ClipboardEngineMapping.entryFromUnits("_", u).type }
    }.toMap()

    // ------------------------------------------------------------------ 版本向量 / 确认

    fun page(remoteVv: Map<String, Long>, limit: Int): Pair<List<ClipboardEngineWireOp>, Boolean> {
        val p = engine.opsFor(remoteVv, limit.coerceIn(1, 1000))
        return p.ops.map(ClipboardEngineMapping::toWire) to p.hasMore
    }

    fun versionVector(): Map<String, Long> = engine.versionVector()
    fun ackGossip(): Map<String, Map<String, Long>> = engine.ackGossip()
    fun recordPeer(peer: String, vv: Map<String, Long>, acks: Map<String, Map<String, Long>> = emptyMap()) {
        engine.recordPeerAck(peer, vv)
        engine.mergeAckGossip(acks.filterKeys { it != ClipboardOplog.deviceId })
    }

    /** 本页里出现的条目 id（发送前标记「可能已暴露」，收到对端确认后再标记送达）。 */
    fun entryIds(ops: List<ClipboardEngineWireOp>): Set<String> =
        ops.filter { it.collection == ClipboardEngineMapping.ENTRIES }.map { it.key }.toSet()

    fun imageHashes(ops: List<ClipboardEngineWireOp>): Set<String> = ops.mapNotNull { op ->
        if (op.collection != ClipboardEngineMapping.ENTRIES) null else op.units?.let { ClipboardEngineMapping.imageHash(it) }
    }.toSet()

    fun receiptVersions(ops: List<ClipboardEngineWireOp>): Map<String, String> = ops.mapNotNull { op ->
        if (op.collection != ClipboardEngineMapping.ENTRIES) return@mapNotNull null
        val e = op.units?.let { ClipboardEngineMapping.entryFromUnits(op.key, it) } ?: return@mapNotNull null
        op.key to ClipboardReceiptVersion.token(e.updatedAt, e.deviceId)
    }.toMap()

    fun exposurePacket(ids: Collection<String>) = ClipboardSyncPacket(deviceId = ClipboardOplog.deviceId,
        entries = ids.map { SyncEntry(syncId = it, text = "", pinned = false, timestamp = 0, type = "", sensitive = false, deleted = false, updatedAt = 0) })

    private fun entryToken(row: ClipboardEntry, group: ClipboardGroup?): String {
        val f = row.imagePath?.let(::File)
        return sha1("${row.copy(id = 0, groupId = 0, hasBeenSynced = true, syncExposurePending = false)}|${group?.syncId}|${f?.isFile}:${f?.length()}:${f?.lastModified()}")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
