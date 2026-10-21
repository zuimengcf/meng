/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Delivery and deletion use the same fence: a captured packet cannot outlive a hard delete. */
object ClipboardDelivery {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
    /** Fence capture through connection/exposure only. Never hold it while awaiting a peer's merge:
     * two devices hosting/joining each other (or loopback) must not deadlock each other's receivers. */
    suspend fun <T> withSendFence(block: suspend (release: () -> Unit) -> T): T {
        mutex.lock()
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) mutex.unlock() }
        return try { block(release) } finally { release() }
    }
    fun ids(packet: ClipboardSyncPacket): Set<String> =
        (packet.entries.map { it.syncId } + packet.history.filter { it.entry != null }.map { it.syncId })
            .filter { it.isNotBlank() }.toSet()

    /** This is NOT a successful sync. Persist uncertainty before potentially exposing bytes. */
    suspend fun beginExposure(packet: ClipboardSyncPacket) = ClipboardManager.withSyncLock {
        ClipboardManager.syncDatabase().withTransaction {
            val dao = ClipboardManager.syncDao()
            ids(packet).forEach { dao.markExposurePending(it) }
        }
    }

    /** Called only with an authenticated, request-bound durable receipt, never for heartbeat/write/timeout. */
    suspend fun confirm(ids: Collection<String>) = ClipboardManager.withSyncLock {
        ClipboardManager.syncDatabase().withTransaction {
            ids.filter { it.isNotBlank() }.distinct().forEach { ClipboardManager.syncDao().markDelivered(it) }
        }
    }

    suspend fun confirmVersions(received: Collection<String>, versions: Map<String, String>) = ClipboardManager.withSyncLock {
        ClipboardManager.syncDatabase().withTransaction {
            val dao = ClipboardManager.syncDao()
            received.filter { it.isNotBlank() }.distinct().forEach { id ->
                val row = dao.getBySyncId(id) ?: return@forEach
                val current = ClipboardReceiptVersion.token(row.updatedAt, row.lastModifiedBy.ifBlank { ClipboardOplog.deviceId })
                if (versions[id] == current) dao.markDelivered(id)
            }
        }
    }

    /** Records an authenticated peer receipt, bound to exact entry versions. */
    fun recordPeerReceipt(peerDeviceId: String, versions: Map<String, String>) {
        ClipboardReceiptStore.record(peerDeviceId, versions)
    }
}

object ClipboardDeletionPolicy {
    fun needsTombstone(hasBeenSynced: Boolean, exposurePending: Boolean): Boolean =
        hasBeenSynced || exposurePending

    /** Null means a provably unexposed row can be physically removed. Never strip shared payloads here. */
    internal fun retainedDeletion(
        old: org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry,
        syncId: String,
        now: Long,
        deviceId: String,
    ): org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry? =
        if (!needsTombstone(old.hasBeenSynced, old.syncExposurePending)) null
        else old.copy(syncId = syncId, deleted = true, updatedAt = now, lastModifiedBy = deviceId)
}
