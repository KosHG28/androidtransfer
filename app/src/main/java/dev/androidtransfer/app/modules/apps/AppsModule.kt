package dev.androidtransfer.app.modules.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class AppsModule : TransferModule {
    override val category = TransferCategory.INSTALLED_APPS

    private val pendingApkParts = mutableMapOf<String, MutableList<File>>()

    override suspend fun export(context: Context, sink: TransferSink) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val infos = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }

        val apps = infos.map { AppRecord(it.packageName, pm.getApplicationLabel(it).toString()) }.sortedBy { it.label.lowercase() }
        sink.sendRecords(Json.encodeToString(apps), apps.size)

        for (info in infos) {
            // One unreadable APK (OEM restriction, DRM-protected app, etc.) must not abort the rest of the list.
            runCatching { sendApkFiles(sink, info) }
        }
    }

    private suspend fun sendApkFiles(sink: TransferSink, info: ApplicationInfo) {
        val apkPaths = buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.let { addAll(it) }
        }
        val files = apkPaths.map { File(it) }.filter { it.canRead() }
        if (files.isEmpty()) return
        files.forEachIndexed { index, apk ->
            sink.sendFile(
                displayName = apk.name,
                sizeBytes = apk.length(),
                mimeType = "application/vnd.android.package-archive",
                groupKey = info.packageName,
                isFinalPart = index == files.lastIndex,
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
