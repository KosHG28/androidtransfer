package dev.androidtransfer.app.core.transport

import dev.androidtransfer.app.core.transfer.ProtocolMessage
import dev.androidtransfer.app.core.transfer.TransferCategory
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

    /**
     * One file failed while the connection itself is still fine. Deliberately
     * NOT a [TransportError]: that one ends the whole session, and a single
     * unreadable photo must not tear down a transfer with hundreds of files
     * still to move. The category is marked failed, the session continues.
     */
    data class FileFailed(val category: TransferCategory?, val displayName: String?, val reason: String?) : TransportEvent

    /** Fatal for the session — the link itself is unusable from here on. */
    data class TransportError(val message: String) : TransportEvent
}
