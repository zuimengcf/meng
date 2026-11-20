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

data class ClipboardFullTransferPreview(
    val localFingerprint: String,
    val remoteFingerprint: String,
    val local: ClipboardSyncSnapshotSummary,
    val remote: ClipboardSyncSnapshotSummary,
    val remoteDeviceId: String,
)

/** 传输层瞬时抖动（响应密文校验失败/截断等）：单次出现不视为同步失败，
 * 不写入 lastSyncError 刷屏；只有连续多次（见 [ClipboardSyncClient.TRANSIENT_ERROR_THRESHOLD]）
 * 才升级为错误展示，下一次成功自动清除。 */
class TransientTransportException(message: String) : IllegalStateException(message)

/** 加密协议 v2 客户端；心跳不携带快照，摘要改变才交换真实快照。 */
object ClipboardSyncClient {
    private val json = Json { ignoreUnknownKeys = true }
    /** 托底周期：主机支持 wait/1 长轮询时 1 小时（变化由长轮询即时送达），否则 15 分钟（只剩 UDP NOTIFY 可依赖）。 */
    private const val HEARTBEAT_MS = 3_600_000L
    private const val HEARTBEAT_NO_WAIT_MS = 15 * 60_000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    // 读超时是「两次读之间的空闲」：要盖住主机等锁（最多 60s）+ 处理大合并的时间，
    // 否则主机还在处理、这边先断开，主机再写就写到空连接上。
    private const val READ_TIMEOUT_MS = 300_000
    /** wait/1 长轮询的读超时：主机最多挂起 25s，留足余量。 */
    private const val WAIT_READ_TIMEOUT_MS = 60_000
    /** 时钟校正的最大幅度；超出说明对端时间明显异常，不自动校正。 */
    private const val MAX_CLOCK_OFFSET_MS = 30L * 24 * 3_600_000L
    /** 主机返回、被视为可自愈的错误码（见服务端 X-Clipboard-Sync-Error）。 */
    private val TRANSIENT_CODES = setOf("replay", "envelope", "binding", "busy", "stopping")
    private const val MAX_BODY = 128 * 1024 * 1024
    private const val ENGINE_PAGE = 200
    private const val MAX_PAGES = 100_000
    /** 瞬时传输抖动连续超过此值才升级为错误展示（单次 AEADBadTag/截断只记日志）。 */
    private const val TRANSIENT_ERROR_THRESHOLD = 3
    private const val KICK_DEBOUNCE_MS = 3_000L
    @Volatile private var currentRoom = ""
    @Volatile private var currentPassword = ""
    @Volatile private var lastPingOk = 0L
    @Volatile private var lastUploadedLocalFingerprint = ""
    @Volatile private var lastSeenServerFingerprint = ""
    @Volatile private var peerInventory: ClipboardSyncInventory? = null
    /** 库存的形式（旧格式 / blob 格式）必须与主机一致，形式变了就丢弃缓存的库存。 */
    @Volatile private var peerInventoryBlobMode = false
    /** 主机在最近一次心跳里回带的能力；旧主机为空。 */
    @Volatile private var hostCapabilities: Set<String> = emptySet()
    @Volatile var lastSyncError: String? = null
    @Volatile var lastSyncSummary: ClipboardSyncStats? = null
    /** 非阻塞的次要提示（如图片待补）：不表示同步失败，不触发重试，仅在 UI 展示。 */
    @Volatile var lastSyncNotice: String? = null
    @Volatile var onlineDeviceIds: Set<String> = emptySet()
    val connectedDeviceCount: Int get() = (onlineDeviceIds - ClipboardOplog.deviceId).size
    val joinedRoom: String get() = currentRoom
    val joinedRoomPassword: String get() = currentPassword
    val lastHeartbeatOk: Long get() = lastPingOk
    /** 最近一次「连不上 / 连接中断」的时间：晚于最近一次成功就算断开，不再等 2 小时过期。 */
    @Volatile private var lastLinkFailAt = 0L
    /**
     * 已连接 = 最近一次成功晚于最近一次连接失败，且成功足够新：
     * 有 wait/1 长轮询时每 25 秒就有一次成功，超过 90 秒没成功即视为断开；
     * 旧主机没有长轮询，只能按托底周期（15 分钟）再加 5 分钟宽限判断。
     */
    val isConnected: Boolean get() {
        if (currentRoom.isEmpty() || lastPingOk <= 0 || lastLinkFailAt > lastPingOk) return false
        val fresh = if (ClipboardSyncCapabilities.WAIT_V1 in hostCapabilities) 90_000L else HEARTBEAT_NO_WAIT_MS + 5 * 60_000L
        return System.currentTimeMillis() - lastPingOk <= fresh
    }

    private fun linkDown() {
        val wasUp = isConnected
        lastLinkFailAt = System.currentTimeMillis()
        if (wasUp) runCatching { onDisconnected?.invoke() }
    }

    /** 连续传输失败次数；任一请求成功清零。（linkFailure 已改为失败即断开，保留计数仅供日志/排查。） */
    private val consecutiveLinkFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 任何一次 IO 层失败（连接被拒、重置、中断、读不到响应）都立即判定断开：
     * 显示上宁可偶尔多一次「重连中」，也不能反过来——主机明明离线了还长时间显示已连接。
     * 不做探活轮询：只在本机本来就要发请求（同步/词库/手动操作）时才会发现失败。
     */
    private fun linkFailure(req: ClipboardSyncWireRequest, conn: HttpURLConnection) {
        if (req.kind == "wait" && abortedWaitConn === conn) return // kick 主动中断的旧长轮询，不算失败
        linkDown()
        // 唤醒同步循环尽快复核/重试，而不是干等托底周期
        ClipboardSyncSignal.wake()
    }

    /** 当前正在阻塞读的长轮询连接；kick 时主动断开，旧请求不必再占着主机等待名额和本机 IO 线程。 */
    @Volatile private var activeWaitConn: HttpURLConnection? = null
    @Volatile private var abortedWaitConn: HttpURLConnection? = null
    @Volatile private var lastKickAt = 0L
    @Volatile var onDisconnected: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var waitJob: Job? = null
    private var monitorJob: Job? = null
    /** 每次重启长轮询 +1；被取消但还卡在阻塞读里的旧 waitLoop 读完后发现 epoch 变了就自行退出，不会出现两个长轮询。 */
    @Volatile private var waitEpoch = 0L
    /** 本机与主机的时钟差（主机 - 本机，毫秒）。只用来校正请求时间戳与响应时效检查，不改系统时间。 */
    @Volatile private var clockOffsetMs = 0L

    init {
        // 服务端每个响应都带 Connection: close；在首次建连之前关闭连接复用，避免复用半关闭的连接。
        System.setProperty("http.keepAlive", "false")
    }
    @Volatile private var generation = 0L
    private val requestLock = Mutex()

    fun joinRoom(addr: String, pwd: String) {
        require(pwd.isNotEmpty()) { "房间密码不能为空" }
        val normalized = normalizeBaseUrl(addr)
        ClipboardManager.enableSyncRetention()
        synchronized(this) {
            generation++
            currentRoom = normalized; currentPassword = pwd; lastPingOk = 0L
            peerInventory = null; hostCapabilities = emptySet(); clockOffsetMs = 0L; lastLinkFailAt = 0L
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncNotice = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); waitJob?.cancel(); monitorJob?.cancel(); val myGeneration = generation
            job = scope.launch { loop(myGeneration, normalized, pwd) }
            val epoch = ++waitEpoch
            waitJob = scope.launch { waitLoop(myGeneration, normalized, pwd, epoch) }
            monitorJob = scope.launch { suspendMonitor(myGeneration, normalized) }
        }
        // 监听主机 NOTIFY（内容有变化）：收到即唤醒同步循环立即对齐。
        // room 端口从地址解析；失败则该类触发退化为 1 小时托底，不影响主链路。
        val roomPort = runCatching { URI(normalized).port }.getOrNull() ?: 0
        if (roomPort > 0) {
            Timber.i("clb sync watch start: room=%s port=%d", normalized, roomPort)
            ClipboardSyncDiscovery.startWatch(roomPort) {
                Timber.i("clb sync wake by NOTIFY: room=%s", normalized)
                // 只唤醒同步循环；不把本机数据标脏，也不会在本机兼任主机时反向广播。
                ClipboardSyncSignal.wake()
            }
        } else {
            Timber.w("clb sync watch NOT started: cannot parse port from %s", normalized)
        }
    }

    fun leaveRoom() {
        synchronized(this) {
            generation++; currentRoom = ""; currentPassword = ""; lastPingOk = 0L
            peerInventory = null; hostCapabilities = emptySet(); clockOffsetMs = 0L; lastLinkFailAt = 0L
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncNotice = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); job = null; waitJob?.cancel(); waitJob = null; monitorJob?.cancel(); monitorJob = null; waitEpoch++
        }
        ClipboardSyncDiscovery.stopWatch()
    }

    private suspend fun loop(myGeneration: Long, room: String, pwd: String) {
        // 托底重复机制：不做高频心跳探活。本地变更 / 主机 NOTIFY 唤醒即时同步；
        // 无动静按 1 小时托底。失败后指数退避（30s 起），不无限叠加报错。
        var realFailures = 0
        var backoffMs = 30_000L
        // 瞬时传输抖动（AEADBadTag / 403 信封错位 / 503 繁忙）：重试即可自愈，
        // 永不当作同步失败展示刷屏；只记日志，连续多次在 notice 提示一次，成功后清空。
        var transientCount = 0
        while (isCurrent(myGeneration, room)) {
            Timber.i("clb sync cycle start: room=%s", room)
            try {
                syncOnce(room, pwd)
                realFailures = 0
                backoffMs = 30_000L
                transientCount = 0
                lastSyncError = null
                lastSyncNotice = null
                Timber.i("clb sync cycle ok: room=%s", room)
            } catch (e: CancellationException) { throw e
            } catch (e: OutOfMemoryError) {
                // 原来只 catch Exception：OOM 会让整个同步协程退出，之后再也不同步（看起来像「断了」）。
                realFailures++
                lastSyncError = "主机 $room · 内存不足，同步包过大（可在主机端开启分块图片传输） · 连续 ${realFailures} 次"
                Timber.e(e, "clipboard sync out of memory (host=%s)", room)
                ClipboardSyncSignal.awaitChangeOrHeartbeat(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
                continue
            } catch (e: Exception) {
                if (e is TransientTransportException) {
                    // 瞬时抖动：不写入 lastSyncError，绝不刷屏。退避后重试，成功后自动清空。
                    transientCount++
                    // 前两次瞬时抖动快速重试（2s/5s），不必等 1 分钟以上、也不需要手动重连；连续多次才进入正常退避。
                    val quick = when (transientCount) { 1 -> 2_000L; 2 -> 5_000L; else -> 0L }
                    if (quick == 0L) backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
                    lastSyncError = null
                    if (transientCount >= TRANSIENT_ERROR_THRESHOLD) {
                        lastSyncNotice = "同步传输偶发抖动，正在自动重试（第 $transientCount 次），一般可自愈"
                    }
                    Timber.w(e, "clipboard sync transient (host=%s, transient=%d/%d)",
                        room, transientCount, TRANSIENT_ERROR_THRESHOLD)
                    ClipboardSyncSignal.awaitChangeOrHeartbeat(if (quick > 0L) quick else backoffMs)
                    continue
                }
                // 真实失败（认证拒绝/时钟异常/数据损坏等）：展示错误并退避重试。
                realFailures++
                transientCount = 0
                lastSyncError = "主机 $room · ${e.message ?: "同步失败"} · 连续 ${realFailures} 次 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                Timber.w(e, "clipboard sync failed (host=%s, consecutive=%d)", room, realFailures)
                // 失败退避期间用短托底周期等唤醒：唤醒（本地变更/NOTIFY）到来可立即重试；
                // 否则退避结束后自动重试一次，避免「静默一小时不重连」。
                ClipboardSyncSignal.awaitChangeOrHeartbeat(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
                continue
            }
            // 词库同步跟随剪切板同步周期（同一循环顺带检查，无独立定时器），但至少间隔 RimeLexiconSync.AUTO_INTERVAL_MS；
            // 实际频率 = min(剪切板唤醒频率, 间隔到期)。失败只记在词库状态里，不影响剪切板同步。
            // 限频用持久化时间戳，进程重启也不会立刻再来一次。
            if (RimeLexiconSync.enabled && ClipboardSyncCapabilities.LEXICON_V1 in hostCapabilities &&
                RimeLexiconSync.msSinceLastAutoAttempt() >= RimeLexiconSync.AUTO_INTERVAL_MS) {
                RimeLexiconSync.markAutoAttempt()
                runCatching { lexiconSyncOnce(room, pwd) }.onFailure { if (it is CancellationException) throw it }
            }
            ClipboardSyncSignal.awaitChangeOrHeartbeat(
                if (ClipboardSyncCapabilities.WAIT_V1 in hostCapabilities) HEARTBEAT_MS else HEARTBEAT_NO_WAIT_MS)
        }
    }

    /**
     * wait/1 长轮询：主机内容（指纹）一变就返回，本机立即唤醒同步循环。
     * 不依赖 UDP，路由器隔离组播/广播、熄屏过滤时依然及时；旧主机不支持时自动停用，退回 NOTIFY + 15 分钟托底。
     */
    /**
     * 立刻重新确认连接：亮屏、网络恢复、进程从冻结/休眠中醒来时调用。
     * 重启长轮询（旧的那条连接多半已经死在休眠里，要等 60s 读超时才会发现），并唤醒同步循环发一次真实请求；
     * 失败会立刻走 linkDown，状态马上变成「重连中」，不会继续显示已连接。
     */
    fun kick(reason: String) {
        synchronized(this) {
            if (currentRoom.isEmpty()) return
            // 亮屏、解锁、网络恢复往往在 1~2 秒内连续到达：3 秒内只重启一次长轮询，其余只唤醒同步循环。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastKickAt >= KICK_DEBOUNCE_MS) {
                lastKickAt = now
                val gen = generation; val room = currentRoom; val pwd = currentPassword
                activeWaitConn?.let { old -> abortedWaitConn = old; runCatching { old.disconnect() } }
                waitJob?.cancel(); val epoch = ++waitEpoch
                waitJob = scope.launch { waitLoop(gen, room, pwd, epoch) }
            }
        }
        Timber.i("clb sync kick: %s", reason)
        ClipboardSyncSignal.wake()
    }

    /** 网络丢失等明确断开信号：立即标记断开（不必等请求超时）。 */
    fun markLinkDown(reason: String) {
        if (currentRoom.isEmpty()) return
        Timber.i("clb sync link down: %s", reason)
        linkDown()
    }

    /**
     * 后台被系统冻结（厂商省电 / cached freezer）或 CPU 深睡时协程整段不跑，旧逻辑醒来后不知道自己睡过，
     * 长轮询连接早已失效却仍按「最近成功」显示已连接。这里每 10 秒打一次点，
     * 两次之间实际流逝（elapsedRealtime，含深睡）明显超过 10 秒就说明睡过/冻过：立刻重新确认。
     */
    private suspend fun suspendMonitor(myGeneration: Long, room: String) {
        var last = android.os.SystemClock.elapsedRealtime()
        while (isCurrent(myGeneration, room)) {
            delay(10_000L)
            val now = android.os.SystemClock.elapsedRealtime()
            val gap = now - last; last = now
            if (gap > 40_000L && isCurrent(myGeneration, room)) {
                Timber.w("clb sync resumed after %d ms suspended; re-verifying link", gap)
                // 睡得超过长轮询判定窗口：先按断开显示，等真实请求成功再恢复「已连接」。
                if (gap > 90_000L) linkDown()
                kick("resumed after ${gap / 1000}s")
            }
        }
    }

    private suspend fun waitLoop(myGeneration: Long, room: String, pwd: String, epoch: Long) {
        var baseline = ""
        var backoffMs = 5_000L
        while (isCurrent(myGeneration, room) && waitEpoch == epoch) {
            if (ClipboardSyncCapabilities.WAIT_V1 !in hostCapabilities) { delay(30_000L); continue }
            try {
                val resp = requestAt(room, pwd, ClipboardSyncWireRequest("wait", ClipboardOplog.deviceId, baseline,
                    capabilities = ClipboardSyncCapabilities.LOCAL), readTimeoutMs = WAIT_READ_TIMEOUT_MS)
                if (!isCurrent(myGeneration, room) || waitEpoch != epoch) return
                lastPingOk = System.currentTimeMillis(); onlineDeviceIds = resp.onlineClientIds.toSet()
                if (baseline.isNotEmpty() && resp.statusHash != baseline) {
                    Timber.i("clb sync wake by wait/1: room=%s", room)
                    ClipboardSyncSignal.wake()
                }
                baseline = resp.statusHash
                backoffMs = 5_000L
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (e.message?.contains("未协商 wait/1") == true) { hostCapabilities = hostCapabilities - ClipboardSyncCapabilities.WAIT_V1; continue }
                Timber.w("clipboard wait/1 failed (%s): %s", room, e.javaClass.simpleName)
                delay(backoffMs); backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun isCurrent(gen: Long, room: String) = generation == gen && currentRoom == room && room.isNotEmpty()

    private val lexiconLock = Mutex()

    /** 主机是否支持词库同步（双方都声明了 lexicon/1）。 */
    val hostSupportsLexicon: Boolean get() = ClipboardSyncCapabilities.LEXICON_V1 in hostCapabilities

    data class LexiconResult(val downloaded: Int, val uploaded: Int, val merged: Boolean)

    /**
     * 一次词库同步（lexicon/1）：
     * 1. 键盘空闲时让 Rime 合并已有快照并导出本机快照；
     * 2. 拉主机清单，下载别的设备有变化的快照到 sync/<对方 id>/（从不写本机自己的目录）；
     * 3. 把本机快照中主机没有或不同的上传；
     * 4. 下载到新快照就再让 Rime 合并一次（正在输入则等键盘收起）。
     * 结果和错误只记在词库状态里，不计入剪切板同步失败。
     */
    suspend fun lexiconSyncOnce(baseUrl: String = currentRoom, password: String = currentPassword): LexiconResult = lexiconLock.withLock {
        try {
            require(RimeLexiconSync.enabled) { "未开启用户词库同步" }
            require(hostSupportsLexicon) { "主机版本不支持词库同步，请两端都升级" }
            RimeLexiconSync.prepare()?.let { throw IllegalStateException(it) }
            val me = RimeLexiconSync.installationId() ?: throw IllegalStateException("Rime 尚未初始化")
            val caps = ClipboardSyncCapabilities.LOCAL
            val myClient = ClipboardOplog.deviceId
            RimeLexiconSync.mergeAndExport()

            val list = requestAt(baseUrl, password, ClipboardSyncWireRequest("lexicon_list", myClient, capabilities = caps))
            val hostId = list.lexiconOwner
            if (hostId == me) throw IllegalStateException("本机与主机的 Rime installation_id 相同（多半是直接拷贝了数据目录）：请在一台设备上删除 installation.yaml 后重新部署")
            val remote = list.lexiconFiles.filter { RimeLexiconSync.isSafeId(it.owner) && RimeLexiconSync.isSafeName(it.name) }
                .take(RimeLexiconSync.MAX_FILES)

            var downloaded = 0
            for (info in remote) {
                if (info.owner == me) continue // 本机目录只由本机 Rime 写
                if (info.size !in 1..RimeLexiconSync.MAX_FILE_BYTES) continue
                val local = RimeLexiconSync.fileFor(info.owner, info.name)
                if (local.isFile && local.length() == info.size && RimeLexiconSync.sha256(local) == info.sha256) continue
                var offset = 0L
                while (offset < info.size) {
                    val resp = requestAt(baseUrl, password, ClipboardSyncWireRequest("lexicon_get", myClient,
                        lexiconPath = "${info.owner}/${info.name}", blobOffset = offset, capabilities = caps))
                    // 下载途中主机的这份快照变了：放弃这份，下次再来
                    if (resp.statusHash != info.sha256 || resp.blobTotal != info.size) { offset = -1; break }
                    val chunk = java.util.Base64.getDecoder().decode(resp.blobData.orEmpty())
                    if (chunk.isEmpty()) throw IllegalStateException("主机返回了空分块")
                    RimeLexiconSync.acceptChunk(info.owner, info.name, offset, info.size, info.sha256, chunk, "dl")
                    offset += chunk.size
                }
                if (offset >= info.size) downloaded++
            }

            var uploaded = 0
            val remoteMine = remote.filter { it.owner == me }.associateBy { it.name }
            RimeLexiconSync.listLocal().filter { it.owner == me }.forEach { info ->
                if (remoteMine[info.name]?.sha256 == info.sha256) return@forEach
                val f = RimeLexiconSync.fileFor(me, info.name)
                var offset = 0L
                while (offset < info.size) {
                    val chunk = RimeLexiconSync.readChunk(f, offset)
                    if (chunk.isEmpty()) break
                    requestAt(baseUrl, password, ClipboardSyncWireRequest("lexicon_put", myClient,
                        lexiconPath = "$me/${info.name}", lexiconOwner = me, blobHash = info.sha256,
                        blobData = java.util.Base64.getEncoder().encodeToString(chunk),
                        blobOffset = offset, blobTotal = info.size, capabilities = caps))
                    offset += chunk.size
                }
                uploaded++
            }

            val merged = if (downloaded > 0) RimeLexiconSync.mergeAndExport().also { if (!it) RimeLexiconSync.requestMergeWhenIdle() } else false
            Timber.i("lexicon sync ok: down=%d up=%d merged=%s", downloaded, uploaded, merged)
            RimeLexiconSync.recordResult(null)
            LexiconResult(downloaded, uploaded, merged)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Timber.w(e, "lexicon sync failed")
            RimeLexiconSync.recordResult(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    fun isHostReachable(baseUrl: String, password: String): Boolean {
        if (password.isEmpty()) return false
        return runCatching { runBlocking { requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest("heartbeat", ClipboardOplog.deviceId, "")) }; true }.getOrDefault(false)
    }

    /**
     * 一次内容同步。主机心跳确认了 engine/1（且本机也是当前房间）就走 localsync 引擎的增量协议，
     * 否则走原来的快照协议；两条路径写的是同一个 Room 库，可以随时切换。
     */
    suspend fun syncOnce(baseUrl: String = currentRoom, password: String = currentPassword, onProgress: ((String, Int, Int, Int) -> Unit)? = null): Int {
        val normalized = normalizeBaseUrl(baseUrl)
        val sameRoom = normalized == currentRoom && password == currentPassword
        // 不再有独立的循环探活。同步本身就是探活/托底：仅在首次（能力尚未协商）
        // 时做一次低频能力协商（等同"1小时托底探活"），失败即进入同步失败路径，
        // 不会循环重试、不弹重复错误。协商成功后本会话内不再重复。
        if (sameRoom && hostCapabilities.isEmpty()) {
            val probe = runCatching {
                requestAt(normalized, password, ClipboardSyncWireRequest("heartbeat", ClipboardOplog.deviceId,
                    ClipboardSnapshotStore.fingerprint(), capabilities = ClipboardSyncCapabilities.LOCAL))
            }.getOrNull()
            if (probe != null) {
                hostCapabilities = ClipboardSyncCapabilities.negotiated(probe.capabilities)
                onlineDeviceIds = probe.onlineClientIds.toSet()
                lastPingOk = System.currentTimeMillis()
            }
        }
        return if (sameRoom && ClipboardSyncCapabilities.engineMode(hostCapabilities)) requestLock.withLock { engineSyncOnce(baseUrl, password, onProgress) }
        else legacySyncOnce(baseUrl, password, onProgress)
    }

    private suspend fun engineSyncOnce(baseUrl: String, password: String, onProgress: ((String, Int, Int, Int) -> Unit)?): Int {
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val url = normalizeBaseUrl(baseUrl)
        val startGeneration = generation
        val caps = ClipboardSyncCapabilities.LOCAL
        val me = ClipboardOplog.deviceId
        fun checkCurrent() { if (startGeneration != generation) throw CancellationException("房间已切换或退出") }
        try {
            onProgress?.invoke("扫描本地变更", 0, 0, 0)
            ClipboardEngineSync.scan()
            val hello = requestAt(url, password, ClipboardSyncWireRequest("engine_hello", me,
                vv = ClipboardEngineSync.versionVector(), acks = ClipboardEngineSync.ackGossip(), capabilities = caps))
            checkCurrent()
            require(hello.deviceId.isNotBlank() && hello.deviceId != me) { "主机设备标识无效" }
            ClipboardEngineSync.recordPeer(hello.deviceId, hello.vv, hello.acks)
            onlineDeviceIds = hello.onlineClientIds.toSet()

            // ---- pull：拿本机缺的操作。时钟漂移/格式错误整批拒收，版本向量不推进 ----
            var applied = 0; var pulled = 0; var stats: ClipboardSyncStats? = null; var guard = 0
            while (true) {
                val page = requestAt(url, password, ClipboardSyncWireRequest("engine_pull", me,
                    vv = ClipboardEngineSync.versionVector(), limit = ENGINE_PAGE, capabilities = caps))
                checkCurrent()
                val out = ClipboardEngineSync.applyRemote(page.ops)
                pulled += page.ops.size; applied += out.applied
                out.stats?.let { s -> stats = stats?.let { combine(it, s) } ?: s }
                ClipboardDelivery.recordPeerReceipt(hello.deviceId, ClipboardEngineSync.receiptVersions(page.ops))
                if (out.missingBlobs.isNotEmpty()) fetchEngineBlobs(url, password, onProgress)?.let { s -> stats = stats?.let { combine(it, s) } ?: s }
                onProgress?.invoke("接收变更 $pulled 条", 0, pulled, applied)
                if (!page.hasMore) break
                if (page.ops.isEmpty() || ++guard > MAX_PAGES) error("拉取变更没有进展，本次未完成")
            }

            // ---- push：只按主机真实回应的版本向量推进，绝不吸收对端向量 ----
            var remoteVv = hello.vv; var pushed = 0; var lastStatus = hello.statusHash; guard = 0
            while (true) {
                val (ops, hasMore) = ClipboardEngineSync.page(remoteVv, ENGINE_PAGE)
                if (ops.isEmpty()) break
                val resp = ClipboardDelivery.withSendFence { release ->
                    val ids = ClipboardEngineSync.entryIds(ops)
                    if (ids.isNotEmpty()) ClipboardDelivery.beginExposure(ClipboardEngineSync.exposurePacket(ids))
                    release()
                    requestAt(url, password, ClipboardSyncWireRequest("engine_push", me, ops = ops, capabilities = caps,
                        vv = ClipboardEngineSync.versionVector()))
                }
                checkCurrent()
                ClipboardDelivery.confirm(resp.receivedEntryIds.toSet().intersect(ClipboardEngineSync.entryIds(ops)))
                ClipboardDelivery.recordPeerReceipt(hello.deviceId, ClipboardEngineSync.receiptVersions(ops).filterKeys { it in resp.receivedEntryIds })
                ClipboardEngineSync.recordPeer(hello.deviceId, resp.vv)
                lastStatus = resp.statusHash
                pushed += ops.size
                var latestVv = resp.vv
                resp.missingBlobs.forEachIndexed { i, sha ->
                    val file = ClipboardEngineSync.blobFile(sha) ?: return@forEachIndexed
                    onProgress?.invoke("补传图片 ${i + 1}/${resp.missingBlobs.size}", pushed, pulled, applied)
                    val r = putBlob(url, password, sha, file) ?: return@forEachIndexed
                    lastStatus = r.statusHash
                    // 等图的影子记录在图片收齐后才推进主机向量：以补传后的向量判断进展，避免误报「没有进展」。
                    if (r.vv.isNotEmpty()) latestVv = r.vv
                }
                if (latestVv !== resp.vv) ClipboardEngineSync.recordPeer(hello.deviceId, latestVv)
                onProgress?.invoke("发送变更 $pushed 条", pushed, pulled, applied)
                if (!hasMore) break
                if (latestVv == remoteVv || ++guard > MAX_PAGES) error("推送变更没有进展，本次未完成")
                remoteVv = latestVv
            }
            ClipboardEngineSync.collectGarbage()

            stats?.let { lastSyncSummary = it; ClipboardSyncNotifications.record(it) }
            lastPingOk = System.currentTimeMillis()
            lastUploadedLocalFingerprint = ClipboardSnapshotStore.fingerprint()
            lastSeenServerFingerprint = lastStatus
            // 文字/分组/版本向量已全部一致：缺图只是「等源设备上线补传」的待办状态，
            // 不是失败。若按 error 抛会让 loop() 把它当同步失败反复重试（报错→清空→再报错
            // 的抽搐循环）。降级为次要提示，本次同步正常结束。
            val waiting = ClipboardEngineSync.waitingBlobs()
            val missing = ClipboardSnapshotStore.missingImageIds()
            lastSyncNotice = when {
                waiting.isNotEmpty() -> "文字及分组已同步，${waiting.size} 张图片尚未拿到原图；源设备在线后会自动补齐"
                missing.isNotEmpty() -> "文字及分组已同步，${missing.size} 张图片源缺失；请从有原图的设备补传"
                else -> null
            }
            lastSyncError = null
            onProgress?.invoke("完成（接收 $pulled 条，发送 $pushed 条，落地 $applied 条）", pushed, pulled, applied)
            return applied
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            lastSyncError = e.message ?: "同步失败"
            throw e
        }
    }

    /** 拉取引擎影子记录在等的图片（逐张、校验哈希），然后扫描一次让它们落地。 */
    private suspend fun fetchEngineBlobs(url: String, password: String, onProgress: ((String, Int, Int, Int) -> Unit)?): ClipboardSyncStats? {
        val waiting = ClipboardEngineSync.waitingBlobs()
        if (waiting.isEmpty()) return null
        var got = 0
        waiting.entries.forEachIndexed { i, (sha, mime) ->
            onProgress?.invoke("拉取图片 ${i + 1}/${waiting.size}", 0, i, 0)
            try {
                ClipboardEngineSync.storeFetchedBlob(sha, fetchBlob(url, password, sha), mime)
                got++
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Timber.w("clipboard image fetch failed (%s)", e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) { Timber.e("clipboard image too large to fetch in one piece (%s)", sha) }
        }
        return if (got > 0) ClipboardEngineSync.scan().stats else null
    }

    private fun combine(a: ClipboardSyncStats, b: ClipboardSyncStats) = ClipboardSyncStats(
        a.added + b.added, a.mergedExisting + b.mergedExisting, a.updated + b.updated, a.deleted + b.deleted, b.totalAfter,
        a.addedDetails + b.addedDetails, a.updatedDetails + b.updatedDetails, a.deletedDetails + b.deletedDetails)

    private suspend fun legacySyncOnce(baseUrl: String, password: String, onProgress: ((String, Int, Int, Int) -> Unit)?): Int = requestLock.withLock {
        ClipboardDelivery.withSendFence { releaseFence ->
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val startGeneration = generation
        try {
        onProgress?.invoke("读取本地数据", 0, 0, 0)
        val sameRoom = normalizeBaseUrl(baseUrl) == currentRoom && password == currentPassword
        // blob/1 只在本房间、且主机心跳确认支持时启用；否则完全走旧路径。
        val blobMode = sameRoom && ClipboardSyncCapabilities.BLOB_V1 in hostCapabilities
        val captured = ClipboardSnapshotStore.capture(blobMode)
        val packet = captured.packet
        val uploadFingerprint = captured.fingerprint
        val inventory = ClipboardSyncDelta.inventory(packet)
        val knownPeer = if (sameRoom && peerInventoryBlobMode == blobMode) peerInventory else null
        val delta = ClipboardSyncDelta.difference(packet, knownPeer, inventory)
        // 上传方向：只把这次要发的变更还原出图片字节（主机拉不到客户端），其余图片一律不编码。
        val outgoing = if (blobMode) ClipboardSnapshotStore.hydrate(delta, captured.blobs) else delta
        onProgress?.invoke("交换变更（条目 ${outgoing.entries.size}，分组 ${outgoing.groups.size}，历史 ${outgoing.history.size}）", outgoing.entries.size, 0, 0)
        val body = requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest("sync", ClipboardOplog.deviceId, uploadFingerprint, json.encodeToString(outgoing), inventory,
            capabilities = if (blobMode) ClipboardSyncCapabilities.LOCAL else emptyList()), releaseFence)
        if (startGeneration != generation) throw CancellationException("房间已切换或退出")
        if (!body.ok || body.packetJson == null) throw IllegalStateException(body.error ?: "同步响应无效")
        val incoming = json.decodeFromString<ClipboardSyncPacket>(body.packetJson)
        require(incoming.protocolVersion == ClipboardSyncCrypto.PROTOCOL_VERSION && incoming.deviceId.isNotBlank()) { "同步响应协议或设备标识无效" }
        require(incoming.deviceId != ClipboardOplog.deviceId) { "不能把本机当作其他同步设备" }
        val responseBlobMode = ClipboardSyncCapabilities.blobMode(body.capabilities)
        val fetched = if (responseBlobMode) fetchMissingBlobs(baseUrl, password, incoming, onProgress) else emptyList()
        ClipboardDelivery.confirm(body.receivedEntryIds.toSet().intersect(ClipboardDelivery.ids(outgoing)))
        val outgoingVersions = outgoing.entries.associate { it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId) }
        ClipboardDelivery.recordPeerReceipt(incoming.deviceId, outgoingVersions.filterKeys { it in body.receivedEntryIds })
        lastPingOk = System.currentTimeMillis(); onlineDeviceIds = body.onlineClientIds.toSet()
        val (applied, rawStats) = ClipboardSnapshotStore.mergeWithStats(incoming)
        ClipboardDelivery.recordPeerReceipt(incoming.deviceId, incoming.entries.associate {
            it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
        })
        runCatching {
            requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest(
                "ack", ClipboardOplog.deviceId, receivedEntryIds = ClipboardDelivery.ids(incoming).toList(),
                receivedEntryVersions = incoming.entries.associate { it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId) }))
        }.onFailure { if (it is CancellationException) throw it }
        // A delta does not echo matching rows; count matches from the complete authenticated inventory.
        val stats = rawStats.copy(mergedExisting = body.inventory?.entries?.keys?.count { id -> inventory.entries.containsKey(id) }
            ?: rawStats.mergedExisting)
        lastSyncSummary = stats
        ClipboardSyncNotifications.record(stats)
        if (fetched.isNotEmpty()) ClipboardSnapshotStore.releaseUnreferencedBlobs(fetched)
        if (sameRoom) { peerInventory = body.inventory; peerInventoryBlobMode = responseBlobMode }
        val missing = ClipboardSnapshotStore.missingImageIds()
        // The received merge is durable; an unavailable original is not fixed by resending identical bytes.
        // Track transport progress but retain an explicit incomplete-content error until a source changes.
        // 缺图是待办状态不是失败：降级为 notice，避免 loop() 把它当失败反复重试造成抽搐。
        lastSyncNotice = if (missing.isNotEmpty()) "文字及分组已合并，${missing.size} 张图片源缺失，尚未完成全部同步；请从有原图的设备补传" else null
        lastUploadedLocalFingerprint = uploadFingerprint
        lastSeenServerFingerprint = body.statusHash
        lastPingOk = System.currentTimeMillis(); onlineDeviceIds = body.onlineClientIds.toSet(); lastSyncError = null
        onProgress?.invoke("完成（新增 ${stats.added}，已有匹配 ${stats.mergedExisting}，更新 ${stats.updated}，删除 ${stats.deleted}，现有 ${stats.totalAfter}）", packet.entries.size, incoming.entries.size, applied)
        applied
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            lastSyncError = e.message ?: "同步失败"
            throw e
        }
        }
    }

    suspend fun previewFullTransfer(baseUrl: String = currentRoom, password: String = currentPassword): ClipboardFullTransferPreview = requestLock.withLock {
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val local = ClipboardSnapshotStore.metadataCapture()
        val body = requestAt(normalizeBaseUrl(baseUrl), password,
            ClipboardSyncWireRequest("full_preview", ClipboardOplog.deviceId))
        require(body.statusHash.isNotBlank() && body.summary != null && body.deviceId.isNotBlank()) {
            "主机未返回可用的全量覆盖预览"
        }
        require(body.deviceId != ClipboardOplog.deviceId) { "不能把本机当作远端覆盖目标" }
        ClipboardFullTransferPreview(local.fingerprint, body.statusHash,
            ClipboardSyncSnapshotSummary.from(local.packet), body.summary, body.deviceId)
    }

    /** The client collection replaces the host collection, then adopts the host's tombstones. */
    suspend fun uploadFullSnapshot(preview: ClipboardFullTransferPreview, baseUrl: String = currentRoom,
        password: String = currentPassword): ClipboardSyncStats = requestLock.withLock {
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val startGeneration = generation
        ClipboardDelivery.withSendFence { releaseFence ->
            val captured = ClipboardSnapshotStore.capture()
            require(captured.fingerprint == preview.localFingerprint) { "本机剪贴板在预览后有变化，请重新预览" }
            val outgoing = captured.packet
            val versions = outgoing.entries.associate { it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId) }
            val response = requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest(
                "full_upload", ClipboardOplog.deviceId, statusHash = preview.remoteFingerprint,
                packetJson = json.encodeToString(outgoing), expectedFingerprint = preview.remoteFingerprint), releaseFence)
            if (startGeneration != generation) throw CancellationException("房间已切换或退出")
            val remote = json.decodeFromString<ClipboardSyncPacket>(response.packetJson ?: error("主机未返回覆盖后的快照"))
            require(remote.deviceId == preview.remoteDeviceId) { "主机设备标识已变化，请重新预览" }
            require(response.summary != null && response.deviceId == preview.remoteDeviceId) { "主机未返回完整覆盖回执" }
            ClipboardDelivery.confirmVersions(response.receivedEntryIds, versions)
            ClipboardDelivery.recordPeerReceipt(response.deviceId,
                response.receivedEntryVersions.filterKeys { it in ClipboardDelivery.ids(remote) })
            val (_, stats) = ClipboardSnapshotStore.mergeWithStats(remote)
            ClipboardSyncNotifications.record(stats)
            runCatching { requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest(
                "ack", ClipboardOplog.deviceId, receivedEntryIds = ClipboardDelivery.ids(remote).toList(),
                receivedEntryVersions = response.receivedEntryVersions)) }.onFailure { if (it is CancellationException) throw it }
            lastSyncSummary = stats
            lastPingOk = System.currentTimeMillis()
            onlineDeviceIds = response.onlineClientIds.toSet()
            peerInventory = null
            lastSyncError = null
            lastUploadedLocalFingerprint = ClipboardSnapshotStore.fingerprint()
            lastSeenServerFingerprint = response.statusHash
            stats
        }
    }

    /** The authenticated host snapshot replaces this client's collection exactly, except safe tombstones. */
    suspend fun downloadFullSnapshot(preview: ClipboardFullTransferPreview, baseUrl: String = currentRoom,
        password: String = currentPassword): ClipboardSyncStats = requestLock.withLock {
        require(password.isNotEmpty()) { "房间密码不能为空" }
        val startGeneration = generation
        ClipboardDelivery.withSendFence { releaseFence ->
            val local = ClipboardSnapshotStore.metadataCapture()
            require(local.fingerprint == preview.localFingerprint) { "本机剪贴板在预览后有变化，请重新预览" }
            val response = requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest(
                "full_download", ClipboardOplog.deviceId, statusHash = preview.localFingerprint,
                expectedFingerprint = preview.remoteFingerprint), releaseFence)
            if (startGeneration != generation) throw CancellationException("房间已切换或退出")
            val incoming = json.decodeFromString<ClipboardSyncPacket>(response.packetJson ?: error("主机未返回完整快照"))
            require(incoming.deviceId == preview.remoteDeviceId) { "主机设备标识已变化，请重新预览" }
            require(response.statusHash == preview.remoteFingerprint) { "主机内容在预览后有变化，请重新预览" }
            require(response.summary != null && response.deviceId == preview.remoteDeviceId) { "主机未返回完整覆盖回执" }
            ClipboardManualRestore.overwrite(incoming, preview.localFingerprint)
            ClipboardDelivery.recordPeerReceipt(incoming.deviceId, incoming.entries.associate {
                it.syncId to ClipboardReceiptVersion.token(it.updatedAt, it.deviceId)
            })
            runCatching { requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest(
                "ack", ClipboardOplog.deviceId, receivedEntryIds = ClipboardDelivery.ids(incoming).toList(),
                receivedEntryVersions = response.receivedEntryVersions)) }.onFailure { if (it is CancellationException) throw it }
            val stats = ClipboardSyncSummary.summarize(local.packet, incoming,
                ClipboardSnapshotStore.metadataCapture().packet)
            ClipboardSyncNotifications.record(stats)
            lastSyncSummary = stats
            lastPingOk = System.currentTimeMillis()
            onlineDeviceIds = response.onlineClientIds.toSet()
            peerInventory = null
            lastSyncError = null
            lastUploadedLocalFingerprint = ClipboardSnapshotStore.fingerprint()
            lastSeenServerFingerprint = response.statusHash
            stats
        }
    }

    /** 逐张拉取快照里引用、本机没有的图片；每张都校验哈希后才落盘。失败的留给合并按「缺图」处理。 */
    private suspend fun fetchMissingBlobs(baseUrl: String, password: String, incoming: ClipboardSyncPacket,
        onProgress: ((String, Int, Int, Int) -> Unit)?): List<java.io.File> {
        val dir = ClipboardSnapshotStore.imagesDir() ?: return emptyList()
        val mimes = HashMap<String, String>()
        incoming.entries.forEach { e -> e.imageSha256?.let { mimes.putIfAbsent(it, e.type) } }
        incoming.history.forEach { h -> h.entry?.let { e -> e.imageSha256?.let { mimes.putIfAbsent(it, e.type) } } }
        val missing = ClipboardImageBlobs.referencedHashes(incoming).filter { ClipboardImageBlobs.find(dir, it) == null }
        val stored = ArrayList<java.io.File>()
        missing.forEachIndexed { i, sha ->
            onProgress?.invoke("拉取图片 ${i + 1}/${missing.size}", 0, i, 0)
            try {
                val bytes = fetchBlob(normalizeBaseUrl(baseUrl), password, sha)
                stored += ClipboardImageBlobs.store(dir, sha, bytes, mimes[sha] ?: "image/png")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Timber.w("clipboard image fetch failed (%s)", e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) { Timber.e("clipboard image too large to fetch in one piece (%s)", sha) }
        }
        return stored
    }

    /** 拉一张图：主机支持 blob/2 时按 1 MiB 分块，否则沿用旧的整张传输。返回的字节由调用方校验哈希后落盘。 */
    private suspend fun fetchBlob(url: String, password: String, sha: String): ByteArray {
        if (ClipboardSyncCapabilities.BLOB_V2 !in hostCapabilities) {
            val r = requestAt(url, password, ClipboardSyncWireRequest("blob_get", ClipboardOplog.deviceId,
                blobHash = sha, capabilities = ClipboardSyncCapabilities.LOCAL))
            return java.util.Base64.getDecoder().decode(r.blobData ?: error("主机未返回图片"))
        }
        var offset = 0L; var out: java.io.ByteArrayOutputStream? = null; var total = -1L
        while (total < 0 || offset < total) {
            val r = requestAt(url, password, ClipboardSyncWireRequest("blob_get", ClipboardOplog.deviceId,
                blobHash = sha, blobOffset = offset, capabilities = ClipboardSyncCapabilities.LOCAL))
            val piece = java.util.Base64.getDecoder().decode(r.blobData ?: error("主机未返回图片"))
            if (r.blobTotal <= 0L) return piece // 主机按旧方式整张返回（例如图片来自内联数据）
            if (total < 0) { total = r.blobTotal; require(total <= Int.MAX_VALUE) { "图片过大" }; out = java.io.ByteArrayOutputStream(total.toInt()) }
            require(r.blobTotal == total && piece.isNotEmpty()) { "图片分块不一致" }
            out!!.write(piece); offset += piece.size
        }
        return out!!.toByteArray()
    }

    /** 补传一张图：主机支持 blob/2 时按 1 MiB 分块从文件读取上传，否则整张上传。 */
    private suspend fun putBlob(url: String, password: String, sha: String, file: java.io.File): ClipboardSyncWireResponse? {
        val me = ClipboardOplog.deviceId; val caps = ClipboardSyncCapabilities.LOCAL
        if (ClipboardSyncCapabilities.BLOB_V2 !in hostCapabilities) {
            val data = ClipboardImageBlobs.readBase64(ClipboardImageBlobs.Source.FromFile(file)) ?: return null
            return requestAt(url, password, ClipboardSyncWireRequest("blob_put", me, blobHash = sha, blobData = data, capabilities = caps))
        }
        val total = file.length().takeIf { it > 0 } ?: return null
        var offset = 0L; var last: ClipboardSyncWireResponse? = null
        java.io.RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                val chunk = ByteArray(minOf(ClipboardSyncServer.BLOB_CHUNK.toLong(), total - offset).toInt())
                raf.seek(offset); raf.readFully(chunk)
                last = requestAt(url, password, ClipboardSyncWireRequest("blob_put", me, blobHash = sha,
                    blobData = java.util.Base64.getEncoder().encodeToString(chunk), blobOffset = offset, blobTotal = total, capabilities = caps))
                offset += chunk.size
            }
        }
        return last
    }

    private suspend fun requestAt(baseUrl: String, password: String, req: ClipboardSyncWireRequest, afterExposure: (() -> Unit)? = null,
                                  readTimeoutMs: Int = READ_TIMEOUT_MS): ClipboardSyncWireResponse {
        val first = requestOnce(baseUrl, password, req, afterExposure, readTimeoutMs)
        if (first is Attempt.Done) return first.response
        val skew = first as Attempt.ClockSkew
        // 主机说时钟偏差过大：用它回带的时间校正一次请求时间戳再试（新请求号，不会触发重放）。
        // 主机时间头没有经过认证，最坏只能让本机的时效窗口偏移，偏移量另有上限。
        val offset = skew.serverTime - skew.localTime
        if (kotlin.math.abs(offset) > MAX_CLOCK_OFFSET_MS) throw IllegalStateException(skew.message + "（相差超过 30 天，未自动校正）")
        clockOffsetMs = offset
        lastSyncNotice = "本机与主机时钟相差约 ${kotlin.math.abs(offset) / 60_000L} 分钟，已自动校正；建议在两台设备上开启自动时间"
        Timber.w("clipboard sync clock offset applied: %d ms", offset)
        return when (val second = requestOnce(baseUrl, password, req, afterExposure, readTimeoutMs)) {
            is Attempt.Done -> second.response
            is Attempt.ClockSkew -> throw IllegalStateException(second.message)
        }
    }

    private sealed class Attempt {
        class Done(val response: ClipboardSyncWireResponse) : Attempt()
        class ClockSkew(val serverTime: Long, val localTime: Long, val message: String) : Attempt()
    }

    private suspend fun requestOnce(baseUrl: String, password: String, req: ClipboardSyncWireRequest, afterExposure: (() -> Unit)?,
                                    readTimeoutMs: Int): Attempt = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "房间密码不能为空" }; require(req.clientId.isNotBlank()) { "client ID不能为空" }
        val id = ClipboardSyncCrypto.requestId()
        val offset = clockOffsetMs
        val issuedAt = System.currentTimeMillis() + offset
        val envelope = ClipboardSyncCrypto.encrypt(password, id, req.clientId, json.encodeToString(req), ClipboardSyncCrypto.REQUEST_DIRECTION, issuedAt)
        val body = json.encodeToString(envelope).toByteArray()
        require(body.size <= MAX_BODY) { "同步包超过 128 MiB，本次未确认完成；未截断或删除任何条目" }
        val uri = URI(normalizeBaseUrl(baseUrl) + "/sync")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = readTimeoutMs; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Clipboard-Sync-Request", id)
            setRequestProperty("X-Clipboard-Sync-Issued-At", envelope.issuedAt.toString())
            setRequestProperty("X-Clipboard-Sync-Proof", ClipboardSyncCrypto.requestProof(password, id, issuedAt = envelope.issuedAt))
            setFixedLengthStreamingMode(body.size)
        }
        if (req.kind == "wait") activeWaitConn = conn
        try {
            val output = try { conn.outputStream } catch (e: java.net.ConnectException) {
                linkDown()
                throw IllegalStateException("连不上主机 ${uri.host}:${uri.port}（主机未开启房间或不在同一网络）")
            } catch (e: java.io.IOException) { linkFailure(req, conn); throw TransientTransportException("连接主机失败：${e.javaClass.simpleName}") }
            if (req.kind in setOf("sync", "full_upload") && !req.packetJson.isNullOrBlank()) {
                ClipboardDelivery.beginExposure(json.decodeFromString<ClipboardSyncPacket>(req.packetJson))
            }
            afterExposure?.invoke()
            // 主机提前拒绝（403/413/503）时会先回错误再关读端：写入中途失败不代表没有响应，先尝试读取真实错误。
            val writeFailure = try { output.use { it.write(body) }; null } catch (e: java.io.IOException) { e }
            val code = try { conn.responseCode } catch (e: java.io.IOException) {
                linkFailure(req, conn)
                throw TransientTransportException("连接中断（${(writeFailure ?: e).javaClass.simpleName}），未收到主机响应，将自动重试")
            }
            if (code != 200) {
                val serverError = readServerError(conn)
                val errorCode = conn.getHeaderField("X-Clipboard-Sync-Error").orEmpty()
                val message = "主机返回 HTTP $code${if (serverError.isNotEmpty()) "：$serverError" else ""}"
                if (errorCode == "stopping") linkDown()
                if (code == 403 && errorCode == "clock_skew") {
                    val serverTime = conn.getHeaderField("X-Clipboard-Sync-Server-Time")?.toLongOrNull()
                    if (serverTime != null && offset == clockOffsetMs) return@withContext Attempt.ClockSkew(serverTime, System.currentTimeMillis(), message)
                    throw IllegalStateException(message)
                }
                val transient = errorCode in TRANSIENT_CODES || code == 503 ||
                    // 旧主机没有错误码头：沿用按提示文字判断
                    (errorCode.isEmpty() && code == 403 && (serverError.contains("加密信封") || serverError.contains("客户端标识绑定") || serverError.contains("重放")))
                if (transient) throw TransientTransportException(message)
                throw IllegalStateException(message)
            }
            val responseEnvelope = try { readBounded(conn.inputStream, MAX_BODY) } catch (e: java.io.IOException) {
                linkFailure(req, conn)
                throw TransientTransportException("接收主机响应时连接中断（${e.javaClass.simpleName}），将自动重试")
            }
            val wire = try { json.decodeFromString<ClipboardSyncEnvelope>(responseEnvelope) } catch (e: kotlinx.serialization.SerializationException) {
                // 半截 JSON：响应在传输中被截断，不是数据错误
                throw TransientTransportException("主机响应不完整（收到 ${responseEnvelope.length} 字节），将自动重试")
            }
            if (wire.requestId != id || wire.clientId != req.clientId) throw SecurityException("响应未绑定请求")
            // 旧主机偶发省略响应的 issuedAt（同一毫秒编码时），无法还原 AAD：按瞬时抖动立即重试，不当作数据损坏。
            if (wire.issuedAt == MISSING_ISSUED_AT) throw TransientTransportException("主机响应缺少时间戳（旧版主机偶发），将自动重试")
            val result = try {
                json.decodeFromString<ClipboardSyncWireResponse>(ClipboardSyncCrypto.decrypt(password, wire, ClipboardSyncCrypto.RESPONSE_DIRECTION,
                    System.currentTimeMillis() + clockOffsetMs))
            } catch (e: javax.crypto.AEADBadTagException) {
                Timber.w("clipboard response AEAD failure: body=%d ciphertext=%d", responseEnvelope.length, wire.ciphertext.length)
                throw TransientTransportException("同步数据在传输中被截断或损坏（响应密文校验失败），请重试")
            }
            if (result.requestId != id) throw SecurityException("响应请求号不匹配")
            if (result.kind != req.kind) throw SecurityException("响应操作类型不匹配")
            if (!result.ok) throw IllegalStateException(result.error ?: "同步失败")
            consecutiveLinkFailures.set(0)
            Attempt.Done(result)
        } finally {
            if (activeWaitConn === conn) activeWaitConn = null
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) throw IllegalArgumentException("响应过大"); out.write(buf, 0, n) }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 读取服务端在非 200 响应里写的明文原因（"认证失败"/"同步请求被拒绝…"），供诊断展示。 */
    private fun readServerError(conn: HttpURLConnection): String = runCatching {
        conn.errorStream?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(2048); var total = 0
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > 4096) break; out.write(buf, 0, n) }
            out.toString(Charsets.UTF_8.name()).trim().take(256)
        } ?: ""
    }.getOrDefault("")

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().let { if (it.contains("://")) it else "http://$it" }
        val uri = URI(value)
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 http/https" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "地址不得包含用户名、密码、路径或查询参数" }
        require(!uri.host.isNullOrBlank()) { "无效主机地址" }
        return value.trimEnd('/')
    }
}
