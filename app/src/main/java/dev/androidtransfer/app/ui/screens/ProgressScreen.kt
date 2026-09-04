package dev.androidtransfer.app.ui.screens

import android.os.Build
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transfer.CategoryProgress
import dev.androidtransfer.app.core.transfer.CategoryStatus
import dev.androidtransfer.app.core.transfer.Format
import dev.androidtransfer.app.core.transfer.TransferForegroundService
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.modules.apps.ApkInstaller
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

@Composable
fun ProgressScreen(viewModel: TransferViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.transferState.collectAsState()

    // Without this the transfer dies as soon as the screen turns off or the
    // user switches apps — the service exists purely to hold the process up
    // for the duration of the transfer.
    DisposableEffect(Unit) {
        TransferForegroundService.start(context)
        onDispose { TransferForegroundService.stop(context) }
    }

    LaunchedEffect(Unit) {
        if (viewModel.role == Role.SENDER) {
            viewModel.beginSending("${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    LaunchedEffect(state) {
        if (state is TransferState.Completed) onDone()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Идёт перенос…", style = MaterialTheme.typography.headlineSmall)

            val pendingInstalls by ApkInstaller.remaining.collectAsState()
            if (pendingInstalls > 0) {
                Text(
                    "Приложений в очереди на установку: $pendingInstalls — подтверждайте установку по очереди",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (val s = state) {
                is TransferState.Running -> RunningContent(s)
                is TransferState.Error -> {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("Ошибка: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDone) { Text("Закрыть") }
                }
                else -> {
                    CircularProgressIndicator()
                    Text("Подготовка…")
                }
            }
        }
    }
}

@Composable
private fun RunningContent(state: TransferState.Running) {
    val total = state.categories.size
    val done = state.categories.count { it.status == CategoryStatus.DONE || it.status == CategoryStatus.FAILED }

    state.peerName?.let { Text("Подключено к $it", style = MaterialTheme.typography.bodyMedium) }

    if (total > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$done из $total категорий", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(state.categories) { CategoryRow(it) }
    }

    if (state.currentFileTotalBytes > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${Format.megabytes(state.currentFileBytesTransferred)} / ${Format.megabytes(state.currentFileTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val speedText = Format.speed(state.speedBytesPerSecond)
                if (speedText.isNotEmpty()) Text(speedText, style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { (state.currentFileBytesTransferred.toFloat() / state.currentFileTotalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryRow(progress: CategoryProgress) {
    val info = categoryUiInfo(progress.category)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(info.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(info.labelRes),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (progress.status) {
            CategoryStatus.PENDING -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "Ожидает", tint = MaterialTheme.colorScheme.outline)
            CategoryStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            CategoryStatus.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = "Готово", tint = MaterialTheme.colorScheme.primary)
            CategoryStatus.FAILED -> Icon(Icons.Filled.Error, contentDescription = "Ошибка", tint = MaterialTheme.colorScheme.error)
        }
    }
}
