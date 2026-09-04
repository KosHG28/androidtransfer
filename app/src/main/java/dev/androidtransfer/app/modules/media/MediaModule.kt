package dev.androidtransfer.app.modules.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

    private suspend fun exportCollection(context: Context, collection: Collection, sink: TransferSink) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        }
        context.contentResolver.query(collection.uri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val relPathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val itemUri = ContentUris.withAppendedId(collection.uri, id)
                val relativePath = if (relPathIdx >= 0) cursor.getString(relPathIdx) else collection.defaultRelativeDir + "/"
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

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File) {
        val isVideo = header.mimeType?.startsWith("video/") == true
        val collectionUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, header.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, header.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relDir = header.relativePath ?: (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/AndroidTransfer/"
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val itemUri = context.contentResolver.insert(collectionUri, values) ?: error("Could not create media entry")
        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(itemUri, done, null, null)
        }
    }
}
