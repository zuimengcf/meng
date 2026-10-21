/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

/** Both logical timestamp and writer identify a mutation; timestamp alone can collide across peers. */
internal object ClipboardReceiptVersion {
    fun token(timestamp: Long, writer: String): String = "$timestamp:$writer"
    fun peersForVersion(oldVersion: String?, oldPeers: Set<String>, newVersion: String, peer: String): Set<String> =
        (if (oldVersion == newVersion) oldPeers else emptySet()) + peer
}
