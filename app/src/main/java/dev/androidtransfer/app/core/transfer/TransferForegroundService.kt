package dev.androidtransfer.app.core.transfer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.androidtransfer.app.MainActivity
import dev.androidtransfer.app.R
import dev.androidtransfer.app.TransferApplication
import dev.androidtransfer.app.core.transport.P2pTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    private var transport: P2pTransport? = null
    private var manager: TransferManager? = null

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
        if (transport !== newTransport) releaseTransport()
        transport = newTransport
        val newManager = TransferManager(context.applicationContext, newTransport, modules)
        manager = newManager
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            newManager.state.collect { s ->
                _state.value = s
                if (s is TransferState.Completed || s is TransferState.Error) {
                    // Session over — nothing left here to protect. Stop so a
                    // stale foreground notification doesn't linger, and free
                    // the transport/socket.
                    releaseTransport()
                    stopSelf()
                }
            }
        }
        newManager.startListening()
        return newManager
    }

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
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseTransport()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TransferApplication.TRANSFER_NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(getString(R.string.transfer_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        @Volatile private var instance: TransferForegroundService? = null
        @Volatile private var pendingAction: ((TransferForegroundService) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
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
         */
        fun withService(context: Context, action: (TransferForegroundService) -> Unit) {
            start(context)
            val current = instance
            if (current != null) {
                action(current)
            } else {
                pendingAction = action
            }
        }
    }
}
