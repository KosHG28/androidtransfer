package dev.androidtransfer.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.androidtransfer.app.R
import dev.androidtransfer.app.core.transfer.Permissions
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import dev.androidtransfer.app.ui.viewmodel.TransportKind

@Composable
fun TransportScreen(viewModel: TransferViewModel, onTransportChosen: (TransportKind) -> Unit) {
    val context = LocalContext.current
    val isReceiver = viewModel.role == Role.RECEIVER

    // The receiver doesn't pick categories (the sender does), so incoming data
    // could need any permission at any moment once a connection starts — there
    // is no "ask when the checkbox is ticked" moment for it. Ask for
    // everything here, as early as possible, well before any transfer begins.
    var deniedPermissions by remember { mutableStateOf<List<String>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        deniedPermissions = result.filterValues { granted -> !granted }.keys.toList()
    }
    var canInstallApks by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    val installSourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstallApks = context.packageManager.canRequestPackageInstalls()
    }

    LaunchedEffect(isReceiver) {
        if (isReceiver) {
            permissionLauncher.launch(Permissions.forReceiver().toTypedArray())
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.transport_title), style = MaterialTheme.typography.headlineSmall)

            if (isReceiver) {
                ReceiverReadinessCard(
                    deniedPermissions = deniedPermissions,
                    canInstallApks = canInstallApks,
                    onRetryPermissions = { permissionLauncher.launch(Permissions.forReceiver().toTypedArray()) },
                    onOpenInstallSettings = {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        installSourceLauncher.launch(intent)
                    },
                )
            }

            Card(modifier = Modifier.fillMaxWidth().clickable { onTransportChosen(TransportKind.WIFI) }) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(stringResource(R.string.transport_wifi), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.transport_wifi_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth().clickable { onTransportChosen(TransportKind.USB) }) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cable, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(stringResource(R.string.transport_usb), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.transport_usb_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiverReadinessCard(
    deniedPermissions: List<String>?,
    canInstallApks: Boolean,
    onRetryPermissions: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val checking = deniedPermissions == null
    val allGranted = deniedPermissions?.isEmpty() == true && canInstallApks
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (allGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (allGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    when {
                        checking -> "Проверка разрешений…"
                        allGranted -> "Все разрешения выданы, можно принимать данные"
                        else -> "Нужно выдать разрешения до подключения"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!deniedPermissions.isNullOrEmpty()) {
                Text("Не выданы: " + deniedPermissions.joinToString(", ") { it.substringAfterLast('.') }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetryPermissions) { Text("Запросить снова") }
            }
            if (!checking && !canInstallApks) {
                Text("Установка приложений напрямую (без Play Store) требует разрешения «Установка неизвестных приложений».", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenInstallSettings) { Text("Разрешить установку приложений") }
            }
        }
    }
}
