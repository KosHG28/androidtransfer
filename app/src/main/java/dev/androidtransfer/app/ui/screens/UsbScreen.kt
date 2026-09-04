package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transport.TransportEvent
import dev.androidtransfer.app.core.transport.UsbLinkDiscovery
import dev.androidtransfer.app.core.transport.UsbTetherTransport
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.launch

@Composable
fun UsbScreen(viewModel: TransferViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Выберите режим соединения") }
    var manualIp by remember { mutableStateOf(UsbLinkDiscovery.candidateGatewayAddresses().first()) }

    val transport = remember { UsbTetherTransport(context).also { viewModel.usbTransport = it } }

    LaunchedEffect(transport) {
        transport.events.collect { event ->
            when (event) {
                is TransportEvent.Connected -> {
                    status = "Подключено"
                    viewModel.attachTransportAndStart(transport)
                    onConnected()
                }
                is TransportEvent.TransportError -> status = "Ошибка: ${event.message}"
                is TransportEvent.Disconnected -> status = "Соединение разорвано: ${event.reason}"
                else -> Unit
            }
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Подключение по USB", style = MaterialTheme.typography.headlineSmall)
            Text(
                "1. Соедините телефоны USB-C кабелем.\n" +
                    "2. На ОДНОМ из телефонов включите «USB-модем» (Настройки → Сеть → Точка доступа и модем → USB-модем).\n" +
                    "3. На этом телефоне нажмите «Ждать подключение», на втором — «Подключиться».",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(status)

            Button(onClick = {
                status = "Ожидание подключения…"
                scope.launch { transport.connect(UsbTetherTransport.Role.Server()) }
            }) { Text("Ждать подключение (сервер)") }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("IP адрес второго телефона") },
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = {
                    status = "Подключение к $manualIp…"
                    scope.launch { transport.connect(UsbTetherTransport.Role.Client(manualIp)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Подключиться (клиент)") }
        }
    }
}
