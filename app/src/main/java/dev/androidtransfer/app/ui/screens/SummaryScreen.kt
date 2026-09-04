package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import dev.androidtransfer.app.core.transfer.CategoryStatus
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.modules.apps.ReceivedAppsHolder
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

@Composable
fun SummaryScreen(viewModel: TransferViewModel, onOpenApps: () -> Unit, onFinish: () -> Unit) {
    val apps by ReceivedAppsHolder.apps.collectAsState()
    val state by viewModel.transferState.collectAsState()
    val categories = (state as? TransferState.Completed)?.categories.orEmpty()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.progress_done), style = MaterialTheme.typography.headlineMedium)

            if (categories.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    for (progress in categories) {
                        val info = categoryUiInfo(progress.category)
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(info.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(info.labelRes), modifier = Modifier.padding(start = 8.dp))
                                }
                                if (progress.status == CategoryStatus.FAILED) {
                                    Icon(Icons.Filled.Error, contentDescription = "Не удалось", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                } else {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Готово", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                            progress.detail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (progress.status == CategoryStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 26.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (apps.isNotEmpty()) {
                Text("Получен список из ${apps.size} приложений для установки.", modifier = Modifier.padding(top = 16.dp))
                Button(onClick = onOpenApps, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Открыть список приложений")
                }
            }
            Button(onClick = onFinish, modifier = Modifier.padding(top = 16.dp)) {
                Text("Завершить")
            }
        }
    }
}
