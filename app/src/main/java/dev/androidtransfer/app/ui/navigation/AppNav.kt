package dev.androidtransfer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.androidtransfer.app.ui.screens.AppPickerScreen
import dev.androidtransfer.app.ui.screens.AppsListScreen
import dev.androidtransfer.app.ui.screens.CategorySelectionScreen
import dev.androidtransfer.app.ui.screens.HistoryScreen
import dev.androidtransfer.app.ui.screens.HomeScreen
import dev.androidtransfer.app.ui.screens.ProgressScreen
import dev.androidtransfer.app.ui.screens.SummaryScreen
import dev.androidtransfer.app.ui.screens.TransportScreen
import dev.androidtransfer.app.ui.screens.UsbScreen
import dev.androidtransfer.app.ui.screens.WifiScreen
import dev.androidtransfer.app.ui.viewmodel.Role
import dev.androidtransfer.app.ui.viewmodel.TransferViewModel
import dev.androidtransfer.app.ui.viewmodel.TransportKind

private object Routes {
    const val HOME = "home"
    const val TRANSPORT = "transport"
    const val WIFI = "wifi"
    const val USB = "usb"
    const val CATEGORIES = "categories"
    const val APP_PICKER = "app_picker"
    const val PROGRESS = "progress"
    const val APPS = "apps"
    const val SUMMARY = "summary"
    const val HISTORY = "history"
}

@Composable
fun AppNav() {
    val navController: NavHostController = rememberNavController()
    val viewModel: TransferViewModel = viewModel()

    // Reopened while the service is still driving a transfer — i.e. the user
    // swiped the app away mid-transfer (which the transfer now survives) and
    // came back. Re-attach and open straight on the progress screen, instead
    // of a home screen that hides the fact a transfer is still running and
    // invites starting a second one on top of it.
    val resumedRunningTransfer = remember { viewModel.adoptRunningSessionIfAny() }

    NavHost(
        navController = navController,
        startDestination = if (resumedRunningTransfer) Routes.PROGRESS else Routes.HOME,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onRoleChosen = { role ->
                    viewModel.role = role
                    navController.navigate(Routes.TRANSPORT)
                },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
            )
        }
        composable(Routes.TRANSPORT) {
            TransportScreen(viewModel, onTransportChosen = { kind ->
                viewModel.transportKind = kind
                navController.navigate(if (kind == TransportKind.WIFI) Routes.WIFI else Routes.USB)
            })
        }
        composable(Routes.WIFI) {
            WifiScreen(viewModel, onConnected = { navController.navigate(nextAfterConnect(viewModel)) })
        }
        composable(Routes.USB) {
            UsbScreen(viewModel, onConnected = { navController.navigate(nextAfterConnect(viewModel)) })
        }
        composable(Routes.CATEGORIES) {
            CategorySelectionScreen(
                viewModel,
                onStart = { navController.navigate(Routes.PROGRESS) },
                onPickApps = { navController.navigate(Routes.APP_PICKER) },
            )
        }
        composable(Routes.APP_PICKER) {
            AppPickerScreen(viewModel, onDone = { navController.popBackStack() })
        }
        composable(Routes.PROGRESS) {
            ProgressScreen(viewModel, onDone = { navController.navigate(Routes.SUMMARY) })
        }
        composable(Routes.APPS) {
            AppsListScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SUMMARY) {
            SummaryScreen(
                viewModel,
                onOpenApps = { navController.navigate(Routes.APPS) },
                onFinish = {
                    // When the app opened straight into a resumed transfer,
                    // HOME was never on the back stack — popping to it would
                    // silently do nothing and strand the user on the summary.
                    if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun nextAfterConnect(viewModel: TransferViewModel): String =
    if (viewModel.role == Role.SENDER) Routes.CATEGORIES else Routes.PROGRESS
