package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Conflated wakeups: a burst of edits yields one exchange, never one upload per keystroke. */
object ClipboardSyncSignal {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    fun changed() { changes.trySend(Unit) }
    suspend fun awaitChangeOrHeartbeat(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { changes.receive() }
    }
}
