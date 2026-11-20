package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Conflated wakeups: a burst of edits yields one exchange, never one upload per keystroke. */
object ClipboardSyncSignal {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val counter = AtomicLong(0L)
    /** 与 [generation] 同步的可等待版本号：主机 wait/1 长轮询在这里挂起，不轮询数据库。 */
    private val generationFlow = MutableStateFlow(0L)

    /** Bumped on every durable local/remote change; keys the cached sync fingerprint. */
    val generation: Long get() = counter.get()
    val generations: StateFlow<Long> get() = generationFlow

    private fun bump() { generationFlow.value = counter.incrementAndGet() }

    /** Mark the collection dirty without waking the client (e.g. Room invalidation). */
    fun invalidate() { bump() }

    fun changed() {
        bump(); changes.trySend(Unit)
        // 本机是主机时：任何 durable 变更（含合并对端推送）都 NOTIFY 在线客户端
        // 立即来拉取，而不是等托底。组播+广播+已知 IP 单播三路兜底（带尾沿补发）。
        if (ClipboardSyncServer.isRunning) {
            ClipboardSyncServer.notifyOnlineClients()
        }
    }

    /** 只唤醒本机同步循环（收到主机 NOTIFY / 长轮询发现主机有变化），不把本机数据标脏。 */
    fun wake() { changes.trySend(Unit) }

    suspend fun awaitChangeOrHeartbeat(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { changes.receive() }
    }

    /** 等到 generation 离开 [since] 或超时；返回是否有变化。 */
    suspend fun awaitGenerationAfter(since: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { generationFlow.first { it != since }; true } ?: false
}
