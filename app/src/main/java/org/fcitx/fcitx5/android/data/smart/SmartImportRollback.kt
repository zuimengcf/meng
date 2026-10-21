/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart

internal object SmartImportRollback {
    suspend fun run(publish: suspend () -> Unit, complete: () -> Unit, rollback: suspend () -> Unit) {
        try {
            publish()
            complete()
        } catch (error: Throwable) {
            try { rollback() } catch (recovery: Throwable) { error.addSuppressed(recovery) }
            throw error
        }
    }
}
