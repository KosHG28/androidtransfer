package dev.androidtransfer.app.modules.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmsRecord(
    val address: String?,
    val body: String?,
    val date: Long,
    val type: Int,
    val read: Int = 1,
)

/**
 * Reading SMS only needs READ_SMS. Writing an *imported* history back into
 * the provider on the new device is an Android restriction that requires
 * the app to hold the default-SMS-app role at the moment of import — the
 * UI walks the user through RoleManager.createRequestRoleIntent(ROLE_SMS)
 * for the duration of the import, then hands the role back.
 */
class SmsModule : TransferModule {
    override val category = TransferCategory.SMS

    override suspend fun export(context: Context, sink: TransferSink) {
        val records = mutableListOf<SmsRecord>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                records += SmsRecord(
                    address = cursor.getString(addressIdx),
                    body = cursor.getString(bodyIdx),
                    date = cursor.getLong(dateIdx),
                    type = cursor.getInt(typeIdx),
                    read = cursor.getInt(readIdx),
                )
            }
        }
        sink.sendRecords(Json.encodeToString(records), records.size)
    }

    override suspend fun importRecords(context: Context, jsonArray: String) {
        val records = Json.decodeFromString<List<SmsRecord>>(jsonArray)
        for (record in records) {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, record.address)
                put(Telephony.Sms.BODY, record.body)
                put(Telephony.Sms.DATE, record.date)
                put(Telephony.Sms.TYPE, record.type)
                put(Telephony.Sms.READ, record.read)
            }
            runCatching { context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) }
        }
    }
}
