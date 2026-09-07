package dev.androidtransfer.app.core.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire protocol spoken over both transports (Nearby Connections BYTES
 * payloads on Wi-Fi, and length-prefixed control frames on the USB socket).
 * kotlinx.serialization discovers all sealed subtypes automatically and
 * tags each with the "type" discriminator configured in [ProtocolJson].
 */
@Serializable
sealed interface ProtocolMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(val sessionId: String, val deviceName: String, val appVersion: String) : ProtocolMessage

    /**
     * [estimatedBytes] lets the receiver check its free space before a single
     * file lands, instead of failing halfway through with a confusing write
     * error, and gives both sides an honest byte-based progress bar.
     * [sizesIncomplete] is set when some category couldn't be measured
     * cheaply (a user-picked folder tree), so the figure is a floor rather
     * than a total and the UI can say so.
     */
    @Serializable
    @SerialName("manifest")
    data class Manifest(
        val sessionId: String,
        val categories: List<TransferCategory>,
        val estimatedBytes: Long = 0,
        val sizesIncomplete: Boolean = false,
    ) : ProtocolMessage

    /** A whole category's worth of small records (contacts, SMS, call log, calendar, app list) sent as one JSON array. */
    @Serializable
    @SerialName("records")
    data class Records(val category: TransferCategory, val jsonArray: String, val count: Int) : ProtocolMessage

    /**
     * Announces a file that will follow (Nearby: a correlated FILE payload;
     * USB: raw bytes on the same stream). [groupKey] and [isFinalPart] let a
     * module send several files that belong together (e.g. an app's base
     * APK plus split APKs) and know on the receiving end when the whole
     * group has arrived — everything else defaults to "one file, done".
     */
    @Serializable
    @SerialName("file_header")
    data class FileHeader(
        val itemId: String,
        val category: TransferCategory,
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String?,
        val relativePath: String? = null,
        val nearbyPayloadId: Long? = null,
        val groupKey: String? = null,
        val isFinalPart: Boolean = true,
        /** Set when this file is a record set too large for an inline Records message. */
        val recordCount: Int? = null,
    ) : ProtocolMessage

    @Serializable
    @SerialName("category_start")
    data class CategoryStart(val category: TransferCategory, val totalItems: Int, val totalBytes: Long) : ProtocolMessage

    @Serializable
    @SerialName("category_done")
    data class CategoryDone(val category: TransferCategory, val itemsSent: Int) : ProtocolMessage

    @Serializable
    @SerialName("transfer_done")
    data class TransferDone(val sessionId: String) : ProtocolMessage

    @Serializable
    @SerialName("ack")
    data class Ack(val itemId: String) : ProtocolMessage

    @Serializable
    @SerialName("error")
    data class Error(val category: TransferCategory?, val message: String) : ProtocolMessage
}

object ProtocolJson {
    val instance: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
