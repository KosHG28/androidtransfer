package dev.androidtransfer.app.modules.calllog

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import dev.androidtransfer.app.core.transfer.ImportSummary
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CallLogRecord(
    val number: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val name: String? = null,
)

class CallLogModule : TransferModule {
    override val category = TransferCategory.CALL_LOG

    override suspend fun export(context: Context, sink: TransferSink) {
        val records = mutableListOf<CallLogRecord>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            while (cursor.moveToNext()) {
                records += CallLogRecord(
                    number = cursor.getString(numberIdx),
                    type = cursor.getInt(typeIdx),
                    date = cursor.getLong(dateIdx),
                    duration = cursor.getLong(durationIdx),
                    name = cursor.getString(nameIdx),
                )
            }
        }
        sink.sendRecords(Json.encodeToString(records), records.size)
    }

    /** A call is the same call if it's the same number at the same instant — re-runs must not double the log. */
    private fun existingKeys(context: Context): Set<String> {
        val keys = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    keys += "${cursor.getString(0).orEmpty()}|${cursor.getLong(1)}"
                }
            }
        }
        return keys
    }

    override suspend fun importRecords(context: Context, jsonArray: String): ImportSummary {
        val records = Json.decodeFromString<List<CallLogRecord>>(jsonArray)
        if (records.isEmpty()) return ImportSummary(0)

        val existing = existingKeys(context)
        val fresh = records.filterNot { "${it.number.orEmpty()}|${it.date}" in existing }
        val skipped = records.size - fresh.size
        if (fresh.isEmpty()) return ImportSummary(0, skipped)

        val values = fresh.map { record ->
            ContentValues().apply {
                put(CallLog.Calls.NUMBER, record.number)
                put(CallLog.Calls.TYPE, record.type)
                put(CallLog.Calls.DATE, record.date)
                put(CallLog.Calls.DURATION, record.duration)
                put(CallLog.Calls.CACHED_NAME, record.name)
                put(CallLog.Calls.NEW, 0)
            }
        }.toTypedArray()
        val inserted = context.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, values)
        // Some OEM providers (confirmed on this project with Contacts) accept
        // the call silently but write nothing — a bare success icon would be
        // indistinguishable from an actual transfer.
        if (inserted <= 0) {
            error("Провайдер журнала вызовов не принял ни одной записи")
        }
        return ImportSummary(inserted, skipped)
    }
}
