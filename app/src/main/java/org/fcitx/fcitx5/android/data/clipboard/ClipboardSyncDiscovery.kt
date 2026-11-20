/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 局域网剪切板房间的**自动发现**与「内容有变化」通知（UDP 组播 + 网段广播 + 单播兜底）。
 *
 * 线上格式与旧版完全相同（MAGIC:ANNOUNCE / QUERY / NOTIFY），新旧版本可以互相发现、互相唤醒。
 *
 * 修复点：
 * - 监听线程按「代」管理：旧线程退出时只清理自己那一代的状态，不会把刚启动的新 watcher 关掉；
 * - 换房间/换端口时重启监听，而不是沿用旧端口；
 * - 先 setReuseAddress 再 bind（原来构造即绑定，reuse 实际没生效）；
 * - 真正 joinGroup，并持有 WifiManager.MulticastLock（很多机型熄屏后不持锁会过滤组播/广播）；
 * - NOTIFY 去抖改为「前沿立即发 + 尾沿补发」，窗口内的后续变更不再被丢弃；
 * - 广播地址/组播出口只取 Wi-Fi、热点、以太网接口上的 IPv4，避免走到蜂窝或 VPN。
 */
object ClipboardSyncDiscovery {

    const val DISCOVERY_PORT = 18524
    private const val MULTICAST_GROUP = "239.255.42.99"
    private const val MAGIC = "MENG-CLB-SYNC"

    // ---------------------------------------------------------------- 组播锁

    private val lockLock = Any()
    private var lockHolders = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticast() = synchronized(lockLock) {
        lockHolders++
        if (multicastLock == null) multicastLock = runCatching {
            (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("meng-clipboard-sync").apply { setReferenceCounted(false); acquire() }
        }.onFailure { Timber.w(it, "clb multicast lock unavailable") }.getOrNull()
    }

    private fun releaseMulticast() = synchronized(lockLock) {
        lockHolders = (lockHolders - 1).coerceAtLeast(0)
        if (lockHolders == 0) { runCatching { multicastLock?.release() }; multicastLock = null }
    }

    // ---------------------------------------------------------------- 网卡

    private fun lanName(name: String) = name.startsWith("wlan") || name.startsWith("swlan") ||
        name.startsWith("ap") || name.startsWith("eth") || name.startsWith("softap")

    /** 局域网接口（Wi-Fi 优先，其次热点/以太网）及其 IPv4 地址。 */
    private fun lanInterfaces(): List<Pair<NetworkInterface, Inet4Address>> = runCatching {
        NetworkInterface.getNetworkInterfaces()!!.toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && lanName(it.name) }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .mapNotNull { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.let { nic to it }
            }
    }.getOrDefault(emptyList())

    /** 每个局域网接口的 IPv4 广播地址（取不到时按 /24 推导）。 */
    private fun broadcastAddresses(): List<InetAddress> = lanInterfaces().mapNotNull { (nic, ip) ->
        nic.interfaceAddresses.firstOrNull { it.address is Inet4Address && it.broadcast != null }?.broadcast
            ?: runCatching { InetAddress.getByAddress(ip.address.copyOf().also { it[3] = 255.toByte() }) }.getOrNull()
    }.distinct()

    private fun activeWifiIpv4(): String = lanInterfaces().firstOrNull()?.second?.hostAddress ?: ""

    private fun openSocket(bindPort: Int?): MulticastSocket {
        val sock = MulticastSocket(null as java.net.SocketAddress?)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(bindPort ?: 0))
        runCatching { sock.timeToLive = 1 }
        runCatching { sock.loopbackMode = true } // true = 禁用回环：不收自己发的包
        return sock
    }

    private fun joinGroup(sock: MulticastSocket) {
        val group = InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), DISCOVERY_PORT)
        val nics = lanInterfaces().map { it.first }
        if (nics.isEmpty()) runCatching { sock.joinGroup(InetAddress.getByName(MULTICAST_GROUP)) }
        nics.forEach { nic -> runCatching { sock.joinGroup(group, nic) }.onFailure { Timber.w(it, "clb joinGroup %s", nic.name) } }
    }

    /** 组播 + 每个网段广播 + 额外单播，逐个尽力发送；组播从每个局域网接口各发一份。 */
    private fun sendAll(sock: MulticastSocket, data: ByteArray, unicast: Collection<String> = emptyList()) {
        val group = InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT)
        val nics = lanInterfaces().map { it.first }
        if (nics.isEmpty()) runCatching { sock.send(DatagramPacket(data, data.size, group)) }
        nics.forEach { nic ->
            runCatching { sock.networkInterface = nic; sock.send(DatagramPacket(data, data.size, group)) }
        }
        broadcastAddresses().forEach { b ->
            runCatching { sock.broadcast = true; sock.send(DatagramPacket(data, data.size, InetSocketAddress(b, DISCOVERY_PORT))) }
        }
        unicast.filter { it.isNotBlank() }.forEach { ip ->
            runCatching { sock.send(DatagramPacket(data, data.size, InetSocketAddress(ip, DISCOVERY_PORT))) }
        }
    }

    // ---------------------------------------------------------------- 主机：公告 + 响应查询

    private val hostGeneration = AtomicLong(0L)
    @Volatile private var hostSocket: MulticastSocket? = null
    @Volatile private var _serverPort = 0
    @Volatile private var _roomName = "梦-同步"
    val serverPort: Int get() = _serverPort

    fun startDiscovery(roomPort: Int, roomName: String = "梦-同步") {
        val myGen: Long
        synchronized(this) {
            _serverPort = roomPort; _roomName = roomName
            if (hostSocket != null) return
            myGen = hostGeneration.incrementAndGet()
        }
        acquireMulticast()
        thread(name = "clb-discovery-host", isDaemon = true) {
            var sock: MulticastSocket? = null
            try {
                sock = openSocket(DISCOVERY_PORT)
                synchronized(this) { if (hostGeneration.get() == myGen) hostSocket = sock else return@thread }
                joinGroup(sock)
                sock.soTimeout = 400
                var lastBroadcast = 0L
                while (hostGeneration.get() == myGen) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBroadcast >= 3000) {
                        lastBroadcast = now
                        runCatching { sendAll(sock, announceBytes()) }.onFailure { Timber.w(it, "clb discovery broadcast") }
                    }
                    runCatching {
                        val buf = ByteArray(512)
                        val recv = DatagramPacket(buf, buf.size)
                        sock.receive(recv)
                        val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                        if (data.startsWith("$MAGIC:QUERY")) {
                            val reply = announceBytes()
                            sock.send(DatagramPacket(reply, reply.size, recv.address, recv.port))
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "clb discovery host error")
            } finally {
                runCatching { sock?.close() }
                synchronized(this) { if (hostSocket === sock) hostSocket = null }
                releaseMulticast()
            }
        }
    }

    fun stopDiscovery() {
        synchronized(this) {
            hostGeneration.incrementAndGet()
            runCatching { hostSocket?.close() }
            hostSocket = null
        }
    }

    // ---------------------------------------------------------------- 客户端：扫描主机

    fun discover(timeoutMs: Int = 1500): List<String> {
        val found = LinkedHashSet<String>()
        val myIp = activeWifiIpv4()
        acquireMulticast()
        try {
            thread(name = "clb-discovery-client") {
                var sock: MulticastSocket? = null
                try {
                    sock = openSocket(null)
                    sock.soTimeout = timeoutMs
                    sendAll(sock, "$MAGIC:QUERY".toByteArray(Charsets.UTF_8))
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        runCatching {
                            val buf = ByteArray(512)
                            val recv = DatagramPacket(buf, buf.size)
                            sock.receive(recv)
                            val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                            val host = recv.address.hostAddress ?: return@runCatching
                            if (host == myIp || host.startsWith("127.")) return@runCatching
                            val port = announcePort(data) ?: return@runCatching
                            found.add("$host:$port")
                        }
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "clb discovery client error")
                } finally {
                    runCatching { sock?.close() }
                }
            }.join()
        } finally { releaseMulticast() }
        return found.toList()
    }

    // ---------------------------------------------------------------- 主机：NOTIFY（前沿 + 尾沿）

    private const val NOTIFY_DEBOUNCE_MS = 500L
    private val notifySender = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "clb-notify-sender").apply { isDaemon = true }
    }
    private val notifyState = Any()
    private var lastNotifyAt = 0L
    private var trailingScheduled = false
    private var pendingPort = 0
    private val pendingAddrs = LinkedHashSet<String>()

    /**
     * 主机向局域网广播「内容有变化」通知（NOTIFY 包），不携带任何内容或密码。
     * 去抖窗口内的第一次立即发送；窗口内后续的变更合并为窗口结束时的一次尾沿补发，
     * 保证「最后一次变更之后」至少有一个 NOTIFY。可从任意线程（含主线程）调用。
     */
    fun notifyPeers(roomPort: Int, unicastAddrs: Collection<String> = emptyList()) {
        if (roomPort <= 0) return
        synchronized(notifyState) {
            pendingPort = roomPort
            pendingAddrs += unicastAddrs.filter { it.isNotBlank() }
            val now = SystemClock.elapsedRealtime()
            val wait = lastNotifyAt + NOTIFY_DEBOUNCE_MS - now
            if (wait <= 0 && !trailingScheduled) {
                lastNotifyAt = now
                val (port, addrs) = drainPending()
                notifySender.execute { sendNotify(port, addrs) }
            } else if (!trailingScheduled) {
                trailingScheduled = true
                notifySender.schedule({
                    val (port, addrs) = synchronized(notifyState) {
                        trailingScheduled = false; lastNotifyAt = SystemClock.elapsedRealtime(); drainPending()
                    }
                    sendNotify(port, addrs)
                }, wait.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun drainPending(): Pair<Int, List<String>> {
        val addrs = pendingAddrs.toList(); pendingAddrs.clear()
        return pendingPort to addrs
    }

    private fun sendNotify(port: Int, addrs: List<String>) {
        if (port <= 0) return
        runCatching {
            openSocket(null).use { sock -> sendAll(sock, "$MAGIC:NOTIFY:$port".toByteArray(Charsets.UTF_8), addrs) }
        }.onFailure { Timber.w(it, "clb discovery notify broadcast") }
    }

    // ---------------------------------------------------------------- 客户端：监听 NOTIFY

    private val watchGeneration = AtomicLong(0L)
    @Volatile private var watcherSocket: MulticastSocket? = null
    @Volatile private var targetPort = 0
    @Volatile private var notifyCallback: (() -> Unit)? = null
    /** 最近一次收到 NOTIFY 的时间（elapsedRealtime），0 表示从未收到；供客户端判断 UDP 是否可用。 */
    @Volatile var lastNotifyReceivedAt = 0L
        private set

    /**
     * 监听指定房间端口的 NOTIFY。重复调用会替换端口与回调并重启监听线程；
     * 每个线程只在自己仍是「当前代」时运行，退出时也只清理自己那一代的状态。
     */
    fun startWatch(roomPort: Int, onNotify: () -> Unit) {
        if (roomPort <= 0) return
        val myGen: Long
        synchronized(this) {
            myGen = watchGeneration.incrementAndGet()
            runCatching { watcherSocket?.close() }; watcherSocket = null
            targetPort = roomPort
            notifyCallback = onNotify
        }
        acquireMulticast()
        thread(name = "clb-discovery-watch", isDaemon = true) {
            var sock: MulticastSocket? = null
            try {
                var attempt = 0
                while (watchGeneration.get() == myGen) {
                    try {
                        sock = openSocket(DISCOVERY_PORT)
                        synchronized(this) {
                            if (watchGeneration.get() != myGen) { sock?.close(); return@thread }
                            watcherSocket = sock
                        }
                        joinGroup(sock!!)
                        sock!!.soTimeout = 1000
                        Timber.i("clb watcher bound: port=%d target=%d", sock!!.localPort, roomPort)
                        attempt = 0
                        while (watchGeneration.get() == myGen) {
                            val buf = ByteArray(512)
                            val recv = DatagramPacket(buf, buf.size)
                            try { sock!!.receive(recv) } catch (_: java.net.SocketTimeoutException) { continue }
                            val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                            if (!data.startsWith("$MAGIC:NOTIFY:")) continue
                            val port = data.removePrefix("$MAGIC:NOTIFY:").trim().toIntOrNull()
                            if (port != null && port == targetPort && watchGeneration.get() == myGen) {
                                lastNotifyReceivedAt = SystemClock.elapsedRealtime()
                                runCatching { notifyCallback?.invoke() }.onFailure { Timber.w(it, "clb notify callback") }
                            }
                        }
                    } catch (t: Throwable) {
                        if (watchGeneration.get() != myGen) break
                        // 端口被占用 / 网络切换：退避后重绑，而不是让监听永久死掉。
                        Timber.w(t, "clb discovery watcher error, rebinding")
                        runCatching { sock?.close() }
                        Thread.sleep((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
                        attempt++
                    }
                }
            } finally {
                runCatching { sock?.close() }
                synchronized(this) {
                    if (watchGeneration.get() == myGen) { watcherSocket = null; notifyCallback = null; targetPort = 0 }
                }
                releaseMulticast()
            }
        }
    }

    fun stopWatch() {
        synchronized(this) {
            watchGeneration.incrementAndGet()
            runCatching { watcherSocket?.close() }
            watcherSocket = null; notifyCallback = null; targetPort = 0
        }
    }

    private fun announceBytes(): ByteArray =
        "$MAGIC:ANNOUNCE:$_serverPort:$_roomName".toByteArray(Charsets.UTF_8)

    private fun announcePort(data: String): Int? {
        if (!data.startsWith(MAGIC)) return null
        val parts = data.split(":")
        if (parts.size < 4) return null
        return parts[2].toIntOrNull()
    }
}
