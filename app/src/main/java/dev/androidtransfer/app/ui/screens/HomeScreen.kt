package dev.androidtransfer.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidtransfer.app.R
import dev.androidtransfer.app.ui.viewmodel.Role

@Composable
fun HomeScreen(onRoleChosen: (Role) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(40.dp))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium)

            Column(modifier = Modifier.padding(top = 32.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoleCard(
                    icon = Icons.Filled.Upload,
                    label = stringResource(R.string.home_role_send),
                    onClick = { onRoleChosen(Role.SENDER) },
                )
                RoleCard(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.home_role_receive),
                    onClick = { onRoleChosen(Role.RECEIVER) },
                )
            }
        }
    }
}

@Composable
private fun RoleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
