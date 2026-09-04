package dev.androidtransfer.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import dev.androidtransfer.app.R
import dev.androidtransfer.app.core.transfer.Permissions
import dev.androidtransfer.app.core.transport.NearbyTransport
import dev.androidtransfer.app.core.transport.TransportEvent
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.launch

@Composable
fun WifiScreen(viewModel: TransferViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deniedPermissions by remember { mutableStateOf<List<String>?>(null) } // null = not asked yet
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        deniedPermissions = result.filterValues { granted -> !granted }.keys.toList()
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(Permissions.forNearby().toTypedArray()) }

    val hasPermissions = deniedPermissions?.isEmpty() == true

    val transport = remember(hasPermissions) {
        if (!hasPermissions) return@remember null
        NearbyTransport(context).also { viewModel.nearbyTransport = it }
    }

    var pendingAuth by remember { mutableStateOf<Pair<String, String>?>(null) } // endpointId, digits
    var endpoints by remember { mutableStateOf(emptyMap<String, DiscoveredEndpointInfo>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(transport) {
        val t = transport ?: return@LaunchedEffect
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        if (viewModel.role == Role.RECEIVER) {
            t.startAdvertising(deviceName)
        } else {
            t.startDiscovery()
        }
        scope.launch { t.discoveredEndpoints.collect { endpoints = it } }
        scope.launch { t.pendingAuthDigits.collect { pendingAuth = it } }
        scope.launch {
            t.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> {
                        viewModel.attachTransportAndStart(t)
                        onConnected()
                    }
                    is TransportEvent.TransportError -> errorMessage = event.message
                    else -> Unit
                }
            }
        }
    }

    pendingAuth?.let { (endpointId, digits) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.pairing_title)) },
            text = {
                Text(
                    digits,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    // Both sides of a Nearby connection must call acceptConnection
                    // after onConnectionInitiated, not just the advertiser.
                    transport?.acceptConnection(endpointId)
                    pendingAuth = null
                }) { Text("OK") }
            },
            dismissButton = {
                Button(onClick = {
                    transport?.rejectConnection(endpointId)
                    pendingAuth = null
                }) { Text("Отмена") }
            },
        )
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val denied = deniedPermissions
            if (denied != null && denied.isNotEmpty()) {
                Text(
                    "Не выданы разрешения, без них соединение не установится:\n" + denied.joinToString("\n") { "• $it" },
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permissionLauncher.launch(Permissions.forNearby().toTypedArray()) }) {
                        Text("Запросить снова")
                    }
                }
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }) { Text("Открыть настройки приложения") }
                return@Column
            }

            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            Text(
                "Убедитесь, что на обоих телефонах включены Wi-Fi и Bluetooth (сами по себе, не обязательно подключение к одной сети).",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )

            if (viewModel.role == Role.RECEIVER) {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_waiting))
            } else {
                Text(if (endpoints.isEmpty()) "Поиск устройств…" else stringResource(R.string.pairing_waiting))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(endpoints.entries.toList()) { (endpointId, info) ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                transport?.requestConnection("${Build.MANUFACTURER} ${Build.MODEL}", endpointId)
                            },
                        ) {
                            Text(info.endpointName, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}
