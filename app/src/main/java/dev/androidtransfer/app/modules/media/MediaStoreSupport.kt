package dev.androidtransfer.app.modules.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The folder-mapping and duplicate-detection rules shared by every module
 * that writes through MediaStore. Kept in one place because getting either
 * wrong is expensive: a bad destination path throws "Primary directory X
 * not allowed" and loses the file, and a missing duplicate check doubles
 * the user's library on every re-run.
 */
internal object MediaStoreSupport {

    /**
     * Reproduces the sender's folder as closely as MediaStore permits.
     *
     * Only the *primary* (first) segment of RELATIVE_PATH is validated, and
     * only a fixed set is accepted per collection. A file already rooted in
     * one of them lands in exactly the same folder it came from; anything
     * else keeps its structure underneath [defaultRoot] rather than being
     * rejected outright or flattened into one directory.
     */
    fun destinationPath(senderPath: String?, allowedRoots: List<String>, defaultRoot: String): String {
        val cleaned = sanitize(senderPath) ?: return "$defaultRoot/AndroidTransfer/"
        val root = cleaned.substringBefore('/')
        return if (allowedRoots.any { it.equals(root, ignoreCase = true) }) "$cleaned/" else "$defaultRoot/$cleaned/"
    }

    /**
     * The path arrives over the wire, so it's treated as untrusted input:
     * traversal segments are dropped, and the `Android/media/<package>/`
     * prefix that app-owned media sits behind is stripped so the result
     * reads as `Music/Telegram/...` rather than `Music/Android/media/...`.
     */
    fun sanitize(senderPath: String?): String? {
        val segments = senderPath.orEmpty()
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val stripped = if (segments.size > 3 && segments[0].equals("Android", ignoreCase = true) &&
            segments[1].equals("media", ignoreCase = true)
        ) {
            segments.drop(3)
        } else {
            segments
        }
        return stripped.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * Name plus exact byte size is what a duplicate realistically looks
     * like; two genuinely different files sharing both is vanishingly
     * unlikely. Deliberately not scoped to the destination folder — a file
     * already on the phone is a duplicate wherever it happens to sit.
     */
    fun alreadyPresent(context: Context, collectionUri: Uri, displayName: String, sizeBytes: Long): Boolean {
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

    /** Total bytes of a collection, straight from the index — no disk walk. */
    fun collectionSize(context: Context, collectionUri: Uri): Long {
        var total = 0L
        runCatching {
            context.contentResolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) total += cursor.getLong(0)
            }
        }
        return total
    }
}
