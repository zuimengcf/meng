package org.fcitx.fcitx5.android.data.clipboard

import androidx.room.withTransaction
import java.io.File
import org.fcitx.fcitx5.android.utils.appContext

/**
 * Destructive retention cleanup for deleted clipboard entries.
 * The deletion tombstone (sync id, deletion version and device) remains in the oplog/DB;
 * only plaintext and image bytes are removed after the retention window.
 */
object ClipboardPurge {
    const val DEFAULT_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L

    suspend fun purgeExpiredDeleted(
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = DEFAULT_RETENTION_MS,
    ): Int = ClipboardManager.withSyncLock {
        purgeExpiredDeletedUnlocked(now, retentionMs)
    }

    internal suspend fun purgeExpiredDeletedUnlocked(
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = DEFAULT_RETENTION_MS,
    ): Int {
        require(retentionMs >= 0L) { "retention must not be negative" }
        val cutoff = now - retentionMs
        val dao = ClipboardManager.syncDao()
        val expired = dao.findDeletedBefore(cutoff)
        val dirty = expired.filter { it.text.isNotEmpty() || it.imagePath != null || it.pinned }
        val candidates = expired.mapNotNull { it.imagePath }.toSet() + ClipboardOplog.expiredImagePaths(cutoff)
        // First make the Room redaction durable, never physically delete tombstone rows.
        if (dirty.isNotEmpty()) ClipboardManager.syncDatabase().withTransaction {
            dao.redactDeletedContent(dirty.map { it.id })
        }
        // Delete only unreferenced private files. Oplog paths are evaluated after conceptual redaction,
        // so a crash here can retry from the still-durable journal rows.
        val referenced = dao.getAllIncludingDeleted().mapNotNull { it.imagePath }.toSet() + ClipboardOplog.imagePaths(cutoff)
        candidates.filter { isPrivateClipboardImage(it) && it !in referenced }.forEach {
            val file = File(it)
            check(!file.exists() || file.delete()) { "过期图片清理未完成，将在下次检查重试" }
        }
        // Finally atomically rewrite old journal payloads; a crash before this point remains retry-safe.
        val redactedRevisions = ClipboardOplog.purgeExpiredDeleted(cutoff)
        if (dirty.isNotEmpty()) runCatching {
            val db = ClipboardManager.syncDatabase().openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) { /* consume */ } }
            db.execSQL("VACUUM")
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) { /* consume */ } }
        }
        if (dirty.isNotEmpty() || redactedRevisions > 0) ClipboardAuditLog.record("PURGE", maxOf(dirty.size, 1))
        return dirty.size
    }

    internal fun isPrivateClipboardImage(path: String): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val roots = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            .map { File(it, "clipboard_images").canonicalFile }
        return roots.any { root -> file != root && file.path.startsWith(root.path + File.separator) }
    }
}
