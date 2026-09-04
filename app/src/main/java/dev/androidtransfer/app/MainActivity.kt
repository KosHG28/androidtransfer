package dev.androidtransfer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.androidtransfer.app.ui.navigation.AppNav
import dev.androidtransfer.app.ui.theme.AndroidTransferTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidTransferTheme {
                AppNav()
            }
        }
    }
}
