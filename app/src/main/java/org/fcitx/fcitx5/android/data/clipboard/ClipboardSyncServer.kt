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
    private const val BODY_TIMEOUT_MS = 30_000
    private val online = ConcurrentHashMap<String, Long>()
    private val requestIds = ConcurrentHashMap<String, Long>()
    private data class OfferedDelivery(val ids: Set<String>, val versions: Map<String, String>)
    private val offeredDeliveries = ConcurrentHashMap<String, OfferedDelivery>()
    private val lock = Mutex()
    private val clients = Semaphore(8)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val bodyBudget = ClipboardRequestBudget(MAX_BODY.toLong())

    val isRunning get() = running.get()
    val port get() = serverPort
    val localAddress get() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
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
        ClipboardSyncDiscovery.stopDiscovery(); online.clear(); requestIds.clear(); offeredDeliveries.clear()
    }

    private suspend fun handle(client: Socket, onClientSynced: (Int) -> Unit) {
        client.use { s ->
            var reservedBytes = 0L
            // SO_TIMEOUT does not bound writes: also close clients that stop reading a response.
            val lifetime = scope.launch { delay(60_000L); runCatching { s.close() } }
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
                val result = lock.withLock { process(req, env.requestId, onClientSynced, controlAuthorized) }
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
        if (req.kind == "heartbeat") return ClipboardSyncWireResponse(true, requestId, "heartbeat", ClipboardSnapshotStore.fingerprint(), onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId)
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
        return ClipboardDelivery.withLock {
            val (applied, stats) = ClipboardSnapshotStore.mergeWithStats(packet)
            ClipboardDelivery.recordPeerReceipt(packet.deviceId, packet.entries.associate {
                it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
            })
            ClipboardSyncNotifications.record(stats)
            if (applied > 0) onClientSynced(applied)
            val captured = ClipboardSnapshotStore.capture()
            val inventory = ClipboardSyncDelta.inventory(captured.packet)
            val outgoing = ClipboardSyncDelta.difference(captured.packet, req.inventory, inventory)
            offeredDeliveries[req.clientId] = OfferedDelivery(ClipboardDelivery.ids(outgoing),
                outgoing.entries.associate { it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId) })
            ClipboardDelivery.beginExposure(outgoing)
            ClipboardSyncWireResponse(true, requestId, "sync", captured.fingerprint, json.encodeToString(outgoing),
                onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
                inventory = if (req.inventory != null) inventory else null,
                receivedEntryIds = ClipboardDelivery.ids(packet).toList())
        }
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
    private fun writeResponse(s: Socket, code: Int, body: ByteArray) { val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; else -> "Bad Request" }; val out = s.getOutputStream(); out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush() }
    private class ByteArrayOutputStreamLimited(private val max: Int) : java.io.ByteArrayOutputStream() { override fun write(b: Int) { if (count >= max) throw IllegalArgumentException("headers too large"); super.write(b) } }
}
