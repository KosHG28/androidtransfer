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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transport.TransportEvent
import dev.androidtransfer.app.core.transport.UsbLinkDiscovery
import dev.androidtransfer.app.core.transport.UsbTetherTransport
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun UsbScreen(viewModel: TransferViewModel, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Выберите режим соединения") }
    var manualIp by remember { mutableStateOf(UsbLinkDiscovery.candidateGatewayAddresses().first()) }
    var localAddresses by remember { mutableStateOf(emptyList<String>()) }

    val transport = remember { UsbTetherTransport(context).also { viewModel.usbTransport = it } }

    // Polls because the RNDIS interface can take a few seconds to come up
    // after the user toggles USB tethering, and its address is the one
    // reliable thing to type into the other phone — the hardcoded guesses
    // in UsbLinkDiscovery.candidateGatewayAddresses() vary a lot by OEM.
    LaunchedEffect(Unit) {
        while (isActive) {
            localAddresses = UsbLinkDiscovery.localUsbInterfaceAddresses()
            delay(2000)
        }
    }

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
                    "3. На ЭТОМ телефоне (где включили модем) нажмите «Ждать подключение».\n" +
                    "4. На ВТОРОМ телефоне откройте этот же экран, посмотрите его IP ниже и введите в поле, затем нажмите «Подключиться».",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("IP этого телефона на USB-интерфейсе:", style = MaterialTheme.typography.bodySmall)
            if (localAddresses.isEmpty()) {
                Text(
                    "не обнаружен (включите USB-модем и подождите пару секунд)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        localAddresses.joinToString(", "),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "Этот адрес нужно ввести на ВТОРОМ телефоне, если на нём выбрано «Подключиться»",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }

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
