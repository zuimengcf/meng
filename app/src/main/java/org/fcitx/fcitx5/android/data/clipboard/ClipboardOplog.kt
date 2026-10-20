/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.provider.Settings
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardJournalOutbox
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Journal writes in [withTransaction] commit through a durable Room outbox, never before the rows. */
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

    private class Staged(val database: ClipboardDatabase) {
        // Only this transaction's delta is copied/indexed. A million retained rows
        // must not make every new clipboard capture clone the whole journal.
        val known = HashMap<String, Op>()
        val pending = ArrayList<Op>()
        val syncIds = HashSet<String>()
        val baselineIds = HashSet<String>()
        val deleteIds = HashSet<String>()
        var maxSeq = maxLocalSeq
        val deletions = ArrayList<Deletion>()

        fun add(op: Op) {
            known[revisionId(op)] = op
            pending += op
            syncIds += op.syncId
            if (op.opType == OP_INSERT || op.opType == OP_GROUP_ADD) baselineIds += op.syncId
            if (op.opType == OP_DELETE || op.opType == OP_GROUP_DELETE) deleteIds += op.syncId
            if (op.deviceId == deviceId) maxSeq = maxOf(maxSeq, op.seq)
        }
    }
    private val staged = ThreadLocal<Staged?>()

    /** Caller holds ClipboardManager's sync lock. Failed publication leaves the committed
     * batch in Room and blocks subsequent synchronization until recovery succeeds. */
    internal suspend fun replayPending(database: ClipboardDatabase,
        publish: (List<Op>) -> Unit = ::appendIncomingBatch) {
        check(staged.get() == null) { "不能在未提交的事务内发布历史日志" }
        val outbox = database.journalOutboxDao()
        val batches = outbox.pending()
        if (batches.isNotEmpty()) synchronized(monitor) { recoverInterruptedTail() }
        batches.forEach { batch ->
            when (batch.action) {
                "append" -> publish(json.decodeFromString<List<Op>>(batch.payload))
                "delete" -> applyDeletion(database, json.decodeFromString<Deletion>(batch.payload))
                else -> error("未知历史恢复操作，已停止同步")
            }
            // Crash after fsync but before acknowledgment is safe: revision IDs deduplicate replay.
            outbox.acknowledge(batch.id)
        }
    }

    /** Contains no text/image bytes; committed in the same transaction as the row deletion. */
    @Serializable data class Deletion(val syncId: String, val through: Long, val hard: Boolean,
        val imagePaths: List<String> = emptyList())

    internal fun stageDeletion(deletion: Deletion) {
        checkNotNull(staged.get()) { "删除必须在历史事务内登记" }.deletions += deletion
    }

    /** Pure rewrite rule, also tested without Android. Hard local deletion leaves no journal identity. */
    internal fun deletionRewrite(rows: List<Op>, deletion: Deletion): List<Op> = rows.mapNotNull { op ->
        when {
            op.syncId != deletion.syncId -> op
            deletion.hard -> null
            op.ts <= deletion.through && op.entry != null -> op.copy(entry = op.entry.copy(
                text = "", imagePath = null, imageData = null, pinned = false))
            else -> op
        }
    }

    private suspend fun applyDeletion(database: ClipboardDatabase, deletion: Deletion) {
        synchronized(monitor) {
            val rows = loaded()
            val rewritten = deletionRewrite(rows, deletion)
            if (rows != rewritten) rewriteLocked(rewritten)
        }
        // Shared content-addressed images must survive if another live entry/revision still uses them.
        val references = database.clipboardDao().getAllIncludingDeleted().mapNotNull { it.imagePath }.toSet() +
            readAll().mapNotNull { it.entry?.imagePath }.toSet()
        deletion.imagePaths.filter { it !in references && ClipboardPurge.isPrivateClipboardImage(it) }.forEach { path ->
            val file = File(path)
            check(!file.exists() || file.delete()) { "图片清理未完成，将在下次操作重试" }
        }
        ClipboardSyncSignal.changed()
    }

    private fun rewriteLocked(rewritten: List<Op>) {
        try {
            RandomAccessFile(rewriteFile, "rw").use { file ->
                file.setLength(0)
                rewritten.forEach { file.write((json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                file.fd.sync()
            }
            check(rewriteFile.renameTo(oplogFile)) { "无法替换剪贴板操作日志" }
            clearLoadedState()
        } finally { rewriteFile.delete() }
    }

    /** Rows and their recovery journal either commit together or roll back together.
     * Thread-local context propagation also covers Room's transaction dispatcher. */
    internal suspend fun <T> withTransaction(database: ClipboardDatabase,
        publish: (List<Op>) -> Unit = ::appendIncomingBatch, block: suspend () -> T): T {
        staged.get()?.let {
            check(it.database === database) { "历史事务不能跨数据库嵌套" }
            return database.withTransaction { block() }
        }
        replayPending(database, publish)
        val journal = synchronized(monitor) { loaded(); Staged(database) }
        val result = withContext(staged.asContextElement(journal)) {
            database.withTransaction {
                val value = block()
                if (journal.pending.isNotEmpty()) database.journalOutboxDao().insert(
                    ClipboardJournalOutbox(payload = json.encodeToString(journal.pending.toList())))
                journal.deletions.forEach { deletion -> database.journalOutboxDao().insert(
                    ClipboardJournalOutbox(payload = json.encodeToString(deletion), action = "delete")) }
                value
            }
        }
        replayPending(database, publish)
        return result
    }

    private fun visibleRows(): List<Op> = staged.get()?.let { loaded() + it.pending } ?: loaded()

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

    /** Only called with a committed recovery batch, never for an arbitrary damaged log. */
    private fun recoverInterruptedTail() {
        if (ClipboardJournalFile.recoverInterruptedTail(oplogFile) { parse(it) != null }) clearLoadedState()
    }

    private fun loaded(): MutableList<Op> {
        cache?.let { return it }
        // Only the successfully renamed journal is authoritative after an interrupted rewrite.
        check(!rewriteFile.exists() || rewriteFile.delete()) { "无法清理中断的历史日志临时文件" }
        known.clear(); syncIds.clear(); baselineSyncIds.clear(); deleteSyncIds.clear(); maxLocalSeq = 0L
        val rows = mutableListOf<Op>()
        if (oplogFile.exists()) oplogFile.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) {
                val op = parse(line) ?: error("历史日志损坏，已保留原文件并停止同步")
                val previous = known[revisionId(op)]
                check(previous == null || sameContent(previous, op)) {
                    "历史日志存在序号冲突，已保留原文件并停止同步"
                }
                if (previous == null) {
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
        staged.get()?.let { transaction ->
            fun plan(candidate: Op) {
                val id = revisionId(candidate)
                val previous = transaction.known[id] ?: known[id]
                if (previous != null) {
                    if (!sameContent(previous, candidate) && (candidate.entry != null || candidate.group != null)) {
                        plan(archiveOp(candidate.syncId, candidate.ts, candidate.deviceId, candidate.entry, candidate.group))
                    }
                    return
                }
                transaction.add(candidate)
            }
            ops.forEach(::plan)
            return
        }
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
        try {
            ClipboardJournalFile.append(oplogFile, pending.asSequence().map { json.encodeToString(it) })
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
        val seq = currentMaxSeq()
        check(seq < Long.MAX_VALUE) { "操作序号已耗尽" }
        appendBatchLocked(listOf(Op(seq + 1, deviceId, opType, syncId, ts, entry, group)))
    }

    fun appendIncoming(op: Op) = synchronized(monitor) { appendBatchLocked(listOf(op)) }

    /** Synchronous compatibility API; all records share one durable fd.sync(). */
    fun appendIncomingBatch(ops: List<Op>) = synchronized(monitor) { appendBatchLocked(ops) }

    /** Suspend variant safe to call concurrently from any coroutine without blocking its dispatcher. */
    suspend fun appendIncomingBatchSuspend(ops: List<Op>) = withContext(Dispatchers.IO) {
        synchronized(monitor) { appendBatchLocked(ops) }
    }

    fun readAll(): List<Op> = synchronized(monitor) { visibleRows().toList() }
    fun hasSyncOp(syncId: String): Boolean = synchronized(monitor) {
        loaded(); syncId in syncIds || staged.get()?.syncIds?.contains(syncId) == true
    }
    fun hasBaselineOp(syncId: String): Boolean = synchronized(monitor) {
        loaded(); syncId in baselineSyncIds || staged.get()?.baselineIds?.contains(syncId) == true
    }
    fun hasDeleteOp(syncId: String): Boolean = synchronized(monitor) {
        loaded(); syncId in deleteSyncIds || staged.get()?.deleteIds?.contains(syncId) == true
    }
    fun currentMaxSeq(): Long = synchronized(monitor) {
        staged.get()?.maxSeq ?: run { loaded(); maxLocalSeq }
    }
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
        // Immediate deletion is irreversible even before the legacy retention window expires.
        return shouldRedactRevision(op.ts, expiredDeleteTimes(Long.MAX_VALUE)[op.syncId])
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
