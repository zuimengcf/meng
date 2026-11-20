package org.fcitx.fcitx5.android.data.clipboard

import android.app.*
import android.content.*
import android.os.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.ClipboardManageActivity
import org.fcitx.fcitx5.android.utils.notificationManager
import timber.log.Timber

/** 同步前台服务：网络工作永不在主线程，客户端断线保持前台并自动重连。 */
class ClipboardSyncForegroundService : Service() {
    companion object {
        private const val ACTION_STOP = "org.fcitx.fcitx5.android.clipboard.SYNC_STOP"
        private const val EXTRA_MODE = "mode"; private const val EXTRA_PORT = "port"; private const val EXTRA_PASSWORD = "password"; private const val EXTRA_ADDRESS = "address"
        private const val MODE_HOST = "host"; private const val MODE_CLIENT = "client"; private const val CHANNEL_ID = "clipboard-sync-room"; private const val NOTIFY_ID = 0x4D51; private const val RESULT_NOTIFY_ID = 0x4D52; private const val STOP_REQ=1001; private const val OPEN_REQ=1002; private const val BATTERY_REQ=1004; private const val TIMEOUT_NOTIFY_ID = 0x4D53
        fun startHost(c: Context, port:Int, password:String) { ContextCompat.startForegroundService(c, Intent(c,ClipboardSyncForegroundService::class.java).putExtra(EXTRA_MODE,MODE_HOST).putExtra(EXTRA_PORT,port).putExtra(EXTRA_PASSWORD,password)) }
        fun startClient(c: Context, address:String, password:String) { ContextCompat.startForegroundService(c, Intent(c,ClipboardSyncForegroundService::class.java).putExtra(EXTRA_MODE,MODE_CLIENT).putExtra(EXTRA_ADDRESS,address).putExtra(EXTRA_PASSWORD,password)) }
        fun requestStop(c: Context) { c.startService(Intent(c,ClipboardSyncForegroundService::class.java).setAction(ACTION_STOP)) }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifyHandler = Handler(Looper.getMainLooper())
    private var startupJob: Job? = null
    private var lastNotificationText: String? = null
    /** 启动中标记：启动完成后由 statusText 自动切换到实时状态，避免通知永久停留在「启动中…」。 */
    @Volatile private var startupDone = false
    private var startupWatchdog: Runnable? = null
    private val resultChannelId = "clipboard-sync-changes"
    private val resultListener: (ClipboardSyncResult) -> Unit = { result ->
        if (result.hasVisibleChanges) notifyHandler.post {
            runCatching { notificationManager.notify(RESULT_NOTIFY_ID, buildResultNotification(result)) }
                .onFailure { Timber.w("clipboard result notification unavailable; result retained in app (%s)", it.javaClass.simpleName) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 后台保活与断开感知 ----
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    @Volatile private var currentMode: String? = null

    /**
     * WiFi 锁：息屏后网卡进入省电，主机收不到连接、客户端长轮询被掐。两种模式都持有。
     * CPU 唤醒锁：只有主机持有——主机深睡就没人接客户端的请求；客户端息屏时不必同步，醒来由 kick 立即补齐。
     */
    private fun acquireKeepAlive(mode: String) {
        runCatching {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(type, "fcitx5:clipboard-sync").apply { setReferenceCounted(false); acquire() }
            }
        }.onFailure { Timber.w("clipboard sync wifi lock unavailable: %s", it.javaClass.simpleName) }
        runCatching {
            if (mode == MODE_HOST) {
                if (wakeLock?.isHeld != true) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fcitx5:clipboard-sync-host").apply { setReferenceCounted(false); acquire() }
                }
            } else { wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null }
        }.onFailure { Timber.w("clipboard sync wake lock unavailable: %s", it.javaClass.simpleName) }
    }
    private fun releaseKeepAlive() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null
    }

    /** 网络丢失立即显示断开；网络恢复、亮屏、解锁立即重新确认（不再等 1 小时托底或 60s 读超时）。 */
    private fun registerLinkWatchers() {
        if (networkCallback == null) runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { ClipboardSyncClient.kick("network available"); scheduleNotificationsSafe() }
                override fun onLost(network: Network) {
                    // 只在没有任何可用网络时判定断开（WiFi 与流量切换时会先 lost 再 available）
                    val active = runCatching { cm.activeNetwork }.getOrNull()
                    if (active == null || active == network) ClipboardSyncClient.markLinkDown("network lost")
                    scheduleNotificationsSafe()
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), cb)
            networkCallback = cb
        }.onFailure { Timber.w("clipboard sync network callback unavailable: %s", it.javaClass.simpleName) }
        if (screenReceiver == null) runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { ClipboardSyncClient.kick(i?.action ?: "screen"); scheduleNotificationsSafe() }
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, filter)
            screenReceiver = r
        }.onFailure { Timber.w("clipboard sync screen receiver unavailable: %s", it.javaClass.simpleName) }
    }
    private fun unregisterLinkWatchers() {
        networkCallback?.let { cb -> runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }; networkCallback = null
        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }; screenReceiver = null
    }
    private fun scheduleNotificationsSafe() { notifyHandler.post { if (startupDone) scheduleNotifications() } }

    /** 没有加入电池优化白名单时，厂商省电策略会在后台冻结/杀掉同步：在通知里明确提示，而不是静默断开。 */
    private fun batteryRestricted(): Boolean = Build.VERSION.SDK_INT >= 23 && runCatching {
        !(getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)
    private fun batteryExemptIntent(): PendingIntent? = if (Build.VERSION.SDK_INT < 23) null else runCatching {
        @android.annotation.SuppressLint("BatteryLife")
        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
        PendingIntent.getActivity(this, BATTERY_REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }.getOrNull()

    override fun onCreate() {
        super.onCreate()
        ClipboardSyncNotifications.addListener(resultListener)
    }

    override fun onStartCommand(intent: Intent?, flags:Int, startId:Int): Int {
        if (intent?.action == ACTION_STOP) { stopAndCleanup(); return START_NOT_STICKY }
        val saved = if (intent == null) runCatching { ClipboardSyncSettings.readSession() }.getOrNull() else null
        val mode = intent?.getStringExtra("mode") ?: saved?.mode
        if (mode != MODE_HOST && mode != MODE_CLIENT) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        val pwd = intent?.getStringExtra(EXTRA_PASSWORD) ?: saved?.password.orEmpty()
        if (pwd.length < 8) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        createChannel(); startupDone = false
        // 后台（开机、进程被回收后 START_STICKY 重建）不允许启动前台服务：两种类型都会抛异常。
        // 原来回退 dataSync 后没有兜底会直接崩溃；现在改发一条「点按恢复同步」通知并安静退出，会话设置保留。
        if (!runCatching { startForegroundCompat(buildStatusNotification("启动中…")) }
                .onFailure { Timber.w(it, "clipboard sync FGS start not allowed") }.isSuccess) {
            notifyResumeNeeded("系统不允许后台启动同步，点按重新开启")
            stopSelf(startId); return START_NOT_STICKY
        }
        currentMode = mode; acquireKeepAlive(mode); registerLinkWatchers()
        runCatching { notificationManager.cancel(TIMEOUT_NOTIFY_ID) }
        // 刷新循环立即启动，不依赖 startupJob 完成；启动即使假死，通知也有实时状态反馈
        notifyHandler.removeCallbacks(notifyRefresh); notifyHandler.post(notifyRefresh)
        startupJob?.cancel(); startupWatchdog?.let { notifyHandler.removeCallbacks(it) }; startupWatchdog = null
        startupJob = serviceScope.launch {
            try {
                if (mode == MODE_CLIENT) {
                    val addr = intent?.getStringExtra(EXTRA_ADDRESS) ?: saved?.host.orEmpty()
                    if (addr.isBlank()) throw IllegalArgumentException("缺少房间地址")
                    withContext(Dispatchers.Main.immediate) { installClientCallback() }
                    if (ClipboardSyncClient.joinedRoom != addr || ClipboardSyncClient.joinedRoomPassword != pwd) ClipboardSyncClient.joinRoom(addr,pwd)
                } else {
                    val port = intent?.getIntExtra(EXTRA_PORT,0)?.takeIf { it > 0 } ?: saved?.port ?: 0
                    if (port <= 0) throw IllegalArgumentException("缺少监听端口")
                    if (!ClipboardSyncServer.isRunning && ClipboardSyncServer.start(port,pwd) { }.let { it <= 0 }) throw IllegalStateException("房间启动失败")
                }
                startupDone = true
                withContext(Dispatchers.Main.immediate) { scheduleNotifications() }
            } catch (e: Exception) {
                Timber.w(e,"clipboard sync service startup failed")
                withContext(Dispatchers.Main.immediate) {
                    // 失败也要有反馈：先推一次明文文案，再停
                    lastNotificationText = null
                    runCatching { notificationManager.notify(NOTIFY_ID, buildStatusNotification("启动失败：${e.message?.take(40) ?: e.javaClass.simpleName}")) }
                    stopNetworkOnly(); stopForegroundCompat(); stopSelf()
                }
            }
        }
        // 启动看门狗：45s 未完成 -> 通知明确提示（不杀服务，保住前台，用户可自行停止重开）
        val watchdog = Runnable {
            if (!startupDone) {
                lastNotificationText = null
                runCatching { notificationManager.notify(NOTIFY_ID, buildStatusNotification("启动异常，已停留过久，请停止后重试")) }
            }
        }
        startupWatchdog = watchdog
        notifyHandler.postDelayed(watchdog, 45_000L)
        return START_STICKY
    }

    private fun installClientCallback() { ClipboardSyncClient.onDisconnected = { notifyHandler.post { scheduleNotifications() } } }
    private fun scheduleNotifications() { notifyHandler.removeCallbacks(notifyRefresh); notifyHandler.post(notifyRefresh) }
    private val notifyRefresh = object : Runnable { override fun run() { val text = statusText(); if (text != lastNotificationText) { lastNotificationText=text; notificationManager.notify(NOTIFY_ID,buildStatusNotification(text)) }; notifyHandler.postDelayed(this,1000) } }
    private fun statusText(): String = when {
        !startupDone && (ClipboardSyncServer.isRunning || ClipboardSyncClient.joinedRoom.isNotEmpty()) -> "启动中…"
        ClipboardSyncServer.isRunning -> "${ClipboardSyncServer.localAddress}:${ClipboardSyncServer.port} · 已连接 ${ClipboardSyncServer.onlineClientCount} 台"
        ClipboardSyncClient.joinedRoom.isNotEmpty() -> (if (ClipboardSyncClient.isConnected) "${ClipboardSyncClient.joinedRoom} · 已连接 ${ClipboardSyncClient.connectedDeviceCount} 台" else "${ClipboardSyncClient.joinedRoom} · 连接失败，主机不可达或已离线，重试中") + (ClipboardSyncClient.lastSyncError?.let { " · 同步错误：${it.take(40)}" } ?: "")
        else -> "同步已停止"
    } + (if ((ClipboardSyncServer.isRunning || ClipboardSyncClient.joinedRoom.isNotEmpty()) && batteryRestricted()) " · 受电池优化限制，后台可能被冻结" else "")
    private fun buildStatusNotification(text:String): Notification = NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步").setContentText(text).setContentIntent(openManageIntent(false)).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(NotificationCompat.Action.Builder(0,"停止同步",stopActionIntent()).build())
        .apply { if (batteryRestricted()) batteryExemptIntent()?.let { addAction(NotificationCompat.Action.Builder(0,"允许后台运行",it).build()) } }.build()
    private fun buildResultNotification(result: ClipboardSyncResult): Notification = NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步有变动").setContentText(result.headline()).setStyle(NotificationCompat.BigTextStyle().bigText(result.headline())).setContentIntent(openManageIntent(true)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步").setContentText("同步完成").build()).build()
    private fun openManageIntent(showResult: Boolean) = PendingIntent.getActivity(this,OPEN_REQ + if (showResult) 1 else 0,Intent(this,ClipboardManageActivity::class.java).putExtra("show_sync_result", showResult),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stopActionIntent() = PendingIntent.getService(this,STOP_REQ,Intent(this,ClipboardSyncForegroundService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"剪切板局域网同步",NotificationManager.IMPORTANCE_LOW))
            if (notificationManager.getNotificationChannel(resultChannelId) == null) notificationManager.createNotificationChannel(NotificationChannel(resultChannelId,"剪切板条目变动",NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    /**
     * Android 15 起 dataSync 前台服务每 24 小时最多跑 6 小时，到点系统调用 onTimeout 强停——这就是「过一阵后台同步被压死」。
     * 34+ 改用 connectedDevice（局域网设备互联，无时长上限；已有 CHANGE_WIFI_STATE 满足其前置权限），失败再退回 dataSync。
     */
    private fun startForegroundCompat(n:Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching { startForeground(NOTIFY_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
                .onFailure { Timber.w("connectedDevice FGS rejected, falling back to dataSync: %s", it.javaClass.simpleName) }.isSuccess
            if (!ok) startForeground(NOTIFY_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFY_ID,n)
    }
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else { @Suppress("DEPRECATION") stopForeground(true) } }
    private fun stopNetworkOnly() { ClipboardSyncClient.onDisconnected=null; ClipboardSyncClient.leaveRoom(); ClipboardSyncServer.stop(); startupJob=null; releaseKeepAlive(); unregisterLinkWatchers() }
    private fun stopAndCleanup() { stopNetworkOnly(); runCatching { ClipboardSyncSettings.clearMode() }; notifyHandler.removeCallbacksAndMessages(null); startupWatchdog?.let { notifyHandler.removeCallbacks(it) }; startupWatchdog=null; startupDone=true; stopForegroundCompat(); stopSelf() }
    override fun onDestroy() { ClipboardSyncNotifications.removeListener(resultListener); notifyHandler.removeCallbacksAndMessages(null); startupJob?.cancel(); startupWatchdog?.let { notifyHandler.removeCallbacks(it) }; startupWatchdog=null; serviceScope.cancel(); stopNetworkOnly(); super.onDestroy() }
    private fun notifyResumeNeeded(text: String) {
        runCatching {
            createChannel()
            notificationManager.notify(TIMEOUT_NOTIFY_ID, NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24)
                .setContentTitle("剪切板同步已暂停").setContentText(text)
                .setContentIntent(openManageIntent(false)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
        }
    }
    @Volatile private var timedOut = false
    override fun onTimeout(startId:Int) {
        if (timedOut) return
        timedOut = true
        Timber.w("clipboard sync service timed out; user must reopen room")
        stopNetworkOnly(); stopForegroundCompat()
        // 原来静默停止：通知消失、对端/本机都看不出已断开。现在明确告知，点按回到管理页重新开启。
        runCatching {
            notificationManager.notify(TIMEOUT_NOTIFY_ID, NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24)
                .setContentTitle("剪切板同步已被系统暂停").setContentText("已达到系统后台运行时长上限，同步已断开，点按重新开启")
                .setContentIntent(openManageIntent(false)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build())
        }
        stopSelf(startId)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { onTimeout(startId) }
}
