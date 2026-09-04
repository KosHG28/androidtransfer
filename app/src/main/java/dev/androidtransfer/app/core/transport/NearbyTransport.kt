package dev.androidtransfer.app.core.transport

import android.content.Context
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.androidtransfer.app.core.transfer.ProtocolJson
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wireless transport built on Nearby Connections (P2P_POINT_TO_POINT
 * strategy, which prefers a direct Wi-Fi link and falls back to Bluetooth
 * automatically). This is the same building block Google ships for exactly
 * this kind of device-to-device transfer flow, so payload chunking, retries
 * and bandwidth upgrades are handled by Play Services rather than by us.
 *
 * A FileHeader control message always precedes the matching FILE payload;
 * the two are correlated by [ProtocolMessage.FileHeader.nearbyPayloadId],
 * so [FileReceived] fires only once both the header and the completed
 * payload are in hand, in whichever order they actually arrive.
 */
class NearbyTransport(
    context: Context,
    private val serviceId: String = "dev.androidtransfer.app.SERVICE",
) : P2pTransport {

    override val name: String = "wifi"

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _discoveredEndpoints = MutableStateFlow<Map<String, DiscoveredEndpointInfo>>(emptyMap())
    val discoveredEndpoints: StateFlow<Map<String, DiscoveredEndpointInfo>> = _discoveredEndpoints

    /** endpointId to the short pairing code the user must confirm on both screens. */
    private val _pendingAuthDigits = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val pendingAuthDigits: SharedFlow<Pair<String, String>> = _pendingAuthDigits.asSharedFlow()

    private var connectedEndpointId: String? = null

    private val pendingFileHeaders = ConcurrentHashMap<Long, ProtocolMessage.FileHeader>()
    private val pendingFilePayloads = ConcurrentHashMap<Long, Payload>()

    /**
     * Outgoing payloads staged in cacheDir, keyed by payload id. Nearby reads
     * these asynchronously, so they can only be deleted once the transfer
     * reports a result — otherwise a large photo/APK transfer leaves a full
     * second copy of everything behind in the cache.
     */
    private val stagedOutgoingFiles = ConcurrentHashMap<Long, File>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val message = runCatching {
                        ProtocolJson.instance.decodeFromString(ProtocolMessage.serializer(), String(bytes, Charsets.UTF_8))
                    }.getOrNull() ?: return
                    if (message is ProtocolMessage.FileHeader && message.nearbyPayloadId != null) {
                        pendingFileHeaders[message.nearbyPayloadId] = message
                        tryCompleteFile(message.nearbyPayloadId)
                    } else {
                        scope.launch { _events.emit(TransportEvent.MessageReceived(message)) }
                    }
                }
                Payload.Type.FILE -> {
                    pendingFilePayloads[payload.id] = payload
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            pendingFileHeaders[update.payloadId]?.let { header ->
                scope.launch { _events.emit(TransportEvent.Progress(header.itemId, update.bytesTransferred, update.totalBytes)) }
            }
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                tryCompleteFile(update.payloadId)
                releaseStagedOutgoing(update.payloadId)
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                pendingFileHeaders.remove(update.payloadId)
                pendingFilePayloads.remove(update.payloadId)
                releaseStagedOutgoing(update.payloadId)
                scope.launch { _events.emit(TransportEvent.TransportError("File transfer failed")) }
            }
        }
    }

    /** Drops our own staged copy of an outgoing file, and its progress bookkeeping, once Nearby is done with it. */
    private fun releaseStagedOutgoing(payloadId: Long) {
        val staged = stagedOutgoingFiles.remove(payloadId) ?: return
        staged.delete()
        pendingFileHeaders.remove(payloadId)
    }

    /**
     * Fires FileReceived once both the header and a fully-transferred payload
     * are present. Nearby stages completed FILE payloads outside the app's
     * own storage — on some devices under the shared
     * `/storage/emulated/0/Download/.nearby/` — and [Payload.File.asJavaFile]
     * hands back a raw path into that location. Opening it with a plain
     * `File.inputStream()` then fails with EACCES, because the app holds no
     * general filesystem permission for shared storage outside MediaStore/SAF.
     * [Payload.File.asParcelFileDescriptor] sidesteps that: Nearby opened the
     * descriptor itself, so reading through it doesn't need our own
     * permission. We copy through it once into our private cache so every
     * downstream module — which just expects a plain, readable [File] —
     * keeps working unchanged.
     */
    private fun tryCompleteFile(payloadId: Long) {
        val header = pendingFileHeaders[payloadId] ?: return
        val payload = pendingFilePayloads[payloadId] ?: return
        val payloadFile = payload.asFile() ?: return
        pendingFileHeaders.remove(payloadId)
        pendingFilePayloads.remove(payloadId)
        scope.launch {
            val privateCopy = File(appContext.cacheDir, "incoming_${UUID.randomUUID()}")
            val copied = runCatching {
                val pfd = payloadFile.asParcelFileDescriptor() ?: error("No file descriptor for payload")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    privateCopy.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (copied.isSuccess) {
                _events.emit(TransportEvent.FileReceived(header, privateCopy))
            } else {
                privateCopy.delete()
                _events.emit(TransportEvent.TransportError("Не удалось прочитать принятый файл: ${copied.exceptionOrNull()?.message}"))
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            scope.launch { _pendingAuthDigits.emit(endpointId to info.authenticationDigits) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                connectedEndpointId = endpointId
                scope.launch { _events.emit(TransportEvent.Connected(endpointId)) }
            } else {
                scope.launch { _events.emit(TransportEvent.TransportError("Connection failed: ${resolution.status.statusMessage}")) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointId = null
            scope.launch { _events.emit(TransportEvent.Disconnected("Peer disconnected")) }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            _discoveredEndpoints.value = _discoveredEndpoints.value + (endpointId to info)
        }

        override fun onEndpointLost(endpointId: String) {
            _discoveredEndpoints.value = _discoveredEndpoints.value - endpointId
        }
    }

    /**
     * Every Nearby call below returns a Task whose failure we used to
     * silently drop — a missing permission or Play Services error meant
     * the UI just sat on "waiting" forever with zero indication anything
     * was wrong. Route every failure through [_events] instead.
     */
    private fun emitFailure(context: String, exception: Exception) {
        scope.launch { _events.emit(TransportEvent.TransportError("$context: ${exception.message ?: exception.javaClass.simpleName}")) }
    }

    fun startAdvertising(deviceName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(deviceName, serviceId, connectionLifecycleCallback, options)
            .addOnFailureListener { e -> emitFailure("Не удалось начать ожидание (advertising)", e) }
    }

    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnFailureListener { e -> emitFailure("Не удалось начать поиск (discovery)", e) }
    }

    fun requestConnection(localDeviceName: String, endpointId: String) {
        client.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { e -> emitFailure("Не удалось запросить подключение", e) }
    }

    fun acceptConnection(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { e -> emitFailure("Не удалось принять подключение", e) }
    }

    fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    fun stopDiscoveryAndAdvertising() {
        client.stopDiscovery()
        client.stopAdvertising()
    }

    override suspend fun sendMessage(message: ProtocolMessage) {
        val endpointId = connectedEndpointId ?: return
        val json = ProtocolJson.instance.encodeToString(ProtocolMessage.serializer(), message)
        client.sendPayload(endpointId, Payload.fromBytes(json.toByteArray(Charsets.UTF_8))).await()
    }

    /**
     * Nearby's FILE payload type needs a real File. Callers hand us an
     * InputStream (it may come from a content:// Uri with no direct path),
     * so we stage it into our cache dir first and let Payload.fromFile take
     * ownership of the completed copy.
     */
    override suspend fun sendFile(header: ProtocolMessage.FileHeader, open: () -> InputStream) {
        val endpointId = connectedEndpointId ?: return
        val staged = File(appContext.cacheDir, "outgoing_${UUID.randomUUID()}")
        open().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        val filePayload = Payload.fromFile(staged)

        // Registering the header against the outgoing payload id is what lets
        // onPayloadTransferUpdate report progress on the *sending* side too;
        // the staged copy is deleted when that same callback reports a result.
        stagedOutgoingFiles[filePayload.id] = staged
        pendingFileHeaders[filePayload.id] = header.copy(nearbyPayloadId = filePayload.id)

        sendMessage(header.copy(nearbyPayloadId = filePayload.id))
        client.sendPayload(endpointId, filePayload).await()
    }

    override fun close() {
        connectedEndpointId?.let { client.disconnectFromEndpoint(it) }
        client.stopAllEndpoints()
        stopDiscoveryAndAdvertising()
        // Anything still staged here belongs to a transfer that never finished.
        stagedOutgoingFiles.values.forEach { runCatching { it.delete() } }
        stagedOutgoingFiles.clear()
        appContext.cacheDir.listFiles { file -> file.name.startsWith("outgoing_") }
            ?.forEach { runCatching { it.delete() } }
    }
}
