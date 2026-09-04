package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.core.transfer.Format
import dev.androidtransfer.app.modules.apps.InstalledApp
import dev.androidtransfer.app.modules.apps.InstalledApps
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppPickerScreen(viewModel: TransferViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { InstalledApps.list(context) }
        apps = loaded
        // First visit selects everything transferable, so the default behaviour
        // matches "transfer my apps" without forcing the user through this screen.
        if (!viewModel.appSelectionInitialized) {
            viewModel.initAppSelection(loaded.filter { it.isTransferable }.map { it.packageName })
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val loaded = apps
            if (loaded == null) {
                Text("Читаем список приложений…")
                return@Column
            }

            val transferable = loaded.filter { it.isTransferable }
            val selectedSize = transferable.filter { it.packageName in viewModel.selectedAppPackages }.sumOf { it.sizeBytes }

            Text("Какие приложения перенести", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Выбрано ${viewModel.selectedAppPackages.size} из ${transferable.size} · ${Format.megabytes(selectedSize)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Переносится сам APK — приложение установится на новом телефоне без Play Store. Данные внутри приложений (переписки, вход в аккаунт) при этом не переносятся.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.initAppSelection(transferable.map { it.packageName }) }) { Text("Выбрать все") }
                TextButton(onClick = { viewModel.initAppSelection(emptyList()) }) { Text("Снять все") }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(loaded) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName in viewModel.selectedAppPackages,
                            enabled = app.isTransferable,
                            onCheckedChange = { viewModel.toggleApp(app.packageName, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (app.isTransferable) Format.megabytes(app.sizeBytes) else "APK недоступен для чтения",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Готово") }
        }
    }
}
