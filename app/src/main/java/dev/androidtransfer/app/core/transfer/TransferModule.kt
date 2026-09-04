package dev.androidtransfer.app.core.transfer

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.UUID

/** What an export module uses to hand data to the transport, without knowing which transport is active. */
interface TransferSink {
    suspend fun sendRecords(jsonArray: String, count: Int)

    /**
     * [groupKey] + [isFinalPart] let several files travel as one logical
     * unit (e.g. an app's base APK plus split APKs) — the importer sees
     * every part with the same [groupKey] before the one marked
     * [isFinalPart], and can act once the group is complete.
     */
    suspend fun sendFile(
        displayName: String,
        sizeBytes: Long,
        mimeType: String?,
        relativePath: String? = null,
        groupKey: String? = null,
        isFinalPart: Boolean = true,
        open: () -> InputStream,
    )
}

/**
 * One category's worth of transfer logic, both directions. A module only
 * needs to override the methods relevant to how its category travels:
 * record-based categories (contacts, call log, calendar, SMS, app list)
 * override [export] + [importRecords]; file-based categories (media,
 * files, WhatsApp/shared app data) override [export] + [importFile].
 */
interface TransferModule {
    val category: TransferCategory

    suspend fun export(context: Context, sink: TransferSink)

    suspend fun importRecords(context: Context, jsonArray: String) {}

    suspend fun importFile(context: Context, header: ProtocolMessage.FileHeader, file: File) {}
}

class CategorySink(private val category: TransferCategory, private val transport: dev.androidtransfer.app.core.transport.P2pTransport) : TransferSink {

    companion object {
        /**
         * Nearby Connections caps a BYTES payload at 32 KB, and anything over
         * that is simply rejected — which is why a real contact list (hundreds
         * of entries, easily hundreds of KB) never arrived while the much
         * smaller app list did. Record sets past this threshold travel as a
         * file payload instead, which is streamed and has no such limit.
         */
        const val INLINE_RECORDS_LIMIT_BYTES = 24 * 1024

        /** Marks a file payload that is really a record set, not user content. */
        const val RECORDS_GROUP_KEY = "__records__"
        const val RECORDS_MIME_TYPE = "application/json"
    }

    override suspend fun sendRecords(jsonArray: String, count: Int) {
        val bytes = jsonArray.toByteArray(Charsets.UTF_8)
        if (bytes.size <= INLINE_RECORDS_LIMIT_BYTES) {
            transport.sendMessage(ProtocolMessage.Records(category, jsonArray, count))
            return
        }
        val header = ProtocolMessage.FileHeader(
            itemId = UUID.randomUUID().toString(),
            category = category,
            displayName = "records.json",
            sizeBytes = bytes.size.toLong(),
            mimeType = RECORDS_MIME_TYPE,
            groupKey = RECORDS_GROUP_KEY,
            isFinalPart = true,
            recordCount = count,
        )
        transport.sendFile(header) { bytes.inputStream() }
    }

    override suspend fun sendFile(
        displayName: String,
        sizeBytes: Long,
        mimeType: String?,
        relativePath: String?,
        groupKey: String?,
        isFinalPart: Boolean,
        open: () -> InputStream,
    ) {
        val header = ProtocolMessage.FileHeader(
            itemId = UUID.randomUUID().toString(),
            category = category,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            relativePath = relativePath,
            groupKey = groupKey,
            isFinalPart = isFinalPart,
        )
        transport.sendFile(header, open)
    }
}
