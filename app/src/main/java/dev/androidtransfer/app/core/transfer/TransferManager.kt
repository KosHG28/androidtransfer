package dev.androidtransfer.app.core.transfer

import android.content.Context
import dev.androidtransfer.app.core.history.HistoryCategoryResult
import dev.androidtransfer.app.core.history.TransferHistoryEntry
import dev.androidtransfer.app.core.history.TransferHistoryStore
import dev.androidtransfer.app.core.transport.P2pTransport
import dev.androidtransfer.app.core.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
enum class CategoryStatus { PENDING, RUNNING, DONE, FAILED }

/** [detail] carries what actually happened: a count on success, or the failure reason — a bare red icon tells the user nothing. */
data class CategoryProgress(val category: TransferCategory, val status: CategoryStatus, val detail: String? = null)

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
    private var modules: Map<TransferCategory, TransferModule>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state

    private val categoryOrder = mutableListOf<TransferCategory>()
    private val categoryStatus = mutableMapOf<TransferCategory, CategoryStatus>()
    private val categoryDetail = mutableMapOf<TransferCategory, String>()
    private val categoryFileCount = mutableMapOf<TransferCategory, Int>()
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

    /**
     * Set once the transport reports the peer gone. Without this, a
     * disconnect mid-transfer only affected the UI via the Disconnected event
     * handler below — the sender's own send loop (a separate coroutine) kept
     * walking the remaining categories regardless, since sendMessage/sendFile
     * on a dead transport just silently no-op rather than throw. It would
     * then reach the end of the loop and overwrite the Error state the
     * Disconnected handler had already set with a false "Completed",
     * reporting success for a transfer that actually died halfway through.
     */
    @Volatile private var connectionLost = false

    /** Set at the top of [startSending] — distinguishes the two terminal-state call sites below for history recording, since Disconnected/TransportError can happen on either side. */
    private var startedAsSender = false

    /**
     * The module map built in [TransferViewModel.attachTransportAndStart] is
     * captured right after the transport connects — for the sender, that's
     * *before* CategorySelectionScreen/AppPickerScreen, where SAF folder URIs
     * and the app selection are actually chosen. Without a way to refresh it,
     * FilesModule/CustomFolderModule/WhatsAppModule always saw a null tree URI
     * (so export() no-ops) and AppsModule always saw "send everything",
     * silently ignoring whatever the user picked. Call this right before
     * [startSending] with a freshly built map so it reflects the real choices.
     */
    fun updateModules(newModules: Map<TransferCategory, TransferModule>) {
        modules = newModules
    }

    fun startSending(sessionId: String, categories: List<TransferCategory>, deviceName: String) {
        startedAsSender = true
        categoryOrder.clear()
        categoryOrder.addAll(categories)
        categories.forEach { categoryStatus[it] = CategoryStatus.PENDING }
        emitRunning()

        scope.launch {
            runCatching {
                transport.sendMessage(ProtocolMessage.Hello(sessionId, deviceName, appVersion = "0.1.0"))
                transport.sendMessage(ProtocolMessage.Manifest(sessionId, categories))
                for (category in categories) {
                    if (connectionLost) break
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
                    result.onFailure { e ->
                        val reason = e.message ?: e.javaClass.simpleName
                        categoryDetail[category] = reason
                        transport.sendMessage(ProtocolMessage.Error(category, reason))
                    }
                    categoryStatus[category] = if (result.isSuccess) CategoryStatus.DONE else CategoryStatus.FAILED
                    emitRunning()
                    transport.sendMessage(ProtocolMessage.CategoryDone(category, itemsSent = 0))
                }
                if (connectionLost) {
                    error("Соединение потеряно во время переноса")
                }
                transport.sendMessage(ProtocolMessage.TransferDone(sessionId))
                finishSession(TransferState.Completed(snapshotCategories()))
            }.onFailure { e ->
                // A disconnect already put a more specific message in _state
                // (and recorded history) via the Disconnected handler in
                // startListening() — don't clobber it with the generic
                // "Соединение потеряно" above.
                if (!connectionLost) finishSession(TransferState.Error(e.message ?: "Transfer failed"))
            }
        }
    }

    /**
     * Starts consuming transport events; call once a connection is
     * established, on BOTH sides. The receiver needs it to route incoming
     * data, and the sender needs it too — progress/speed ticks arrive as
     * transport events, so without this the sending phone shows a transfer
     * with no progress bar and no speed at all.
     */
    fun startListening() {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> {
                        peerName = event.peerName
                        emitRunning()
                    }
                    is TransportEvent.Disconnected -> {
                        connectionLost = true
                        finishSession(TransferState.Error(event.reason))
                    }
                    is TransportEvent.TransportError -> finishSession(TransferState.Error(event.message))
                    is TransportEvent.Progress -> {
                        onProgress(event.itemId, event.bytesTransferred, event.totalBytes)
                        emitRunningUnlessFinished()
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
                result
                    .onSuccess { categoryDetail[message.category] = "${message.count} шт." }
                    .onFailure { e ->
                        categoryStatus[message.category] = CategoryStatus.FAILED
                        categoryDetail[message.category] = e.message ?: e.javaClass.simpleName
                    }
                emitRunning()
            }
            is ProtocolMessage.CategoryDone -> {
                if (categoryStatus[message.category] != CategoryStatus.FAILED) {
                    categoryStatus[message.category] = CategoryStatus.DONE
                }
                emitRunning()
            }
            is ProtocolMessage.TransferDone -> finishSession(TransferState.Completed(snapshotCategories()))
            is ProtocolMessage.Error -> {
                message.category?.let {
                    categoryStatus[it] = CategoryStatus.FAILED
                    categoryDetail[it] = message.message
                }
                emitRunning()
            }
            else -> Unit
        }
    }

    private suspend fun handleFile(header: ProtocolMessage.FileHeader, file: java.io.File) {
        categoryStatus[header.category] = CategoryStatus.RUNNING
        emitRunning()

        // A record set too big for an inline message arrives as a file; unwrap
        // it here so modules still only ever see importRecords.
        if (header.groupKey == CategorySink.RECORDS_GROUP_KEY) {
            val outcome = runCatching {
                val json = file.readText(Charsets.UTF_8)
                modules[header.category]?.importRecords(context, json)
            }
            outcome
                .onSuccess { categoryDetail[header.category] = "${header.recordCount ?: 0} шт." }
                .onFailure { e ->
                    categoryStatus[header.category] = CategoryStatus.FAILED
                    categoryDetail[header.category] = e.message ?: e.javaClass.simpleName
                }
            file.delete()
            emitRunning()
            return
        }

        val result = runCatching { modules[header.category]?.importFile(context, header, file) }
        result
            .onSuccess {
                val count = (categoryFileCount[header.category] ?: 0) + 1
                categoryFileCount[header.category] = count
                categoryDetail[header.category] = "$count файл(ов)"
            }
            .onFailure { e ->
                categoryStatus[header.category] = CategoryStatus.FAILED
                categoryDetail[header.category] = e.message ?: e.javaClass.simpleName
            }
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
        categoryOrder.map { CategoryProgress(it, categoryStatus[it] ?: CategoryStatus.PENDING, categoryDetail[it]) }

    /**
     * The single path to a terminal state. Guards against being called twice
     * for the same session (e.g. a disconnect already recorded, then the
     * send loop's own failure handler firing right after) and logs a
     * [TransferHistoryEntry] alongside setting [_state] — every place that
     * used to assign Completed/Error to [_state] directly goes through this
     * instead, so history can't silently miss one.
     */
    private fun finishSession(state: TransferState) {
        if (_state.value is TransferState.Completed || _state.value is TransferState.Error) return
        _state.value = state
        runCatching {
            TransferHistoryStore.record(
                context,
                TransferHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    role = if (startedAsSender) "SENDER" else "RECEIVER",
                    transport = transport.name,
                    peerName = peerName,
                    overallFailed = state is TransferState.Error,
                    errorMessage = (state as? TransferState.Error)?.message,
                    categories = snapshotCategories().map { HistoryCategoryResult(it.category, it.status, it.detail) },
                ),
            )
        }
    }

    /** Late progress ticks must not drag a finished transfer back to "running". */
    private fun emitRunningUnlessFinished() {
        if (_state.value is TransferState.Completed || _state.value is TransferState.Error) return
        emitRunning()
    }

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
