package dev.androidtransfer.app.core.transfer

import java.util.Locale

/** Byte/speed formatting shared by the progress and summary screens — always MB, never KB. */
object Format {
    fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f МБ", bytes / 1_048_576.0)

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1024) "" else String.format(Locale.getDefault(), "%.1f МБ/с", bytesPerSecond / 1_048_576.0)
}
