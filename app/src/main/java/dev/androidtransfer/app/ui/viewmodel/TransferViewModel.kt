package dev.androidtransfer.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferManager
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.core.transport.NearbyTransport
import dev.androidtransfer.app.core.transport.P2pTransport
import dev.androidtransfer.app.core.transport.UsbTetherTransport
import dev.androidtransfer.app.modules.apps.AppsModule
import dev.androidtransfer.app.modules.appdata.WhatsAppModule
import dev.androidtransfer.app.modules.calendar.CalendarModule
import dev.androidtransfer.app.modules.calllog.CallLogModule
import dev.androidtransfer.app.modules.contacts.ContactsModule
import dev.androidtransfer.app.modules.files.CustomFolderModule
import dev.androidtransfer.app.modules.files.FilesModule
import dev.androidtransfer.app.modules.media.MediaModule
import dev.androidtransfer.app.modules.wallpaper.WallpaperModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class Role { SENDER, RECEIVER }
enum class TransportKind { WIFI, USB }

class TransferViewModel(application: Application) : AndroidViewModel(application) {

    var role by mutableStateOf<Role?>(null)
    var transportKind by mutableStateOf<TransportKind?>(null)

    val selectedCategories: SnapshotStateList<TransferCategory> = mutableStateListOf(
        TransferCategory.CONTACTS,
        TransferCategory.CALL_LOG,
        TransferCategory.CALENDAR,
        TransferCategory.MEDIA,
        TransferCategory.INSTALLED_APPS,
        TransferCategory.WALLPAPER,
    )

    var filesTreeUri by mutableStateOf<Uri?>(null)
    var customFolderTreeUri by mutableStateOf<Uri?>(null)
    var whatsappTreeUri by mutableStateOf<Uri?>(null)

    var nearbyTransport: NearbyTransport? = null
    var usbTransport: UsbTetherTransport? = null
    private var transferManager: TransferManager? = null

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState

    fun toggleCategory(category: TransferCategory, enabled: Boolean) {
        if (enabled && category !in selectedCategories) selectedCategories.add(category)
        if (!enabled) selectedCategories.remove(category)
    }

    private fun buildModules(): Map<TransferCategory, TransferModule> = mapOf(
        TransferCategory.CONTACTS to ContactsModule(),
        TransferCategory.CALL_LOG to CallLogModule(),
        TransferCategory.CALENDAR to CalendarModule(),
        TransferCategory.MEDIA to MediaModule(),
        TransferCategory.FILES to FilesModule(filesTreeUri),
        TransferCategory.INSTALLED_APPS to AppsModule(),
        TransferCategory.WHATSAPP_MEDIA to WhatsAppModule(whatsappTreeUri),
        TransferCategory.CUSTOM_FOLDER to CustomFolderModule(customFolderTreeUri),
        TransferCategory.WALLPAPER to WallpaperModule(),
    )

    /** Wires a connected transport to a fresh TransferManager; receivers start listening immediately. */
    fun attachTransportAndStart(transport: P2pTransport) {
        val manager = TransferManager(getApplication(), transport, buildModules())
        transferManager = manager
        _transferState.value = TransferState.Idle
        viewModelScope.launch { manager.state.collect { _transferState.value = it } }
        if (role == Role.RECEIVER) manager.startReceiving()
    }

    fun beginSending(deviceName: String) {
        transferManager?.startSending(UUID.randomUUID().toString(), selectedCategories.toList(), deviceName)
    }

    override fun onCleared() {
        super.onCleared()
        nearbyTransport?.close()
        usbTransport?.close()
    }
}
