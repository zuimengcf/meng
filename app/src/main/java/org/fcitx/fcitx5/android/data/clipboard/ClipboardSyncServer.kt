package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val online = ConcurrentHashMap<String, Long>()
    private val requestIds = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()
    private val clients = Semaphore(8)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

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
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
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
        ClipboardSyncDiscovery.stopDiscovery(); online.clear(); requestIds.clear()
    }

    private suspend fun handle(client: Socket, onClientSynced: (Int) -> Unit) {
        client.use { s ->
            try {
                s.soTimeout = 60_000
                val input = s.getInputStream(); val headers = ByteArrayOutputStreamLimited(MAX_HEADER); var state = 0
                while (true) { val b = input.read(); if (b < 0) throw IllegalArgumentException("header eof"); headers.write(b); state = if ((state == 0 && b == 13) || (state == 1 && b == 10) || (state == 2 && b == 13) || (state == 3 && b == 10)) state + 1 else if (b == 13) 1 else 0; if (state == 4) break }
                val hs = headers.toString(Charsets.UTF_8.name()).split("\r\n"); val first = hs.first().split(" ")
                if (first.size < 2 || first[0] != "POST") { writeResponse(s, 403, "仅允许加密 POST /sync 请求".toByteArray()); return }
                if (first[1] == "/mcp") { writeResponse(s, 426, "旧版明文 MCP 已禁用，请使用加密 wire.kind=mcp".toByteArray()); return }
                if (first[1] != "/sync") { writeResponse(s, 403, "仅允许 POST /sync".toByteArray()); return }
                val len = hs.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(":")?.trim()?.toLongOrNull()
                if (len == null || len !in 1..MAX_BODY) { writeResponse(s, 413, "请求体过大或缺少长度".toByteArray()); return }
                val body = readExact(input, len.toInt()); val env = json.decodeFromString<ClipboardSyncEnvelope>(String(body, Charsets.UTF_8))
                if (env.protocolVersion != ClipboardSyncCrypto.PROTOCOL_VERSION || env.direction != ClipboardSyncCrypto.REQUEST_DIRECTION || env.clientId.isBlank() || env.requestId.isBlank()) throw SecurityException("invalid authenticated envelope")
                // Authenticate and validate the payload before inserting requestId into replay cache.
                val req = json.decodeFromString<ClipboardSyncWireRequest>(ClipboardSyncCrypto.decrypt(password, env, ClipboardSyncCrypto.REQUEST_DIRECTION))
                if (req.clientId.isBlank() || req.clientId != env.clientId) throw SecurityException("client binding failed")
                if (requestIds.putIfAbsent(env.requestId, System.currentTimeMillis()) != null) throw SecurityException("replay")
                online[req.clientId] = System.currentTimeMillis(); purge()
                val result = lock.withLock { process(req, env.requestId, onClientSynced) }
                val outEnv = ClipboardSyncCrypto.encrypt(password, env.requestId, env.clientId, json.encodeToString(result), ClipboardSyncCrypto.RESPONSE_DIRECTION)
                writeResponse(s, 200, json.encodeToString(outEnv).toByteArray())
            } catch (e: SerializationException) {
                Timber.w("legacy or malformed clipboard request rejected (%s)", e.javaClass.simpleName)
                runCatching { writeResponse(s, 426, "旧版明文请求已拒绝，请使用协议 v2 加密信封".toByteArray()) }
            } catch (e: Exception) {
                Timber.w("clipboard request rejected (%s)", e.javaClass.simpleName)
                runCatching { writeResponse(s, if (e is SecurityException) 403 else 400, "同步请求被拒绝，请检查密码、协议版本、存储空间与数据完整性".toByteArray()) }
            }
        }
    }

    private suspend fun process(req: ClipboardSyncWireRequest, requestId: String, onClientSynced: (Int) -> Unit): ClipboardSyncWireResponse {
        if (req.kind == "mcp") {
            if (!McpServer.enabled) return ClipboardSyncWireResponse(false, requestId, "mcp", error = "MCP 服务未启用")
            return ClipboardSyncWireResponse(true, requestId, "mcp", packetJson = McpServer.handle(req.packetJson ?: ""))
        }
        if (req.kind == "heartbeat") return ClipboardSyncWireResponse(true, requestId, "heartbeat", ClipboardSnapshotStore.fingerprint(), onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId)
        if (req.kind != "sync" || req.packetJson.isNullOrBlank()) throw IllegalArgumentException("invalid sync request")
        val packet = json.decodeFromString<ClipboardSyncPacket>(req.packetJson)
        require(packet.protocolVersion == ClipboardSyncCrypto.PROTOCOL_VERSION && packet.deviceId == req.clientId && packet.deviceId.isNotBlank()) { "packet identity or version invalid" }
        val (applied, stats) = ClipboardSnapshotStore.mergeWithStats(packet)
        ClipboardSyncNotifications.record(stats)
        if (applied > 0) onClientSynced(applied)
        val captured = ClipboardSnapshotStore.capture()
        val inventory = ClipboardSyncDelta.inventory(captured.packet)
        val outgoing = ClipboardSyncDelta.difference(captured.packet, req.inventory, inventory)
        return ClipboardSyncWireResponse(true, requestId, "sync", captured.fingerprint, json.encodeToString(outgoing),
            onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
            inventory = if (req.inventory != null) inventory else null)
    }

    private fun readExact(input: InputStream, length: Int): ByteArray { val body = ByteArray(length); var off = 0; while (off < length) { val n = input.read(body, off, length - off); if (n < 0) throw IllegalArgumentException("body eof"); off += n }; return body }
    private fun writeResponse(s: Socket, code: Int, body: ByteArray) { val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; else -> "Bad Request" }; val out = s.getOutputStream(); out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush() }
    private class ByteArrayOutputStreamLimited(private val max: Int) : java.io.ByteArrayOutputStream() { override fun write(b: Int) { if (count >= max) throw IllegalArgumentException("headers too large"); super.write(b) } }
}
