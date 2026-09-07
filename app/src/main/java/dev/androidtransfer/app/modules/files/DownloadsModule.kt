package dev.androidtransfer.app.modules.files

import android.net.Uri
import android.provider.DocumentsContract
import dev.androidtransfer.app.core.transfer.TransferCategory

/**
 * The Download folder — PDFs, documents, installers, everything that isn't
 * photos or music and that "перенеси всё" would otherwise silently skip.
 *
 * Goes through the SAF folder picker rather than MediaStore.Downloads on
 * purpose. From Android 10 an app only sees its *own* entries in that
 * collection, and on Android 13+ the READ_MEDIA_* permissions cover media
 * only — so a MediaStore-based version of this category would quietly
 * transfer nothing at all on any modern phone, which is exactly the kind of
 * silent no-op this app keeps getting bitten by. One folder grant costs the
 * user a tap and actually works everywhere.
 */
class DownloadsModule(sourceTreeUri: Uri?) :
    SafFolderModule(TransferCategory.DOWNLOADS, sourceTreeUri, destinationSubFolder = "Downloads") {

    companion object {
        /** Best-effort starting point for the picker; OEMs vary in whether they honour it. */
        fun initialUriHint(): Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download",
        )
    }
}
