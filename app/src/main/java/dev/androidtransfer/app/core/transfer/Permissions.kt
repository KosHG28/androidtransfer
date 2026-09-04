package dev.androidtransfer.app.core.transfer

import android.Manifest
import android.os.Build

/** Runtime permissions each category needs before its module can run. */
object Permissions {
    fun forCategory(category: TransferCategory): List<String> = when (category) {
        TransferCategory.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        TransferCategory.CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        TransferCategory.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        TransferCategory.SMS -> listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        TransferCategory.MEDIA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        TransferCategory.FILES, TransferCategory.CUSTOM_FOLDER, TransferCategory.WHATSAPP_MEDIA -> emptyList() // granted via SAF folder picker instead
        TransferCategory.INSTALLED_APPS -> emptyList()
    }

    /** Permissions the Nearby (Wi-Fi) transport needs before advertising/discovering. */
    fun forNearby(): List<String> {
        val base = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base += listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            base += listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            base += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return base
    }
}
