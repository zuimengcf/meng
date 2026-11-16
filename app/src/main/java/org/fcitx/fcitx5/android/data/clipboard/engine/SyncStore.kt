/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync core (io.localsync.core.SyncStore).
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

/** 引擎自己的持久化（操作日志 + 合并状态 + 版本向量 + 对端确认 + 元数据），与业务库分离。 */
interface SyncStore {
    fun <T> transaction(block: () -> T): T

    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String?)

    fun versionVector(): Map<String, Long>
    fun setVersion(origin: String, seq: Long)

    fun setPeerAck(peer: String, vv: Map<String, Long>, at: Long)
    fun peerAcks(): Map<String, PeerAck>
    fun removePeerAck(peer: String)

    fun getState(collection: String, key: String): RecordState?
    fun putState(state: RecordState, alive: Boolean, token: String? = null)
    fun removeState(collection: String, key: String)
    fun stateDigests(collection: String): Map<String, StateDigest>
    fun shadowKeys(collection: String): List<String>
    fun tombstones(): List<Pair<String, String>>

    fun appendOp(op: SyncOp)
    fun hasOp(origin: String, seq: Long): Boolean
    fun opsForKey(collection: String, key: String): List<SyncOp>
    fun deleteOp(origin: String, seq: Long)
    /** seq > vv[origin]，按 (origin, seq) 升序，最多 limit 条 */
    fun opsAfter(vv: Map<String, Long>, limit: Int): List<SyncOp>
    fun opCount(): Long
}

data class PeerAck(val vv: Map<String, Long>, val at: Long)

data class StateDigest(val hash: String, val alive: Boolean, val shadow: Boolean, val token: String? = null)

/** 内存实现：单元测试用。 */
class InMemorySyncStore : SyncStore {
    private val lock = Any()
    private val meta = HashMap<String, String>()
    private val vv = HashMap<String, Long>()
    private val acks = HashMap<String, PeerAck>()
    private class Row(val st: RecordState, val alive: Boolean, val token: String?, val hash: String)
    private val states = HashMap<String, HashMap<String, Row>>()
    private val ops = java.util.TreeMap<Pair<String, Long>, SyncOp>(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second })
    private val byKey = HashMap<Pair<String, String>, MutableSet<Pair<String, Long>>>()

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }
    override fun getMeta(key: String): String? = synchronized(lock) { meta[key] }
    override fun setMeta(key: String, value: String?) { synchronized(lock) { if (value == null) meta.remove(key) else meta[key] = value } }
    override fun versionVector(): Map<String, Long> = synchronized(lock) { HashMap(vv) }
    override fun setVersion(origin: String, seq: Long) { synchronized(lock) { vv[origin] = seq } }
    override fun setPeerAck(peer: String, vv: Map<String, Long>, at: Long) { synchronized(lock) { acks[peer] = PeerAck(HashMap(vv), at) } }
    override fun peerAcks(): Map<String, PeerAck> = synchronized(lock) { HashMap(acks) }
    override fun removePeerAck(peer: String) { synchronized(lock) { acks.remove(peer) } }
    override fun getState(collection: String, key: String): RecordState? = synchronized(lock) { states[collection]?.get(key)?.st?.copyDeep() }
    override fun putState(state: RecordState, alive: Boolean, token: String?) {
        synchronized(lock) { states.getOrPut(state.collection) { HashMap() }[state.key] = Row(state.copyDeep(), alive, token, state.hash()) }
    }
    override fun removeState(collection: String, key: String) { synchronized(lock) { states[collection]?.remove(key) } }
    override fun stateDigests(collection: String): Map<String, StateDigest> = synchronized(lock) {
        states[collection].orEmpty().mapValues { (_, r) -> StateDigest(r.hash, r.alive, r.st.shadow, r.token) }
    }
    override fun shadowKeys(collection: String): List<String> = synchronized(lock) { states[collection].orEmpty().values.filter { it.st.shadow }.map { it.st.key } }
    override fun tombstones(): List<Pair<String, String>> = synchronized(lock) {
        states.values.flatMap { m -> m.values.filter { !it.alive && it.st.deleted != null && !it.st.shadow } }.map { it.st.collection to it.st.key }
    }
    override fun appendOp(op: SyncOp) {
        synchronized(lock) {
            if (ops.containsKey(op.origin to op.seq)) return
            ops[op.origin to op.seq] = op
            byKey.getOrPut(op.collection to op.key) { HashSet() }.add(op.origin to op.seq)
        }
    }
    override fun hasOp(origin: String, seq: Long): Boolean = synchronized(lock) { ops.containsKey(origin to seq) }
    override fun opsForKey(collection: String, key: String): List<SyncOp> = synchronized(lock) { byKey[collection to key].orEmpty().mapNotNull { ops[it] } }
    override fun deleteOp(origin: String, seq: Long) {
        synchronized(lock) { val op = ops.remove(origin to seq) ?: return; byKey[op.collection to op.key]?.remove(origin to seq) }
    }
    override fun opsAfter(vv: Map<String, Long>, limit: Int): List<SyncOp> = synchronized(lock) {
        ops.values.asSequence().filter { it.seq > (vv[it.origin] ?: 0L) }.take(limit).toList()
    }
    override fun opCount(): Long = synchronized(lock) { ops.size.toLong() }
}
