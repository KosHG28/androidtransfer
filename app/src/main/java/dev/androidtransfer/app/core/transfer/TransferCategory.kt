package dev.androidtransfer.app.core.transfer

import kotlinx.serialization.Serializable

/**
 * Every category the app knows how to move. Each maps to one module under
 * dev.androidtransfer.app.modules.* that implements export (read from this
 * device) and import (write to this device).
 */
@Serializable
enum class TransferCategory {
    CONTACTS,
    CALL_LOG,
    CALENDAR,
    MEDIA,
    FILES,
    INSTALLED_APPS,
    WHATSAPP_MEDIA,
    CUSTOM_FOLDER,
}
