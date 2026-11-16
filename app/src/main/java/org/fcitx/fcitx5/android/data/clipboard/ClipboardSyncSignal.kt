package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Conflated wakeups: a burst of edits yields one exchange, never one upload per keystroke. */
object ClipboardSyncSignal {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val counter = AtomicLong(0L)

    /** Bumped on every durable local/remote change; keys the cached sync fingerprint. */
    val generation: Long get() = counter.get()

    /** Mark the collection dirty without waking the client (e.g. Room invalidation). */
    fun invalidate() { counter.incrementAndGet() }

    fun changed() { counter.incrementAndGet(); changes.trySend(Unit) }
    suspend fun awaitChangeOrHeartbeat(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { changes.receive() }
    }
}
