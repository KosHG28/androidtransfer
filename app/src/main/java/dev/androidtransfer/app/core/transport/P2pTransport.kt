package dev.androidtransfer.app.core.transport

import dev.androidtransfer.app.core.transfer.ProtocolMessage
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.InputStream

/**
 * Transport-agnostic channel used by TransferManager to move the protocol
 * defined in ProtocolMessage.kt. Two implementations exist:
 *  - NearbyTransport: wireless, built on Google Play Services Nearby Connections
 *  - UsbTetherTransport: wired, a plain TCP socket over the USB-tethering link
 *
 * Connection setup (advertising/discovery/pairing for Nearby, IP discovery
 * for USB) is transport-specific and lives outside this interface; once a
 * transport reports itself connected via [events], TransferManager only
 * ever talks to it through these four methods.
 */
interface P2pTransport {
    val name: String

    /** Connection lifecycle, incoming control messages, incoming files, and progress ticks. */
    val events: SharedFlow<TransportEvent>

    suspend fun sendMessage(message: ProtocolMessage)

    /** Streams [sizeBytes] from [open] to the peer, announced by [header]. */
    suspend fun sendFile(header: ProtocolMessage.FileHeader, open: () -> InputStream)

    fun close()
}

sealed interface TransportEvent {
    data class Connected(val peerName: String) : TransportEvent
    data class Disconnected(val reason: String) : TransportEvent
    data class MessageReceived(val message: ProtocolMessage) : TransportEvent
    data class FileReceived(val header: ProtocolMessage.FileHeader, val file: File) : TransportEvent
    data class Progress(val itemId: String, val bytesTransferred: Long, val totalBytes: Long) : TransportEvent
    data class TransportError(val message: String) : TransportEvent
}
