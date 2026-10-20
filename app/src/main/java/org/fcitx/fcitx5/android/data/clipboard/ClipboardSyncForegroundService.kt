package org.fcitx.fcitx5.android.data.clipboard

import android.app.*
import android.content.*
import android.os.*
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
        private const val MODE_HOST = "host"; private const val MODE_CLIENT = "client"; private const val CHANNEL_ID = "clipboard-sync-room"; private const val NOTIFY_ID = 0x4D51; private const val RESULT_NOTIFY_ID = 0x4D52; private const val STOP_REQ=1001; private const val OPEN_REQ=1002
        fun startHost(c: Context, port:Int, password:String) { ContextCompat.startForegroundService(c, Intent(c,ClipboardSyncForegroundService::class.java).putExtra(EXTRA_MODE,MODE_HOST).putExtra(EXTRA_PORT,port).putExtra(EXTRA_PASSWORD,password)) }
        fun startClient(c: Context, address:String, password:String) { ContextCompat.startForegroundService(c, Intent(c,ClipboardSyncForegroundService::class.java).putExtra(EXTRA_MODE,MODE_CLIENT).putExtra(EXTRA_ADDRESS,address).putExtra(EXTRA_PASSWORD,password)) }
        fun requestStop(c: Context) { c.startService(Intent(c,ClipboardSyncForegroundService::class.java).setAction(ACTION_STOP)) }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifyHandler = Handler(Looper.getMainLooper())
    private var startupJob: Job? = null
    private var lastNotificationText: String? = null
    private val resultChannelId = "clipboard-sync-changes"
    private val resultListener: (ClipboardSyncResult) -> Unit = { result ->
        if (result.hasVisibleChanges) notifyHandler.post {
            runCatching { notificationManager.notify(RESULT_NOTIFY_ID, buildResultNotification(result)) }
                .onFailure { Timber.w("clipboard result notification unavailable; result retained in app (%s)", it.javaClass.simpleName) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

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
        createChannel(); startForegroundCompat(buildStatusNotification("启动中…"))
        startupJob?.cancel()
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
                withContext(Dispatchers.Main.immediate) { scheduleNotifications() }
            } catch (e: Exception) { Timber.w(e,"clipboard sync service startup failed"); withContext(Dispatchers.Main.immediate) { stopNetworkOnly(); stopForegroundCompat(); stopSelf() } }
        }
        return START_STICKY
    }

    private fun installClientCallback() { ClipboardSyncClient.onDisconnected = { notifyHandler.post { scheduleNotifications() } } }
    private fun scheduleNotifications() { notifyHandler.removeCallbacks(notifyRefresh); notifyHandler.post(notifyRefresh) }
    private val notifyRefresh = object : Runnable { override fun run() { val text = statusText(); if (text != lastNotificationText) { lastNotificationText=text; notificationManager.notify(NOTIFY_ID,buildStatusNotification(text)) }; notifyHandler.postDelayed(this,1000) } }
    private fun statusText(): String = when {
        ClipboardSyncServer.isRunning -> "${ClipboardSyncServer.localAddress}:${ClipboardSyncServer.port} · 已连接 ${ClipboardSyncServer.onlineClientCount} 台"
        ClipboardSyncClient.joinedRoom.isNotEmpty() -> (if (ClipboardSyncClient.isConnected) "${ClipboardSyncClient.joinedRoom} · 已连接 ${ClipboardSyncClient.connectedDeviceCount} 台" else "${ClipboardSyncClient.joinedRoom} · 重连中") + (ClipboardSyncClient.lastSyncError?.let { " · 同步错误：${it.take(40)}" } ?: "")
        else -> "同步已停止"
    }
    private fun buildStatusNotification(text:String): Notification = NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步").setContentText(text).setContentIntent(openManageIntent(false)).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(NotificationCompat.Action.Builder(0,"停止同步",stopActionIntent()).build()).build()
    private fun buildResultNotification(result: ClipboardSyncResult): Notification = NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步有变动").setContentText(result.headline()).setStyle(NotificationCompat.BigTextStyle().bigText(result.headline())).setContentIntent(openManageIntent(true)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(NotificationCompat.Builder(this,resultChannelId).setSmallIcon(R.drawable.ic_baseline_sync_24).setContentTitle("剪切板同步").setContentText("同步完成").build()).build()
    private fun openManageIntent(showResult: Boolean) = PendingIntent.getActivity(this,OPEN_REQ + if (showResult) 1 else 0,Intent(this,ClipboardManageActivity::class.java).putExtra("show_sync_result", showResult),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun stopActionIntent() = PendingIntent.getService(this,STOP_REQ,Intent(this,ClipboardSyncForegroundService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"剪切板局域网同步",NotificationManager.IMPORTANCE_LOW))
            if (notificationManager.getNotificationChannel(resultChannelId) == null) notificationManager.createNotificationChannel(NotificationChannel(resultChannelId,"剪切板条目变动",NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun startForegroundCompat(n:Notification) { if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFY_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIFY_ID,n) }
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else { @Suppress("DEPRECATION") stopForeground(true) } }
    private fun stopNetworkOnly() { ClipboardSyncClient.onDisconnected=null; ClipboardSyncClient.leaveRoom(); ClipboardSyncServer.stop(); startupJob=null }
    private fun stopAndCleanup() { stopNetworkOnly(); runCatching { ClipboardSyncSettings.clearMode() }; notifyHandler.removeCallbacksAndMessages(null); stopForegroundCompat(); stopSelf() }
    override fun onDestroy() { ClipboardSyncNotifications.removeListener(resultListener); notifyHandler.removeCallbacksAndMessages(null); startupJob?.cancel(); serviceScope.cancel(); stopNetworkOnly(); super.onDestroy() }
    override fun onTimeout(startId:Int) { Timber.w("clipboard sync service timed out; user must reopen room"); stopNetworkOnly(); stopForegroundCompat(); stopSelf(startId) }
    override fun onTimeout(startId: Int, fgsType: Int) { onTimeout(startId) }
}
