package org.fcitx.fcitx5.android.data.clipboard

import java.io.File
import org.fcitx.fcitx5.android.utils.appContext

/** Expired payloads are erased; deletion identities and versions remain to prevent resurrection. */
object ClipboardPurge {
    const val DEFAULT_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L

    internal data class DeletionCandidate(
        val syncId: String,
        val through: Long,
        val imagePaths: Set<String> = emptySet(),
    )

    /** Merge observations, never creating a rewrite rule for a blank legacy id. */
    internal fun mergeDeletionCandidates(candidates: Iterable<DeletionCandidate>): List<ClipboardOplog.Deletion> {
        val boundaries = linkedMapOf<String, Long>()
        val paths = hashMapOf<String, MutableSet<String>>()
        candidates.forEach { candidate ->
            if (candidate.syncId.isNotBlank()) {
                boundaries[candidate.syncId] = maxOf(boundaries[candidate.syncId] ?: Long.MIN_VALUE, candidate.through)
                paths.getOrPut(candidate.syncId) { linkedSetOf() }.addAll(candidate.imagePaths)
            }
        }
        return boundaries.map { (id, through) ->
            ClipboardOplog.Deletion(id, through, hard = false, imagePaths = paths.getValue(id).toList())
        }
    }

    suspend fun purgeExpiredDeleted(
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = DEFAULT_RETENTION_MS,
    ): Int = try {
        ClipboardManager.withSyncLock { purgeExpiredDeletedUnlocked(now, retentionMs) }
    } finally {
        // secure_delete protects freed DB pages. Truncate the WAL outside syncLock,
        // even after restart/no-op cleanup; never run a whole-database VACUUM here.
        runCatching {
            ClipboardManager.syncDatabase().openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) { /* consume */ } }
        }
    }

    internal suspend fun purgeExpiredDeletedUnlocked(
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = DEFAULT_RETENTION_MS,
    ): Int {
        require(retentionMs >= 0L) { "retention must not be negative" }
        val cutoff = now - retentionMs
        val database = ClipboardManager.syncDatabase()
        val dao = ClipboardManager.syncDao()
        val dirty = dao.findDeletedBefore(cutoff)
            .filter { it.text.isNotEmpty() || it.imagePath != null || it.pinned }
        val rows = ClipboardOplog.readAll()
        val through = ClipboardOplog.expiredDeleteTimes(cutoff, rows).toMutableMap()
        dirty.filter { it.syncId.isNotBlank() }.forEach { row ->
            through[row.syncId] = maxOf(through[row.syncId] ?: Long.MIN_VALUE, row.updatedAt)
        }
        // One pass, not one full-history search per expired tombstone. Already empty
        // histories generate no plans, so opening the panel does not keep rewriting them.
        val candidates = ArrayList<DeletionCandidate>()
        dirty.forEach { row ->
            if (row.syncId.isNotBlank()) candidates += DeletionCandidate(
                row.syncId, through.getValue(row.syncId), listOfNotNull(row.imagePath).toSet())
        }
        rows.forEach { op ->
            val boundary = through[op.syncId]
            val entry = op.entry
            if (entry != null && ClipboardOplog.shouldRedactRevision(op.ts, boundary) &&
                (entry.text.isNotEmpty() || entry.imagePath != null || entry.imageData != null || entry.pinned)) {
                candidates += DeletionCandidate(op.syncId, boundary!!, listOfNotNull(entry.imagePath).toSet())
            }
        }
        val plans = mergeDeletionCandidates(candidates).toMutableList()
        val legacyPaths = dirty.filter { it.syncId.isBlank() }.mapNotNull { it.imagePath }.distinct()
        if (legacyPaths.isNotEmpty()) {
            // Blank identities are image-cleanup-only in Oplog; the durable outbox
            // retains retry paths without ever matching unrelated blank-id revisions.
            plans += ClipboardOplog.Deletion("", cutoff, hard = false, imagePaths = legacyPaths)
        }
        if (dirty.isEmpty() && plans.isEmpty()) return 0
        ClipboardOplog.withTransaction(database) {
            if (dirty.isNotEmpty()) dao.redactDeletedContent(dirty.map { it.id })
            plans.forEach(ClipboardOplog::stageDeletion)
        }
        // Publication rewrites once and deletes only private, unreferenced images;
        // failure leaves all deletion tasks available for replay before the next sync.
        ClipboardAuditLog.record("PURGE", maxOf(dirty.size, plans.size, 1))
        return dirty.size
    }

    internal fun isPrivateClipboardImage(path: String): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val roots = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            .map { File(it, "clipboard_images").canonicalFile }
        return roots.any { root -> file != root && file.path.startsWith(root.path + File.separator) }
    }
}
