package dev.androidtransfer.app.modules.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class AppRecord(val packageName: String, val label: String)

/**
 * Two complementary ways installed apps travel:
 *  1. The app list (this always goes through, cheaply) backs the "open in
 *     Play Store" fallback screen for anything the direct install below
 *     couldn't handle.
 *  2. Each app's own APK bytes (base + split APKs) are read straight off
 *     disk — reading your own APK file needs no special permission on
 *     Android, since the *bytes of the app* aren't treated as private data,
 *     only its /data/data contents are — and reinstalled via
 *     PackageInstaller on the new phone. This is the same mechanism Smart
 *     Switch's "app" transfer ultimately relies on; it does not carry the
 *     app's internal data (login state, local databases), only the app
 *     itself, and needs "Install unknown apps" enabled for this app once.
 */
object ReceivedAppsHolder {
    private val _apps = MutableStateFlow<List<AppRecord>>(emptyList())
    val apps: StateFlow<List<AppRecord>> = _apps

    fun set(list: List<AppRecord>) {
        _apps.value = list
    }

    fun playStoreIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun playStoreWebIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/**
 * @param selectedPackages which apps to send APKs for; null means "everything".
 * Sending every installed APK can easily run to several gigabytes, which is why
 * the sender gets to pick — an app category that never finishes also starves
 * every category queued behind it.
 */
class AppsModule(private val selectedPackages: Set<String>? = null) : TransferModule {
    override val category = TransferCategory.INSTALLED_APPS

    private val pendingApkParts = mutableMapOf<String, MutableList<File>>()

    override suspend fun export(context: Context, sink: TransferSink) {
        val all = InstalledApps.list(context)
        val chosen = all.filter { selectedPackages == null || it.packageName in selectedPackages }

        // The list always goes over, so the receiver can fall back to the Play
        // Store for anything whose APK couldn't be read off this device.
        val records = chosen.map { AppRecord(it.packageName, it.label) }
        sink.sendRecords(Json.encodeToString(records), records.size)

        for (app in chosen) {
            // One unreadable APK (OEM restriction, DRM-protected app, ...) must not abort the rest.
            runCatching { sendApkFiles(sink, app) }
        }
    }

    private suspend fun sendApkFiles(sink: TransferSink, app: InstalledApp) {
        if (!app.isTransferable) return
        app.apkFiles.forEachIndexed { index, apk ->
            sink.sendFile(
                displayName = apk.name,
                sizeBytes = apk.length(),
                mimeType = "application/vnd.android.package-archive",
                groupKey = app.packageName,
                isFinalPart = index == app.apkFiles.lastIndex,
                open = { apk.inputStream() },
            )
        }
    }

    override suspend fun importRecords(context: Context, jsonArray: String) {
        val apps = Json.decodeFromString<List<AppRecord>>(jsonArray)
        ReceivedAppsHolder.set(apps)
    }

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File) {
        val packageName = header.groupKey ?: return
        // TransferManager deletes `file` right after this call returns, so
        // claim it (rename, falling back to copy) rather than reading it
        // here — later parts of the same app may still be in flight.
        val staged = File(context.cacheDir, "apk_${UUID.randomUUID()}.apk")
        if (!file.renameTo(staged)) file.copyTo(staged, overwrite = true)

        val parts = pendingApkParts.getOrPut(packageName) { mutableListOf() }
        parts += staged

        if (header.isFinalPart) {
            pendingApkParts.remove(packageName)
            if (ApkInstaller.isInstalled(context, packageName)) {
                parts.forEach { it.delete() }
            } else {
                ApkInstaller.enqueue(context, packageName, parts)
            }
        }
    }
}
