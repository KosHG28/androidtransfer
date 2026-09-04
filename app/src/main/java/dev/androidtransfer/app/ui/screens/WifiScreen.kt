package dev.androidtransfer.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermissions = result.values.all { it }
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(Permissions.forNearby().toTypedArray()) }

    val transport = remember(hasPermissions) {
        if (!hasPermissions) return@remember null
        NearbyTransport(context).also { viewModel.nearbyTransport = it }
    }

    var pendingAuth by remember { mutableStateOf<Pair<String, String>?>(null) } // endpointId, digits
    var endpoints by remember { mutableStateOf(emptyMap<String, DiscoveredEndpointInfo>()) }

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
                if (event is TransportEvent.Connected) {
                    viewModel.attachTransportAndStart(t)
                    onConnected()
                }
            }
        }
    }

    pendingAuth?.let { (endpointId, digits) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.pairing_title)) },
            text = { Text(digits, style = MaterialTheme.typography.displaySmall) },
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
            if (viewModel.role == Role.RECEIVER) {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_waiting))
            } else {
                Text(stringResource(R.string.pairing_waiting))
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
