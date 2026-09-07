package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import dev.androidtransfer.app.core.transfer.CategoryProgress
import dev.androidtransfer.app.core.transfer.CategoryStatus
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.modules.apps.ApkInstaller
import dev.androidtransfer.app.modules.apps.ReceivedAppsHolder
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

/**
 * The hand-back screen. An operator needs two things from it in about three
 * seconds: did anything fail, and can I start the next phone. Everything
 * that failed is therefore listed first and in full; the successes collapse
 * into one line.
 */
@Composable
fun SummaryScreen(viewModel: TransferViewModel, onOpenApps: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val apps by ReceivedAppsHolder.apps.collectAsState()
    val state by viewModel.transferState.collectAsState()
    val categories = (state as? TransferState.Completed)?.categories.orEmpty()
    val failed = categories.filter { it.status == CategoryStatus.FAILED }
    val succeeded = categories.filter { it.status != CategoryStatus.FAILED }
    val installStats by ApkInstaller.stats.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (failed.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (failed.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    if (failed.isEmpty()) "Перенос завершён" else "Завершено с ошибками",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            if (failed.isNotEmpty()) {
                Text(
                    "Не перенеслось:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                for (progress in failed) {
                    CategoryResultRow(progress, isFailure = true)
                }
            }

            if (succeeded.isNotEmpty()) {
                Text("Перенесено:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                for (progress in succeeded) {
                    CategoryResultRow(progress, isFailure = false)
                }
            }

            if (installStats.anythingHappened) {
                Text(
                    "Приложения: установлено ${installStats.installed}" +
                        (if (installStats.waiting > 0) ", ждут подтверждения ${installStats.waiting}" else "") +
                        (if (installStats.failed > 0) ", не удалось ${installStats.failed}" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                installStats.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (installStats.failed > 0) {
                    OutlinedButton(onClick = { ApkInstaller.retryFailed(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Повторить установку")
                    }
                }
            }

            if (apps.isNotEmpty()) {
                OutlinedButton(onClick = onOpenApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Список приложений (${apps.size}) — доустановить из Play Store")
                }
            }

            // Primary action, because in a service centre there is always a
            // next phone: clears everything customer-specific and returns to
            // the start, keeping the category selection for the next one.
            Button(
                onClick = {
                    viewModel.resetForNextTransfer()
                    onFinish()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Готово — следующий телефон")
            }
        }
    }
}

@Composable
private fun CategoryResultRow(progress: CategoryProgress, isFailure: Boolean) {
    val info = categoryUiInfo(progress.category)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(info.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(info.labelRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            progress.detail?.takeIf { !isFailure }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        // A failure's detail carries the actual reason, so it gets a full line
        // rather than being squeezed in beside the label.
        if (isFailure) {
            progress.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 26.dp),
                )
            }
        }
    }
}
