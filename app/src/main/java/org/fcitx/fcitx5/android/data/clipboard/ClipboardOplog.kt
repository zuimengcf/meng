/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** 追加式持久版本日志。写入失败必须抛出，使调用方的 Room 事务回滚。 */
object ClipboardOplog {
    const val OP_INSERT = "INSERT"
    const val OP_DELETE = "DELETE"
    const val OP_UPDATE_TEXT = "UPDATE_TEXT"
    const val OP_PIN = "PIN"
    const val OP_SET_GROUP = "SET_GROUP"
    const val OP_GROUP_ADD = "GROUP_ADD"
    const val OP_GROUP_RENAME = "GROUP_RENAME"
    const val OP_GROUP_DELETE = "GROUP_DELETE"
    const val OP_HISTORY_ENTRY = "HISTORY_ENTRY"
    const val OP_HISTORY_GROUP = "HISTORY_GROUP"

    @Serializable data class EntryOp(
        val text: String, val pinned: Boolean, val timestamp: Long, val type: String,
        val sensitive: Boolean, val deleted: Boolean, val groupName: String = "",
        val imagePath: String? = null, val groupSyncId: String = "",
        val sortOrder: Long = 0, val imageData: String? = null,
    )
    @Serializable data class GroupOp(val name: String, val sortOrder: Int)
    @Serializable data class Op(
        val seq: Long, val deviceId: String, val opType: String, val syncId: String,
        val ts: Long, val entry: EntryOp? = null, val group: GroupOp? = null,
    )

    /** Input for [archiveBatch]; constructing these does not touch disk. */
    data class Archive(
        val syncId: String, val ts: Long, val modifier: String,
        val entry: EntryOp? = null, val group: GroupOp? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val monitor = Any()
    private val oplogFile: File by lazy { File(appContext.filesDir, "clipboard_oplog.jsonl") }
    private val rewriteFile: File by lazy { File(appContext.filesDir, "clipboard_oplog.jsonl.redacting") }
    val deviceId: String by lazy {
        runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: run {
                val prefs = appContext.getSharedPreferences("clipboard_sync", 0)
                prefs.getString("stable_device_id", null) ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("stable_device_id", it).commit()) { "无法持久保存设备标识" }
                }
            }
    }

    private var cache: MutableList<Op>? = null
    private val known = HashMap<String, Op>()
    private val syncIds = HashSet<String>()
    private val baselineSyncIds = HashSet<String>()
    private val deleteSyncIds = HashSet<String>()
    private var maxLocalSeq = 0L

    private fun clearLoadedState() {
        cache = null
        known.clear()
        syncIds.clear()
        baselineSyncIds.clear()
        deleteSyncIds.clear()
        maxLocalSeq = 0L
    }

    private fun index(op: Op) {
        syncIds += op.syncId
        when (op.opType) {
            OP_INSERT, OP_GROUP_ADD -> baselineSyncIds += op.syncId
            OP_DELETE, OP_GROUP_DELETE -> deleteSyncIds += op.syncId
        }
    }

    private fun loaded(): MutableList<Op> {
        cache?.let { return it }
        // Only the successfully renamed journal is authoritative after an interrupted rewrite.
        check(!rewriteFile.exists() || rewriteFile.delete()) { "无法清理中断的历史日志临时文件" }
        known.clear(); syncIds.clear(); baselineSyncIds.clear(); deleteSyncIds.clear(); maxLocalSeq = 0L
        val rows = mutableListOf<Op>()
        if (oplogFile.exists()) oplogFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) parse(line)?.let { op ->
                if (revisionId(op) !in known) {
                    known[revisionId(op)] = op; rows.add(op); index(op)
                }
                if (op.deviceId == deviceId) maxLocalSeq = maxOf(maxLocalSeq, op.seq)
            }
        }
        cache = rows
        return rows
    }

    fun revisionId(op: Op): String = "${op.deviceId}:${op.seq}"
    fun parse(line: String): Op? = runCatching { json.decodeFromString<Op>(line) }.getOrNull()

    private fun sameContent(a: Op, b: Op): Boolean =
        a.copy(entry = a.entry?.copy(imagePath = null)) == b.copy(entry = b.entry?.copy(imagePath = null))

    /** Make the deterministic history revision used by archive and archiveBatch. */
    fun archiveOp(syncId: String, ts: Long, modifier: String, entry: EntryOp? = null, group: GroupOp? = null): Op {
        require((entry == null) != (group == null)) { "历史操作必须恰好包含 entry 或 group" }
        val type = if (entry != null) OP_HISTORY_ENTRY else OP_HISTORY_GROUP
        val canonical = json.encodeToString(Op(0, modifier, type, syncId, ts, entry?.copy(imagePath = null), group))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val id = buildString(digest.size * 2) { digest.forEach { b ->
            val v = b.toInt() and 255; append(hex[v ushr 4]); append(hex[v and 15])
        } }
        return Op(1, "history/$id", type, syncId, ts, entry, group)
    }

    /**
     * Append all revisions in one file transaction and one fd.sync(). Planning happens
     * entirely in memory; cache/known/indexes are changed only after sync succeeds.
     */
    private fun appendBatchLocked(ops: List<Op>) {
        if (ops.isEmpty()) { loaded(); return }
        val rows = loaded()
        val pending = ArrayList<Op>()
        val working = HashMap<String, Op>()
        fun plan(candidate: Op) {
            val id = revisionId(candidate)
            val previous = working[id] ?: known[id]
            if (previous != null) {
                if (!sameContent(previous, candidate) && (candidate.entry != null || candidate.group != null)) {
                    plan(archiveOp(candidate.syncId, candidate.ts, candidate.deviceId, candidate.entry, candidate.group))
                }
                return
            }
            working[id] = candidate
            pending += candidate
        }
        ops.forEach(::plan)
        if (pending.isEmpty()) return

        try {
            oplogFile.parentFile?.mkdirs()
            RandomAccessFile(oplogFile, "rw").use { file ->
                var length = file.length()
                if (length > 0) {
                    file.seek(length - 1)
                    if (file.read() != '\n'.code) { file.seek(length); file.write('\n'.code); length++ }
                }
                file.seek(length)
                pending.forEach { file.write((json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                file.fd.sync()
            }
        } catch (t: Throwable) {
            // A failed block must not make a caller believe revisions are durable. Reload
            // after a possible partial write on the next operation, then rethrow unchanged.
            clearLoadedState()
            throw t
        }
        pending.forEach { op -> rows += op; known[revisionId(op)] = op; index(op) }
        pending.filter { it.deviceId == deviceId }.forEach { maxLocalSeq = maxOf(maxLocalSeq, it.seq) }
        // Audit only operation type/count; never expose text, ids, or group names.
        pending.groupingBy { it.opType }.eachCount().forEach { (type, count) ->
            runCatching { ClipboardAuditLog.record(type, count) }
        }
        // Wake the sync client only after the complete block is durable, and only once.
        ClipboardSyncSignal.changed()
    }

    fun append(opType: String, syncId: String, entry: EntryOp? = null, group: GroupOp? = null,
        ts: Long = System.currentTimeMillis()) = synchronized(monitor) {
        loaded(); check(maxLocalSeq < Long.MAX_VALUE) { "操作序号已耗尽" }
        appendBatchLocked(listOf(Op(maxLocalSeq + 1, deviceId, opType, syncId, ts, entry, group)))
    }

    fun appendIncoming(op: Op) = synchronized(monitor) { appendBatchLocked(listOf(op)) }

    /** Synchronous compatibility API; all records share one durable fd.sync(). */
    fun appendIncomingBatch(ops: List<Op>) = synchronized(monitor) { appendBatchLocked(ops) }

    /** Suspend variant safe to call concurrently from any coroutine without blocking its dispatcher. */
    suspend fun appendIncomingBatchSuspend(ops: List<Op>) = withContext(Dispatchers.IO) {
        synchronized(monitor) { appendBatchLocked(ops) }
    }

    fun readAll(): List<Op> = synchronized(monitor) { loaded().toList() }
    fun hasSyncOp(syncId: String): Boolean = synchronized(monitor) { loaded(); syncId in syncIds }
    fun hasBaselineOp(syncId: String): Boolean = synchronized(monitor) { loaded(); syncId in baselineSyncIds }
    fun hasDeleteOp(syncId: String): Boolean = synchronized(monitor) { loaded(); syncId in deleteSyncIds }
    fun currentMaxSeq(): Long = synchronized(monitor) { loaded(); maxLocalSeq }
    fun newSyncId(): String = "$deviceId-${UUID.randomUUID()}"

    fun archiveBatch(items: List<Archive>) = synchronized(monitor) {
        appendBatchLocked(items.map { archiveOp(it.syncId, it.ts, it.modifier, it.entry, it.group) })
    }

    /** Legacy single-item archive API. */
    fun archive(syncId: String, ts: Long, modifier: String, entry: EntryOp? = null, group: GroupOp? = null) {
        archiveBatch(listOf(Archive(syncId, ts, modifier, entry, group)))
    }

    /**
     * Strip old deleted-entry content from the journal without removing its tombstone.
     * The rewrite is atomic and fsynced; a stale remote history packet is subsequently
     * unable to reintroduce the old text because the local delete marker remains.
     */
    /** Pure retention rule: a revision is redactable only when it predates its expired DELETE. */
    internal fun shouldRedactRevision(revisionTs: Long, expiredDeleteTs: Long?): Boolean =
        expiredDeleteTs != null && revisionTs <= expiredDeleteTs

    fun expiredDeleteTimes(cutoff: Long, rows: List<Op> = readAll()): Map<String, Long> =
        rows.asSequence().filter { (it.opType == OP_DELETE || it.entry?.deleted == true) && it.syncId.isNotBlank() && it.ts <= cutoff }
            .groupBy { it.syncId }.mapValues { (_, ops) -> ops.maxOf { it.ts } }

    fun isPurgedPayload(op: Op, cutoff: Long = System.currentTimeMillis() - ClipboardPurge.DEFAULT_RETENTION_MS): Boolean {
        if (op.group != null) return false
        val payload = op.entry ?: return true
        if (payload.text.isNotEmpty() || payload.imagePath != null || payload.imageData != null) return false
        return shouldRedactRevision(op.ts, expiredDeleteTimes(cutoff)[op.syncId])
    }

    fun expiredImagePaths(cutoff: Long): Set<String> = synchronized(monitor) {
        val expired = expiredDeleteTimes(cutoff, loaded())
        loaded().asSequence().filter { shouldRedactRevision(it.ts, expired[it.syncId]) }
            .mapNotNull { it.entry?.imagePath }.toSet()
    }

    /** Paths still referenced after applying the retention policy conceptually. */
    fun imagePaths(cutoff: Long): Set<String> = synchronized(monitor) {
        val expired = expiredDeleteTimes(cutoff, loaded())
        loaded().asSequence().filterNot { shouldRedactRevision(it.ts, expired[it.syncId]) }
            .mapNotNull { it.entry?.imagePath }.toSet()
    }

    fun purgeExpiredDeleted(cutoff: Long): Int = synchronized(monitor) {
        val rows = loaded()
        val expired = expiredDeleteTimes(cutoff, rows)
        if (expired.isEmpty()) return@synchronized 0
        var changed = 0
        val rewritten = rows.map { op ->
            if (op.entry == null || !shouldRedactRevision(op.ts, expired[op.syncId])) return@map op
            val entry = op.entry
            if (entry.text.isEmpty() && entry.imagePath == null && entry.imageData == null) return@map op
            changed++
            op.copy(entry = entry.copy(text = "", imagePath = null, imageData = null))
        }
        if (changed == 0) return@synchronized 0
        val temporary = rewriteFile
        try {
            RandomAccessFile(temporary, "rw").use { file ->
                file.setLength(0)
                rewritten.forEach { file.write((json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                file.fd.sync()
            }
            check(temporary.renameTo(oplogFile)) { "无法替换剪切板操作日志" }
            clearLoadedState()
            changed
        } finally {
            temporary.delete()
        }
    }
}
