package dev.androidtransfer.app.modules.files

import android.net.Uri
import dev.androidtransfer.app.core.transfer.TransferCategory

class FilesModule(sourceTreeUri: Uri?) : SafFolderModule(TransferCategory.FILES, sourceTreeUri, destinationSubFolder = "Files")

class CustomFolderModule(sourceTreeUri: Uri?) : SafFolderModule(TransferCategory.CUSTOM_FOLDER, sourceTreeUri, destinationSubFolder = "Custom")
