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

/** 加密协议 v2 客户端；心跳不携带快照，摘要改变才交换真实快照。 */
object ClipboardSyncClient {
    private val json = Json { ignoreUnknownKeys = true }
    private const val HEARTBEAT_MS = 3_600_000L // 探活周期：每小时一次；本地新增/主机 kick 经信号即时唤醒
    private const val CONNECT_TIMEOUT_MS = 8_000
    // Must be at least as generous as the server's connection lifetime (300s) so a large
    // image-backed snapshot response is never cut mid-envelope by this side's timeout.
    private const val READ_TIMEOUT_MS = 180_000
    private const val MAX_BODY = 128 * 1024 * 1024
    private const val ENGINE_PAGE = 200
    private const val MAX_PAGES = 100_000
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
    val isConnected: Boolean get() = currentRoom.isNotEmpty() && lastPingOk > 0 && System.currentTimeMillis() - lastPingOk <= 2 * 3_600_000L
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
            peerInventory = null; hostCapabilities = emptySet()
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncNotice = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); val myGeneration = generation
            job = scope.launch { loop(myGeneration, normalized, pwd) }
        }
        // 监听主机 NOTIFY（内容有变化）：收到即唤醒同步循环立即对齐。
        // room 端口从地址解析；失败则该类触发退化为 1 小时托底，不影响主链路。
        val roomPort = runCatching { URI(normalized).port }.getOrNull() ?: 0
        if (roomPort > 0) {
            Timber.i("clb sync watch start: room=%s port=%d", normalized, roomPort)
            ClipboardSyncDiscovery.startWatch(roomPort) {
                Timber.i("clb sync wake by NOTIFY: room=%s", normalized)
                ClipboardSyncSignal.changed()
            }
        } else {
            Timber.w("clb sync watch NOT started: cannot parse port from %s", normalized)
        }
    }

    fun leaveRoom() {
        synchronized(this) {
            generation++; currentRoom = ""; currentPassword = ""; lastPingOk = 0L
            peerInventory = null; hostCapabilities = emptySet()
            lastUploadedLocalFingerprint = ""; lastSeenServerFingerprint = ""; lastSyncError = null; lastSyncNotice = null; lastSyncSummary = null; onlineDeviceIds = emptySet()
            job?.cancel(); job = null
        }
        ClipboardSyncDiscovery.stopWatch()
    }

    private suspend fun loop(myGeneration: Long, room: String, pwd: String) {
        // 托底重复机制：不做高频心跳探活。本地变更 / 主机 NOTIFY 唤醒即时同步；
        // 无动静按 1 小时托底。失败后指数退避（30s 起），不无限叠加报错。
        var consecutiveFailures = 0
        var backoffMs = 30_000L
        while (isCurrent(myGeneration, room)) {
            Timber.i("clb sync cycle start: room=%s", room)
            try {
                syncOnce(room, pwd)
                consecutiveFailures = 0
                backoffMs = 30_000L
                lastSyncError = null
                Timber.i("clb sync cycle ok: room=%s", room)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                consecutiveFailures++
                lastSyncError = "主机 $room · ${e.message ?: "同步失败"} · 连续 ${consecutiveFailures} 次 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                Timber.w(e, "clipboard sync failed (host=%s, consecutive=%d)", room, consecutiveFailures)
                // 失败退避期间用短托底周期等唤醒：唤醒（本地变更/NOTIFY）到来可立即重试；
                // 否则退避结束后自动重试一次，避免「静默一小时不重连」。
                ClipboardSyncSignal.awaitChangeOrHeartbeat(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
                continue
            }
            ClipboardSyncSignal.awaitChangeOrHeartbeat(HEARTBEAT_MS)
        }
    }

    private fun isCurrent(gen: Long, room: String) = generation == gen && currentRoom == room && room.isNotEmpty()

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
                resp.missingBlobs.forEachIndexed { i, sha ->
                    val file = ClipboardEngineSync.blobFile(sha) ?: return@forEachIndexed
                    onProgress?.invoke("补传图片 ${i + 1}/${resp.missingBlobs.size}", pushed, pulled, applied)
                    val data = ClipboardImageBlobs.readBase64(ClipboardImageBlobs.Source.FromFile(file)) ?: return@forEachIndexed
                    lastStatus = requestAt(url, password, ClipboardSyncWireRequest("blob_put", me, blobHash = sha, blobData = data, capabilities = caps)).statusHash
                }
                onProgress?.invoke("发送变更 $pushed 条", pushed, pulled, applied)
                if (!hasMore) break
                if (resp.vv == remoteVv || ++guard > MAX_PAGES) error("推送变更没有进展，本次未完成")
                remoteVv = resp.vv
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
                val r = requestAt(url, password, ClipboardSyncWireRequest("blob_get", ClipboardOplog.deviceId,
                    blobHash = sha, capabilities = ClipboardSyncCapabilities.LOCAL))
                ClipboardEngineSync.storeFetchedBlob(sha, java.util.Base64.getDecoder().decode(r.blobData ?: error("主机未返回图片")), mime)
                got++
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Timber.w("clipboard image fetch failed (%s)", e.javaClass.simpleName) }
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
                val r = requestAt(normalizeBaseUrl(baseUrl), password, ClipboardSyncWireRequest("blob_get", ClipboardOplog.deviceId,
                    blobHash = sha, capabilities = ClipboardSyncCapabilities.LOCAL))
                val bytes = java.util.Base64.getDecoder().decode(r.blobData ?: error("主机未返回图片"))
                stored += ClipboardImageBlobs.store(dir, sha, bytes, mimes[sha] ?: "image/png")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Timber.w("clipboard image fetch failed (%s)", e.javaClass.simpleName) }
        }
        return stored
    }

    private suspend fun requestAt(baseUrl: String, password: String, req: ClipboardSyncWireRequest, afterExposure: (() -> Unit)? = null): ClipboardSyncWireResponse = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "房间密码不能为空" }; require(req.clientId.isNotBlank()) { "client ID不能为空" }
        val id = ClipboardSyncCrypto.requestId()
        val envelope = ClipboardSyncCrypto.encrypt(password, id, req.clientId, json.encodeToString(req), ClipboardSyncCrypto.REQUEST_DIRECTION)
        val body = json.encodeToString(envelope).toByteArray()
        require(body.size <= MAX_BODY) { "同步包超过 128 MiB，本次未确认完成；未截断或删除任何条目" }
        val uri = URI(normalizeBaseUrl(baseUrl) + "/sync")
        val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; doOutput = true
            instanceFollowRedirects = false
            // 服务端响应带 Connection: close；显式禁用客户端连接复用，避免复用
            // 已被服务端声明关闭、但关闭竞态未完成的连接读到错位字节流
            // （表现为 GCM 校验失败：完整 JSON 信封 + AEADBadTag）。
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Clipboard-Sync-Request", id)
            setRequestProperty("X-Clipboard-Sync-Issued-At", envelope.issuedAt.toString())
            setRequestProperty("X-Clipboard-Sync-Proof", ClipboardSyncCrypto.requestProof(password, id, issuedAt = envelope.issuedAt))
            setFixedLengthStreamingMode(body.size)
        }
        try {
            val output = conn.outputStream
            if (req.kind in setOf("sync", "full_upload") && !req.packetJson.isNullOrBlank()) {
                ClipboardDelivery.beginExposure(json.decodeFromString<ClipboardSyncPacket>(req.packetJson))
            }
            afterExposure?.invoke()
            output.use { it.write(body) }
            if (conn.responseCode != 200) throw IllegalStateException("主机返回 HTTP ${conn.responseCode}${readServerError(conn).let { e -> if (e.isNotEmpty()) "：$e" else "" }}")
            val responseEnvelope = readBounded(conn.inputStream, MAX_BODY)
            val wire = json.decodeFromString<ClipboardSyncEnvelope>(responseEnvelope)
            if (wire.requestId != id || wire.clientId != req.clientId) throw SecurityException("响应未绑定请求")
            val result = try {
                json.decodeFromString<ClipboardSyncWireResponse>(ClipboardSyncCrypto.decrypt(password, wire, ClipboardSyncCrypto.RESPONSE_DIRECTION))
            } catch (e: javax.crypto.AEADBadTagException) {
                throw IllegalStateException("同步数据在传输中被截断或损坏（响应密文校验失败，常见于图片等大内容未完整接收），请重试")
            }
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
