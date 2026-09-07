package dev.androidtransfer.app.core.transport

import android.content.Context
import dev.androidtransfer.app.core.transfer.ProtocolJson
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID

/**
 * Wired transport carried over the USB-tethering link between the two
 * phones. Plugging a USB-C cable between two Android phones does not by
 * itself create a data link the way a PC<->phone cable does; one side has
 * to turn on "USB tethering" (Settings > Network > Hotspot & tethering),
 * which brings up an RNDIS network interface on both ends of the cable.
 * Once that interface exists we treat it exactly like any other local
 * network link: one side is a TCP server, the other a TCP client, using a
 * simple length-prefixed framing (see [FrameType]) instead of Nearby's
 * Payload API (which only operates over Nearby's own advertise/discover
 * connection, not an arbitrary socket).
 *
 * The RNDIS gateway address is not standardized across OEMs, so
 * [UsbLinkDiscovery] offers a best-effort guess plus a manual fallback
 * surfaced in the UI.
 *
 * Framing assumes [sendMessage]/[sendFile] are only ever called
 * sequentially by a single writer (true of TransferManager's one
 * send-loop coroutine): a control frame sent mid-file-transfer would be
 * misread as a stray chunk by [receiveFile] and dropped. A file's end is
 * marked explicitly (FRAME_TYPE_FILE_END) rather than inferred by counting
 * up to the header's declared size, so a size that's off by even one byte
 * can't desync the rest of the session.
 */
class UsbTetherTransport(context: Context) : P2pTransport {

    override val name: String = "usb"

    companion object {
        const val DEFAULT_PORT = 57123
        private const val FRAME_TYPE_CONTROL: Byte = 1
        private const val FRAME_TYPE_FILE_CHUNK: Byte = 2

        /**
         * Sent once, right after the last chunk. The receiver used to decide a
         * file was complete once it had counted [ProtocolMessage.FileHeader.sizeBytes]
         * worth of chunk bytes — fine as long as that declared size exactly
         * matches what open() actually streams. If a module's declared size is
         * ever off by even one byte (a stale MediaStore SIZE column, a provider
         * that estimates), the stream desyncs permanently: leftover bytes get
         * misread as the next frame's header, or the receiver hangs waiting for
         * bytes that already arrived. An explicit end marker makes completion
         * independent of the size being exactly right.
         */
        private const val FRAME_TYPE_FILE_END: Byte = 3
        private const val CHUNK_SIZE = 256 * 1024
    }

    sealed interface Role {
        data class Server(val port: Int = DEFAULT_PORT) : Role
        data class Client(val host: String, val port: Int = DEFAULT_PORT) : Role
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    suspend fun connect(role: Role) = withContext(Dispatchers.IO) {
        try {
            val active = when (role) {
                is Role.Server -> {
                    val server = ServerSocket(role.port)
                    serverSocket = server
                    server.accept()
                }
                is Role.Client -> Socket().apply { connect(InetSocketAddress(role.host, role.port), 8_000) }
            }
            socket = active
            output = DataOutputStream(active.getOutputStream())
            scope.launch { _events.emit(TransportEvent.Connected(active.inetAddress?.hostAddress ?: "usb-peer")) }
            scope.launch { readLoop(DataInputStream(active.getInputStream())) }
        } catch (t: Throwable) {
            _events.emit(TransportEvent.TransportError("USB connection failed: ${t.message}"))
        }
    }

    private suspend fun readLoop(input: DataInputStream) {
        try {
            while (true) {
                val type = input.readByte()
                val length = input.readInt()
                when (type) {
                    FRAME_TYPE_CONTROL -> {
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        val message = runCatching {
                            ProtocolJson.instance.decodeFromString(ProtocolMessage.serializer(), String(bytes, Charsets.UTF_8))
                        }.getOrNull() ?: continue
                        if (message is ProtocolMessage.FileHeader) {
                            receiveFile(message, input)
                        } else {
                            _events.emit(TransportEvent.MessageReceived(message))
                        }
                    }
                    else -> {
                        // Unexpected chunk with no preceding header; drain and drop it.
                        skipFully(input, length)
                    }
                }
            }
        } catch (t: Throwable) {
            _events.emit(TransportEvent.Disconnected(t.message ?: "USB link closed"))
        }
    }

    private suspend fun receiveFile(header: ProtocolMessage.FileHeader, input: DataInputStream) {
        val dest = File(appContext.cacheDir, "incoming_${UUID.randomUUID()}")
        var received = 0L
        dest.outputStream().use { out ->
            loop@ while (true) {
                val type = input.readByte()
                val length = input.readInt()
                when (type) {
                    FRAME_TYPE_FILE_CHUNK -> {
                        val buffer = ByteArray(length)
                        input.readFully(buffer)
                        out.write(buffer)
                        received += length
                        // header.sizeBytes is only ever used for the progress
                        // fraction here, never to decide when the file is done.
                        _events.emit(TransportEvent.Progress(header.itemId, received, header.sizeBytes))
                    }
                    FRAME_TYPE_FILE_END -> break@loop
                    else -> skipFully(input, length)
                }
            }
        }
        _events.emit(TransportEvent.FileReceived(header, dest))
    }

    private fun skipFully(input: DataInputStream, length: Int) {
        var remaining = length
        val buffer = ByteArray(minOf(remaining, CHUNK_SIZE))
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read < 0) break
            remaining -= read
        }
    }

    override suspend fun sendMessage(message: ProtocolMessage) {
        val out = output ?: return
        val json = ProtocolJson.instance.encodeToString(ProtocolMessage.serializer(), message).toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            out.writeByte(FRAME_TYPE_CONTROL.toInt())
            out.writeInt(json.size)
            out.write(json)
            out.flush()
        }
    }

    override suspend fun sendFile(header: ProtocolMessage.FileHeader, open: () -> InputStream) {
        val out = output ?: return
        sendMessage(header)
        open().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var sent = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                writeLock.withLock {
                    out.writeByte(FRAME_TYPE_FILE_CHUNK.toInt())
                    out.writeInt(read)
                    out.write(buffer, 0, read)
                    out.flush()
                }
                sent += read
                _events.emit(TransportEvent.Progress(header.itemId, sent, header.sizeBytes))
            }
        }
        // Tells the receiver the file is complete regardless of whether `sent`
        // ended up matching header.sizeBytes exactly.
        writeLock.withLock {
            out.writeByte(FRAME_TYPE_FILE_END.toInt())
            out.writeInt(0)
            out.flush()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
    }
}

/**
 * Best-effort discovery of the peer's IP address on the USB-tethering
 * link. The RNDIS gateway address differs by OEM/Android version, so this
 * only narrows the search; the UI must still offer a manual IP field.
 */
object UsbLinkDiscovery {
    private val commonGatewayGuesses = listOf("192.168.42.129", "192.168.42.1", "192.168.43.1", "192.168.55.1")

    fun candidateGatewayAddresses(): List<String> = commonGatewayGuesses

    /** Non-loopback IPv4 interfaces that look like a USB/RNDIS/Ethernet-over-USB link rather than Wi-Fi or mobile data. */
    fun localUsbInterfaceAddresses(): List<String> {
        val names = listOf("rndis", "usb", "eth")
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { iface -> iface.isUp && !iface.isLoopback && names.any { iface.name.contains(it, ignoreCase = true) } }
            .flatMap { iface -> Collections.list(iface.inetAddresses) }
            .filter { addr -> addr.hostAddress?.contains(':') == false } // IPv4 only
            .mapNotNull { it.hostAddress }
    }
}
