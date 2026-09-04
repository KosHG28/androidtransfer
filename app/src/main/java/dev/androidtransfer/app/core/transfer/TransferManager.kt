package dev.androidtransfer.app.core.transfer

import android.content.Context
import dev.androidtransfer.app.core.transport.P2pTransport
import dev.androidtransfer.app.core.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface TransferState {
    data object Idle : TransferState
    data class Connected(val peerName: String) : TransferState
    data class RunningCategory(val category: TransferCategory) : TransferState
    data class ItemProgress(val itemId: String, val bytesTransferred: Long, val totalBytes: Long) : TransferState
    data object Completed : TransferState
    data class Error(val message: String) : TransferState
}

/**
 * Drives one transfer session end to end over whichever [P2pTransport] the
 * user picked (Wi-Fi/Nearby or USB). The sender walks the selected
 * categories in order, asking each module to push its data through a
 * [CategorySink]; the receiver just reacts to whatever arrives and routes
 * it to the matching module.
 */
class TransferManager(
    private val context: Context,
    private val transport: P2pTransport,
    private val modules: Map<TransferCategory, TransferModule>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state

    fun startSending(sessionId: String, categories: List<TransferCategory>, deviceName: String) {
        scope.launch {
            runCatching {
                transport.sendMessage(ProtocolMessage.Hello(sessionId, deviceName, appVersion = "0.1.0"))
                transport.sendMessage(ProtocolMessage.Manifest(sessionId, categories))
                for (category in categories) {
                    val module = modules[category]
                    if (module == null) {
                        transport.sendMessage(ProtocolMessage.Error(category, "No module registered for $category"))
                        continue
                    }
                    _state.value = TransferState.RunningCategory(category)
                    runCatching {
                        module.export(context, CategorySink(category, transport))
                    }.onFailure { e ->
                        transport.sendMessage(ProtocolMessage.Error(category, e.message ?: "export failed"))
                    }
                    transport.sendMessage(ProtocolMessage.CategoryDone(category, itemsSent = 0))
                }
                transport.sendMessage(ProtocolMessage.TransferDone(sessionId))
                _state.value = TransferState.Completed
            }.onFailure { e -> _state.value = TransferState.Error(e.message ?: "Transfer failed") }
        }
    }

    /** Starts listening for incoming protocol events; call once a connection is established on the receiving side. */
    fun startReceiving() {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> _state.value = TransferState.Connected(event.peerName)
                    is TransportEvent.Disconnected -> _state.value = TransferState.Error(event.reason)
                    is TransportEvent.TransportError -> _state.value = TransferState.Error(event.message)
                    is TransportEvent.Progress -> _state.value = TransferState.ItemProgress(event.itemId, event.bytesTransferred, event.totalBytes)
                    is TransportEvent.MessageReceived -> handleMessage(event.message)
                    is TransportEvent.FileReceived -> handleFile(event.header, event.file)
                }
            }
        }
    }

    private suspend fun handleMessage(message: ProtocolMessage) {
        when (message) {
            is ProtocolMessage.Manifest -> Unit
            is ProtocolMessage.Records -> {
                _state.value = TransferState.RunningCategory(message.category)
                runCatching { modules[message.category]?.importRecords(context, message.jsonArray) }
                    .onFailure { e -> _state.value = TransferState.Error(e.message ?: "import failed") }
            }
            is ProtocolMessage.CategoryDone -> Unit
            is ProtocolMessage.TransferDone -> _state.value = TransferState.Completed
            is ProtocolMessage.Error -> _state.value = TransferState.Error(message.message)
            else -> Unit
        }
    }

    private suspend fun handleFile(header: ProtocolMessage.FileHeader, file: java.io.File) {
        _state.value = TransferState.RunningCategory(header.category)
        runCatching { modules[header.category]?.importFile(context, header, file) }
            .onFailure { e -> _state.value = TransferState.Error(e.message ?: "import failed") }
        file.delete()
    }
}
