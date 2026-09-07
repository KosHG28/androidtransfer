package dev.androidtransfer.app.core.transfer

import java.util.Locale

/** Byte/speed/time formatting shared by the transfer screens. Never KB — the smallest useful unit here is a megabyte. */
object Format {
    private const val MB = 1_048_576.0
    private const val GB = 1_073_741_824.0

    /**
     * Megabytes, switching to gigabytes past a thousand of them: a phone's
     * whole photo library reads as "12,4 ГБ", not "12 700,0 МБ", and an
     * operator sizing up a job needs the first form.
     */
    fun size(bytes: Long): String =
        if (bytes >= GB) {
            String.format(Locale.getDefault(), "%.1f ГБ", bytes / GB)
        } else {
            String.format(Locale.getDefault(), "%.1f МБ", bytes / MB)
        }

    fun megabytes(bytes: Long): String = size(bytes)

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1024) "" else String.format(Locale.getDefault(), "%.1f МБ/с", bytesPerSecond / MB)

    /**
     * Remaining time, rounded coarsely on purpose — the estimate is only as
     * good as the current speed, and a false "осталось 7 мин 32 с" invites
     * being held to it. What the operator needs is whether to wait or to go
     * serve someone else.
     */
    fun remaining(bytesLeft: Long, bytesPerSecond: Double): String? {
        if (bytesLeft <= 0 || bytesPerSecond < 1024) return null
        val seconds = (bytesLeft / bytesPerSecond).toLong()
        return when {
            seconds < 60 -> "меньше минуты"
            seconds < 3600 -> "~${seconds / 60} мин"
            else -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                if (minutes == 0L) "~$hours ч" else "~$hours ч $minutes мин"
            }
        }
    }
}
