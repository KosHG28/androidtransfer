package dev.androidtransfer.app.modules.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Installs an app from its own APK bytes (base + split APKs) via
 * PackageInstaller — this is the non-root, non-Play-Store install path
 * every sideloading tool (including Smart Switch's own "app data" transfer)
 * ultimately relies on. It always shows the system's normal install
 * confirmation dialog per app: nothing here can install silently without
 * either being a privileged system installer or holding root, and this app
 * is neither on purpose.
 */
object ApkInstaller {

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    fun install(context: Context, packageName: String, apkParts: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        runCatching { params.setAppPackageName(packageName) }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            for ((index, apk) in apkParts.withIndex()) {
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
            apkParts.forEach { it.delete() }
        }
    }
}

/**
 * PackageInstaller delivers the session outcome here. A pending-user-action
 * result carries the system's own install confirmation screen as an Intent
 * we just need to forward to startActivity; success/failure need no further
 * action from us (failures — e.g. a signature mismatch against an existing
 * install — are expected and silently leave that one app for the user to
 * grab from Play Store instead, via the app-list fallback screen).
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        confirmIntent?.let { runCatching { context.startActivity(it) } }
    }
}
