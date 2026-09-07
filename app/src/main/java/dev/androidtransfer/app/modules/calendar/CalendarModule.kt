package dev.androidtransfer.app.modules.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import dev.androidtransfer.app.core.transfer.ImportSummary
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CalendarEventRecord(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val dtStart: Long,
    val dtEnd: Long? = null,
    val allDay: Boolean = false,
    val rrule: String? = null,
    val timezone: String? = null,
)

/**
 * Reads/writes events through CalendarContract. Imported events land in a
 * dedicated local calendar ("AndroidTransfer Imported") created on the new
 * device, since writing into someone else's synced calendar (Google,
 * Exchange...) isn't something a third-party app should do.
 */
class CalendarModule : TransferModule {
    override val category = TransferCategory.CALENDAR

    override suspend fun export(context: Context, sink: TransferSink) {
        val records = mutableListOf<CalendarEventRecord>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EVENT_TIMEZONE,
        )
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val rruleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
            val tzIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)
            while (cursor.moveToNext()) {
                records += CalendarEventRecord(
                    title = cursor.getString(titleIdx),
                    description = cursor.getString(descIdx),
                    location = cursor.getString(locIdx),
                    dtStart = cursor.getLong(startIdx),
                    dtEnd = if (cursor.isNull(endIdx)) null else cursor.getLong(endIdx),
                    allDay = cursor.getInt(allDayIdx) != 0,
                    rrule = cursor.getString(rruleIdx),
                    timezone = cursor.getString(tzIdx),
                )
            }
        }
        sink.sendRecords(Json.encodeToString(records), records.size)
    }

    /**
     * Same title at the same start time is the same event. Checked across
     * every calendar on the device, not just the one we create: an event the
     * user already gets from their Google account shouldn't be duplicated
     * into a second local copy either.
     */
    private fun existingKeys(context: Context): Set<String> {
        val keys = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
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
        val records = Json.decodeFromString<List<CalendarEventRecord>>(jsonArray)
        if (records.isEmpty()) return ImportSummary(0)

        val existing = existingKeys(context)
        val fresh = records.filterNot { "${it.title.orEmpty()}|${it.dtStart}" in existing }
        val skipped = records.size - fresh.size
        if (fresh.isEmpty()) return ImportSummary(0, skipped)

        val calendarId = ensureLocalCalendar(context)
        var inserted = 0
        for (record in fresh) {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, record.title)
                put(CalendarContract.Events.DESCRIPTION, record.description)
                put(CalendarContract.Events.EVENT_LOCATION, record.location)
                put(CalendarContract.Events.DTSTART, record.dtStart)
                put(CalendarContract.Events.ALL_DAY, if (record.allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, record.timezone ?: java.util.TimeZone.getDefault().id)
                if (record.rrule != null) {
                    put(CalendarContract.Events.RRULE, record.rrule)
                    val durationSeconds = ((record.dtEnd ?: record.dtStart) - record.dtStart) / 1000
                    put(CalendarContract.Events.DURATION, "PT${durationSeconds}S")
                } else {
                    put(CalendarContract.Events.DTEND, record.dtEnd ?: record.dtStart)
                }
            }
            if (context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) inserted++
        }
        // Matches the same silent-drop failure mode already confirmed for
        // Contacts on this project: applyBatch/insert can report success on
        // some OEM providers while writing nothing. Only reachable when there
        // was something new to write — the all-duplicates case returned above.
        if (inserted <= 0) {
            error("Провайдер календаря не принял ни одной записи")
        }
        return ImportSummary(inserted, skipped)
    }

    private fun ensureLocalCalendar(context: Context): Long {
        val accountName = "AndroidTransfer"
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(accountName, CalendarContract.ACCOUNT_TYPE_LOCAL)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, accountName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "AndroidTransfer Imported")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2196F3.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
        }
        val result: Uri = context.contentResolver.insert(uri, values) ?: error("Could not create local calendar")
        return ContentUris.parseId(result)
    }
}
