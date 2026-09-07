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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transfer.CategoryProgress
import dev.androidtransfer.app.core.transfer.CategoryStatus
import dev.androidtransfer.app.core.transfer.Format
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.modules.apps.ApkInstaller
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

@Composable
fun ProgressScreen(viewModel: TransferViewModel, onDone: () -> Unit) {
    val state by viewModel.transferState.collectAsState()

    // The foreground service is started (and stopped) by
    // TransferViewModel.attachTransportAndStart()/TransferForegroundService
    // itself once the transfer reaches a terminal state — not tied to this
    // screen's composition. Starting/stopping it from a DisposableEffect
    // here used to mean navigating away (or the Activity being torn down by
    // a task swipe) stopped the service mid-transfer, exactly the situation
    // it exists to survive.

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

            val installStats by ApkInstaller.stats.collectAsState()
            if (installStats.anythingHappened) {
                Text(
                    buildString {
                        if (installStats.waiting > 0) append("Ожидают подтверждения: ${installStats.waiting}. ")
                        if (installStats.installed > 0) append("Установлено: ${installStats.installed}. ")
                        if (installStats.failed > 0) append("Не удалось: ${installStats.failed}.")
                    }.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                installStats.lastError?.let {
                    Text("Причина: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
        progress.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (progress.status == CategoryStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
    }
}
