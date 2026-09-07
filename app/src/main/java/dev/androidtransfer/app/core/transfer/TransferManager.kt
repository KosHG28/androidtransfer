package dev.androidtransfer.app.core.transfer

import android.content.Context
import dev.androidtransfer.app.core.history.HistoryCategoryResult
import dev.androidtransfer.app.core.history.TransferHistoryEntry
import dev.androidtransfer.app.core.history.TransferHistoryStore
import dev.androidtransfer.app.core.transport.P2pTransport
import dev.androidtransfer.app.core.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
        /** Nothing has moved for a while, but the link never reported a clean disconnect. */
        val stalled: Boolean = false,
        /** Announced up front by the sender; 0 when unknown. */
        val totalBytesExpected: Long = 0,
        /** Set on the receiver when the incoming transfer looks bigger than the free space it has. */
        val spaceWarning: String? = null,
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
    private val categoryFailedFiles = mutableMapOf<TransferCategory, Int>()
    private val categorySkippedFiles = mutableMapOf<TransferCategory, Int>()
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

    private var sendJob: Job? = null

    // Stall detection. A link can stop moving data without ever reporting a
    // disconnect — the peer walks out of range, Play Services wedges, the
    // cable is nudged — and the UI would otherwise sit on a frozen progress
    // bar indefinitely with no way to tell whether to keep waiting.
    @Volatile private var lastActivityMs = 0L
    @Volatile private var stalled = false
    /** The clock only starts once data is actually meant to be flowing; a receiver waiting on a sender who is still picking categories is not stalled. */
    @Volatile private var transferStarted = false

    private var totalBytesExpected = 0L
    private var spaceWarning: String? = null

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
        markActivity()
        transferStarted = true
        emitRunning()

        sendJob = scope.launch {
            runCatching {
                transport.sendMessage(ProtocolMessage.Hello(sessionId, deviceName, appVersion = "0.1.0"))

                // Measured before anything is sent so the receiver can refuse
                // or warn about free space up front rather than filling its
                // disk and failing mid-transfer.
                var planned = 0L
                var incomplete = false
                for (category in categories) {
                    val estimate = runCatching { modules[category]?.estimate(context) }.getOrNull()
                    if (estimate == null) incomplete = true else planned += estimate.bytes
                    // Scanning a large media library or hundreds of APKs takes
                    // real time, and the stall clock is already running — without
                    // this the sender would accuse itself of having frozen before
                    // it has sent a single byte.
                    markActivity()
                }
                totalBytesExpected = planned
                markActivity()
                transport.sendMessage(ProtocolMessage.Manifest(sessionId, categories, planned, incomplete))
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
        startStallWatchdog()
        scope.launch {
            transport.events.collect { event ->
                markActivity()
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
                    is TransportEvent.FileFailed -> {
                        event.category?.let { noteFileFailure(it, event.reason ?: "файл не передан", event.displayName) }
                        emitRunningUnlessFinished()
                    }
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

    /**
     * Warns instead of refusing: the estimate can be short (a folder tree that
     * couldn't be measured) or long (duplicates that will be skipped on
     * arrival), so the honest move is to show the numbers and let the user
     * decide — they have a cancel button now. Silence was the bad option:
     * running out of space mid-transfer surfaces as an opaque write error
     * several gigabytes in.
     */
    private fun checkFreeSpace(estimatedBytes: Long, sizesIncomplete: Boolean): String? {
        if (estimatedBytes <= 0) return null
        val free = runCatching {
            @Suppress("DEPRECATION")
            val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBytes
        }.getOrNull() ?: return null
        if (estimatedBytes <= free) return null
        val needed = Format.megabytes(estimatedBytes)
        val available = Format.megabytes(free)
        return buildString {
            append("Может не хватить места: нужно ")
            if (sizesIncomplete) append("минимум ")
            append("$needed, свободно $available")
        }
    }

    private fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
        stalled = false
    }

    /**
     * Reports a transfer that has stopped moving without the transport ever
     * saying so, so the user can stop waiting on something that is never
     * coming back. Deliberately only a warning plus an offer to cancel, never
     * an automatic abort: a slow link mid-way through a 2 GB app is not a
     * failure, and killing it would be worse than waiting.
     */
    private fun startStallWatchdog() {
        scope.launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                if (!transferStarted) continue
                if (_state.value !is TransferState.Running) continue
                val idleFor = System.currentTimeMillis() - lastActivityMs
                val nowStalled = idleFor >= STALL_THRESHOLD_MS
                if (nowStalled != stalled) {
                    stalled = nowStalled
                    emitRunning()
                }
            }
        }
    }

    /**
     * User-initiated stop. [connectionLost] doubles as the send loop's "give
     * up" flag, and cancelling the job interrupts a file already streaming —
     * the transport itself is closed by TransferForegroundService once it
     * sees the terminal state.
     */
    fun cancel(reason: String = "Перенос отменён") {
        connectionLost = true
        sendJob?.cancel()
        finishSession(TransferState.Error(reason))
    }

    private suspend fun handleMessage(message: ProtocolMessage) {
        when (message) {
            is ProtocolMessage.Manifest -> {
                categoryOrder.clear()
                categoryOrder.addAll(message.categories)
                message.categories.forEach { categoryStatus.putIfAbsent(it, CategoryStatus.PENDING) }
                totalBytesExpected = message.estimatedBytes
                spaceWarning = checkFreeSpace(message.estimatedBytes, message.sizesIncomplete)
                // The receiver's stall clock starts here: the sender has
                // announced what it's about to send, so data is now due.
                transferStarted = true
                emitRunning()
            }
            is ProtocolMessage.Records -> {
                categoryStatus[message.category] = CategoryStatus.RUNNING
                emitRunning()
                val result = runCatching { modules[message.category]?.importRecords(context, message.jsonArray) }
                result
                    .onSuccess { categoryDetail[message.category] = recordDetail(it, message.count) }
                    .onFailure { e ->
                        categoryStatus[message.category] = CategoryStatus.FAILED
                        categoryDetail[message.category] = e.message ?: e.javaClass.simpleName
                    }
                emitRunning()
            }
            is ProtocolMessage.CategoryDone -> {
                // handleFile resets a category to RUNNING for every incoming
                // file, so an earlier per-file failure would be erased by a
                // later success and the category would end up reported as
                // fully done. Settle it against the failure tally instead.
                val hadFailure = categoryStatus[message.category] == CategoryStatus.FAILED ||
                    (categoryFailedFiles[message.category] ?: 0) > 0
                categoryStatus[message.category] = if (hadFailure) CategoryStatus.FAILED else CategoryStatus.DONE
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
                .onSuccess { categoryDetail[header.category] = recordDetail(it, header.recordCount ?: 0) }
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
            .onSuccess { outcome ->
                if (outcome == ImportOutcome.SKIPPED_DUPLICATE) {
                    categorySkippedFiles[header.category] = (categorySkippedFiles[header.category] ?: 0) + 1
                } else {
                    categoryFileCount[header.category] = (categoryFileCount[header.category] ?: 0) + 1
                }
                categoryDetail[header.category] = fileCountDetail(header.category)
            }
            .onFailure { e -> noteFileFailure(header.category, e.message ?: e.javaClass.simpleName, header.displayName) }
        file.delete()
        emitRunning()
    }

    /**
     * What a record category actually did. [announced] is the sender's count,
     * used only when a module reports nothing back (no summary at all) —
     * otherwise the receiver's own tally is the honest number, since
     * deduplication means "500 отправлено" and "500 записано" are no longer
     * the same thing.
     */
    private fun recordDetail(summary: ImportSummary?, announced: Int): String {
        if (summary == null) return "$announced шт."
        return buildString {
            append("${summary.imported} шт.")
            if (summary.skipped > 0) append(", пропущено ${summary.skipped} (уже есть)")
        }
    }

    /**
     * "12 файл(ов), пропущено 340 (уже есть)" — the skipped tally has to be
     * visible, otherwise deduplication looks exactly like data loss to
     * someone re-running a transfer and seeing 12 files instead of 352.
     */
    private fun fileCountDetail(category: TransferCategory): String {
        val imported = categoryFileCount[category] ?: 0
        val skipped = categorySkippedFiles[category] ?: 0
        val failed = categoryFailedFiles[category] ?: 0
        return buildString {
            append("$imported файл(ов)")
            if (skipped > 0) append(", пропущено $skipped (уже есть)")
            if (failed > 0) append(", не передано: $failed")
        }
    }

    /**
     * One file of a category failed. Counted rather than just flagged: the
     * count is what [ProtocolMessage.CategoryDone] consults to decide the
     * category's final status, and it's the difference between "полностью
     * перенесено" and "перенесено, кроме трёх файлов" in the UI.
     */
    private fun noteFileFailure(category: TransferCategory, reason: String, displayName: String?) {
        val failed = (categoryFailedFiles[category] ?: 0) + 1
        categoryFailedFiles[category] = failed
        categoryStatus[category] = CategoryStatus.FAILED
        categoryDetail[category] = buildString {
            displayName?.let { append("«").append(it).append("»: ") }
            append(reason)
            if (failed > 1) append(" (не передано файлов: $failed)")
        }
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
            stalled = stalled,
            totalBytesExpected = totalBytesExpected,
            spaceWarning = spaceWarning,
        )
    }

    companion object {
        private const val STALL_CHECK_INTERVAL_MS = 10_000L

        /** Generous on purpose: reading a huge media library or hashing a multi-gigabyte APK can legitimately go quiet for a while. */
        private const val STALL_THRESHOLD_MS = 120_000L
    }
}
