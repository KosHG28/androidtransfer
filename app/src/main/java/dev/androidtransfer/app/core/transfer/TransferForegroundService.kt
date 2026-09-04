package dev.androidtransfer.app.core.transfer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.androidtransfer.app.MainActivity
import dev.androidtransfer.app.R
import dev.androidtransfer.app.TransferApplication

/**
 * Keeps the process (and the transfer's sockets/Nearby connection) alive
 * while the app is backgrounded mid-transfer, with a persistent
 * notification as Android requires for a dataSync foreground service.
 */
class TransferForegroundService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TransferApplication.TRANSFER_NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(getString(R.string.transfer_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        /** Keeps the process (and the transport's sockets/Nearby connection) alive while the screen is off or the app is backgrounded. */
        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferForegroundService::class.java)) }
        }
    }
}
