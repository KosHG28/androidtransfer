package dev.androidtransfer.app.modules.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * One installed, user-visible app and the APK files that make it up (base plus
 * any split APKs). Reading an app's own APK needs no special permission — the
 * app binary isn't treated as private data on Android, unlike its /data/data
 * contents — so [apkFiles] is what actually gets transferred.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val apkFiles: List<File>,
) {
    val sizeBytes: Long get() = apkFiles.sumOf { it.length() }
    val isTransferable: Boolean get() = apkFiles.isNotEmpty()
}

object InstalledApps {
    fun list(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                    apkFiles = apkFilesOf(info),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun apkFilesOf(info: ApplicationInfo): List<File> = runCatching {
        buildList {
            info.sourceDir?.let { add(it) }
            info.splitSourceDirs?.let { addAll(it) }
        }.map { File(it) }.filter { it.canRead() }
    }.getOrDefault(emptyList())
}
