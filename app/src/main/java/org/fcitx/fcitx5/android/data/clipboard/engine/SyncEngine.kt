/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync core (io.localsync.core.SyncEngine).
 *
 * meng 改动（都写在注释里，便于以后与 localsync 对齐）：
 *  - [SyncCollection.versionWall]：本地操作的 HLC 以记录自带版本时间为 wall，与旧快照协议的「较新者胜」一致；
 *  - [SyncCollection.onMissing]：业务层自行保留期回收时，记录消失不广播删除；
 *  - [markShadow]：业务层批量落地失败后把记录退回影子状态，下一次扫描重试；
 *  - deviceId 必须由上层给定（meng 已有稳定设备 id）。
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SyncEngine(
    private val store: SyncStore,
    collections: List<SyncCollection>,
    deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    /** 远端 HLC 超前本机物理时间的上限，超过则整批拒收 */
    maxForwardDriftMs: Long = 24L * 3600 * 1000,
) {
    private val lock = ReentrantLock()
    private val collections = ConcurrentHashMap<String, SyncCollection>(collections.associateBy { it.name })
    /** 扫描按注册顺序（分组先于条目，条目落地时分组已存在）。 */
    private val order: List<String> = collections.map { it.name }
    private val dirty = HashMap<String, MutableSet<String>>()
    private val listeners = CopyOnWriteArrayList<ChangeListener>()

    val deviceId: String = deviceId.also { id ->
        require(id.isNotBlank() && '@' !in id) { "invalid device id" }
        store.transaction {
            val saved = store.getMeta(META_DEVICE_ID)
            require(saved == null || saved == id) { "engine store belongs to device $saved, not $id" }
            if (saved == null) store.setMeta(META_DEVICE_ID, id)
        }
    }

    private val clock = HlcClock(
        this.deviceId,
        store.getMeta(META_HLC)?.let { runCatching { Hlc.decode(it) }.getOrNull() },
        now,
        maxForwardDriftMs,
    )

    fun interface ChangeListener {
        fun onRemoteChanged(collection: String, keys: Set<String>)
    }

    fun addListener(l: ChangeListener) = listeners.add(l)

    fun collection(name: String): SyncCollection? = collections[name]

    fun markDirty(collection: String, keys: Collection<String>) = lock.withLock {
        dirty.getOrPut(collection) { HashSet() }.addAll(keys)
    }

    private fun policyOf(collection: String) = collections[collection]?.policy ?: ConflictPolicy.DELETE_WINS

    // ==================================================================== 本地扫描

    data class ScanResult(
        var created: Int = 0, var updated: Int = 0, var deleted: Int = 0, var materialized: Int = 0,
        var loaded: Int = 0, var hashed: Int = 0,
    ) {
        val opCount get() = created + updated + deleted
    }

    fun scan(only: Collection<String>? = null): ScanResult {
        val changed = HashMap<String, MutableSet<String>>()
        val result = lock.withLock {
            val result = ScanResult()
            val targets = (only?.let { o -> order.filter { it in o } } ?: order).mapNotNull { collections[it] }
            for (c in targets) {
                try {
                    scanCollection(c, result, changed)
                } catch (e: Exception) {
                    logger("scan ${c.name} failed", e)
                    throw e
                }
            }
            result
        }
        // 通知放在锁外：监听者回调可能重入引擎（查询/扫描），持锁回调会嵌套取锁。
        notify(changed)
        return result
    }

    private fun scanCollection(c: SyncCollection, r: ScanResult, changed: MutableMap<String, MutableSet<String>>) {
        val policy = c.policy
        val inc = c as? IncrementalCollection
        val forced = dirty.remove(c.name).orEmpty()
        val tokens: Map<String, String>? = inc?.tokens()
        store.transaction {
            val present: (String) -> Boolean
            val snapshot: MutableMap<String, Units>
            if (inc != null && tokens != null) {
                val digests0 = store.stateDigests(c.name)
                val need = HashSet<String>(forced.filter { it in tokens })
                for ((key, tok) in tokens) {
                    val d = digests0[key]
                    if (d == null || !d.alive || d.shadow || d.token != tok) need.add(key)
                }
                snapshot = HashMap(if (need.isEmpty()) emptyMap() else inc.load(need))
                r.loaded += snapshot.size
                val live = HashSet(tokens.keys)
                present = { it in live || it in snapshot }
            } else {
                snapshot = HashMap(c.snapshot())
                r.loaded += snapshot.size
                present = { it in snapshot }
            }
            val removedByShadow = HashSet<String>()

            // 1) 影子记录：把远端状态写入业务层
            for (key in store.shadowKeys(c.name)) {
                val st = store.getState(c.name, key) ?: continue
                val alive = st.isAlive(policy)
                if (alive) {
                    if (safeUpsert(c, key, st.values)) {
                        st.shadow = false
                        snapshot[key] = HashMap(st.values)
                        r.materialized++
                        changed.getOrPut(c.name) { HashSet() }.add(key)
                    }
                } else {
                    if (present(key)) {
                        safeDelete(c, key)
                        snapshot.remove(key)
                        removedByShadow.add(key)
                        r.materialized++
                        changed.getOrPut(c.name) { HashSet() }.add(key)
                    }
                    st.shadow = false
                }
                store.putState(st, alive, null)
            }

            val digests = store.stateDigests(c.name)

            // 2) 新增 / 修改
            for ((key, units) in snapshot) {
                val tok = tokens?.get(key)
                val d = digests[key]
                r.hashed++
                if (d != null && d.alive && !d.shadow && d.hash == EngineJson.hash(units)) {
                    if (tok != d.token) store.getState(c.name, key)?.let { store.putState(it, true, tok) }
                    continue
                }
                val st = store.getState(c.name, key) ?: RecordState(c.name, key)
                if (st.shadow) continue
                val op: SyncOp
                if (!st.isAlive(policy)) {
                    val all = LinkedHashMap<String, JsonElement>(units)
                    st.values.keys.forEach { if (it !in all) all[it] = JsonNull }
                    op = newOp(c, st, OpType.UPSERT, true, all)
                    r.created++
                } else {
                    val diff = diff(units, st.values)
                    if (diff.isEmpty()) {
                        store.putState(st, true, tok)
                        continue
                    }
                    op = newOp(c, st, OpType.UPSERT, false, diff)
                    r.updated++
                }
                merge(st, op)
                commitLocal(st, op, policy, tok)
            }

            // 3) 删除
            for ((key, d) in digests) {
                if (present(key) || key in removedByShadow || !d.alive || d.shadow) continue
                val st = store.getState(c.name, key) ?: continue
                if (c.onMissing(key, st.values) == MissingPolicy.IGNORE) continue
                val op = newOp(c, st, OpType.DELETE, false, null)
                merge(st, op)
                commitLocal(st, op, policy, null)
                r.deleted++
            }
        }
    }

    private fun diff(now: Units, old: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for (u in now.keys + old.keys) {
            val a = now[u]
            val b = old[u]
            if (!EngineJson.same(a, b)) out[u] = a ?: JsonNull
        }
        return out
    }

    private fun newOp(c: SyncCollection, st: RecordState, type: OpType, create: Boolean, units: Map<String, JsonElement>?): SyncOp {
        val wall = if (type == OpType.UPSERT && units != null) c.versionWall(units)?.takeIf { it >= 0 } else null
        val hlc = if (wall == null) clock.tick() else {
            // 以记录版本时间为 wall；本地变更必须压过本记录已有的任何时钟，否则会被自己的旧状态吞掉。
            val floor = st.maxClock()?.let { runCatching { Hlc.decode(it) }.getOrNull() }
            val stamped = Hlc(wall, 0, deviceId)
            val h = if (floor != null && stamped <= floor) floor.successor(deviceId) else stamped
            clock.receive(h)
            h
        }
        store.setMeta(META_HLC, clock.current().encode())
        return SyncOp(deviceId, nextSeq(), c.name, st.key, hlc.encode(), type, create, units)
    }

    private fun nextSeq(): Long {
        val cur = maxOf(store.getMeta(META_SEQ)?.toLongOrNull() ?: 0L, store.versionVector()[deviceId] ?: 0L)
        val next = cur + 1
        store.setMeta(META_SEQ, next.toString())
        return next
    }

    private fun commitLocal(st: RecordState, op: SyncOp, policy: ConflictPolicy, token: String?) {
        st.shadow = false
        store.putState(st, st.isAlive(policy), token)
        store.appendOp(op)
        store.setVersion(op.origin, op.seq)
        compactKey(st, policy)
    }

    // ==================================================================== 合并

    private fun merge(st: RecordState, op: SyncOp): Boolean {
        var changed = false
        when (op.type) {
            OpType.UPSERT -> {
                op.units?.forEach { (u, v) ->
                    if (Hlc.cmp(op.hlc, st.clocks[u]) > 0) {
                        st.clocks[u] = op.hlc
                        val old = st.values[u]
                        if (v is JsonNull) st.values.remove(u) else st.values[u] = v
                        if (!EngineJson.same(old, v)) changed = true
                    }
                }
                if (op.create && Hlc.cmp(op.hlc, st.born) > 0) st.born = op.hlc
            }
            OpType.DELETE -> if (Hlc.cmp(op.hlc, st.deleted) > 0) st.deleted = op.hlc
        }
        return changed
    }

    /** 压缩：删除被完全覆盖的旧操作（覆盖它的操作一定还在日志里，版本向量空洞是安全的）。 */
    private fun compactKey(st: RecordState, policy: ConflictPolicy) {
        val deleteWins = policy == ConflictPolicy.DELETE_WINS
        for (op in store.opsForKey(st.collection, st.key)) {
            val superseded = when (op.type) {
                OpType.DELETE -> Hlc.cmp(st.deleted, op.hlc) > 0
                OpType.UPSERT -> {
                    val unitsNewer = op.units.orEmpty().keys.all { Hlc.cmp(st.clocks[it], op.hlc) > 0 }
                    val killedByDelete = deleteWins && Hlc.cmp(st.deleted, op.hlc) > 0
                    if (op.create) (unitsNewer && Hlc.cmp(st.born, op.hlc) > 0) || killedByDelete
                    else unitsNewer || killedByDelete
                }
            }
            if (superseded) store.deleteOp(op.origin, op.seq)
        }
    }

    data class ApplyResult(var applied: Int = 0, var skipped: Int = 0, var rejected: Int = 0)

    /**
     * 应用远端操作（幂等）。先整批校验：任何一条 HLC 无法解析、标识非法或超前本机过多，
     * 整批拒收、一条都不合并，版本向量不推进。
     */
    fun applyRemote(ops: List<SyncOp>): ApplyResult {
        val result = ApplyResult()
        val changed = HashMap<String, MutableSet<String>>()
        lock.withLock {
            val decoded = ops.map { op ->
                val h = runCatching { Hlc.decode(op.hlc) }.getOrNull() ?: throw BadOpException("bad op hlc from ${op.origin}")
                if (op.origin.isEmpty() || op.seq <= 0 || op.collection.isEmpty() || op.key.isEmpty()) throw BadOpException("bad op id ${op.origin}/${op.seq}")
                if (h.node != op.origin) throw BadOpException("hlc node does not match origin ${op.origin}")
                if (op.type == OpType.UPSERT && op.units == null) throw BadOpException("upsert without units")
                if (clock.isTooFarAhead(h)) throw ClockDriftException(op.origin, op.hlc)
                h
            }
            store.transaction {
            for ((i, op) in ops.withIndex()) {
                val known = store.versionVector()[op.origin] ?: 0L
                if (op.origin == deviceId || op.seq <= known || store.hasOp(op.origin, op.seq)) {
                    result.skipped++
                    continue
                }
                if (!clock.receive(decoded[i])) throw ClockDriftException(op.origin, op.hlc)
                val policy = policyOf(op.collection)
                val st = store.getState(op.collection, op.key) ?: RecordState(op.collection, op.key)
                val wasAlive = st.isAlive(policy)
                val valuesChanged = merge(st, op)
                val nowAlive = st.isAlive(policy)
                val coll = collections[op.collection]
                val needsWrite = nowAlive && (valuesChanged || !wasAlive || st.shadow)
                val needsDelete = !nowAlive && (wasAlive || st.shadow)
                if (coll != null) {
                    if (needsWrite) {
                        val ok = safeUpsert(coll, op.key, st.values)
                        st.shadow = !ok
                        if (ok) changed.getOrPut(op.collection) { HashSet() }.add(op.key) else result.rejected++
                    } else if (needsDelete) {
                        safeDelete(coll, op.key)
                        st.shadow = false
                        changed.getOrPut(op.collection) { HashSet() }.add(op.key)
                    }
                } else if (needsWrite || needsDelete) {
                    st.shadow = true // 未知集合：只记录并照常转发
                }
                store.putState(st, nowAlive, null)
                store.appendOp(op)
                store.setVersion(op.origin, op.seq)
                compactKey(st, policy)
                result.applied++
            }
            store.setMeta(META_HLC, clock.current().encode())
            }
        }
        // 通知放在锁外：监听者回调可能重入引擎（查询/扫描），持锁回调会嵌套取锁。
        notify(changed)
        return result
    }

    /** meng 扩展：业务层落地失败，把这些记录退回影子，下一次扫描重试且不会被当成本地修改。 */
    fun markShadow(collection: String, keys: Collection<String>) = lock.withLock {
        if (keys.isEmpty()) return@withLock
        val policy = policyOf(collection)
        store.transaction {
            keys.forEach { key ->
                val st = store.getState(collection, key) ?: return@forEach
                st.shadow = true
                store.putState(st, st.isAlive(policy), null)
            }
        }
    }

    fun shadowValues(collection: String): Map<String, Units> = lock.withLock {
        store.shadowKeys(collection).mapNotNull { k -> store.getState(collection, k)?.let { k to it.values.toMap() } }.toMap()
    }

    // ==================================================================== 版本向量 / 差量

    fun versionVector(): VersionVector = lock.withLock { store.versionVector() }

    data class OpsPage(val ops: List<SyncOp>, val hasMore: Boolean, val vv: VersionVector)

    fun opsFor(remoteVv: VersionVector, limit: Int = 300): OpsPage = lock.withLock {
        val list = store.opsAfter(remoteVv, limit + 1)
        val hasMore = list.size > limit
        OpsPage(if (hasMore) list.subList(0, limit) else list, hasMore, store.versionVector())
    }

    // ==================================================================== 对端确认 / 墓碑回收

    /** 记录对端报告的版本向量，只增不减；只用于墓碑回收，绝不推进本机版本向量。 */
    fun recordPeerAck(peer: String, vv: VersionVector) = lock.withLock {
        if (peer == deviceId || peer.isBlank()) return@withLock
        store.transaction {
            val merged = HashMap(store.peerAcks()[peer]?.vv.orEmpty())
            vv.forEach { (o, s) -> if (s > (merged[o] ?: 0L)) merged[o] = s }
            store.setPeerAck(peer, merged, now())
        }
    }

    fun mergeAckGossip(acks: Map<String, Map<String, Long>>) {
        acks.forEach { (peer, vv) -> recordPeerAck(peer, vv) }
    }

    fun ackGossip(): Map<String, Map<String, Long>> = lock.withLock {
        val out = HashMap<String, Map<String, Long>>()
        store.peerAcks().forEach { (p, a) -> out[p] = a.vv }
        out[deviceId] = store.versionVector()
        out
    }

    fun forgetPeer(peer: String) = lock.withLock { store.removePeerAck(peer) }

    data class GcResult(var purged: Int = 0, var waiting: Int = 0)

    /** 墓碑：删除够久、所有需要确认的设备都确认收到、且本机已拥有它们确认时刻之前的全部操作，才回收。 */
    fun collectGarbage(minAgeMs: Long = 30L * 24 * 3600 * 1000, knownPeers: Set<String> = emptySet()): GcResult = lock.withLock {
        val r = GcResult()
        store.transaction {
            val acks = store.peerAcks()
            val myVv = store.versionVector()
            val required = (knownPeers + acks.keys + myVv.keys) - deviceId
            val cutoff = now() - minAgeMs
            for ((cName, key) in store.tombstones()) {
                val st = store.getState(cName, key) ?: continue
                val del = st.deleted ?: continue
                if (st.isAlive(policyOf(cName))) continue
                if (Hlc.decode(del).wall > cutoff) { r.waiting++; continue }
                val ops = store.opsForKey(cName, key)
                val delOp = ops.firstOrNull { it.type == OpType.DELETE && it.hlc == del }
                if (delOp == null) { r.waiting++; continue }
                val ok = required.all { p ->
                    val a = acks[p]?.vv ?: return@all false
                    (a[delOp.origin] ?: 0L) >= delOp.seq && (myVv[p] ?: 0L) >= (a[p] ?: 0L)
                }
                if (!ok) { r.waiting++; continue }
                ops.forEach { store.deleteOp(it.origin, it.seq) }
                store.removeState(cName, key)
                r.purged++
            }
        }
        r
    }

    fun opCount(): Long = store.opCount()

    private fun safeUpsert(c: SyncCollection, key: String, values: Map<String, JsonElement>): Boolean =
        try { c.upsert(key, HashMap(values)) } catch (e: Exception) { logger("upsert ${c.name} failed", e); false }

    private fun safeDelete(c: SyncCollection, key: String) {
        try { c.delete(key) } catch (e: Exception) { logger("delete ${c.name} failed", e) }
    }

    private fun notify(changed: Map<String, Set<String>>) {
        for ((c, keys) in changed) for (l in listeners) runCatching { l.onRemoteChanged(c, keys) }.onFailure { logger("listener failed", it) }
    }

    companion object {
        const val META_DEVICE_ID = "deviceId"
        const val META_SEQ = "seq"
        const val META_HLC = "hlc"
    }
}

class ClockDriftException(val origin: String, val hlc: String) :
    Exception("clock drift: op from $origin is too far ahead of local time")

class BadOpException(msg: String) : Exception(msg)
