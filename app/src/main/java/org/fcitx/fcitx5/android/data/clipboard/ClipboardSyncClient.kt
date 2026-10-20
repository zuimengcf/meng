package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** 加密协议 v2 客户端；心跳不携带快照，摘要改变才交换真实快照。 */
object ClipboardSyncClient {
    private val json = Json { ignoreUnknownKeys = true }
    private const val HEARTBEAT_MS = 1_000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_BODY = 128 * 1024 * 1024
    @Volatile private var currentRoom = ""
    @Volatile private var currentPassword = ""
    @Volatile private var lastPingOk = 0L
    @Volatile private var lastUploadedLocalFingerprint = ""
    @Volatile private var lastSeenServerFingerprint = ""
    @Volatile private var peerInventory: ClipboardSyncInventory? = null
    @Volatile var lastSyncError: String? = null
    @Volatile var lastSyncSummary: ClipboardSyncStats? = null
    @Volatile var onlineDeviceIds: Set<String> = emptySet()
    val connectedDeviceCount: Int get() = (onlineDeviceIds - ClipboardOplog.deviceId).size
    val joinedRoom: String get() = currentRoom
    val joinedRoomPassword: String get() = currentPassword
    val lastHeartbeatOk: Long get() = lastPingOk
    val isConnected: Boolean get() = currentRoom.isNotEmpty() && lastPingOk > 0 && System.currentTimeMillis() - lastPingOk <= 15_000L
    @Volatile var onDisconnected: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var generation = 0L
    private val requestLock = Mutex()

    fun joinRoom(addr: String, pwd: String) {
        require(pwd.isNotEmpty()) { "房间密码不能为空" }
        val normalized = normalizeBaseUrl(addr)
        ClipboardManager.enableSyncRetention()
        synchronized(this) {
            generation++
            currentRoom = normalized; currentPassword = pwd; lastPingOk = 0L
            peerInventory = null
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); val myGeneration = generation
            job = scope.launch { loop(myGeneration, normalized, pwd) }
        }
    }

    fun leaveRoom() {
        synchronized(this) {
            generation++; currentRoom = ""; currentPassword = ""; lastPingOk = 0L
            peerInventory = null
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); job = null
        }
    }

    private suspend fun loop(myGeneration: Long, room: String, pwd: String) {
        var backoff = 1_000L; var wasConnected = false
        while (isCurrent(myGeneration, room)) {
            try {
                val localFingerprint = ClipboardSnapshotStore.fingerprint()
                val response = requestAt(room, pwd, ClipboardSyncWireRequest("heartbeat", ClipboardOplog.deviceId, localFingerprint))
                if (!isCurrent(myGeneration, room)) return
                lastPingOk = System.currentTimeMillis(); backoff = 1_000L
                wasConnected = true
                onlineDeviceIds = response.onlineClientIds.toSet()
                if (ClipboardSyncRules.requiresExchange(localFingerprint, lastUploadedLocalFingerprint, response.statusHash, lastSeenServerFingerprint)) {
                    try { syncOnce(room, pwd) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        lastSyncError = e.message ?: "内容同步失败"
                        Timber.w("clipboard content merge incomplete (%s)", e.javaClass.simpleName)
                        delay(5_000L)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                lastPingOk = 0L; onlineDeviceIds = emptySet(); lastSyncError = e.message ?: "同步连接失败"
                Timber.w("clipboard sync heartbeat failed (%s)", e.javaClass.simpleName)
                if (wasConnected) onDisconnected?.invoke(); wasConnected = false
                delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000L); continue
            }
            ClipboardSyncSignal.awaitChangeOrHeartbeat(HEARTBEAT_MS)
        }
    }

    private fun isCurrent(gen: Long, room: String) = generation == gen && currentRoom == room && room.isNotEmpty()

    fun isHostReachable(baseUrl: String, password: String): Boolean {
        if (password.isEmpty()) return false
        return runCatching { runBlocking { requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest("heartbeat", ClipboardOplog.deviceId, "")) }; true }.getOrDefault(false)
    }

    suspend fun syncOnce(baseUrl: String = currentRoom, password: String = currentPassword, onProgress: ((String, Int, Int, Int) -> Unit)? = null): Int = requestLock.withLock {
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val startGeneration = generation
        try {
        onProgress?.invoke("读取本地数据", 0, 0, 0)
        val captured = ClipboardSnapshotStore.capture()
        val packet = captured.packet
        val uploadFingerprint = captured.fingerprint
        val inventory = ClipboardSyncDelta.inventory(packet)
        val sameRoom = normalizeBaseUrl(baseUrl) == currentRoom && password == currentPassword
        val outgoing = ClipboardSyncDelta.difference(packet, if (sameRoom) peerInventory else null, inventory)
        onProgress?.invoke("交换变更（条目 ${outgoing.entries.size}，分组 ${outgoing.groups.size}，历史 ${outgoing.history.size}）", outgoing.entries.size, 0, 0)
        val body = requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest("sync", ClipboardOplog.deviceId, uploadFingerprint, json.encodeToString(outgoing), inventory))
        if (startGeneration != generation) throw CancellationException("房间已切换或退出")
        if (!body.ok || body.packetJson == null) throw IllegalStateException(body.error ?: "同步响应无效")
        val incoming = json.decodeFromString<ClipboardSyncPacket>(body.packetJson)
        require(incoming.protocolVersion == ClipboardSyncCrypto.PROTOCOL_VERSION && incoming.deviceId.isNotBlank()) { "同步响应协议或设备标识无效" }
        lastPingOk = System.currentTimeMillis(); onlineDeviceIds = body.onlineClientIds.toSet()
        val (applied, rawStats) = ClipboardSnapshotStore.mergeWithStats(incoming)
        // A delta does not echo matching rows; count matches from the complete authenticated inventory.
        val stats = rawStats.copy(mergedExisting = body.inventory?.entries?.keys?.count { id -> inventory.entries.containsKey(id) }
            ?: rawStats.mergedExisting)
        lastSyncSummary = stats
        ClipboardSyncNotifications.record(stats)
        if (sameRoom) peerInventory = body.inventory
        val missing = ClipboardSnapshotStore.missingImageIds()
        // The received merge is durable; an unavailable original is not fixed by resending identical bytes.
        // Track transport progress but retain an explicit incomplete-content error until a source changes.
        lastUploadedLocalFingerprint = uploadFingerprint
        lastSeenServerFingerprint = body.statusHash
        if (missing.isNotEmpty()) error("文字及分组已合并，但 ${missing.size} 张图片源缺失，尚未完成全部同步；请从有原图的设备补传")
        lastPingOk = System.currentTimeMillis(); onlineDeviceIds = body.onlineClientIds.toSet(); lastSyncError = null
        onProgress?.invoke("完成（新增 ${stats.added}，已有匹配 ${stats.mergedExisting}，更新 ${stats.updated}，删除 ${stats.deleted}，现有 ${stats.totalAfter}）", packet.entries.size, incoming.entries.size, applied)
        applied
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            lastSyncError = e.message ?: "同步失败"
            throw e
        }
    }

    private suspend fun requestAt(baseUrl: String, password: String, req: ClipboardSyncWireRequest): ClipboardSyncWireResponse = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "房间密码不能为空" }; require(req.clientId.isNotBlank()) { "client ID不能为空" }
        val id = ClipboardSyncCrypto.requestId()
        val envelope = ClipboardSyncCrypto.encrypt(password, id, req.clientId, json.encodeToString(req), ClipboardSyncCrypto.REQUEST_DIRECTION)
        val body = json.encodeToString(envelope).toByteArray()
        require(body.size <= MAX_BODY) { "同步包超过 128 MiB，本次未确认完成；未截断或删除任何条目" }
        val uri = URI(normalizeBaseUrl(baseUrl) + "/sync")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Clipboard-Sync-Request", id)
            setRequestProperty("X-Clipboard-Sync-Issued-At", envelope.issuedAt.toString())
            setRequestProperty("X-Clipboard-Sync-Proof", ClipboardSyncCrypto.requestProof(password, id, issuedAt = envelope.issuedAt))
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IllegalStateException("主机返回 HTTP ${conn.responseCode}")
            val responseEnvelope = readBounded(conn.inputStream, MAX_BODY)
            val wire = json.decodeFromString<ClipboardSyncEnvelope>(responseEnvelope)
            if (wire.requestId != id || wire.clientId != req.clientId) throw SecurityException("响应未绑定请求")
            val result = json.decodeFromString<ClipboardSyncWireResponse>(ClipboardSyncCrypto.decrypt(password, wire, ClipboardSyncCrypto.RESPONSE_DIRECTION))
            if (result.requestId != id) throw SecurityException("响应请求号不匹配")
            if (result.kind != req.kind) throw SecurityException("响应操作类型不匹配")
            if (!result.ok) throw IllegalStateException(result.error ?: "同步失败")
            result
        } finally { conn.disconnect() }
    }

    private fun readBounded(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) throw IllegalArgumentException("响应过大"); out.write(buf, 0, n) }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().let { if (it.contains("://")) it else "http://$it" }
        val uri = URI(value)
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 http/https" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "地址不得包含用户名、密码、路径或查询参数" }
        require(!uri.host.isNullOrBlank()) { "无效主机地址" }
        return value.trimEnd('/')
    }
}
