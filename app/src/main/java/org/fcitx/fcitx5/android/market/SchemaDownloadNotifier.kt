/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.notificationManager

/**
 * 方案下载的通知助手。
 *
 * 负责：
 * - 创建/复用一个「方案下载」通知渠道；
 * - 下载中推送带进度条的通知；
 * - 下载成功推送完成通知；失败推送失败通知（可点击重新打开方案商店）。
 */
object SchemaDownloadNotifier {

    private const val CHANNEL_ID = "schema-download"
    private const val NOTIFY_ID = 0x5C4A4 // schema-download 通知 id

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                ctx.getText(R.string.schema_download_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_ID
                setShowBadge(false)
            }
            ctx.notificationManager.createNotificationChannel(channel)
        }
    }

    private fun mainPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

    /** 推送/更新下载进度通知。progress 0..100（total 未知时传 null）。 */
    fun showProgress(source: SchemaSource, progress: Int?, byteText: String) {
        val ctx = appContext
        ensureChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_keyboard_24)
            .setContentTitle(ctx.getString(R.string.schema_downloading_title, source.displayName))
            .setContentText(byteText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent(ctx))
        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        ctx.notificationManager.notify(NOTIFY_ID, builder.build())
    }

    /** 下载并安装成功 */
    fun showSuccess(source: SchemaSource) {
        cancelProgress()
        val ctx = appContext
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_keyboard_24)
            .setContentTitle(ctx.getString(R.string.schema_download_done, source.displayName))
            .setContentText(ctx.getString(R.string.schema_download_done_msg, source.version))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent(ctx))
            .build()
            .let { ctx.notificationManager.notify(NOTIFY_ID, it) }
    }

    /** 下载/安装失败 */
    fun showFailure(source: SchemaSource, reason: String?) {
        cancelProgress()
        val ctx = appContext
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(ctx.getString(R.string.schema_download_failed, source.displayName))
            .setContentText(reason ?: ctx.getString(R.string.schema_download_failed_retry))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent(ctx))
            .build()
            .let { ctx.notificationManager.notify(NOTIFY_ID, it) }
    }

    /** 取消进程通知 */
    fun cancelProgress() {
        appContext.notificationManager.cancel(NOTIFY_ID)
    }
}