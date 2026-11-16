package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.data.clipboard.engine.*
import org.junit.Assert.*
import org.junit.Test

/** 模拟 meng 设备：Room 表 = rows，适配器逻辑与 ClipboardEngineSync 相同（原子 unit、versionWall、IGNORE、缺图影子）。 */
class FakeDevice(val id: String, val clock: () -> Long) {
    val rows = LinkedHashMap<String, SyncEntry>()
    val blobs = HashSet<String>()
    var writes = 0
    private val pending = LinkedHashMap<String, SyncEntry>()
    val coll = object : IncrementalCollection {
        override val name = ClipboardEngineMapping.ENTRIES
        override val policy = ConflictPolicy.UPDATE_REVIVES
        override fun tokens() = rows.mapValues { it.value.hashCode().toString() }
        override fun load(keys: Collection<String>) = keys.mapNotNull { k -> rows[k]?.let { k to ClipboardEngineMapping.entryUnits(it.copy(imageSha256 = it.imageSha256?.takeIf { s -> s in blobs })) } }.toMap()
        override fun upsert(key: String, units: Units): Boolean {
            val e = ClipboardEngineMapping.entryFromUnits(key, units)
            if (e.imageSha256 != null && e.imageSha256 !in blobs) return false
            pending[key] = e; return true
        }
        override fun delete(key: String) = Unit
        override fun versionWall(units: Units) = ClipboardEngineMapping.versionWall(units)
        override fun onMissing(key: String, last: Units) = MissingPolicy.IGNORE
    }
    val store = InMemorySyncStore()
    val engine = SyncEngine(store, listOf(coll), id, clock, maxForwardDriftMs = ClipboardSyncRules.MAX_REMOTE_FUTURE_MS)
    /** 落地：对应 applyEngineUnlocked（已删除条目保留本地正文，与 Room 行为一致）。 */
    fun flush() { pending.forEach { (k, e) -> val old = rows[k]; rows[k] = if (e.deleted && old != null) e.copy(text = old.text) else e; writes++ }; pending.clear() }
    fun scan() { engine.scan(); flush() }
    fun apply(wire: List<ClipboardEngineWireOp>): SyncEngine.ApplyResult {
        val ops = wire.map(ClipboardEngineMapping::fromWire)
        scan()
        val r = engine.applyRemote(ops); flush(); return r
    }
    fun put(key: String, text: String, at: Long, deleted: Boolean = false, image: String? = null) {
        rows[key] = SyncEntry(key, text, false, 1, if (image != null) "image/png" else "text/plain", false, deleted, updatedAt = at, deviceId = id, imageSha256 = image)
    }
    fun visible() = rows.filterValues { !it.deleted }.mapValues { it.value.text }
}

private val wire = Json { ignoreUnknownKeys = true }
private fun roundTrip(ops: List<ClipboardEngineWireOp>): List<ClipboardEngineWireOp> =
    wire.decodeFromString(wire.encodeToString(ops))

/** 与 ClipboardSyncClient.engineSyncOnce 相同的会话：hello -> 分页 pull -> 分页 push（只按对端真实回应推进）。 */
fun session(client: FakeDevice, host: FakeDevice, page: Int = 200) {
    client.scan(); host.scan()
    host.engine.recordPeerAck(client.id, client.engine.versionVector())
    client.engine.recordPeerAck(host.id, host.engine.versionVector())
    while (true) {
        val p = host.engine.opsFor(client.engine.versionVector(), page)
        client.apply(roundTrip(p.ops.map(ClipboardEngineMapping::toWire)))
        if (!p.hasMore) break
    }
    var remoteVv = host.engine.versionVector()
    while (true) {
        val p = client.engine.opsFor(remoteVv, page)
        if (p.ops.isEmpty()) break
        host.apply(roundTrip(p.ops.map(ClipboardEngineMapping::toWire)))
        val vv = host.engine.versionVector()
        client.engine.recordPeerAck(host.id, vv)
        if (!p.hasMore) break
        assertNotEquals(remoteVv, vv); remoteVv = vv
    }
}

class ClipboardEngineTest {
    private var t = 1_700_000_000_000L
    private val clock = { t }

    @Test fun propagatesBothWaysThroughHost() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock); val b = FakeDevice("B", clock)
        a.put("1", "from a", t); b.put("2", "from b", t + 1)
        session(a, h); session(b, h); session(a, h)
        assertEquals(mapOf("1" to "from a", "2" to "from b"), a.visible())
        assertEquals(a.visible(), b.visible()); assertEquals(a.visible(), h.visible())
    }

    @Test fun newerUpdatedAtWinsLikeLegacyRule() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock); val b = FakeDevice("B", clock)
        a.put("k", "base", t); session(a, h); session(b, h)
        a.rows["k"] = a.rows["k"]!!.copy(text = "older edit", updatedAt = t + 10, deviceId = "A")
        b.rows["k"] = b.rows["k"]!!.copy(text = "newer edit", updatedAt = t + 20, deviceId = "B")
        session(b, h); session(a, h); session(b, h)
        listOf(a, b, h).forEach { assertEquals("newer edit", it.rows["k"]!!.text) }
    }

    @Test fun equalTimestampsConvergeDeterministically() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock); val b = FakeDevice("B", clock)
        a.put("k", "x", t); b.put("k", "y", t)
        session(a, h); session(b, h); session(a, h); session(b, h)
        assertEquals(a.rows["k"]!!.text, b.rows["k"]!!.text); assertEquals(a.rows["k"]!!.text, h.rows["k"]!!.text)
    }

    @Test fun bootstrapOfIdenticalDataWritesNothingAndCompacts() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        for (i in 0 until 50) { a.put("$i", "t$i", t + i); h.rows["$i"] = a.rows["$i"]!! }
        session(a, h)
        assertEquals(0, a.writes); assertEquals(0, h.writes)
        assertTrue("op log compacted: ${h.engine.opCount()}", h.engine.opCount() <= 50)
        // 再来一次：没有任何新操作
        val before = a.engine.opCount(); session(a, h); assertEquals(before, a.engine.opCount())
    }

    @Test fun pagingConverges() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock); val b = FakeDevice("B", clock)
        for (i in 0 until 450) a.put("a$i", "x$i", t + i)
        for (i in 0 until 330) b.put("b$i", "y$i", t + i)
        session(a, h, page = 200); session(b, h, page = 200); session(a, h, page = 200)
        assertEquals(780, a.visible().size); assertEquals(a.visible(), b.visible())
    }

    @Test fun clockDriftRejectsWholeBatch() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        a.put("ok", "fine", t); a.put("bad", "future", t + 400L * 24 * 3600 * 1000)
        a.scan()
        val ops = a.engine.opsFor(emptyMap()).ops.map(ClipboardEngineMapping::toWire)
        assertEquals(2, ops.size)
        try { h.apply(ops); fail("expected drift rejection") } catch (e: ClockDriftException) { }
        assertTrue(h.rows.isEmpty()); assertTrue(h.engine.versionVector().isEmpty())
    }

    @Test fun forgedHlcNodeIsRejected() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        a.put("k", "v", t); a.scan()
        val op = a.engine.opsFor(emptyMap()).ops.single().let(ClipboardEngineMapping::toWire)
        val forged = op.copy(hlc = op.hlc.substringBefore('@') + "@Z")
        try { h.apply(listOf(forged)); fail() } catch (e: BadOpException) { }
        assertTrue(h.rows.isEmpty())
    }

    @Test fun malformedUnitsRejected() {
        val bad = ClipboardEngineWireOp("A", 1, ClipboardEngineMapping.ENTRIES, "k", Hlc(t, 0, "A").encode(), "UPSERT", true,
            JsonObject(mapOf("v" to JsonObject(mapOf("text" to JsonPrimitive("x"))))))
        try { ClipboardEngineMapping.fromWire(bad); fail() } catch (e: Exception) { }
        val unknown = bad.copy(collection = "other")
        try { ClipboardEngineMapping.fromWire(unknown); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun missingImageStaysShadowUntilBlobArrives() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        val sha = "a".repeat(64)
        a.blobs += sha; a.put("img", "", t, image = sha)
        session(a, h)
        assertNull("host must not write an image row without bytes", h.rows["img"])
        assertEquals(listOf("img"), h.engine.shadowValues(ClipboardEngineMapping.ENTRIES).keys.toList())
        h.blobs += sha; h.scan()
        assertEquals(sha, h.rows["img"]!!.imageSha256)
        assertTrue(h.engine.shadowValues(ClipboardEngineMapping.ENTRIES).isEmpty())
        // 落地后再扫描不会产生回声操作
        val n = h.engine.opCount(); h.scan(); assertEquals(n, h.engine.opCount())
    }

    @Test fun physicalPurgeDoesNotPropagateDelete() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        a.put("k", "v", t); session(a, h)
        a.rows.remove("k"); session(a, h)
        assertEquals("v", h.rows["k"]!!.text)
    }

    @Test fun softDeleteSyncsWithoutContent() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        a.put("k", "secret", t); session(a, h)
        a.rows["k"] = a.rows["k"]!!.copy(deleted = true, updatedAt = t + 5)
        a.scan()
        val ops = a.engine.opsFor(h.engine.versionVector()).ops
        assertTrue(ops.none { it.units.toString().contains("secret") })
        session(a, h)
        assertTrue(h.rows["k"]!!.deleted)
        // 复活：更新的修改压过删除
        h.rows["k"] = h.rows["k"]!!.copy(deleted = false, text = "back", updatedAt = t + 9, deviceId = "H")
        session(a, h)
        assertEquals("back", a.rows["k"]!!.text); assertFalse(a.rows["k"]!!.deleted)
    }

    @Test fun localEditWithOlderStampStillWins() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        h.put("k", "host", t + 100); session(a, h)
        // 本机时钟落后：本地编辑的 updatedAt 比已有版本还早，仍然必须成为新版本
        a.rows["k"] = a.rows["k"]!!.copy(text = "mine", updatedAt = t + 50, deviceId = "A")
        session(a, h)
        assertEquals("mine", h.rows["k"]!!.text)
        val n = a.engine.opCount(); session(a, h); assertEquals(n, a.engine.opCount())
    }

    @Test fun pushNeverAbsorbsPeerVersionVector() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        h.put("h", "host only", t); h.scan()
        a.put("a", "client", t); a.scan()
        // 只推不拉
        h.apply(a.engine.opsFor(h.engine.versionVector()).ops.map(ClipboardEngineMapping::toWire))
        a.engine.recordPeerAck("H", h.engine.versionVector())
        assertNull(a.engine.versionVector()["H"])
    }

    @Test fun replayIsIdempotent() {
        val a = FakeDevice("A", clock); val h = FakeDevice("H", clock)
        a.put("k", "v", t); a.scan()
        val ops = a.engine.opsFor(emptyMap()).ops.map(ClipboardEngineMapping::toWire)
        assertEquals(1, h.apply(ops).applied); assertEquals(0, h.apply(ops).applied)
        assertEquals(1, h.writes)
    }

    @Test fun tombstoneGcWaitsForAllPeers() {
        // 通用引擎：PROPAGATE_DELETE 集合的墓碑只在所有设备确认后回收
        class Mem(val name0: String) : SyncCollection {
            val m = HashMap<String, Units>()
            override val name = name0
            override fun snapshot() = HashMap(m)
            override fun upsert(key: String, units: Units): Boolean { m[key] = units; return true }
            override fun delete(key: String) { m.remove(key) }
        }
        val ca = Mem("c"); val cb = Mem("c")
        val a = SyncEngine(InMemorySyncStore(), listOf(ca), "A", clock)
        val b = SyncEngine(InMemorySyncStore(), listOf(cb), "B", clock)
        ca.m["k"] = mapOf("x" to JsonPrimitive(1)); a.scan()
        b.applyRemote(a.opsFor(b.versionVector()).ops)
        ca.m.remove("k"); a.scan()
        t += 40L * 24 * 3600 * 1000
        assertEquals(0, a.collectGarbage(knownPeers = setOf("B")).purged)
        b.applyRemote(a.opsFor(b.versionVector()).ops)
        a.recordPeerAck("B", b.versionVector())
        assertEquals(1, a.collectGarbage(knownPeers = setOf("B")).purged)
        assertEquals(0L, a.opCount())
    }

    @Test fun capabilityNegotiation() {
        assertTrue(ClipboardSyncCapabilities.engineMode(listOf("blob/1", "engine/1")))
        assertFalse(ClipboardSyncCapabilities.engineMode(listOf("engine/1")))
        assertFalse(ClipboardSyncCapabilities.engineMode(listOf("blob/1")))
        assertFalse(ClipboardSyncCapabilities.engineMode(emptyList()))
    }

    @Test fun engineFieldsAreNotEncodedForLegacyRequests() {
        val s = wire.encodeToString(ClipboardSyncWireRequest("sync", "c", "h", "{}"))
        assertFalse(s.contains("\"vv\"") || s.contains("\"ops\"") || s.contains("\"acks\"") || s.contains("\"limit\""))
        val r = wire.encodeToString(ClipboardSyncWireResponse(true, "r", "heartbeat", "h"))
        assertFalse(r.contains("hasMore") || r.contains("missingBlobs") || r.contains("\"ops\""))
    }

    @Test fun hlcSuccessorSaturates() {
        val h = Hlc(Long.MAX_VALUE, Int.MAX_VALUE, "A").successor("A")
        assertEquals(Long.MAX_VALUE, h.wall)
        assertEquals(Hlc(5, 7, "B"), Hlc.decode(Hlc(5, 7, "B").encode()))
    }
}
