package dev.androidtransfer.app.core.history

import android.content.Context
import android.content.SharedPreferences
import dev.androidtransfer.app.core.transfer.CategoryStatus
import dev.androidtransfer.app.core.transfer.TransferCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class HistoryCategoryResult(val category: TransferCategory, val status: CategoryStatus, val detail: String? = null)

@Serializable
data class TransferHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    /** "SENDER" or "RECEIVER" — a plain string rather than an enum so old entries always still decode after future changes. */
    val role: String,
    /** Transport.name — "wifi" or "usb". */
    val transport: String,
    val peerName: String? = null,
    val overallFailed: Boolean,
    val errorMessage: String? = null,
    val categories: List<HistoryCategoryResult> = emptyList(),
)

/**
 * Lightweight local log of past transfer sessions, purely so the user can
 * see afterwards what actually happened — nothing here is sent to the peer
 * or leaves the device. Backed by SharedPreferences: one entry per manual
 * transfer never justifies a real database.
 */
object TransferHistoryStore {
    private const val PREFS_NAME = "transfer_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50

    private val json = Json { ignoreUnknownKeys = true }

    fun record(context: Context, entry: TransferHistoryEntry) {
        val prefs = prefs(context)
        val updated = listOf(entry) + load(prefs)
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(updated.take(MAX_ENTRIES))).apply()
    }

    fun all(context: Context): List<TransferHistoryEntry> = load(prefs(context))

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(prefs: SharedPreferences): List<TransferHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<TransferHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }
}
