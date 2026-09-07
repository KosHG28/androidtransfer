package dev.androidtransfer.app.modules.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.androidtransfer.app.core.transfer.CategoryEstimate
import dev.androidtransfer.app.core.transfer.ImportOutcome
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import java.io.File

/** Photos and videos via MediaStore — the same scoped-storage API every gallery app uses, no special privilege required. */
class MediaModule : TransferModule {
    override val category = TransferCategory.MEDIA

    private data class Collection(val uri: Uri, val defaultRelativeDir: String)

    private fun collections() = listOf(
        Collection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES),
        Collection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES),
    )

    override suspend fun export(context: Context, sink: TransferSink) {
        for (collection in collections()) {
            exportCollection(context, collection, sink)
        }
    }

    /** Cheap: MediaStore already knows every file's size, so this is one indexed query per collection, not a disk walk. */
    override suspend fun estimate(context: Context): CategoryEstimate {
        var total = 0L
        for (collection in collections()) {
            runCatching {
                context.contentResolver.query(
                    collection.uri,
                    arrayOf(MediaStore.MediaColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) total += cursor.getLong(0)
                }
            }
        }
        return CategoryEstimate(total)
    }

    private suspend fun exportCollection(context: Context, collection: Collection, sink: TransferSink) {
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        if (hasRelativePath) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            // Pre-Android-10 has no RELATIVE_PATH column at all. Without this
            // the folder every photo came from would be lost on exactly the
            // old phones this app exists to migrate away from, and everything
            // would land in one flat folder on the new one.
            @Suppress("DEPRECATION")
            projection += MediaStore.MediaColumns.DATA
        }
        context.contentResolver.query(collection.uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val relPathIdx = if (hasRelativePath) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            @Suppress("DEPRECATION")
            val dataIdx = if (hasRelativePath) -1 else cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val itemUri = ContentUris.withAppendedId(collection.uri, id)
                val relativePath = when {
                    relPathIdx >= 0 -> cursor.getString(relPathIdx)
                    dataIdx >= 0 -> legacyRelativePath(cursor.getString(dataIdx))
                    else -> null
                } ?: (collection.defaultRelativeDir + "/")
                sink.sendFile(
                    displayName = cursor.getString(nameIdx) ?: "media_$id",
                    sizeBytes = cursor.getLong(sizeIdx),
                    mimeType = cursor.getString(mimeIdx),
                    relativePath = relativePath,
                    open = { context.contentResolver.openInputStream(itemUri) ?: error("Cannot open $itemUri") },
                )
            }
        }
    }

    /** "/storage/emulated/0/DCIM/Camera/IMG_1.jpg" -> "DCIM/Camera/", matching what RELATIVE_PATH would say on newer Android. */
    private fun legacyRelativePath(absolutePath: String?): String? {
        val path = absolutePath ?: return null
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val relative = path.removePrefix(root).trimStart('/')
        val dir = relative.substringBeforeLast('/', missingDelimiterValue = "")
        return dir.takeIf { it.isNotBlank() }?.plus("/")
    }

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File): ImportOutcome {
        val isVideo = header.mimeType?.startsWith("video/") == true
        val collectionUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        if (alreadyPresent(context, collectionUri, header.displayName, header.sizeBytes)) {
            return ImportOutcome.SKIPPED_DUPLICATE
        }

        val destination = destinationPath(header.relativePath, isVideo)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-scoped-storage: MediaStore has no RELATIVE_PATH and won't
            // create the file for us — write it into public storage first,
            // then register the real path so it shows up in the gallery.
            // Nothing restricts which folder we may write here, so the
            // sender's original one is reproduced exactly.
            @Suppress("DEPRECATION")
            val destDir = File(Environment.getExternalStorageDirectory(), destination)
            destDir.mkdirs()
            val destFile = File(destDir, header.displayName)
            destFile.outputStream().use { out -> file.inputStream().use { it.copyTo(out) } }
            val legacyValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, header.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, header.mimeType)
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
            }
            context.contentResolver.insert(collectionUri, legacyValues)
            return ImportOutcome.IMPORTED
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, header.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, header.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, destination)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val itemUri = context.contentResolver.insert(collectionUri, values) ?: error("Could not create media entry")
        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(itemUri, done, null, null)
        return ImportOutcome.IMPORTED
    }

    /**
     * A photo the receiver already holds must not be copied again — otherwise
     * every re-run (or a restart after a dropped connection) doubles the
     * gallery. Matching on name + exact byte size is what a duplicate
     * realistically looks like; two genuinely different photos sharing both
     * is vanishingly unlikely. Deliberately not scoped to the destination
     * folder: a file already on the phone is a duplicate wherever it sits.
     */
    private fun alreadyPresent(context: Context, collectionUri: Uri, displayName: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0) return false
        return runCatching {
            context.contentResolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(displayName, sizeBytes.toString()),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    /**
     * Reproduces the sender's folder as closely as MediaStore permits.
     *
     * MediaStore validates only the *primary* (first) segment of
     * RELATIVE_PATH, and accepts just a fixed set per collection — for images
     * that's DCIM and Pictures. A photo from `DCIM/Camera` or
     * `Pictures/Screenshots` therefore lands in exactly the same folder on
     * the new phone. Anything rooted elsewhere (`SHAREit/…`, `Telegram/…`)
     * would be rejected outright with "Primary directory X not allowed", so
     * its structure is kept intact underneath an allowed root instead of
     * being flattened away.
     */
    private fun destinationPath(senderPath: String?, isVideo: Boolean): String {
        val defaultRoot = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val cleaned = sanitize(senderPath) ?: return "$defaultRoot/AndroidTransfer/"
        val root = cleaned.substringBefore('/')
        val allowed = if (isVideo) {
            listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)
        } else {
            listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
        }
        return if (allowed.any { it.equals(root, ignoreCase = true) }) "$cleaned/" else "$defaultRoot/$cleaned/"
    }

    /**
     * The path arrives over the wire, so it gets treated as untrusted input:
     * traversal segments are dropped, and the `Android/media/<package>/`
     * prefix app-owned media sits behind is stripped so the result reads as
     * `Pictures/WhatsApp/Media/...` rather than `Pictures/Android/media/...`.
     */
    private fun sanitize(senderPath: String?): String? {
        val segments = senderPath.orEmpty()
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val withoutAppMediaPrefix = if (segments.size > 3 && segments[0].equals("Android", ignoreCase = true) &&
            segments[1].equals("media", ignoreCase = true)
        ) {
            segments.drop(3)
        } else {
            segments
        }
        return withoutAppMediaPrefix.takeIf { it.isNotEmpty() }?.joinToString("/")
    }
}
