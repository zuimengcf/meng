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

    fun changed() {
        counter.incrementAndGet(); changes.trySend(Unit)
        // 本机是主机时：任何 durable 变更（含合并对端推送）都 NOTIFY 在线客户端
        // 立即来拉取，而不是等 1 小时托底。组播+广播+已知 IP 单播三路兜底。
        if (ClipboardSyncServer.isRunning) {
            ClipboardSyncServer.notifyOnlineClients()
        }
    }
    suspend fun awaitChangeOrHeartbeat(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { changes.receive() }
    }
}
