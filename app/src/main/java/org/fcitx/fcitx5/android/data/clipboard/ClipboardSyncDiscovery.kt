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