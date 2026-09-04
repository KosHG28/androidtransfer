package dev.androidtransfer.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transfer.TransferState
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel

@Composable
fun ProgressScreen(viewModel: TransferViewModel, onDone: () -> Unit) {
    val state by viewModel.transferState.collectAsState()

    LaunchedEffect(Unit) {
        if (viewModel.role == Role.SENDER) {
            viewModel.beginSending("${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    LaunchedEffect(state) {
        if (state is TransferState.Completed) onDone()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Идёт перенос…", style = MaterialTheme.typography.headlineSmall)
            Text(describeState(state), modifier = Modifier.padding(top = 8.dp))

            val progress = (state as? TransferState.ItemProgress)
            if (progress != null && progress.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (progress.bytesTransferred.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }

            if (state is TransferState.Error) {
                Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Закрыть") }
            }
        }
    }
}

private fun describeState(state: TransferState): String = when (state) {
    TransferState.Idle -> "Подготовка…"
    is TransferState.Connected -> "Подключено к ${state.peerName}"
    is TransferState.RunningCategory -> "Категория: ${state.category}"
    is TransferState.ItemProgress -> "Передача файла: ${state.bytesTransferred / 1024} / ${state.totalBytes / 1024} КБ"
    TransferState.Completed -> "Готово"
    is TransferState.Error -> "Ошибка: ${state.message}"
}
