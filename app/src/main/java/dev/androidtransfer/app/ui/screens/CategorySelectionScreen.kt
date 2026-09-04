package dev.androidtransfer.app.ui.screens

import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.androidtransfer.app.core.transfer.Permissions
import dev.androidtransfer.app.core.transfer.TransferCategory
import dev.androidtransfer.app.modules.appdata.WhatsAppModule
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

private val allCategories: List<TransferCategory> = categoryUiInfoList.map { it.category }

private val groups: List<Pair<String, List<TransferCategory>>> = listOf(
    "Личные данные" to listOf(TransferCategory.CONTACTS, TransferCategory.CALL_LOG, TransferCategory.CALENDAR),
    "Медиа и файлы" to listOf(
        TransferCategory.MEDIA,
        TransferCategory.FILES,
        TransferCategory.WHATSAPP_MEDIA,
        TransferCategory.CUSTOM_FOLDER,
        TransferCategory.WALLPAPER,
    ),
    "Приложения" to listOf(TransferCategory.INSTALLED_APPS),
)

@Composable
fun CategorySelectionScreen(viewModel: TransferViewModel, onStart: () -> Unit) {
    val context = LocalContext.current

    var denied by remember { mutableStateOf<List<String>>(emptyList()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.filterValues { granted -> !granted }.keys.toList()
    }

    // Categories start pre-checked, so their checkboxes are never tapped and
    // onCheckedChange never fires for them — without this, contacts/call log/
    // calendar exports hit the provider with no permission and fail.
    LaunchedEffect(Unit) {
        val perms = Permissions.forSelection(viewModel.selectedCategories.toList())
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Что перенести?", style = MaterialTheme.typography.headlineSmall)
                val allSelected = viewModel.selectedCategories.size == allCategories.size
                TextButton(onClick = {
                    if (allSelected) {
                        allCategories.forEach { viewModel.toggleCategory(it, false) }
                    } else {
                        allCategories.forEach { category ->
                            viewModel.toggleCategory(category, true)
                            val perms = Permissions.forCategory(category)
                            if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                        }
                    }
                }) {
                    Text(if (allSelected) "Снять всё" else "Выбрать всё")
                }
            }

            for ((groupTitle, groupCategories) in groups) {
                Text(
                    groupTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                for (category in groupCategories) {
                    val info = categoryUiInfo(category)
                    val checked = category in viewModel.selectedCategories
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(info.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { enabled ->
                                viewModel.toggleCategory(category, enabled)
                                if (enabled) {
                                    val perms = Permissions.forCategory(category)
                                    if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                                }
                            },
                        )
                        Text(stringResource(info.labelRes), modifier = Modifier.weight(1f))
                    }
                    when (category) {
                        TransferCategory.FILES -> if (checked) {
                            OutlinedButton(onClick = { filesPicker.launch(null) }, modifier = Modifier.padding(start = 48.dp)) {
                                Text(if (viewModel.filesTreeUri != null) "Папка выбрана" else "Выбрать папку")
                            }
                        }
                        TransferCategory.CUSTOM_FOLDER -> if (checked) {
                            OutlinedButton(onClick = { customPicker.launch(null) }, modifier = Modifier.padding(start = 48.dp)) {
                                Text(if (viewModel.customFolderTreeUri != null) "Папка выбрана" else "Выбрать папку")
                            }
                        }
                        TransferCategory.WHATSAPP_MEDIA -> if (checked) {
                            OutlinedButton(
                                onClick = {
                                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WhatsAppModule.initialUriHint() else null
                                    whatsappPicker.launch(hint)
                                },
                                modifier = Modifier.padding(start = 48.dp),
                            ) {
                                Text(if (viewModel.whatsappTreeUri != null) "Папка выбрана" else "Выбрать папку Android/media/com.whatsapp/WhatsApp")
                            }
                        }
                        else -> Unit
                    }
                }
            }

            if (denied.isNotEmpty()) {
                Text(
                    "Без этих разрешений соответствующие категории перенести не получится: " +
                        denied.joinToString(", ") { it.substringAfterLast('.') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedButton(onClick = {
                    permissionLauncher.launch(Permissions.forSelection(viewModel.selectedCategories.toList()).toTypedArray())
                }) { Text("Запросить снова") }
            }

            Button(
                onClick = {
                    // Re-check right before starting: the selection may have changed
                    // since the screen opened, and a missing permission here means that
                    // category silently exports nothing. Asking once is enough — if the
                    // user deliberately said no, let them start anyway.
                    val missing = Permissions.forSelection(viewModel.selectedCategories.toList())
                        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                        .filterNot { it in denied }
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else onStart()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text("Начать перенос")
            }
        }
    }
}
