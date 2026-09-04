package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.R
import dev.androidtransfer.app.ui.viewmodel.TransportKind

@Composable
fun TransportScreen(onTransportChosen: (TransportKind) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.transport_title), style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth().clickable { onTransportChosen(TransportKind.WIFI) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.transport_wifi), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.transport_wifi_desc), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth().clickable { onTransportChosen(TransportKind.USB) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.transport_usb), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.transport_usb_desc), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
