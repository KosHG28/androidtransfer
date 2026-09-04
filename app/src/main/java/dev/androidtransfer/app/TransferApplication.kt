package dev.androidtransfer.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class TransferApplication : Application() {
    companion object {
        const val TRANSFER_NOTIFICATION_CHANNEL = "transfer_progress"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TRANSFER_NOTIFICATION_CHANNEL,
                getString(R.string.transfer_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
