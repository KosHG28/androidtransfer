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
import dev.androidtransfer.app.core.transfer.TransferForegroundService
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
import kotlinx.coroutines.Job
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

    /** Which apps' APKs to send. Empty until the picker has been opened at least once. */
    val selectedAppPackages: SnapshotStateList<String> = mutableStateListOf()
    var appSelectionInitialized by mutableStateOf(false)
        private set

    fun initAppSelection(packages: List<String>) {
        selectedAppPackages.clear()
        selectedAppPackages.addAll(packages)
        appSelectionInitialized = true
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        if (enabled && packageName !in selectedAppPackages) selectedAppPackages.add(packageName)
        if (!enabled) selectedAppPackages.remove(packageName)
    }

    var nearbyTransport: NearbyTransport? = null
    var usbTransport: UsbTetherTransport? = null
    private var activeManager: TransferManager? = null
    private var stateCollectJob: Job? = null

    /**
     * Flips once [attachTransportAndStart] hands the transport to
     * TransferForegroundService — from that point on the service owns
     * closing it, so [onCleared] below must not also close it (that would
     * defeat the whole point of moving ownership there).
     */
    private var transportOwnedByService = false

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
        TransferCategory.INSTALLED_APPS to AppsModule(
            selectedPackages = if (appSelectionInitialized) selectedAppPackages.toSet() else null,
        ),
        TransferCategory.WHATSAPP_MEDIA to WhatsAppModule(whatsappTreeUri),
        TransferCategory.CUSTOM_FOLDER to CustomFolderModule(customFolderTreeUri),
        TransferCategory.WALLPAPER to WallpaperModule(),
    )

    /**
     * Hands the now-connected transport to TransferForegroundService, which
     * becomes its sole owner from this point on and drives a fresh
     * TransferManager over it. Both roles need the manager listening: the
     * receiver to route incoming data, the sender because progress/speed
     * also arrive as transport events.
     *
     * Doing the handoff here (rather than leaving the manager owned by this
     * ViewModel) matters because the ViewModel is Activity-scoped: swiping
     * the task away from Recents destroys the Activity and clears the
     * ViewModel even though the *process* survives (the foreground service
     * itself keeps it alive). Once the transport belongs to the service,
     * that teardown no longer touches it.
     */
    fun attachTransportAndStart(transport: P2pTransport) {
        _transferState.value = TransferState.Idle
        val app = getApplication<Application>()
        val handedOff = TransferForegroundService.withService(app) { service ->
            val manager = service.attach(app, transport, buildModules())
            activeManager = manager
            stateCollectJob?.cancel()
            stateCollectJob = viewModelScope.launch { service.state.collect { _transferState.value = it } }
        }
        transportOwnedByService = handedOff
        if (!handedOff) {
            // The system refused to start the service at all. Run the
            // transfer in-process rather than leaving the user on
            // "Подготовка…" forever waiting for a handoff that will never
            // happen — it just won't survive the app being swiped away.
            val manager = TransferManager(app, transport, buildModules())
            activeManager = manager
            stateCollectJob?.cancel()
            stateCollectJob = viewModelScope.launch { manager.state.collect { _transferState.value = it } }
            manager.startListening()
        }
    }

    /**
     * Re-attaches the UI to a transfer the service is still running — the
     * case where the user swiped the app away mid-transfer (which the
     * service now survives) and then reopened it. Without this the fresh
     * ViewModel would show a blank Idle home screen while a transfer is
     * still going in the background, and the user could even kick off a
     * second one on top of it.
     *
     * @return true if there was a live session to adopt.
     */
    fun adoptRunningSessionIfAny(): Boolean {
        val service = TransferForegroundService.runningSession() ?: return false
        val manager = service.activeManager() ?: return false
        activeManager = manager
        transportOwnedByService = true
        stateCollectJob?.cancel()
        stateCollectJob = viewModelScope.launch { service.state.collect { _transferState.value = it } }
        return true
    }

    fun beginSending(deviceName: String) {
        // Rebuild with whatever the user actually chose on CategorySelectionScreen/
        // AppPickerScreen — the map built in attachTransportAndStart is stale (it
        // was captured right after connecting, before those screens ran).
        activeManager?.updateModules(buildModules())
        activeManager?.startSending(UUID.randomUUID().toString(), orderedSelection(), deviceName)
    }

    /** Stops the running transfer at the user's request; the service closes the link once it sees the terminal state. */
    fun cancelTransfer() {
        activeManager?.cancel()
    }

    /**
     * Small, quick categories go first and the app APKs — potentially several
     * gigabytes — go last. Otherwise a long-running app transfer starves
     * everything queued behind it, which is how wallpaper ended up never
     * running at all.
     */
    private fun orderedSelection(): List<TransferCategory> {
        val order = listOf(
            TransferCategory.CONTACTS,
            TransferCategory.CALL_LOG,
            TransferCategory.CALENDAR,
            TransferCategory.WALLPAPER,
            TransferCategory.MEDIA,
            TransferCategory.FILES,
            TransferCategory.WHATSAPP_MEDIA,
            TransferCategory.CUSTOM_FOLDER,
            TransferCategory.INSTALLED_APPS,
        )
        return order.filter { it in selectedCategories }
    }

    override fun onCleared() {
        super.onCleared()
        // Once handed to TransferForegroundService, closing here would kill
        // the very connection the service exists to protect. Only clean up
        // a transport that never got past pairing (no transfer ever
        // attached it to the service).
        if (!transportOwnedByService) {
            nearbyTransport?.close()
            usbTransport?.close()
        }
    }
}
