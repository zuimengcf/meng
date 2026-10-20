/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * Pure recovery eligibility rules shared by the manager and the history UI.
 *
 * The image predicate is deliberately supplied by the caller: it must return true only
 * for a file that both exists and is a private clipboard image.  Keeping that policy at
 * the boundary means this helper remains JVM-testable and never performs file I/O.
 */
object ClipboardRevisionRecovery {
    /**
     * Returns whether [revision] still contains enough payload to restore.
     * Deleted operations/payloads and redacted empty tombstones are never recoverable.
     */
    fun canRestore(
        revision: SyncRevision,
        imageExists: (String) -> Boolean = { false },
    ): Boolean {
        if (revision.group != null) {
            return revision.opType != ClipboardOplog.OP_GROUP_DELETE
        }
        return revision.entry?.let { entry ->
            if (entry.deleted || revision.opType == ClipboardOplog.OP_DELETE) return@let false
            if (entry.type.startsWith("image/", ignoreCase = true)) {
                entry.imageData?.isNotBlank() == true ||
                    (entry.imagePath?.isNotBlank() == true && imageExists(entry.imagePath))
            } else {
                entry.text.isNotEmpty()
            }
        } == true
    }

    /** Oplog form used by manager-side revision filtering and restore defense. */
    fun canRestore(
        op: ClipboardOplog.Op,
        imageExists: (String) -> Boolean = { false },
    ): Boolean = canRestore(
        SyncRevision(
            id = ClipboardOplog.revisionId(op),
            syncId = op.syncId,
            ts = op.ts,
            deviceId = op.deviceId,
            opType = op.opType,
            entry = op.entry,
            group = op.group,
        ),
        imageExists,
    )
}
