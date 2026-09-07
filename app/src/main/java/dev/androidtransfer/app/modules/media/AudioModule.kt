package dev.androidtransfer.app.modules.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
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

/**
 * Music and other audio via MediaStore. Worth its own category rather than
 * folding into "фото и видео": READ_MEDIA_AUDIO is a separate permission on
 * Android 13+, a music library can be huge, and plenty of people want one
 * without the other. Ringtones, alarms and podcasts come along too — they
 * live in the same collection and are just as annoying to set up again by
 * hand.
 */
class AudioModule : TransferModule {
    override val category = TransferCategory.AUDIO

    private val collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** Every root MediaStore accepts for audio; anything else keeps its structure under Music/. */
    private fun allowedRoots(): List<String> = buildList {
        add(Environment.DIRECTORY_MUSIC)
        add(Environment.DIRECTORY_ALARMS)
        add(Environment.DIRECTORY_NOTIFICATIONS)
        add(Environment.DIRECTORY_PODCASTS)
        add(Environment.DIRECTORY_RINGTONES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Environment.DIRECTORY_AUDIOBOOKS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Environment.DIRECTORY_RECORDINGS)
    }

    override suspend fun estimate(context: Context): CategoryEstimate =
        CategoryEstimate(MediaStoreSupport.collectionSize(context, collectionUri))

    override suspend fun export(context: Context, sink: TransferSink) {
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
            @Suppress("DEPRECATION")
            projection += MediaStore.MediaColumns.DATA
        }
        context.contentResolver.query(collectionUri, projection.toTypedArray(), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val relPathIdx = if (hasRelativePath) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            @Suppress("DEPRECATION")
            val dataIdx = if (hasRelativePath) -1 else cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val itemUri = ContentUris.withAppendedId(collectionUri, id)
                val relativePath = when {
                    relPathIdx >= 0 -> cursor.getString(relPathIdx)
                    dataIdx >= 0 -> legacyRelativePath(cursor.getString(dataIdx))
                    else -> null
                } ?: (Environment.DIRECTORY_MUSIC + "/")
                sink.sendFile(
                    displayName = cursor.getString(nameIdx) ?: "audio_$id",
                    sizeBytes = cursor.getLong(sizeIdx),
                    mimeType = cursor.getString(mimeIdx),
                    relativePath = relativePath,
                    open = { context.contentResolver.openInputStream(itemUri) ?: error("Cannot open $itemUri") },
                )
            }
        }
    }

    private fun legacyRelativePath(absolutePath: String?): String? {
        val path = absolutePath ?: return null
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val relative = path.removePrefix(root).trimStart('/')
        val dir = relative.substringBeforeLast('/', missingDelimiterValue = "")
        return dir.takeIf { it.isNotBlank() }?.plus("/")
    }

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File): ImportOutcome {
        if (MediaStoreSupport.alreadyPresent(context, collectionUri, header.displayName, header.sizeBytes)) {
            return ImportOutcome.SKIPPED_DUPLICATE
        }
        val destination = MediaStoreSupport.destinationPath(header.relativePath, allowedRoots(), Environment.DIRECTORY_MUSIC)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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
        val itemUri = context.contentResolver.insert(collectionUri, values) ?: error("Could not create audio entry")
        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(itemUri, done, null, null)
        return ImportOutcome.IMPORTED
    }
}
