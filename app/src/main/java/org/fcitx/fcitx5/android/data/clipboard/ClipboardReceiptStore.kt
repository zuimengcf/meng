/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.utils.appContext

/** Authenticated peer-version evidence; never inferred from lastModifiedBy or a heartbeat. */
object ClipboardReceiptStore {
    private const val PREFIX = "receipt_"
    private const val REVISION = "revision"
    private val prefs by lazy { appContext.getSharedPreferences("clipboard_sync_receipts", 0) }

    @Synchronized
    fun record(peerDeviceId: String, versions: Map<String, String>) {
        if (!peerDeviceId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) || peerDeviceId == ClipboardOplog.deviceId) return
        val editor = prefs.edit()
        var changed = false
        versions.forEach { (syncId, version) ->
            if (syncId.isBlank() || version.length > 180 || version.contains('|')) return@forEach
            val timestamp = version.substringBefore(':').toLongOrNull() ?: return@forEach
            if (timestamp < 0L) return@forEach
            val old = prefs.getString(PREFIX + syncId, null)?.split('|', limit = 2)
            val oldVersion = old?.firstOrNull()
            val oldTime = oldVersion?.substringBefore(':')?.toLongOrNull() ?: Long.MIN_VALUE
            if (timestamp < oldTime) return@forEach
            val peers = ClipboardReceiptVersion.peersForVersion(oldVersion,
                old?.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }.toSet(), version, peerDeviceId)
            val encoded = "$version|${peers.sorted().joinToString(",")}"
            if (encoded != prefs.getString(PREFIX + syncId, null)) {
                editor.putString(PREFIX + syncId, encoded); changed = true
            }
        }
        if (changed) check(editor.putLong(REVISION, prefs.getLong(REVISION, 0L) + 1L).commit()) {
            "cannot persist peer-version receipt"
        }
    }

    val revision: Long get() = prefs.getLong(REVISION, 0L)

    @Synchronized
    fun getAll(syncId: String, currentVersion: String): Set<String> {
        val parts = prefs.getString(PREFIX + syncId, null)?.split('|', limit = 2) ?: return emptySet()
        return if (parts.firstOrNull() == currentVersion) parts.getOrNull(1).orEmpty().split(',')
            .filter { it.isNotBlank() }.toSet() else emptySet()
    }
}
