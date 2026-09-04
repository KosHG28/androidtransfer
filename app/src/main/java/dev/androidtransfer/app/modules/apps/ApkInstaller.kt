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

/**
 * Installs apps from their own APK bytes (base + split APKs) via
 * PackageInstaller — the non-root, non-Play-Store install path every
 * sideloading tool ultimately relies on. Android always shows its own
 * confirmation screen per app: nothing here can install silently without
 * being a privileged system installer or holding root, and this app is
 * neither, on purpose.
 *
 * Installs are queued and committed strictly one at a time. Committing
 * every finished APK immediately would stack dozens of system install
 * dialogs on top of each other, and most of them would simply be lost.
 */
object ApkInstaller {

    private data class PendingInstall(val packageName: String, val parts: List<File>)

    private val queue = ArrayDeque<PendingInstall>()
    private var installing = false

    private val _remaining = MutableStateFlow(0)

    /** How many transferred apps are still waiting for their install confirmation. */
    val remaining: StateFlow<Int> = _remaining

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    @Synchronized
    fun enqueue(context: Context, packageName: String, apkParts: List<File>) {
        queue.addLast(PendingInstall(packageName, apkParts))
        _remaining.value = queue.size + if (installing) 1 else 0
        pump(context.applicationContext)
    }

    /** Called by [ApkInstallReceiver] once a session reaches a final state. */
    @Synchronized
    fun onSessionFinished(context: Context) {
        installing = false
        pump(context.applicationContext)
    }

    private fun pump(context: Context) {
        if (installing) return
        val next = queue.pollFirst()
        _remaining.value = queue.size + if (next != null) 1 else 0
        if (next == null) return
        installing = true
        val started = runCatching { commit(context, next) }.isSuccess
        if (!started) {
            // Nothing will report back for a session that never opened, so keep the queue moving.
            next.parts.forEach { runCatching { it.delete() } }
            installing = false
            pump(context)
        }
    }

    private fun commit(context: Context, install: PendingInstall) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        runCatching { params.setAppPackageName(install.packageName) }

        val sessionId = installer.createSession(params)
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
            install.parts.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * PackageInstaller delivers each session's outcome here. A pending-user-action
 * result carries the system's own install confirmation screen as an Intent we
 * just forward; any final result (success, failure, the user backing out)
 * releases the queue so the next app can be offered. Failures are expected and
 * harmless — e.g. a signature mismatch against an app already installed — and
 * simply leave that one app for the Play Store fallback list.
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launched = confirmIntent?.let { runCatching { context.startActivity(it) }.isSuccess } ?: false
                // If the dialog could not be shown, this session is never coming
                // back with a result — don't let it wedge the queue.
                if (!launched) ApkInstaller.onSessionFinished(context)
            }
            else -> ApkInstaller.onSessionFinished(context)
        }
    }
}
