package dev.androidtransfer.app.modules.appdata

import android.net.Uri
import android.provider.DocumentsContract
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.modules.files.SafFolderModule

/**
 * WhatsApp keeps its local encrypted chat backup and media under
 * Android/media/com.whatsapp/WhatsApp/ specifically *because* that path
 * (unlike Android/data/...) is not blocked by scoped storage for other
 * apps — WhatsApp put it there so tools like this one (and OEM clone
 * tools) can read it with an ordinary SAF grant, no root needed. After the
 * files land in Download/AndroidTransfer/WhatsApp/ on the new phone, the
 * user still has to install WhatsApp there and choose "Restore" when it
 * detects the local backup — we can move the bytes, but only WhatsApp's
 * own restore flow can re-import its encrypted database.
 *
 * Chat apps that sync to their own cloud (Telegram, Signal-with-cloud,
 * etc.) don't need this at all: signing back in already restores history,
 * so there is no folder worth registering for them here.
 */
class WhatsAppModule(sourceTreeUri: Uri?) :
    SafFolderModule(TransferCategory.WHATSAPP_MEDIA, sourceTreeUri, destinationSubFolder = "WhatsApp") {

    companion object {
        private const val KNOWN_RELATIVE_PATH = "Android/media/com.whatsapp/WhatsApp"

        /**
         * Best-effort starting point for the SAF folder picker so the user
         * doesn't have to hunt for the WhatsApp folder by hand. Whether the
         * system picker actually honors EXTRA_INITIAL_URI varies by OEM; if
         * it's ignored the user simply has to navigate there manually.
         */
        fun initialUriHint(): Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$KNOWN_RELATIVE_PATH",
        )
    }
}
