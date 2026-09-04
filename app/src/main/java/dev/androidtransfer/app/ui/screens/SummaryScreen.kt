package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.R
import dev.androidtransfer.app.modules.apps.ReceivedAppsHolder

@Composable
fun SummaryScreen(onOpenApps: () -> Unit, onFinish: () -> Unit) {
    val apps by ReceivedAppsHolder.apps.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.progress_done), style = MaterialTheme.typography.headlineMedium)
            if (apps.isNotEmpty()) {
                Text("Получен список из ${apps.size} приложений для установки.", modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onOpenApps, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Открыть список приложений")
                }
            }
            Button(onClick = onFinish, modifier = Modifier.padding(top = 16.dp)) {
                Text("Завершить")
            }
        }
    }
}
