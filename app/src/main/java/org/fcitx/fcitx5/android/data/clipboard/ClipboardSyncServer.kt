package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.mcp.McpServer
import timber.log.Timber
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 房间服务端协议 v2：只接受 /sync 上的 AES-GCM 加密信封。 */
object ClipboardSyncServer {
    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var socket: ServerSocket? = null
    private var serverPort = 0
    @Volatile private var password = ""
    private const val TTL = 15_000L
    private const val MAX_HEADER = 16 * 1024
    private const val MAX_BODY = 128 * 1024 * 1024
    private const val HEADER_TIMEOUT_MS = 10_000
    // Request body upload (incl. inline image base64) can be large; match the client's 180s read
    // timeout and the 300s connection lifetime so a slow upload is never cut mid-envelope.
    private const val BODY_TIMEOUT_MS = 180_000
    private val online = ConcurrentHashMap<String, Long>()
    private val requestIds = ConcurrentHashMap<String, Long>()
    /** 主机 UI 请求的下一次强制同步标记：每个在线客户端下次心跳时回带并清除。 */
    private val kickPending = ConcurrentHashMap.newKeySet<String>()
    /** 主机 UI：请求所有在线客户端立刻执行一次完整同步。 */
    fun requestKickAll() { online.keys.forEach { kickPending.add(it) } }
    private data class OfferedDelivery(val ids: Set<String>, val versions: Map<String, String>)
    private val offeredDeliveries = ConcurrentHashMap<String, OfferedDelivery>()
    /** blob/1：每个客户端只能拉取主机最近一次发给它的快照里出现过的图片。 */
    private data class OfferedBlobs(val sources: Map<String, ClipboardImageBlobs.Source>, val at: Long)
    private val offeredBlobs = ConcurrentHashMap<String, OfferedBlobs>()
    private const val BLOB_OFFER_TTL = 10 * 60_000L
    /** engine/1：已发给某客户端、等它在下一次请求的版本向量里确认的操作 (origin, seq, 条目 id)。 */
    private data class EngineOffer(val origin: String, val seq: Long, val key: String)
    private val engineOffers = ConcurrentHashMap<String, MutableList<EngineOffer>>()
    private val lock = Mutex()
    private val clients = Semaphore(8)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val bodyBudget = ClipboardRequestBudget(MAX_BODY.toLong())

    val isRunning get() = running.get()
    val port get() = serverPort
    /** 优先 Wi-Fi/以太网上的私网地址，避免把蜂窝或 VPN 地址展示给同局域网设备。 */
    val localAddress: String get() {
        val candidates = NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { nic.name to it } }
        fun lanNic(name: String) = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("swlan")
        return (candidates.firstOrNull { (n, a) -> lanNic(n) && a.isSiteLocalAddress }
            ?: candidates.firstOrNull { (_, a) -> a.isSiteLocalAddress }
            ?: candidates.firstOrNull())?.second?.hostAddress ?: "127.0.0.1"
    }
    val passwordHint get() = password
    private fun purge() { val now = System.currentTimeMillis(); online.entries.removeIf { now - it.value > TTL }; requestIds.entries.removeIf { now - it.value > 2 * ClipboardSyncCrypto.CLOCK_SKEW_MS + 60_000L } }
    /** 仅统计远端客户端；主机自身通过 online response 的 host id 返回给客户端。 */
    val onlineClientIds: Set<String> get() { purge(); return online.keys.toSet() - ClipboardOplog.deviceId }
    val onlineClientCount: Int get() = onlineClientIds.size

    suspend fun start(port: Int, roomPassword: String, onClientSynced: (Int) -> Unit): Int {
        require(roomPassword.isNotEmpty()) { "房间密码不能为空" }
        ClipboardManager.enableSyncRetention()
        if (running.get()) return serverPort
        return withContext(Dispatchers.IO) {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 8) }
                socket = ss; serverPort = ss.localPort; password = roomPassword; running.set(true)
                ClipboardSyncDiscovery.startDiscovery(serverPort)
                acceptJob = scope.launch {
                    while (isActive && running.get() && socket === ss) {
                        try {
                            val client = ss.accept()
                            if (clients.tryAcquire()) {
                                activeSockets.add(client)
                                launch { try { handle(client, onClientSynced) } finally { activeSockets.remove(client); runCatching { client.close() }; clients.release() } }
                            } else client.close()
                        }
                        catch (e: java.net.SocketException) { if (running.get()) Timber.w(e, "clipboard accept failed") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (running.get()) Timber.w(e, "clipboard accept failed") }
                    }
                }
                serverPort
            } catch (e: Exception) { Timber.w(e, "clipboard server start failed"); 0 }
        }
    }

    fun stop() {
        running.set(false); acceptJob?.cancel(); acceptJob = null
        runCatching { socket?.close() }; socket = null; serverPort = 0; password = ""
        activeSockets.forEach { runCatching { it.close() } }; activeSockets.clear()
        ClipboardSyncDiscovery.stopDiscovery(); online.clear(); requestIds.clear(); offeredDeliveries.clear(); offeredBlobs.clear(); engineOffers.clear(); kickPending.clear()
    }

    private suspend fun handle(client: Socket, onClientSynced: (Int) -> Unit) {
        client.use { s ->
            var reservedBytes = 0L
            // SO_TIMEOUT does not bound writes: also close clients that stop reading a response.
            // Keep the guard generous: large image-backed snapshots can legitimately take minutes
            // to upload/download on Wi-Fi, and killing the socket mid-write truncates the GCM
            // envelope, surfacing on the client as a BAD_DECRYPT (1e000065) corruption error.
            val lifetime = scope.launch { delay(300_000L); runCatching { s.close() } }
            try {
                s.soTimeout = HEADER_TIMEOUT_MS
                val headerDeadline = System.nanoTime() + HEADER_TIMEOUT_MS * 1_000_000L
                val input = s.getInputStream()
                val headers = ByteArrayOutputStreamLimited(MAX_HEADER)
                var state = 0
                while (true) {
                    setRemainingTimeout(s, headerDeadline)
                    val b = input.read()
                    if (b < 0) throw IllegalArgumentException("header eof")
                    headers.write(b)
                    state = if ((state == 0 && b == 13) || (state == 1 && b == 10) || (state == 2 && b == 13) || (state == 3 && b == 10)) state + 1 else if (b == 13) 1 else 0
                    if (state == 4) break
                }
                val lines = headers.toString(Charsets.UTF_8.name()).split("\r\n")
                val first = lines.firstOrNull()?.split(" ") ?: emptyList()
                if (first.size < 2 || first[0] != "POST" || first[1] != "/sync") {
                    writeResponse(s, if (first.getOrNull(1) == "/mcp") 426 else 403, "仅允许加密 POST /sync 请求".toByteArray()); return
                }
                val fields = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
                val len = fields["content-length"]?.toLongOrNull()
                if (len == null || len !in 1..MAX_BODY) { writeResponse(s, 413, "请求体过大或缺少长度".toByteArray()); return }
                val requestId = fields["x-clipboard-sync-request"]
                val proof = fields["x-clipboard-sync-proof"]
                // Never accept a raw token over the room's HTTP transport.
                if (fields.containsKey("authorization")) throw SecurityException("plaintext credential header disabled")
                val requestScope = fields["x-clipboard-sync-scope"] ?: "sync"
                val controlToken = if (requestScope == "mcp" && McpServer.enabled) ClipboardSyncSettings.getMcpToken() else null
                val issuedAt = fields["x-clipboard-sync-issued-at"]?.toLongOrNull() ?: 0L
                val grant = ClipboardRequestAuth.authorize(password, requestId, proof, requestScope, controlToken, issuedAt)
                if (grant == null) { writeResponse(s, 403, "认证失败".toByteArray()); return }
                purge()
                if (requestIds.size >= 4096) { writeResponse(s, 503, "请求过于频繁，请稍后重试".toByteArray()); return }
                if (requestIds.putIfAbsent(checkNotNull(requestId), System.currentTimeMillis()) != null) throw SecurityException("replay")
                if (!bodyBudget.reserve(len)) { writeResponse(s, 503, "同步繁忙，请稍后重试".toByteArray()); return }
                reservedBytes = len
                s.soTimeout = BODY_TIMEOUT_MS
                val body = readExact(s, input, len.toInt())
                val env = json.decodeFromString<ClipboardSyncEnvelope>(String(body, Charsets.UTF_8))
                if (env.protocolVersion != ClipboardSyncCrypto.PROTOCOL_VERSION || env.direction != ClipboardSyncCrypto.REQUEST_DIRECTION || env.clientId.isBlank() || env.requestId.isBlank() || env.requestId != requestId || env.issuedAt != issuedAt) throw SecurityException("invalid authenticated envelope")
                val controlAuthorized = grant == ClipboardRequestAuth.Grant.CONTROL
                val credential = if (controlAuthorized) checkNotNull(controlToken) else password
                val req = json.decodeFromString<ClipboardSyncWireRequest>(ClipboardSyncCrypto.decrypt(credential, env, ClipboardSyncCrypto.REQUEST_DIRECTION))
                if (req.clientId.isBlank() || req.clientId != env.clientId) throw SecurityException("client binding failed")
                if (controlAuthorized && req.kind != "mcp") throw SecurityException("MCP token cannot authorize sync")
                if (!controlAuthorized) online[req.clientId] = System.currentTimeMillis()
                purge()
                // Heartbeats only read the (cached) fingerprint and online list: never queue them
                // behind a long merge, or every client times out and reports a disconnect.
                val result = if (req.kind == "heartbeat" || req.kind == "blob_get") process(req, env.requestId, onClientSynced, controlAuthorized)
                    else lock.withLock { process(req, env.requestId, onClientSynced, controlAuthorized) }
                val outEnv = ClipboardSyncCrypto.encrypt(credential, env.requestId, env.clientId, json.encodeToString(result), ClipboardSyncCrypto.RESPONSE_DIRECTION)
                writeResponse(s, 200, json.encodeToString(outEnv).toByteArray())
            } catch (e: SerializationException) {
                Timber.w("legacy or malformed clipboard request rejected (%s)", e.javaClass.simpleName)
                runCatching { writeResponse(s, 426, "旧版明文请求已拒绝，请使用协议 v2 加密信封".toByteArray()) }
            } catch (e: Exception) {
                Timber.w("clipboard request rejected (%s)", e.javaClass.simpleName)
                runCatching { writeResponse(s, if (e is SecurityException) 403 else 400, "同步请求被拒绝，请检查密码、协议版本、存储空间与数据完整性".toByteArray()) }
            } finally {
                lifetime.cancel()
                if (reservedBytes > 0) bodyBudget.release(reservedBytes)
            }
        }
    }

    private suspend fun process(req: ClipboardSyncWireRequest, requestId: String, onClientSynced: (Int) -> Unit, mcpAuthorized: Boolean): ClipboardSyncWireResponse {
        if (req.kind == "mcp") {
            if (!mcpAuthorized || !McpServer.enabled) return ClipboardSyncWireResponse(false, requestId, "mcp", error = "MCP 未配置独立令牌或服务未启用")
            return ClipboardSyncWireResponse(true, requestId, "mcp", packetJson = McpServer.handle(req.packetJson ?: ""))
        }
        if (req.kind == "heartbeat") {
            val kicked = req.clientId.let { id -> id != ClipboardOplog.deviceId && kickPending.remove(id) }
            val caps: List<String> = if (req.capabilities.isEmpty()) emptyList()
                else {
                    val negotiated = ClipboardSyncCapabilities.negotiated(req.capabilities).toMutableSet()
                    if (kicked) negotiated += ClipboardSyncCapabilities.KICK_V1
                    negotiated.toList()
                }
            return ClipboardSyncWireResponse(true, requestId, "heartbeat", ClipboardSnapshotStore.fingerprint(), onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                // Only answer capabilities to a client that announced some: an old client's response stays byte-identical.
                capabilities = caps)
        }
        if (req.kind == "blob_get") {
            require(!mcpAuthorized) { "MCP token cannot read clipboard images" }
            val sha = req.blobHash
            require(ClipboardImageBlobs.isSha256(sha)) { "无效图片哈希" }
            // 原子「读 + TTL 清理」：与 engine_pull 写入端共用同一键级原子性，过期项顺带移除，
            // 避免「读到过期 offer 的同时写入端刚重建」的时序裂缝（修复：blob_get 不再无锁裸读）。
            val offer = offeredBlobs.compute(req.clientId) { _, old ->
                if (old != null && System.currentTimeMillis() - old.at <= BLOB_OFFER_TTL) old else null
            }
            // engine/1：上一轮会话里没拉完的图片，只要仍被本机未删除的条目引用就可以补拉（已删除条目的图片永不外发）。
            val source = offer?.sources?.get(sha)
                ?: (if (ClipboardSyncCapabilities.engineMode(req.capabilities)) ClipboardEngineSync.liveImage(sha!!)?.let { ClipboardImageBlobs.Source.FromFile(it) } else null)
                ?: return ClipboardSyncWireResponse(false, requestId, "blob_get", error = "图片不在本次同步内容中")
            val data = ClipboardImageBlobs.readBase64(source)
                ?.takeIf { ClipboardImageBlobs.sha256OfBase64(it) == sha }
                ?: return ClipboardSyncWireResponse(false, requestId, "blob_get", error = "主机上的图片已变化或缺失")
            return ClipboardSyncWireResponse(true, requestId, "blob_get", blobData = data)
        }
        if (req.kind.startsWith("engine_") || req.kind == "blob_put") {
            require(!mcpAuthorized) { "MCP token cannot authorize clipboard sync" }
            require(req.clientId != ClipboardOplog.deviceId) { "不能将同步到本机当作另一台设备接收" }
            if (!ClipboardSyncCapabilities.engineMode(req.capabilities)) return ClipboardSyncWireResponse(false, requestId, req.kind, error = "主机未协商 engine/1")
            return try { processEngine(req, requestId, onClientSynced) }
            catch (e: org.fcitx.fcitx5.android.data.clipboard.engine.ClockDriftException) {
                ClipboardSyncWireResponse(false, requestId, req.kind, error = "对端设备时钟异常（版本时间超前过多），已整批拒收，请校准该设备时间")
            }
            catch (e: org.fcitx.fcitx5.android.data.clipboard.engine.BadOpException) {
                ClipboardSyncWireResponse(false, requestId, req.kind, error = "同步操作格式无效，已整批拒收")
            }
        }
        if (req.kind == "full_preview") {
            require(!mcpAuthorized) { "MCP token cannot authorize clipboard preview" }
            val captured = ClipboardSnapshotStore.metadataCapture()
            return ClipboardSyncWireResponse(true, requestId, req.kind, captured.fingerprint,
                onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId, deviceId = ClipboardOplog.deviceId,
                summary = ClipboardSyncSnapshotSummary.from(captured.packet))
        }
        if (req.kind == "full_download") {
            require(!mcpAuthorized) { "MCP token cannot authorize full download" }
            require(req.expectedFingerprint.isNullOrBlank().not()) { "全量下载缺少预览摘要" }
            return ClipboardDelivery.withSendFence { _ ->
                val captured = ClipboardSnapshotStore.capture()
                require(captured.fingerprint == req.expectedFingerprint) { "主机内容在预览后有变化，请重新预览" }
                ClipboardDelivery.beginExposure(captured.packet)
                val versions = captured.packet.entries.associate {
                    it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
                }
                offeredDeliveries[req.clientId] = OfferedDelivery(ClipboardDelivery.ids(captured.packet), versions)
                ClipboardSyncWireResponse(true, requestId, req.kind, captured.fingerprint,
                    json.encodeToString(captured.packet), onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                    receivedEntryIds = ClipboardDelivery.ids(captured.packet).toList(), deviceId = ClipboardOplog.deviceId,
                    summary = ClipboardSyncSnapshotSummary.from(captured.packet), receivedEntryVersions = versions)
            }
        }
        if (req.kind == "ack") {
            require(req.clientId != ClipboardOplog.deviceId) { "不能接受本机送达回执" }
            return ClipboardDelivery.withLock {
                val offered = offeredDeliveries.remove(req.clientId)
                if (offered != null) {
                    ClipboardDelivery.confirm(req.receivedEntryIds.filter { it in offered.ids })
                    ClipboardDelivery.recordPeerReceipt(req.clientId,
                        req.receivedEntryVersions.filter { (id, version) ->
                            id in req.receivedEntryIds && offered.versions[id] == version
                        })
                }
                ClipboardSyncWireResponse(true, requestId, "ack")
            }
        }
        if (req.kind == "full_upload") {
            require(!mcpAuthorized) { "MCP token cannot authorize full upload" }
            require(!req.expectedFingerprint.isNullOrBlank()) { "全量上传缺少预览摘要" }
            require(req.statusHash == req.expectedFingerprint) { "全量上传预览摘要不匹配" }
            val packet = json.decodeFromString<ClipboardSyncPacket>(req.packetJson ?: error("全量上传缺少数据包"))
            require(packet.deviceId == req.clientId && packet.deviceId != ClipboardOplog.deviceId) { "全量上传来源设备不匹配" }
            ClipboardManualRestore.overwrite(packet, req.expectedFingerprint)
            ClipboardDelivery.recordPeerReceipt(packet.deviceId, packet.entries.associate {
                it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
            })
            return ClipboardDelivery.withSendFence { _ ->
                val captured = ClipboardSnapshotStore.capture()
                ClipboardDelivery.beginExposure(captured.packet)
                val versions = captured.packet.entries.associate {
                    it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
                }
                offeredDeliveries[req.clientId] = OfferedDelivery(ClipboardDelivery.ids(captured.packet), versions)
                ClipboardSyncWireResponse(true, requestId, req.kind, captured.fingerprint,
                    json.encodeToString(captured.packet), onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                    receivedEntryIds = ClipboardDelivery.ids(packet).toList(), deviceId = ClipboardOplog.deviceId,
                    summary = ClipboardSyncSnapshotSummary.from(captured.packet), receivedEntryVersions = versions)
            }
        }
        if (req.kind != "sync" || req.packetJson.isNullOrBlank()) throw IllegalArgumentException("invalid sync request")
        val packet = json.decodeFromString<ClipboardSyncPacket>(req.packetJson)
        require(packet.protocolVersion == ClipboardSyncCrypto.PROTOCOL_VERSION && packet.deviceId == req.clientId && packet.deviceId.isNotBlank()) { "packet identity or version invalid" }
        require(packet.deviceId != ClipboardOplog.deviceId) { "不能将同步到本机当作另一台设备接收" }
        val blobMode = ClipboardSyncCapabilities.blobMode(req.capabilities)
        return ClipboardDelivery.withLock {
            val (applied, stats) = ClipboardSnapshotStore.mergeWithStats(packet)
            ClipboardDelivery.recordPeerReceipt(packet.deviceId, packet.entries.associate {
                it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
            })
            ClipboardSyncNotifications.record(stats)
            if (applied > 0) onClientSynced(applied)
            val captured = ClipboardSnapshotStore.capture(blobMode)
            val inventory = ClipboardSyncDelta.inventory(captured.packet)
            val outgoing = ClipboardSyncDelta.difference(captured.packet, req.inventory, inventory)
            if (blobMode) offeredBlobs[req.clientId] = OfferedBlobs(
                ClipboardImageBlobs.referencedHashes(outgoing).mapNotNull { sha -> captured.blobs[sha]?.let { sha to it } }.toMap(),
                System.currentTimeMillis())
            offeredDeliveries[req.clientId] = OfferedDelivery(ClipboardDelivery.ids(outgoing),
                outgoing.entries.associate { it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId) })
            ClipboardDelivery.beginExposure(outgoing)
            ClipboardSyncWireResponse(true, requestId, "sync", captured.fingerprint, json.encodeToString(outgoing),
                onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                inventory = if (req.inventory != null) inventory else null,
                receivedEntryIds = ClipboardDelivery.ids(packet).toList(),
                capabilities = if (blobMode) listOf(ClipboardSyncCapabilities.BLOB_V1) else emptyList())
        }
    }

    private suspend fun processEngine(req: ClipboardSyncWireRequest, requestId: String, onClientSynced: (Int) -> Unit): ClipboardSyncWireResponse {
        val caps = ClipboardSyncCapabilities.negotiated(req.capabilities).toList()
        fun confirmCovered(vv: Map<String, Long>): List<String> = engineOffers[req.clientId]?.let { list ->
            synchronized(list) {
                val covered = list.filter { (vv[it.origin] ?: 0L) >= it.seq }
                list.removeAll(covered.toSet())
                covered.map { it.key }
            }
        }.orEmpty()
        when (req.kind) {
            "engine_hello" -> {
                ClipboardEngineSync.recordPeer(req.clientId, req.vv, req.acks)
                ClipboardDelivery.confirm(confirmCovered(req.vv))
                ClipboardEngineSync.scan()
                return ClipboardSyncWireResponse(true, requestId, req.kind, ClipboardSnapshotStore.fingerprint(),
                    onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId, deviceId = ClipboardOplog.deviceId,
                    capabilities = caps, vv = ClipboardEngineSync.versionVector(), acks = ClipboardEngineSync.ackGossip())
            }
            "engine_pull" -> {
                ClipboardEngineSync.recordPeer(req.clientId, req.vv)
                ClipboardDelivery.confirm(confirmCovered(req.vv))
                return ClipboardDelivery.withSendFence { _ ->
                    val (ops, hasMore) = ClipboardEngineSync.page(req.vv, if (req.limit > 0) req.limit else 200)
                    val ids = ClipboardEngineSync.entryIds(ops)
                    if (ids.isNotEmpty()) ClipboardDelivery.beginExposure(ClipboardEngineSync.exposurePacket(ids))
                    engineOffers.getOrPut(req.clientId) { java.util.Collections.synchronizedList(ArrayList()) }
                        .addAll(ops.filter { it.collection == ClipboardEngineMapping.ENTRIES }.map { EngineOffer(it.origin, it.seq, it.key) })
                    val sources = ClipboardEngineSync.imageHashes(ops).mapNotNull { sha ->
                        ClipboardEngineSync.blobFile(sha)?.let { sha to (ClipboardImageBlobs.Source.FromFile(it) as ClipboardImageBlobs.Source) }
                    }.toMap()
                    if (sources.isNotEmpty()) offeredBlobs.compute(req.clientId) { _, old ->
                        val keep = old?.takeIf { System.currentTimeMillis() - it.at <= BLOB_OFFER_TTL }?.sources.orEmpty()
                        OfferedBlobs(keep + sources, System.currentTimeMillis())
                    }
                    ClipboardSyncWireResponse(true, requestId, req.kind, onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                        capabilities = caps, vv = ClipboardEngineSync.versionVector(), ops = ops, hasMore = hasMore)
                }
            }
            "engine_push" -> {
                require(req.ops.size <= 1000) { "单页操作过多" }
                // 客户端随 push 携带版本向量：用它确认之前 pull 投递的操作已被客户端吸收，
                // 否则 engineOffers 永不清理、投递确认永远缺失（修复：push 登记确认）。
                ClipboardDelivery.confirm(confirmCovered(req.vv))
                val outcome = ClipboardEngineSync.applyRemote(req.ops)
                ClipboardDelivery.recordPeerReceipt(req.clientId, ClipboardEngineSync.receiptVersions(req.ops))
                outcome.stats?.let { ClipboardSyncNotifications.record(it) }
                if (outcome.applied > 0) onClientSynced(outcome.applied)
                val vv = ClipboardEngineSync.versionVector()
                return ClipboardSyncWireResponse(true, requestId, req.kind, ClipboardSnapshotStore.fingerprint(),
                    onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId, capabilities = caps, vv = vv,
                    missingBlobs = ClipboardEngineSync.waitingBlobs().keys.toList(), receivedEntryIds = ClipboardEngineSync.entryIds(req.ops).toList())
            }
            "blob_put" -> {
                val sha = req.blobHash
                require(ClipboardImageBlobs.isSha256(sha)) { "无效图片哈希" }
                val bytes = java.util.Base64.getDecoder().decode(req.blobData ?: error("缺少图片数据"))
                val outcome = ClipboardEngineSync.acceptBlob(sha!!, bytes)
                outcome.stats?.let { ClipboardSyncNotifications.record(it) }
                if (outcome.applied > 0) onClientSynced(outcome.applied)
                return ClipboardSyncWireResponse(true, requestId, req.kind, ClipboardSnapshotStore.fingerprint(), capabilities = caps,
                    missingBlobs = ClipboardEngineSync.waitingBlobs().keys.toList())
            }
        }
        throw IllegalArgumentException("invalid engine request")
    }

    private fun setRemainingTimeout(socket: Socket, deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("request deadline")
        socket.soTimeout = ((remaining + 999_999L) / 1_000_000L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private fun readExact(socket: Socket, input: InputStream, length: Int): ByteArray {
        val deadline = System.nanoTime() + BODY_TIMEOUT_MS * 1_000_000L
        val body = ByteArray(length); var off = 0
        while (off < length) {
            setRemainingTimeout(socket, deadline)
            val n = input.read(body, off, length - off)
            if (n < 0) throw IllegalArgumentException("body eof")
            off += n
        }
        return body
    }
    private fun writeResponse(s: Socket, code: Int, body: ByteArray) { val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; 426 -> "Upgrade Required"; 503 -> "Service Unavailable"; else -> "Bad Request" }; val out = s.getOutputStream(); out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush() }
    private class ByteArrayOutputStreamLimited(private val max: Int) : java.io.ByteArrayOutputStream() { override fun write(b: Int) { if (count >= max) throw IllegalArgumentException("headers too large"); super.write(b) } }
}
