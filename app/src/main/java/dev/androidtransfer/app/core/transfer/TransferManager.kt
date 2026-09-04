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

enum class CategoryStatus { PENDING, RUNNING, DONE, FAILED }

data class CategoryProgress(val category: TransferCategory, val status: CategoryStatus)

sealed interface TransferState {
    data object Idle : TransferState

    /** The one "in progress" state: a live checklist plus session-wide byte/speed stats. */
    data class Running(
        val peerName: String?,
        val categories: List<CategoryProgress>,
        val bytesTransferred: Long,
        val speedBytesPerSecond: Double,
        val currentFileBytesTransferred: Long,
        val currentFileTotalBytes: Long,
    ) : TransferState

    data class Completed(val categories: List<CategoryProgress>) : TransferState
    data class Error(val message: String) : TransferState
}

/**
 * Drives one transfer session end to end over whichever [P2pTransport] the
 * user picked (Wi-Fi/Nearby or USB). The sender walks the selected
 * categories in order, asking each module to push its data through a
 * [CategorySink]; the receiver just reacts to whatever arrives and routes
 * it to the matching module. Both sides maintain the same kind of
 * checklist — the sender seeds it from the categories it was asked to
 * send, the receiver seeds it from the [ProtocolMessage.Manifest] the
 * sender announces up front.
 */
class TransferManager(
    private val context: Context,
    private val transport: P2pTransport,
    private val modules: Map<TransferCategory, TransferModule>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state

    private val categoryOrder = mutableListOf<TransferCategory>()
    private val categoryStatus = mutableMapOf<TransferCategory, CategoryStatus>()
    private var peerName: String? = null

    // Cumulative-bytes + smoothed-speed tracking across the whole session, not just the current file.
    private var cumulativeBytes = 0L
    private var currentItemId: String? = null
    private var currentItemBaseline = 0L
    private var currentFileBytes = 0L
    private var currentFileTotal = 0L
    private var lastSampleTimeMs = 0L
    private var lastSampleBytes = 0L
    private var speedBytesPerSecond = 0.0

    fun startSending(sessionId: String, categories: List<TransferCategory>, deviceName: String) {
        categoryOrder.clear()
        categoryOrder.addAll(categories)
        categories.forEach { categoryStatus[it] = CategoryStatus.PENDING }
        emitRunning()

        scope.launch {
            runCatching {
                transport.sendMessage(ProtocolMessage.Hello(sessionId, deviceName, appVersion = "0.1.0"))
                transport.sendMessage(ProtocolMessage.Manifest(sessionId, categories))
                for (category in categories) {
                    val module = modules[category]
                    if (module == null) {
                        transport.sendMessage(ProtocolMessage.Error(category, "No module registered for $category"))
                        categoryStatus[category] = CategoryStatus.FAILED
                        emitRunning()
                        continue
                    }
                    categoryStatus[category] = CategoryStatus.RUNNING
                    emitRunning()
                    val result = runCatching { module.export(context, CategorySink(category, transport)) }
                    result.onFailure { e -> transport.sendMessage(ProtocolMessage.Error(category, e.message ?: "export failed")) }
                    categoryStatus[category] = if (result.isSuccess) CategoryStatus.DONE else CategoryStatus.FAILED
                    emitRunning()
                    transport.sendMessage(ProtocolMessage.CategoryDone(category, itemsSent = 0))
                }
                transport.sendMessage(ProtocolMessage.TransferDone(sessionId))
                _state.value = TransferState.Completed(snapshotCategories())
            }.onFailure { e -> _state.value = TransferState.Error(e.message ?: "Transfer failed") }
        }
    }

    /** Starts listening for incoming protocol events; call once a connection is established on the receiving side. */
    fun startReceiving() {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> {
                        peerName = event.peerName
                        emitRunning()
                    }
                    is TransportEvent.Disconnected -> _state.value = TransferState.Error(event.reason)
                    is TransportEvent.TransportError -> _state.value = TransferState.Error(event.message)
                    is TransportEvent.Progress -> {
                        onProgress(event.itemId, event.bytesTransferred, event.totalBytes)
                        emitRunning()
                    }
                    is TransportEvent.MessageReceived -> handleMessage(event.message)
                    is TransportEvent.FileReceived -> handleFile(event.header, event.file)
                }
            }
        }
    }

    private suspend fun handleMessage(message: ProtocolMessage) {
        when (message) {
            is ProtocolMessage.Manifest -> {
                categoryOrder.clear()
                categoryOrder.addAll(message.categories)
                message.categories.forEach { categoryStatus.putIfAbsent(it, CategoryStatus.PENDING) }
                emitRunning()
            }
            is ProtocolMessage.Records -> {
                categoryStatus[message.category] = CategoryStatus.RUNNING
                emitRunning()
                val result = runCatching { modules[message.category]?.importRecords(context, message.jsonArray) }
                if (result.isFailure) categoryStatus[message.category] = CategoryStatus.FAILED
                emitRunning()
            }
            is ProtocolMessage.CategoryDone -> {
                if (categoryStatus[message.category] != CategoryStatus.FAILED) {
                    categoryStatus[message.category] = CategoryStatus.DONE
                }
                emitRunning()
            }
            is ProtocolMessage.TransferDone -> _state.value = TransferState.Completed(snapshotCategories())
            is ProtocolMessage.Error -> {
                message.category?.let { categoryStatus[it] = CategoryStatus.FAILED }
                emitRunning()
            }
            else -> Unit
        }
    }

    private suspend fun handleFile(header: ProtocolMessage.FileHeader, file: java.io.File) {
        categoryStatus[header.category] = CategoryStatus.RUNNING
        emitRunning()
        val result = runCatching { modules[header.category]?.importFile(context, header, file) }
        if (result.isFailure) categoryStatus[header.category] = CategoryStatus.FAILED
        file.delete()
        emitRunning()
    }

    private fun onProgress(itemId: String, bytesTransferred: Long, totalBytes: Long) {
        if (itemId != currentItemId) {
            currentItemBaseline = cumulativeBytes
            currentItemId = itemId
        }
        currentFileBytes = bytesTransferred
        currentFileTotal = totalBytes
        cumulativeBytes = currentItemBaseline + bytesTransferred

        val now = System.currentTimeMillis()
        if (lastSampleTimeMs == 0L) {
            lastSampleTimeMs = now
            lastSampleBytes = cumulativeBytes
            return
        }
        val elapsedSeconds = (now - lastSampleTimeMs) / 1000.0
        if (elapsedSeconds < 0.2) return // sample too soon to give a stable instantaneous rate
        val instantSpeed = (cumulativeBytes - lastSampleBytes) / elapsedSeconds
        speedBytesPerSecond = if (speedBytesPerSecond <= 0.0) instantSpeed else speedBytesPerSecond * 0.7 + instantSpeed * 0.3
        lastSampleTimeMs = now
        lastSampleBytes = cumulativeBytes
    }

    private fun snapshotCategories(): List<CategoryProgress> =
        categoryOrder.map { CategoryProgress(it, categoryStatus[it] ?: CategoryStatus.PENDING) }

    private fun emitRunning() {
        _state.value = TransferState.Running(
            peerName = peerName,
            categories = snapshotCategories(),
            bytesTransferred = cumulativeBytes,
            speedBytesPerSecond = speedBytesPerSecond,
            currentFileBytesTransferred = currentFileBytes,
            currentFileTotalBytes = currentFileTotal,
        )
    }
}
