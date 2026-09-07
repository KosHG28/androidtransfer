package dev.androidtransfer.app.modules.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.ArrayDeque

/** What actually happened to the transferred APKs, so the UI never has to say "nothing happened" without a reason. */
data class InstallStats(
    val waiting: Int = 0,
    val installed: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val anythingHappened: Boolean get() = waiting > 0 || installed > 0 || failed > 0
}

/**
 * Installs apps from their own APK bytes (base + split APKs) via
 * PackageInstaller — the non-root, non-Play-Store install path every
 * sideloading tool ultimately relies on. Android always shows its own
 * confirmation screen per app: nothing here can install silently without
 * being a privileged system installer or holding root, and this app is
 * neither, on purpose.
 *
 * Sessions are committed strictly one at a time — committing every finished
 * APK immediately stacks dozens of system dialogs on top of each other and
 * most are simply lost. Failed installs keep their APKs so [retryFailed] can
 * offer them again.
 */
object ApkInstaller {

    private data class PendingInstall(val packageName: String, val parts: List<File>)

    private val queue = ArrayDeque<PendingInstall>()
    private val inFlight = mutableMapOf<Int, PendingInstall>()
    private val failedInstalls = mutableListOf<PendingInstall>()
    private var installing = false

    private val _stats = MutableStateFlow(InstallStats())
    val stats: StateFlow<InstallStats> = _stats

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    @Synchronized
    fun enqueue(context: Context, packageName: String, apkParts: List<File>) {
        queue.addLast(PendingInstall(packageName, apkParts))
        publishStats()
        pump(context.applicationContext)
    }

    /**
     * Clears everything left over from a previous transfer. This object is a
     * singleton living as long as the process, so without an explicit reset
     * the next phone's progress screen opens showing the previous one's
     * "Установлено: 47" — which matters here, because the app is run back to
     * back on one device after another rather than once in a lifetime. Also
     * drops APKs kept for a retry nobody is going to ask for now.
     */
    @Synchronized
    fun reset() {
        queue.clear()
        inFlight.clear()
        failedInstalls.forEach { install -> install.parts.forEach { runCatching { it.delete() } } }
        failedInstalls.clear()
        installing = false
        _stats.value = InstallStats()
    }

    @Synchronized
    fun retryFailed(context: Context) {
        queue.addAll(failedInstalls)
        failedInstalls.clear()
        _stats.value = _stats.value.copy(failed = 0, lastError = null)
        publishStats()
        pump(context.applicationContext)
    }

    /** Called by [ApkInstallReceiver] once a session reaches a final state. */
    @Synchronized
    fun onSessionFinished(context: Context, sessionId: Int, success: Boolean, message: String?) {
        val finished = inFlight.remove(sessionId)
        if (success) {
            finished?.parts?.forEach { runCatching { it.delete() } }
            _stats.value = _stats.value.copy(installed = _stats.value.installed + 1)
        } else if (finished != null) {
            // Keep the APKs around so the user can retry without re-transferring.
            failedInstalls.add(finished)
            _stats.value = _stats.value.copy(
                failed = _stats.value.failed + 1,
                lastError = message?.takeIf { it.isNotBlank() } ?: "установка отклонена",
            )
        }
        installing = false
        publishStats()
        pump(context.applicationContext)
    }

    private fun publishStats() {
        _stats.value = _stats.value.copy(waiting = queue.size + inFlight.size)
    }

    private fun pump(context: Context) {
        if (installing) return
        val next = queue.pollFirst() ?: run { publishStats(); return }
        installing = true
        runCatching { commit(context, next) }
            .onFailure { e ->
                failedInstalls.add(next)
                _stats.value = _stats.value.copy(
                    failed = _stats.value.failed + 1,
                    lastError = e.message ?: e.javaClass.simpleName,
                )
                installing = false
                pump(context)
            }
        publishStats()
    }

    private fun commit(context: Context, install: PendingInstall) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        runCatching { params.setAppPackageName(install.packageName) }

        val sessionId = installer.createSession(params)
        inFlight[sessionId] = install
        val session = installer.openSession(sessionId)
        try {
            for ((index, apk) in install.parts.withIndex()) {
                session.openWrite("part_$index.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(context, ApkInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
    }
}

/**
 * PackageInstaller delivers each session's outcome here. A pending-user-action
 * result carries the system's own install confirmation screen as an Intent we
 * just forward; every final result releases the queue so the next app can be
 * offered, and carries the system's own message when something went wrong.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = confirmIntent?.let { runCatching { context.startActivity(it) }.isSuccess } ?: false
            // A dialog that never opened is never coming back with a result —
            // don't let it wedge the queue, and say why.
            if (!launched) {
                ApkInstaller.onSessionFinished(context, sessionId, success = false, message = "не удалось показать диалог установки")
            }
            return
        }

        ApkInstaller.onSessionFinished(
            context,
            sessionId,
            success = status == PackageInstaller.STATUS_SUCCESS,
            message = message,
        )
    }
}
