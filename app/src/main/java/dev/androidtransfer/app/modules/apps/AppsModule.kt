package dev.androidtransfer.app.modules.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppRecord(val packageName: String, val label: String)

/**
 * We can't silently reinstall another app's private data without root —
 * see the README's tier table — so "transferring" installed apps means:
 * export the list of what's installed, then let the user tap through to
 * install each one from the Play Store on the new phone. Real per-app
 * *data* for apps that support it travels through WhatsAppModule/
 * SafFolderModule instead.
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

    override suspend fun export(context: Context, sink: TransferSink) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .map { AppRecord(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
        sink.sendRecords(Json.encodeToString(apps), apps.size)
    }

    override suspend fun importRecords(context: Context, jsonArray: String) {
        val apps = Json.decodeFromString<List<AppRecord>>(jsonArray)
        ReceivedAppsHolder.set(apps)
    }
}
