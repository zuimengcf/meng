package org.fcitx.fcitx5.android.ui.main

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.setup.SetupActivity

/**
 * Reports a package update without trying to restore Android's protected IME choice.
 * The notification reuses the existing setup wizard and its first unfinished step.
 */
class ImeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.ime_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openSetup = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_keyboard_24)
                    .setContentTitle(context.getString(R.string.ime_update_title))
                    .setContentText(context.getString(R.string.ime_update_message))
                    .setContentIntent(openSetup)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        } catch (_: SecurityException) {
            // Android 13+ notification permission is optional; the launcher still opens the existing setup wizard when needed.
        }
    }

    companion object {
        private const val CHANNEL_ID = "ime_update"
        private const val NOTIFICATION_ID = 234
    }
}
