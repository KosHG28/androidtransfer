package dev.androidtransfer.app.modules.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.core.transfer.TransferModule
import dev.androidtransfer.app.core.transfer.TransferSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContactRecord(
    val displayName: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String? = null,
)

/** Full contact export/import via the public ContactsContract API — works on any Android device, no root needed. */
class ContactsModule : TransferModule {
    override val category = TransferCategory.CONTACTS

    override suspend fun export(context: Context, sink: TransferSink) {
        val byContact = LinkedHashMap<Long, ContactRecord>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?)"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        )
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val dataIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIdx)
                val value = cursor.getString(dataIdx) ?: continue
                val existing = byContact.getOrPut(contactId) { ContactRecord() }
                byContact[contactId] = when (cursor.getString(mimeIdx)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> existing.copy(displayName = value)
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> existing.copy(phones = existing.phones + value)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> existing.copy(emails = existing.emails + value)
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> existing.copy(organization = value)
                    else -> existing
                }
            }
        }
        val records = byContact.values.toList()
        val json = Json.encodeToString(records)
        sink.sendRecords(json, records.size)
    }

    override suspend fun importRecords(context: Context, jsonArray: String) {
        val records = Json.decodeFromString<List<ContactRecord>>(jsonArray)
        // Batches are capped well under the ~500-operation binder transaction limit.
        records.chunked(80).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>()
            for (record in chunk) {
                val rawContactIndex = ops.size
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build(),
                )
                if (!record.displayName.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, record.displayName)
                            .build(),
                    )
                }
                for (phone in record.phones) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build(),
                    )
                }
                for (email in record.emails) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .build(),
                    )
                }
                if (!record.organization.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, record.organization)
                            .build(),
                    )
                }
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }
}
