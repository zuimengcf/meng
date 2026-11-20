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
    private const val TTL = 25 * 60_000L // 客户端托底心跳 15 分钟一次；25 分钟没动静即视为离线
    private const val MAX_HEADER = 16 * 1024
    private const val MAX_BODY = 128 * 1024 * 1024
    private const val HEADER_TIMEOUT_MS = 10_000
    /** 请求体/响应体的时限按长度放宽：基础时间 + 按最低 64 KiB/s 计算的传输时间（弱 Wi-Fi 也不会被拦腰截断）。 */
    private const val TRANSFER_BASE_MS = 30_000L
    private const val MIN_THROUGHPUT_BYTES_PER_S = 64 * 1024L
    /** 单次读之间的最长空闲：对端完全不发数据超过这个时间才判定断开。 */
    private const val IDLE_TIMEOUT_MS = 60_000
    /** 等主机同步锁的最长时间；超时回 503「繁忙」（客户端按瞬时错误退避重试），不再无限排队。 */
    private const val LOCK_WAIT_MS = 60_000L
    /** 被拒绝的请求最多吞掉多少未读请求体再关闭，避免 TCP RST 把错误响应冲掉。 */
    private const val DRAIN_MAX_BYTES = 8L * 1024 * 1024
    private const val DRAIN_MAX_MS = 5_000L
    /** 小请求（心跳、engine 分页、ack 等）不占大包预算：一个大上传占满预算时它们照常处理。 */
    private const val SMALL_REQUEST = 256 * 1024L
    /** wait/1 长轮询：主机最多挂起这么久；同时挂起的长轮询数有上限，不挤占普通请求。 */
    private const val WAIT_HOLD_MS = 25_000L
    private const val MAX_WAITERS = 6
    /** blob/2 单块大小上限（原始字节）。 */
    const val BLOB_CHUNK = 1024 * 1024
    private const val MAX_BLOB_BYTES = 96L * 1024 * 1024
    /** 机器可读的错误码头：新客户端据此判断是否瞬时错误；旧客户端忽略。 */
    private const val ERROR_HEADER = "X-Clipboard-Sync-Error"
    private const val SERVER_TIME_HEADER = "X-Clipboard-Sync-Server-Time"
    private val online = ConcurrentHashMap<String, Long>()
    /** 用 wait/1 长轮询的客户端：每 25 秒必有一次请求，90 秒没动静就算离线，不再挂 2 小时。
     *  登记时机有两个：wait 请求的 finally，以及 heartbeat 里声明过 WAIT_V1 能力的客户端——
     *  后者保证客户端离线后（最后一次 wait 连接失败走不到 finally）也能按 90 秒过期。 */
    private val waitClients = ConcurrentHashMap<String, Long>()
    private const val WAIT_CLIENT_TTL = 90_000L
    /** 在线客户端最近一次连接的来源 IP（NOTIFY 单播兜底用，组播/广播不可达时直接打到客户端）。 */
    private val clientAddrs = ConcurrentHashMap<String, String>()
    private val requestIds = ConcurrentHashMap<String, Long>()
    /** 主机 UI 请求的下一次强制同步标记：每个在线客户端下次心跳时回带并清除。 */
    private val kickPending = ConcurrentHashMap.newKeySet<String>()
    /** 主机 UI：请求所有在线客户端立刻执行一次完整同步。 */
    fun requestKickAll() {
        online.keys.forEach { kickPending.add(it) }
        // kick 标记要等客户端下次主动请求才会被回带消费——删掉高频心跳后这等于失效。
        // 直接 NOTIFY 唤醒在线客户端（组播+广播+已知 IP 单播兜底），它们收到后立刻同步。
        notifyOnlineClients()
    }

    /** 向所有在线客户端发 NOTIFY 唤醒（主机数据变化 / 手动 kick 共用）。 */
    fun notifyOnlineClients() {
        if (!running.get()) return
        purge()
        // 只通知仍在线的客户端：按 clientAddrs 键 ∩ online 取交集，防止单播到已离线 IP。
        val addrs = clientAddrs.filterKeys { online.containsKey(it) }.values.toSet()
        ClipboardSyncDiscovery.notifyPeers(serverPort, addrs)
    }
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
    private val clients = Semaphore(8 + MAX_WAITERS)
    private val waiters = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val bodyBudget = ClipboardRequestBudget(MAX_BODY.toLong())
    private val smallBudget = ClipboardRequestBudget(16L * 1024 * 1024)
    /** blob/2 上传暂存：clientId+sha → 已收到的字节（按偏移顺序追加）。 */
    private class BlobUpload(val total: Long, val file: java.io.File, var received: Long, var at: Long)
    private val blobUploads = ConcurrentHashMap<String, BlobUpload>()
    private class Rejected(val code: Int, val errorCode: String, message: String) : Exception(message)

    val isRunning get() = running.get()
    val port get() = serverPort
    /** 优先 Wi-Fi/以太网上的私网地址，避免把蜂窝或 VPN 地址展示给同局域网设备。
     *  网卡枚举在部分 ROM 上偶发阻塞数秒，绝不能放在主线程；这里缓存 30 秒，start() 时在 IO 线程预热。 */
    private const val ADDR_CACHE_MS = 30_000L
    @Volatile private var cachedLocalAddress = "127.0.0.1"
    @Volatile private var cachedAddrAt = 0L
    private val addrLock = Any()
    val localAddress: String get() {
        if (System.currentTimeMillis() - cachedAddrAt < ADDR_CACHE_MS) return cachedLocalAddress
        return refreshLocalAddress()
    }
    private fun refreshLocalAddress(): String = synchronized(addrLock) {
        val now = System.currentTimeMillis()
        if (now - cachedAddrAt < ADDR_CACHE_MS) return cachedLocalAddress
        val computed = computeLocalAddress()
        cachedLocalAddress = computed; cachedAddrAt = now; computed
    }
    private fun computeLocalAddress(): String {
        val candidates = NetworkInterface.getNetworkInterfaces().toList().filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { nic.name to it } }
        fun lanNic(name: String) = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("swlan")
        return (candidates.firstOrNull { (n, a) -> lanNic(n) && a.isSiteLocalAddress }
            ?: candidates.firstOrNull { (_, a) -> a.isSiteLocalAddress }
            ?: candidates.firstOrNull())?.second?.hostAddress ?: "127.0.0.1"
    }
    val passwordHint get() = password
    private fun purge() {
        val now = System.currentTimeMillis()
        online.entries.removeIf { now - it.value > (if (waitClients.containsKey(it.key)) WAIT_CLIENT_TTL else TTL) }
        waitClients.keys.retainAll(online.keys)
        clientAddrs.keys.retainAll(online.keys)
        requestIds.entries.removeIf { now - it.value > 2 * ClipboardSyncCrypto.CLOCK_SKEW_MS + 60_000L }
    }
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
                            } else launch {
                                // 连接数已满：回一个 503「繁忙」再关，而不是直接 close（客户端只会看到连接被重置）。
                                runCatching { client.soTimeout = 2_000; writeResponse(client, 503, "同步繁忙，请稍后重试".toByteArray(), "busy") }
                                runCatching { client.shutdownOutput() }
                                runCatching { drain(client, client.getInputStream(), 64 * 1024L) }
                                runCatching { client.close() }
                            }
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
        ClipboardSyncDiscovery.stopDiscovery(); online.clear(); waitClients.clear(); clientAddrs.clear(); requestIds.clear(); offeredDeliveries.clear(); offeredBlobs.clear(); engineOffers.clear(); kickPending.clear()
        blobUploads.values.forEach { runCatching { it.file.delete() } }; blobUploads.clear()
    }

    private suspend fun handle(client: Socket, onClientSynced: (Int) -> Unit) {
        client.use { s ->
            var reservedBytes = 0L
            var reservedBudget: ClipboardRequestBudget? = null
            var reqKind = "?"
            var reqClient = "?"
            // 未读完的请求体长度：提前拒绝时据此吞掉剩余字节，让客户端读到真实的错误响应而不是 RST。
            var unread = 0L
            var input: InputStream? = null
            // 只在「读请求体」和「写响应」阶段各自挂一个按长度计算的看门狗；
            // 等锁和处理不再计入（原来从 accept 起硬计 300s，排队等锁的请求会被拦腰关闭）。
            var watchdog: Job? = null
            fun arm(ms: Long) { watchdog?.cancel(); watchdog = scope.launch { delay(ms); runCatching { s.close() } } }
            fun disarm() { watchdog?.cancel(); watchdog = null }
            suspend fun reject(code: Int, errorCode: String, message: String, extraHeaders: Map<String, String> = emptyMap()) {
                disarm()
                runCatching { s.soTimeout = 5_000; writeResponse(s, code, message.toByteArray(), errorCode, extraHeaders) }
                runCatching { s.shutdownOutput() }
                val inp = input
                if (inp != null && unread > 0) runCatching { drain(s, inp, minOf(unread, DRAIN_MAX_BYTES)) }
            }
            try {
                s.soTimeout = HEADER_TIMEOUT_MS
                val headerDeadline = System.nanoTime() + HEADER_TIMEOUT_MS * 1_000_000L
                val inp = s.getInputStream(); input = inp
                val headers = ByteArrayOutputStreamLimited(MAX_HEADER)
                var state = 0
                while (true) {
                    setRemainingTimeout(s, headerDeadline)
                    val b = inp.read()
                    if (b < 0) throw IllegalArgumentException("header eof")
                    headers.write(b)
                    state = if ((state == 0 && b == 13) || (state == 1 && b == 10) || (state == 2 && b == 13) || (state == 3 && b == 10)) state + 1 else if (b == 13) 1 else 0
                    if (state == 4) break
                }
                val lines = headers.toString(Charsets.UTF_8.name()).split("\r\n")
                val first = lines.firstOrNull()?.split(" ") ?: emptyList()
                val fields = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
                val len = fields["content-length"]?.toLongOrNull()
                unread = len?.coerceAtLeast(0L) ?: 0L
                if (first.size < 2 || first[0] != "POST" || first[1] != "/sync") {
                    reject(if (first.getOrNull(1) == "/mcp") 426 else 403, "path", "仅允许加密 POST /sync 请求"); return
                }
                if (len == null || len !in 1..MAX_BODY) { reject(413, "too_large", "请求体过大或缺少长度"); return }
                val requestId = fields["x-clipboard-sync-request"]
                val proof = fields["x-clipboard-sync-proof"]
                if (fields.containsKey("authorization")) { reject(403, "plaintext", "明文凭据头已禁用，请使用加密协议"); return }
                val requestScope = fields["x-clipboard-sync-scope"] ?: "sync"
                val controlToken = if (requestScope == "mcp" && McpServer.enabled) ClipboardSyncSettings.getMcpToken() else null
                val issuedAt = fields["x-clipboard-sync-issued-at"]?.toLongOrNull() ?: 0L
                val roomPassword = password
                if (roomPassword.isEmpty()) { reject(503, "stopping", "房间正在关闭，请稍后重试"); return }
                val grant = ClipboardRequestAuth.authorize(roomPassword, requestId, proof, requestScope, controlToken, issuedAt)
                if (grant == null) {
                    // 区分「时钟偏差」与「密码错误」：前者回带主机时间（仅作提示，客户端只用它校正请求时间戳）。
                    if (ClipboardRequestAuth.isClockSkewed(issuedAt)) {
                        val diffMin = (issuedAt - System.currentTimeMillis()) / 60_000L
                        reject(403, "clock_skew", "两台设备时钟相差约 ${kotlin.math.abs(diffMin)} 分钟，请校准时间",
                            mapOf(SERVER_TIME_HEADER to System.currentTimeMillis().toString()))
                    } else reject(403, "auth", "认证失败：房间密码不一致")
                    return
                }
                purge()
                if (requestIds.size >= 4096) { reject(503, "busy", "请求过于频繁，请稍后重试"); return }
                if (requestIds.putIfAbsent(checkNotNull(requestId), System.currentTimeMillis()) != null) { reject(403, "replay", "请求重放或过期，请重试"); return }
                val budget = if (len <= SMALL_REQUEST) smallBudget else bodyBudget
                if (!budget.reserve(len)) { reject(503, "busy", "同步繁忙，请稍后重试"); return }
                reservedBytes = len; reservedBudget = budget
                s.soTimeout = IDLE_TIMEOUT_MS
                arm(transferBudgetMs(len))
                val body = readExact(s, inp, len.toInt())
                unread = 0L
                disarm()
                val env = json.decodeFromString<ClipboardSyncEnvelope>(String(body, Charsets.UTF_8)).let {
                    // 旧客户端偶发省略 issuedAt（见 ClipboardSyncEnvelope.issuedAt）；请求头里有同一个值，且已被 proof 认证。
                    if (it.issuedAt == MISSING_ISSUED_AT && issuedAt != MISSING_ISSUED_AT) it.copy(issuedAt = issuedAt) else it
                }
                if (env.protocolVersion != ClipboardSyncCrypto.PROTOCOL_VERSION || env.direction != ClipboardSyncCrypto.REQUEST_DIRECTION || env.clientId.isBlank() || env.requestId.isBlank() || env.requestId != requestId || env.issuedAt != issuedAt) throw SecurityException("invalid authenticated envelope")
                val controlAuthorized = grant == ClipboardRequestAuth.Grant.CONTROL
                val credential = if (controlAuthorized) checkNotNull(controlToken) else roomPassword
                val plaintext = try { ClipboardSyncCrypto.decrypt(credential, env, ClipboardSyncCrypto.REQUEST_DIRECTION) }
                    catch (e: javax.crypto.AEADBadTagException) {
                        Timber.w("clipboard request AEAD failure: body=%d ciphertext=%d", body.size, env.ciphertext.length)
                        throw SecurityException("invalid authenticated envelope")
                    }
                val req = json.decodeFromString<ClipboardSyncWireRequest>(plaintext)
                reqKind = req.kind; reqClient = req.clientId
                if (req.clientId.isBlank() || req.clientId != env.clientId) throw SecurityException("client binding failed")
                if (controlAuthorized && req.kind != "mcp") throw SecurityException("MCP token cannot authorize sync")
                if (!controlAuthorized) {
                    online[req.clientId] = System.currentTimeMillis()
                    clientAddrs[req.clientId] = client.inetAddress.hostAddress ?: ""
                }
                purge()
                // 只读请求不排队：心跳、长轮询、拉图都不等合并锁。
                val result = when (req.kind) {
                    "heartbeat", "blob_get" -> process(req, env.requestId, onClientSynced, controlAuthorized)
                    "wait" -> waitForChange(req, env.requestId)
                    // 词库清单与下载只读文件，不等剪切板合并锁
                    "lexicon_list", "lexicon_get" -> RimeLexiconSync.handle(req, env.requestId)
                    else -> {
                        if (!tryLockFor(LOCK_WAIT_MS)) throw Rejected(503, "busy", "主机正在处理其他设备的同步，请稍后重试")
                        try { process(req, env.requestId, onClientSynced, controlAuthorized) } finally { lock.unlock() }
                    }
                }
                val outBytes = json.encodeToString(ClipboardSyncCrypto.encrypt(credential, env.requestId, env.clientId, json.encodeToString(result), ClipboardSyncCrypto.RESPONSE_DIRECTION)).toByteArray()
                arm(transferBudgetMs(outBytes.size.toLong()))
                writeResponse(s, 200, outBytes)
                disarm()
            } catch (e: CancellationException) { throw e
            } catch (e: Rejected) {
                Timber.w("clipboard request rejected (kind=%s client=%s code=%d %s)", reqKind, reqClient, e.code, e.errorCode)
                reject(e.code, e.errorCode, e.message ?: "")
            } catch (e: SerializationException) {
                Timber.w(e, "clipboard request rejected (malformed, kind=%s client=%s)", reqKind, reqClient)
                reject(426, "malformed", "旧版明文请求已拒绝，请使用协议 v2 加密信封")
            } catch (e: OutOfMemoryError) {
                // 大包解析时内存不足：不要让异常冒到协程外把输入法进程带崩。
                Timber.e(e, "clipboard request out of memory (kind=%s client=%s)", reqKind, reqClient)
                reject(503, "oom", "主机内存不足，无法处理这么大的同步包")
            } catch (e: java.io.IOException) {
                // 读写中断（对端断开、看门狗关闭）：连接已不可用，只记日志。
                Timber.w("clipboard connection dropped (kind=%s client=%s): %s", reqKind, reqClient, e.javaClass.simpleName)
            } catch (e: Exception) {
                Timber.w(e, "clipboard request rejected (kind=%s client=%s code=%s)", reqKind, reqClient,
                    if (e is SecurityException) "403" else "400")
                val (code, reason) = when {
                    e is SecurityException && e.message == "invalid authenticated envelope" -> "envelope" to "加密信封校验失败，请检查两端版本与密码"
                    e is SecurityException && e.message == "client binding failed" -> "binding" to "客户端标识绑定失败，请重试"
                    e is SecurityException && e.message == "MCP token cannot authorize sync" -> "mcp" to "MCP 令牌不能用于同步操作"
                    e is SecurityException -> "forbidden" to "同步请求被拒绝"
                    else -> "bad_request" to ("同步请求被拒绝：" + (e.message?.take(120) ?: "请检查协议版本、存储空间与数据完整性"))
                }
                reject(if (e is SecurityException) 403 else 400, code, reason)
            } finally {
                disarm()
                if (reservedBytes > 0) reservedBudget?.release(reservedBytes)
            }
        }
    }

    private fun transferBudgetMs(bytes: Long): Long = TRANSFER_BASE_MS + bytes * 1000L / MIN_THROUGHPUT_BYTES_PER_S

    /** 有限时地拿同步锁（轮询 tryLock，避免 withTimeout 包 lock() 时「拿到锁却被取消」造成泄漏）。 */
    private suspend fun tryLockFor(ms: Long): Boolean {
        val deadline = System.nanoTime() + ms * 1_000_000L
        while (true) {
            if (lock.tryLock()) return true
            if (System.nanoTime() >= deadline) return false
            delay(50)
        }
    }

    /** wait/1：主机内容（指纹）与客户端给的基线不同就立即返回，否则挂起到有变化或 [WAIT_HOLD_MS]。 */
    private suspend fun waitForChange(req: ClipboardSyncWireRequest, requestId: String): ClipboardSyncWireResponse {
        require(ClipboardSyncCapabilities.WAIT_V1 in ClipboardSyncCapabilities.negotiated(req.capabilities)) { "主机未协商 wait/1" }
        require(req.clientId != ClipboardOplog.deviceId) { "不能长轮询本机" }
        fun reply(fp: String) = ClipboardSyncWireResponse(true, requestId, "wait", fp,
            onlineClientIds = onlineClientIds.toList() + ClipboardOplog.deviceId,
            capabilities = ClipboardSyncCapabilities.negotiated(req.capabilities).toList())
        if (waiters.incrementAndGet() > MAX_WAITERS) {
            waiters.decrementAndGet()
            delay(10_000); return reply(ClipboardSnapshotStore.fingerprint())
        }
        try {
            val deadline = System.currentTimeMillis() + WAIT_HOLD_MS
            while (true) {
                val gen = ClipboardSyncSignal.generation
                val fp = ClipboardSnapshotStore.fingerprint()
                // 主机 UI 的「立即同步」：消费 kick 标记并立即返回，客户端随即同步（不会反复命中）。
                if (kickPending.remove(req.clientId)) return reply(fp + "#kick")
                if (req.statusHash.isEmpty() || fp != req.statusHash) return reply(fp)
                val left = deadline - System.currentTimeMillis()
                if (left <= 0 || !running.get()) return reply(fp)
                ClipboardSyncSignal.awaitGenerationAfter(gen, left)
            }
        } finally {
            waiters.decrementAndGet()
            if (running.get()) { val now = System.currentTimeMillis(); online[req.clientId] = now; waitClients[req.clientId] = now }
        }
    }

    private suspend fun process(req: ClipboardSyncWireRequest, requestId: String, onClientSynced: (Int) -> Unit, mcpAuthorized: Boolean): ClipboardSyncWireResponse {
        if (req.kind == "lexicon_put") return RimeLexiconSync.handle(req, requestId) // 在同步锁内串行落盘
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
            // 声明过 wait/1 的客户端按 90 秒判离线（waitClients 是 purge 的快过期依据）。
            // 不登记的话：客户端离线后最后一次 wait 因连接失败走不到 waitForChange 的 finally，
            // 主机会按 2 小时 TTL 一直虚报它在线。
            if (ClipboardSyncCapabilities.WAIT_V1 in caps) waitClients[req.clientId] = System.currentTimeMillis()
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
            if (ClipboardSyncCapabilities.BLOB_V2 in ClipboardSyncCapabilities.negotiated(req.capabilities) && source is ClipboardImageBlobs.Source.FromFile) {
                // blob/2：按块读文件，不再把整张图编码进一个响应（大图内存峰值从数倍降到 1 MiB）。
                val file = source.file
                val total = file.length()
                require(total > 0 && req.blobOffset in 0 until total) { "图片分块偏移无效" }
                // 第一块时校验整张图仍与哈希一致（文件可能被替换）。
                if (req.blobOffset == 0L && ClipboardImageBlobs.sha256OfFile(file) != sha)
                    return ClipboardSyncWireResponse(false, requestId, "blob_get", error = "主机上的图片已变化或缺失")
                val size = minOf(BLOB_CHUNK.toLong(), total - req.blobOffset).toInt()
                val chunk = ByteArray(size)
                java.io.RandomAccessFile(file, "r").use { raf -> raf.seek(req.blobOffset); raf.readFully(chunk) }
                return ClipboardSyncWireResponse(true, requestId, "blob_get", blobData = java.util.Base64.getEncoder().encodeToString(chunk), blobTotal = total)
            }
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
                val piece = java.util.Base64.getDecoder().decode(req.blobData ?: error("缺少图片数据"))
                val bytes = if (req.blobTotal > 0 && ClipboardSyncCapabilities.BLOB_V2 in ClipboardSyncCapabilities.negotiated(req.capabilities)) {
                    // blob/2：分块上传，按偏移顺序追加到临时文件，收齐后一次性交给引擎（引擎内部再校验哈希）。
                    require(req.blobTotal <= MAX_BLOB_BYTES) { "图片过大" }
                    val key = req.clientId + "/" + sha
                    val now = System.currentTimeMillis()
                    blobUploads.entries.removeIf { (_, u) -> (now - u.at > BLOB_OFFER_TTL).also { if (it) runCatching { u.file.delete() } } }
                    val upload = if (req.blobOffset == 0L) {
                        blobUploads.remove(key)?.let { runCatching { it.file.delete() } }
                        BlobUpload(req.blobTotal, java.io.File.createTempFile("clb-up-", ".part"), 0L, now).also { blobUploads[key] = it }
                    } else blobUploads[key] ?: error("图片分块上传已过期，请从头重传")
                    require(upload.total == req.blobTotal && upload.received == req.blobOffset) { "图片分块顺序错误，请从头重传" }
                    require(upload.received + piece.size <= upload.total) { "图片分块超出总长度" }
                    java.io.FileOutputStream(upload.file, true).use { it.write(piece) }
                    upload.received += piece.size; upload.at = now
                    if (upload.received < upload.total) {
                        return ClipboardSyncWireResponse(true, requestId, req.kind, ClipboardSnapshotStore.fingerprint(), capabilities = caps,
                            vv = ClipboardEngineSync.versionVector(), blobTotal = upload.received)
                    }
                    blobUploads.remove(key)
                    try { upload.file.readBytes() } finally { runCatching { upload.file.delete() } }
                } else piece
                val outcome = ClipboardEngineSync.acceptBlob(sha!!, bytes)
                outcome.stats?.let { ClipboardSyncNotifications.record(it) }
                if (outcome.applied > 0) onClientSynced(outcome.applied)
                // 回带最新版本向量：客户端补传图片后据此判断推送是否有进展（影子记录收齐图片后才推进）。
                return ClipboardSyncWireResponse(true, requestId, req.kind, ClipboardSnapshotStore.fingerprint(), capabilities = caps,
                    missingBlobs = ClipboardEngineSync.waitingBlobs().keys.toList(), vv = ClipboardEngineSync.versionVector())
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
        // 总时限由按长度计算的看门狗负责；这里每次读只受空闲超时（soTimeout）约束。
        val body = ByteArray(length); var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw java.io.EOFException("body eof")
            off += n
        }
        return body
    }
    /** 有限地读掉并丢弃剩余请求体，让对端把请求写完、读到我们的错误响应。 */
    private fun drain(socket: Socket, input: InputStream, max: Long) {
        val deadline = System.nanoTime() + DRAIN_MAX_MS * 1_000_000L
        val buf = ByteArray(16 * 1024); var left = max
        while (left > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            socket.soTimeout = (remaining / 1_000_000L).coerceIn(1L, DRAIN_MAX_MS).toInt()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }
    private fun writeResponse(s: Socket, code: Int, body: ByteArray, errorCode: String? = null, extra: Map<String, String> = emptyMap()) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 413 -> "Payload Too Large"; 426 -> "Upgrade Required"; 503 -> "Service Unavailable"; else -> "Bad Request" }
        val headers = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: ${if (code == 200) "application/json" else "text/plain"}; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        if (errorCode != null) headers.append("$ERROR_HEADER: $errorCode\r\n")
        if (code == 503) headers.append("Retry-After: 5\r\n")
        extra.forEach { (k, v) -> headers.append("$k: $v\r\n") }
        headers.append("\r\n")
        val out = s.getOutputStream(); out.write(headers.toString().toByteArray()); out.write(body); out.flush()
    }
    private class ByteArrayOutputStreamLimited(private val max: Int) : java.io.ByteArrayOutputStream() { override fun write(b: Int) { if (count >= max) throw IllegalArgumentException("headers too large"); super.write(b) } }
}
