package dev.androidtransfer.app.core.transfer

import android.Manifest
import android.os.Build

/** Runtime permissions each category needs before its module can run. */
object Permissions {
    fun forCategory(category: TransferCategory): List<String> = when (category) {
        TransferCategory.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        TransferCategory.CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
        TransferCategory.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        TransferCategory.MEDIA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        TransferCategory.FILES, TransferCategory.CUSTOM_FOLDER, TransferCategory.WHATSAPP_MEDIA -> emptyList() // granted via SAF folder picker instead
        TransferCategory.INSTALLED_APPS -> emptyList()
        TransferCategory.WALLPAPER -> emptyList() // SET_WALLPAPER is a normal (install-time) permission; reading has no permission to request, only an OS-version restriction
    }

    /**
     * The receiver doesn't choose categories — the sender does — so it can't
     * request permissions "as the user checks a box" the way CategorySelectionScreen
     * does. It has to ask for everything any category might need up front,
     * before a connection even starts: incoming data has no way to wait for
     * a permission dialog mid-transfer, so anything requested late just
     * fails silently (caught, logged as a transient error, then overwritten
     * by the next successful category).
     */
    fun forReceiver(): List<String> = (TransferCategory.entries.flatMap { forCategory(it) } + writeStorageIfNeeded() + notificationsIfNeeded())
        .distinct()

    /** Everything the given selection needs, plus the notification permission the transfer service needs. */
    fun forSelection(categories: Collection<TransferCategory>): List<String> =
        (categories.flatMap { forCategory(it) } + notificationsIfNeeded()).distinct()

    /** Pre-Android-10 receivers write incoming files straight into public storage. */
    private fun writeStorageIfNeeded(): List<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()

    /** Without this the foreground-service notification that keeps a transfer alive is invisible on API 33+. */
    private fun notificationsIfNeeded(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

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
