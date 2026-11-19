/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.os.SystemClock
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 局域网剪切板房间的**自动发现**（UDP 组播）。
 *
 * - **主机**：房间开启后启动发现线程，进入多播组并周期性广播「房间公告」，
 *   同时响应对端的「查询」包（单播回 ip:port + 房间名）。
 * - **客户端**：[discover] 向多播地址发查询包，收集若干秒内主机回复，
 *   得到可用房间地址列表（如 "192.168.1.5:18523"），供加入房间自动填充。
 *
 * 只拿主机的 `ip:port`，不传剪贴板内容、不传密码；密码仍由用户输入、HTTP 层校验。
 */
object ClipboardSyncDiscovery {

    const val DISCOVERY_PORT = 18524
    private const val MULTICAST_GROUP = "239.255.42.99"
    private const val MAGIC = "MENG-CLB-SYNC"

    private val running = AtomicBoolean(false)
    private var hostSocket: DatagramSocket? = null
    @Volatile private var _serverPort = 0
    @Volatile private var _roomName = "梦-同步"

    /** 客户端「变化通知」监听：收到与本机目标端口匹配的 NOTIFY 即回调（触发同步）。 */
    @Volatile private var targetPort = 0
    @Volatile private var notifyCallback: (() -> Unit)? = null
    private val watcherRunning = AtomicBoolean(false)
    private var watcherSocket: DatagramSocket? = null

    val serverPort: Int get() = _serverPort

    /** 主机：开启发现线程（广播 + 响应查询）。 */
    fun startDiscovery(roomPort: Int, roomName: String = "梦-同步") {
        synchronized(this) {
            _serverPort = roomPort
            _roomName = roomName
            if (running.getAndSet(true)) return
        }
        thread(name = "clb-discovery-host") {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(DISCOVERY_PORT).apply { reuseAddress = true }
                hostSocket = sock
                var lastBroadcast = 0L
                while (running.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBroadcast >= 3000) {
                        lastBroadcast = now
                        runCatching {
                            val data = announceBytes()
                            sock!!.send(DatagramPacket(data, data.size,
                                InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT)))
                            val bcast = broadcastAddress()
                            if (bcast != null) sock!!.send(DatagramPacket(data, data.size,
                                InetSocketAddress(bcast, DISCOVERY_PORT)))
                        }.onFailure { Timber.w(it, "clb discovery broadcast") }
                    }
                    runCatching { sock!!.soTimeout = 400 } .onFailure { }
                    runCatching {
                        val buf = ByteArray(512)
                        val recv = DatagramPacket(buf, buf.size)
                        sock!!.receive(recv)
                        val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                        if (data.startsWith("$MAGIC:QUERY")) {
                            val reply = announceBytes()
                            sock!!.send(DatagramPacket(reply, reply.size, recv.address, recv.port))
                        }
                    }.onFailure { /* timeout 等忽略 */ }
                }
            } catch (t: Throwable) {
                Timber.w(t, "clb discovery host error")
            } finally {
                running.set(false)
                runCatching { sock?.close() }
                hostSocket = null
            }
        }
    }

    /** 客户端：主动扫描局域网内主机。最多收集 [timeoutMs] 毫秒。 */
    fun discover(timeoutMs: Int = 1500): List<String> {
        val found = LinkedHashSet<String>()
        val myIp = activeWifiIpv4()
        thread(name = "clb-discovery-client") {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply { reuseAddress = true }
                sock.soTimeout = timeoutMs
                val query = "$MAGIC:QUERY".toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(query, query.size,
                    InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT)))
                // 组播 + 网段广播各发一次，提升发现成功率
                val bcast = broadcastAddress()
                if (bcast != null) sock.send(DatagramPacket(query, query.size,
                    InetSocketAddress(bcast, DISCOVERY_PORT)))

                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    runCatching {
                        val buf = ByteArray(512)
                        val recv = DatagramPacket(buf, buf.size)
                        sock!!.receive(recv)
                        val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                        val host = recv.address.hostAddress ?: return@runCatching
                        if (host == myIp || host.startsWith("127.")) return@runCatching
                        val port = announcePort(data) ?: return@runCatching
                        found.add("$host:$port")
                    }.onFailure { /* timeout 忽略 */ }
                }
            } catch (t: Throwable) {
                Timber.w(t, "clb discovery client error")
            } finally {
                runCatching { sock?.close() }
            }
        }.join()
        return found.toList()
    }

    /** 停止主机发现线程。 */
    fun stopDiscovery() {
        running.set(false)
        runCatching { hostSocket?.close() }
        hostSocket = null
    }

    /** 主机向局域网广播「内容有变化」通知（NOTIFY 包）的去抖：批量变更合并为一次广播。 */
    private val lastNotifyAt = AtomicLong(0L)
    private const val NOTIFY_DEBOUNCE_MS = 500L
    /** 单线程发送器：NOTIFY 可能从主线程（Room 写入事务 / UI 点击）触发，
     * 直接把 DatagramSocket.send 丢到后台，避免 NetworkOnMainThreadException 吞掉整个通知。
     * 去抖在调用线程完成（AtomicLong CAS），只有真正发出的那次才提交发送，不会堆积。 */
    private val notifySender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "clb-notify-sender").apply { isDaemon = true }
    }

    /**
     * 主机向局域网广播「内容有变化」通知（NOTIFY 包）。
     * 客户端若正监听同一个房间端口，收到后会立即唤醒一次同步；
     * 不携带任何剪贴板内容或密码，只是「有变化，请来拉取」的提示。
     * [unicastAddrs]：主机已知的在线客户端 IP——组播/广播之外逐个单播兜底，
     * 路由器隔离组播或广播被 AP 抑制时，单播依然可达（客户端必在活动连接上）。
     * 注意：本函数可被任意线程调用（含主线程），实际 UDP 发送在后台单线程执行。
     */
    fun notifyPeers(roomPort: Int, unicastAddrs: Collection<String> = emptyList()) {
        if (roomPort <= 0) return
        val now = System.currentTimeMillis()
        val last = lastNotifyAt.get()
        if (now - last < NOTIFY_DEBOUNCE_MS) return
        if (!lastNotifyAt.compareAndSet(last, now)) return
        val data = "$MAGIC:NOTIFY:$roomPort".toByteArray(Charsets.UTF_8)
        val addrs = unicastAddrs.filter { it.isNotBlank() }.toList()
        notifySender.execute {
            runCatching {
                DatagramSocket().use { sock ->
                    sock.send(DatagramPacket(data, data.size,
                        InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT)))
                    val bcast = broadcastAddress()
                    if (bcast != null) sock.send(DatagramPacket(data, data.size,
                        InetSocketAddress(bcast, DISCOVERY_PORT)))
                    addrs.forEach { ip ->
                        runCatching { sock.send(DatagramPacket(data, data.size, InetSocketAddress(ip, DISCOVERY_PORT))) }
                    }
                }
            }.onFailure { Timber.w(it, "clb discovery notify broadcast") }
        }
    }

    /**
     * 客户端监听指定房间端口的 NOTIFY 通知。
     * 收到匹配的 NOTIFY 包即回调 [onNotify]（调用方应触发一次同步），
     * 回调在监听线程执行，调用方自行切到合适的 dispatcher。
     * 返回后即可 [stopWatch] 停止。
     */
    fun startWatch(roomPort: Int, onNotify: () -> Unit) {
        if (roomPort <= 0) return
        synchronized(this) {
            if (watcherRunning.getAndSet(true)) return
            targetPort = roomPort
            notifyCallback = onNotify
        }
        thread(name = "clb-discovery-watch") {
            var sock: DatagramSocket? = null
            try {
                // 与主机 discovery 线程同模式：绑定 DISCOVERY_PORT + reuseAddress。
                // NOTIFY 同时走组播和网段广播；广播不依赖 joinGroup，绑定 18524
                // 就能收到。同一设备 host/client 互斥（加入房间会先检查），无端口冲突。
                sock = DatagramSocket(DISCOVERY_PORT).apply { reuseAddress = true }
                watcherSocket = sock
                Timber.i("clb watcher bound: port=%d target=%d", sock.localPort, targetPort)
                sock.soTimeout = 600
                while (watcherRunning.get()) {
                    runCatching {
                        val buf = ByteArray(512)
                        val recv = DatagramPacket(buf, buf.size)
                        sock!!.receive(recv)
                        val data = String(recv.data, recv.offset, recv.length, Charsets.UTF_8)
                        if (data.startsWith("$MAGIC:NOTIFY:")) {
                            val notifyPort = data.removePrefix("$MAGIC:NOTIFY:").trim().toIntOrNull()
                            if (notifyPort != null && notifyPort == targetPort) {
                                notifyCallback?.invoke()
                            }
                        }
                    }.onFailure { /* timeout 等忽略 */ }
                }
            } catch (t: Throwable) {
                Timber.w(t, "clb discovery watcher error")
            } finally {
                runCatching { sock?.close() }
                watcherSocket = null
                synchronized(this) {
                    watcherRunning.set(false)
                    notifyCallback = null
                    targetPort = 0
                }
            }
        }
    }

    /** 停止客户端 NOTIFY 监听。 */
    fun stopWatch() {
        watcherRunning.set(false)
        runCatching { watcherSocket?.close() }
        watcherSocket = null
    }

    private fun announceBytes(): ByteArray =
        "$MAGIC:ANNOUNCE:$_serverPort:$_roomName".toByteArray(Charsets.UTF_8)

    /** 从 ANNOUNCE 载荷解析出同步 HTTP 端口。 */
    private fun announcePort(data: String): Int? {
        if (!data.startsWith(MAGIC)) return null
        val parts = data.split(":")
        if (parts.size < 4) return null
        return parts[2].toIntOrNull()
    }

    /** 当前网段的广播地址（尽力推导，失败返回 null）。 */
    private fun broadcastAddress(): String? {
        return runCatching {
            val ni = activeWifiInterface() ?: return@runCatching null
            val ipv4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress } ?: return@runCatching null
            val addr = ipv4.address
            val broadcast = ni.interfaceAddresses.firstOrNull()?.broadcast?.address
            if (broadcast != null && broadcast.size == 4) {
                InetAddress.getByAddress(broadcast).hostAddress
            } else {
                // 退而 /24 假设
                addr[3] = 255.toByte()
                InetAddress.getByAddress(addr).hostAddress
            }
        }.getOrNull()
    }

    private fun activeWifiInterface(): NetworkInterface? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()!!.toList()
                .firstOrNull { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
        }.getOrNull()
    }

    private fun activeWifiIpv4(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()!!.toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") && !it.startsWith("0.") }
        }.getOrNull() ?: ""
    }
}