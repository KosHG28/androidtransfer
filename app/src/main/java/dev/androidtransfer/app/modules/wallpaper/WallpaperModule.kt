package dev.androidtransfer.app.modules.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.androidtransfer.app.core.transfer.ImportOutcome
import dev.androidtransfer.app.core.transfer.ProtocolMessage
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import java.io.File

/**
 * Best-effort wallpaper transfer via the public WallpaperManager API.
 * Setting it on the new phone always works (SET_WALLPAPER is a normal,
 * install-time permission). Reading the current wallpaper worked for any
 * app on older Android, but Android 13 restricted WallpaperManager.getDrawable()
 * to the active launcher only, as a privacy fix (wallpapers are often
 * personal photos) — on those devices export silently yields nothing, same
 * as home-screen icon layout, which no non-root app can read at all (it's
 * private data owned by whichever launcher app the user has installed).
 */
class WallpaperModule : TransferModule {
    override val category = TransferCategory.WALLPAPER

    override suspend fun export(context: Context, sink: TransferSink) {
        // Failing loudly matters here: silently exporting nothing is
        // indistinguishable from a broken transfer, and the usual cause is an
        // OS restriction the user can't do anything about but should be told.
        val bitmap = runCatching {
            val drawable = WallpaperManager.getInstance(context).drawable
                ?: error("Система не отдала текущие обои")
            drawableToBitmap(drawable)
        }.getOrElse { cause ->
            // Blaming "Android 13+" for every failure on Android 13+ — instead
            // of only when the OS actually refused — would mask a real bug
            // behind a plausible-sounding but wrong explanation. SecurityException
            // is the actual, documented signal for the launcher-only restriction.
            if (cause is SecurityException) {
                error("Android 13+ разрешает читать обои только лаунчеру — перенести их нельзя без root")
            }
            error(cause.message ?: cause.javaClass.simpleName)
        }

        val staged = File(context.cacheDir, "wallpaper_export.png")
        staged.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

        sink.sendFile(
            displayName = "wallpaper.png",
            sizeBytes = staged.length(),
            mimeType = "image/png",
            open = { staged.inputStream() },
        )
    }

    override suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File): ImportOutcome {
        // No runCatching here on purpose: TransferManager already wraps this
        // call and needs the exception to escape to know the import failed —
        // swallowing it here would report success regardless of what happened.
        file.inputStream().use { WallpaperManager.getInstance(context).setStream(it) }
        return ImportOutcome.IMPORTED
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
