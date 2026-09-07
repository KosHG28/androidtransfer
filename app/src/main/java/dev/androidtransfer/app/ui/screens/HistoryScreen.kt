package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.history.TransferHistoryEntry
import dev.androidtransfer.app.core.history.TransferHistoryStore
import dev.androidtransfer.app.core.transfer.CategoryStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(TransferHistoryStore.all(context)) }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
                    Text("История переносов", style = MaterialTheme.typography.headlineSmall)
                }
                if (entries.isNotEmpty()) {
                    IconButton(onClick = {
                        TransferHistoryStore.clear(context)
                        entries = emptyList()
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Очистить историю") }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    "Переносов пока не было",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { HistoryRow(it) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: TransferHistoryEntry) {
    val dateText = remember(entry.timestampMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMillis))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (entry.role == "SENDER") Icons.Filled.Upload else Icons.Filled.Download,
                    contentDescription = if (entry.role == "SENDER") "Отправлено" else "Принято",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (entry.transport == "usb") Icons.Filled.Cable else Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(dateText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(
                    if (entry.overallFailed) Icons.Filled.Error else Icons.Filled.CheckCircle,
                    contentDescription = if (entry.overallFailed) "Ошибка" else "Успешно",
                    tint = if (entry.overallFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            entry.peerName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (entry.categories.isNotEmpty()) {
                val doneCount = entry.categories.count { it.status == CategoryStatus.DONE }
                val failedCount = entry.categories.count { it.status == CategoryStatus.FAILED }
                Text(
                    "Успешно категорий: $doneCount" + (if (failedCount > 0) ", с ошибкой: $failedCount" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            entry.errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
