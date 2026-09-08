package dev.androidtransfer.app.core.transfer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.androidtransfer.app.MainActivity
import dev.androidtransfer.app.R
import dev.androidtransfer.app.TransferApplication
import dev.androidtransfer.app.core.transport.P2pTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the connected transport and the active [TransferManager] once a
 * transfer session starts — not just the notification. Previously this
 * service only displayed a notification while the transport/manager
 * actually lived in TransferViewModel, which is scoped to the Activity:
 * Android destroys it (calling onCleared(), which closed the transport)
 * when the task is swiped away from Recents, even though the *process*
 * survives thanks to this very foreground service. So "keeps the transfer
 * alive in the background" was only ever true for the screen turning off,
 * never for the task actually being removed. Now the transport/manager live
 * here, so an Activity/ViewModel recreation no longer touches them.
 */
class TransferForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): TransferForegroundService = this@TransferForegroundService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var shutdownJob: Job? = null

    private var transport: P2pTransport? = null
    private var manager: TransferManager? = null
    private var lastNotificationUpdateMs = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingAction?.let { action ->
            pendingAction = null
            action(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    /**
     * Takes ownership of an already-connected transport and starts driving a
     * transfer over it. If this service was still holding a transport from a
     * previous session (finished or abandoned), that one is closed first so
     * it isn't leaked.
     */
    fun attach(context: Context, newTransport: P2pTransport, modules: Map<TransferCategory, TransferModule>): TransferManager {
        // A shutdown still pending from a previous session must not tear down
        // the one being started now.
        shutdownJob?.cancel()
        shutdownJob = null
        if (transport !== newTransport) releaseTransport()
        acquireLocks()
        transport = newTransport
        val newManager = TransferManager(context.applicationContext, newTransport, modules)
        manager = newManager
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            newManager.state.collect { s ->
                _state.value = s
                if (s is TransferState.Running) publishProgressNotification(s)
                if (s is TransferState.Completed || s is TransferState.Error) scheduleShutdown()
            }
        }
        newManager.startListening()
        return newManager
    }

    /**
     * A foreground service keeps the *process* alive and exempt from
     * background restrictions — it does not keep the CPU running. With the
     * screen off the device suspends, and a transfer that was moving happily
     * simply stops, which the stall watchdog then reports two minutes later.
     * That is exactly the shape of this app's normal use: start a 40-minute
     * transfer, put the phones down, go serve the next customer.
     *
     * The Wi-Fi lock matters just as much on the wireless path: with the
     * screen off the radio drops into power-save, which either stalls Nearby
     * outright or slows it to the point of being useless.
     *
     * Both carry a timeout so that a bug somewhere in the shutdown path can
     * cost a customer some battery, not their whole battery.
     */
    private fun acquireLocks() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire(MAX_LOCK_DURATION_MS)
                }
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi.createWifiLock(mode, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /**
     * Session over — free the transport and stop, but not instantly. On
     * Nearby, sendPayload().await() resolves once the payload is handed to
     * Play Services, not once the peer has it: closing the link the moment
     * the sender marks itself Completed can drop the very TransferDone the
     * receiver is waiting on, leaving it stuck on the progress screen
     * forever. The linger gives the last messages time to flush.
     */
    private fun scheduleShutdown() {
        if (shutdownJob != null) return
        shutdownJob = serviceScope.launch {
            delay(TERMINAL_LINGER_MS)
            releaseTransport()
            stopSelf()
        }
    }

    /** The manager driving the current session, so a UI recreated after a task swipe can re-attach to it instead of starting over. */
    fun activeManager(): TransferManager? = manager

    fun updateModules(modules: Map<TransferCategory, TransferModule>) {
        manager?.updateModules(modules)
    }

    fun beginSending(sessionId: String, categories: List<TransferCategory>, deviceName: String) {
        manager?.startSending(sessionId, categories, deviceName)
    }

    private fun releaseTransport() {
        stateJob?.cancel()
        stateJob = null
        runCatching { transport?.close() }
        transport = null
        manager = null
        releaseLocks()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseTransport()
        // Belt and braces: releaseTransport() already does this, but a lock
        // still held after the service is gone is the one leak here that
        // costs the user something visible.
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * The notification is the only thing the user can see once they've
     * navigated away — which, now that a transfer survives that, is a state
     * they can sit in for a long time. A static "перенос идёт" gives them no
     * way to tell progress from a hang. Rate-limited because progress ticks
     * arrive far faster than a notification should be redrawn.
     */
    private fun publishProgressNotification(state: TransferState.Running) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationUpdateMs = now
        val text = when {
            state.stalled -> "Передача остановилась — проверьте второй телефон"
            state.totalBytesExpected > 0 ->
                "${Format.megabytes(state.bytesTransferred)} из ${Format.megabytes(state.totalBytesExpected)}" +
                    Format.speed(state.speedBytesPerSecond).let { if (it.isEmpty()) "" else " · $it" }
            state.bytesTransferred > 0 -> Format.megabytes(state.bytesTransferred)
            else -> getString(R.string.transfer_notification_text)
        }
        val percent = if (state.totalBytesExpected > 0) {
            ((state.bytesTransferred * 100) / state.totalBytesExpected).toInt().coerceIn(0, 100)
        } else {
            null
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(text, percent))
        }
    }

    private fun buildNotification(text: String? = null, percent: Int? = null): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TransferApplication.TRANSFER_NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(text ?: getString(R.string.transfer_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .apply {
                if (percent != null) setProgress(100, percent, false)
            }
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        /** How long the link stays open after a session ends, so trailing messages can flush. See [scheduleShutdown]. */
        private const val TERMINAL_LINGER_MS = 4_000L

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L

        private const val WAKE_LOCK_TAG = "AndroidTransfer::transfer"

        /** Safety net, not a budget: long enough for any realistic transfer, short enough that a leak isn't fatal to the battery. */
        private const val MAX_LOCK_DURATION_MS = 3 * 60 * 60 * 1000L

        @Volatile private var instance: TransferForegroundService? = null
        @Volatile private var pendingAction: ((TransferForegroundService) -> Unit)? = null

        /** @return false if the system refused to start the service (e.g. the Android 12+ background-start restriction). */
        fun start(context: Context): Boolean {
            val intent = Intent(context, TransferForegroundService::class.java)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferForegroundService::class.java)) }
        }

        /**
         * Runs [action] against the live service instance, starting the
         * service first if needed. startForegroundService() is asynchronous
         * — right after calling it, onCreate() may not have run yet — so if
         * the instance isn't up yet, [action] is queued and replayed the
         * moment it is.
         *
         * @return false if the service could not be started at all, in which
         * case [action] was NOT run and never will be. The caller must fall
         * back rather than wait: a queued action nobody replays means a
         * transfer that silently never starts, with the UI stuck on
         * "Подготовка…" forever.
         */
        fun withService(context: Context, action: (TransferForegroundService) -> Unit): Boolean {
            val current = instance
            if (current != null) {
                // Already running: still (re)start it so a stopSelf() from a
                // previous finished session can't tear it down underneath us.
                start(context)
                action(current)
                return true
            }
            if (!start(context)) return false
            pendingAction = action
            return true
        }

        /** The service instance only if it's actually driving a transfer right now. */
        fun runningSession(): TransferForegroundService? = instance?.takeIf { it.manager != null }
    }
}
