package org.fcitx.fcitx5.android.data.clipboard

/** 纯 Kotlin 的同步裁决规则：可在 JVM fake 节点中验证乱序、并发和墓碑。 */
object ClipboardSyncRules {
    fun requiresExchange(local: String, acknowledgedLocal: String, remote: String, acknowledgedRemote: String): Boolean =
        local != acknowledgedLocal || acknowledgedRemote.isEmpty() || remote != acknowledgedRemote

    fun canRepairMissingImage(comparison: Int, deleted: Boolean, isImage: Boolean, localFilePresent: Boolean, payloadPresent: Boolean): Boolean =
        comparison == 0 && !deleted && isImage && !localFilePresent && payloadPresent

    data class Revision<T>(val key: String, val value: T, val time: Long, val device: String, val deleted: Boolean = false)
    data class State<T>(val winner: Revision<T>?, val history: List<Revision<T>> = emptyList())

    fun compareVersion(at: Long, ad: String, av: String, bt: Long, bd: String, bv: String): Int = when {
        at != bt -> at.compareTo(bt)
        ad != bd -> ad.compareTo(bd)
        else -> av.compareTo(bv)
    }
    fun <T> compare(a: Revision<T>, b: Revision<T>): Int =
        compareVersion(a.time, a.device, "${a.deleted}:${a.value}", b.time, b.device, "${b.deleted}:${b.value}")

    /** 先比较逻辑时间、再比较 deviceId；被拒绝版本进入历史且不会复活。 */
    fun <T> merge(state: State<T>, incoming: Revision<T>): State<T> {
        val winner = state.winner
        return if (winner == null || compare(incoming, winner) > 0) {
            State(incoming, (state.history + listOfNotNull(winner).filter { it.value != incoming.value || it.deleted != incoming.deleted }).distinct())
        } else if (winner.value != incoming.value || winner.deleted != incoming.deleted) {
            State(winner, (state.history + incoming).distinct())
        } else state
    }

    fun <T> mergeAll(revisions: Iterable<Revision<T>>): State<T> = revisions.sortedWith { a, b -> compare(a, b) }.fold(State(null), ::merge)
}
