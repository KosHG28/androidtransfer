package dev.androidtransfer.app.modules.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dev.androidtransfer.app.core.transfer.ImportOutcome
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import java.io.File

/**
 * Recursively transfers any folder the user grants access to via the
 * Storage Access Framework tree picker (ACTION_OPEN_DOCUMENT_TREE). This is
 * the generic building block behind the "Files", "Custom folder" and
 * "WhatsApp media" categories — only the picked starting folder differs.
 *
 * Imported files always land under Download/AndroidTransfer/<subFolder>/
 * on the receiving device: scoped storage does not let us write back into
 * an arbitrary folder without the receiving user separately granting one,
 * and a single predictable destination is easier to explain than asking
 * for a second SAF grant mid-transfer.
 */
open class SafFolderModule(
    override val category: TransferCategory,
    private val sourceTreeUri: Uri?,
    private val destinationSubFolder: String,
) : TransferModule {

    override suspend fun export(context: Context, sink: TransferSink) {
        val uri = sourceTreeUri ?: return
        val root = DocumentFile.fromTreeUri(context, uri) ?: return
        exportRecursive(context, root, sink, basePath = "")
    }

    private suspend fun exportRecursive(context: Context, dir: DocumentFile, sink: TransferSink, basePath: String) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = if (basePath.isEmpty()) name else "$basePath/$name"
            if (child.isDirectory) {
                exportRecursive(context, child, sink, path)
            } else if (child.isFile) {
                sink.sendFile(
                    displayName = name,
                    sizeBytes = child.length(),
                    mimeType = child.type ?: "application/octet-stream",
                    relativePath = basePath,
                    open = { context.contentResolver.openInputStream(child.uri) ?: error("Cannot open ${child.uri}") },
                )
            }
        }
    }

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File): ImportOutcome {
        val relDir = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append("/AndroidTransfer/").append(destinationSubFolder)
            if (!header.relativePath.isNullOrBlank()) append('/').append(header.relativePath)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (alreadyPresent(context, header)) return ImportOutcome.SKIPPED_DUPLICATE
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, header.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, header.mimeType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            }
            val itemUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create download entry")
            context.contentResolver.openOutputStream(itemUri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            @Suppress("DEPRECATION")
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndroidTransfer/$destinationSubFolder/${header.relativePath.orEmpty()}")
            destDir.mkdirs()
            val destFile = File(destDir, header.displayName)
            if (destFile.exists() && destFile.length() == header.sizeBytes) return ImportOutcome.SKIPPED_DUPLICATE
            destFile.outputStream().use { out -> file.inputStream().use { it.copyTo(out) } }
        }
        return ImportOutcome.IMPORTED
    }

    /** Same reasoning as the media dedup: a re-run must not double the folder. Name + exact size is what a duplicate looks like. */
    private fun alreadyPresent(context: Context, header: ProtocolMessage.FileHeader): Boolean {
        if (header.sizeBytes <= 0) return false
        return runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(header.displayName, header.sizeBytes.toString()),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }
}
