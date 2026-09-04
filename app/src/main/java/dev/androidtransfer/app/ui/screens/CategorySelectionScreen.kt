package dev.androidtransfer.app.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.R
import dev.androidtransfer.app.core.transfer.Permissions
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.modules.appdata.WhatsAppModule
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

private data class CategoryUi(val category: TransferCategory, val labelRes: Int)

private val allCategories = listOf(
    CategoryUi(TransferCategory.CONTACTS, R.string.category_contacts),
    CategoryUi(TransferCategory.CALL_LOG, R.string.category_call_log),
    CategoryUi(TransferCategory.CALENDAR, R.string.category_calendar),
    CategoryUi(TransferCategory.MEDIA, R.string.category_media),
    CategoryUi(TransferCategory.FILES, R.string.category_files),
    CategoryUi(TransferCategory.INSTALLED_APPS, R.string.category_installed_apps),
    CategoryUi(TransferCategory.WHATSAPP_MEDIA, R.string.category_whatsapp_media),
    CategoryUi(TransferCategory.CUSTOM_FOLDER, R.string.category_custom_folder),
    CategoryUi(TransferCategory.WALLPAPER, R.string.category_wallpaper),
)

@Composable
fun CategorySelectionScreen(viewModel: TransferViewModel, onStart: () -> Unit) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.filesTreeUri = it
        }
    }
    val customPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.customFolderTreeUri = it
        }
    }
    val whatsappPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.whatsappTreeUri = it
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Что перенести?", style = MaterialTheme.typography.headlineSmall)

            for (item in allCategories) {
                val checked = item.category in viewModel.selectedCategories
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { enabled ->
                            viewModel.toggleCategory(item.category, enabled)
                            if (enabled) {
                                val perms = Permissions.forCategory(item.category)
                                if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                            }
                        },
                    )
                    Text(stringResource(item.labelRes), modifier = Modifier.weight(1f))
                }
                when (item.category) {
                    TransferCategory.FILES -> if (checked) {
                        OutlinedButton(onClick = { filesPicker.launch(null) }, modifier = Modifier.padding(start = 40.dp)) {
                            Text(if (viewModel.filesTreeUri != null) "Папка выбрана" else "Выбрать папку")
                        }
                    }
                    TransferCategory.CUSTOM_FOLDER -> if (checked) {
                        OutlinedButton(onClick = { customPicker.launch(null) }, modifier = Modifier.padding(start = 40.dp)) {
                            Text(if (viewModel.customFolderTreeUri != null) "Папка выбрана" else "Выбрать папку")
                        }
                    }
                    TransferCategory.WHATSAPP_MEDIA -> if (checked) {
                        OutlinedButton(
                            onClick = {
                                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WhatsAppModule.initialUriHint() else null
                                whatsappPicker.launch(hint)
                            },
                            modifier = Modifier.padding(start = 40.dp),
                        ) {
                            Text(if (viewModel.whatsappTreeUri != null) "Папка выбрана" else "Выбрать папку Android/media/com.whatsapp/WhatsApp")
                        }
                    }
                    else -> Unit
                }
            }

            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Начать перенос")
            }
        }
    }
}
