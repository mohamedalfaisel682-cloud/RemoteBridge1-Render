package com.remotebridge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remotebridge.ui.screens.controller.ControllerScreen
import com.remotebridge.ui.screens.dashboard.DashboardScreen
import com.remotebridge.ui.screens.files.FileListScreen
import com.remotebridge.ui.screens.home.HomeScreen
import com.remotebridge.ui.screens.host.HostScreen
import com.remotebridge.ui.screens.settings.SettingsScreen
import com.remotebridge.ui.screens.web.WebDashboardScreen

/**
 * مسارات التنقل بين الشاشات.
 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HOST = "host"
    const val CONTROLLER = "controller"
    const val DASHBOARD = "dashboard"
    const val FILES = "files/{bucket}"
    const val WEB_DASHBOARD = "web_dashboard"

    /**
     * يبني مسار شاشة الملفات لصندوق محدد.
     */
    fun files(bucket: String): String = "files/$bucket"
}

@Composable
fun BridgeNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onHostClick = { navController.navigate(Routes.HOST) },
                onControllerClick = { navController.navigate(Routes.CONTROLLER) },
                onWebDashboardClick = { navController.navigate(Routes.WEB_DASHBOARD) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.HOST) {
            HostScreen(
                onBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onWebDashboardClick = { navController.navigate(Routes.WEB_DASHBOARD) },
                onConnectedToHost = { navController.navigate(Routes.DASHBOARD) }
            )
        }

        composable(Routes.WEB_DASHBOARD) {
            WebDashboardScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CONTROLLER) {
            ControllerScreen(
                onBack = { navController.popBackStack() },
                onConnected = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.CONTROLLER) { inclusive = true }
                    }
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onBack = { navController.popBackStack() },
                onFilesClick = { bucket -> navController.navigate(Routes.files(bucket)) }
            )
        }

        composable(Routes.FILES) { backStackEntry ->
            val bucket = backStackEntry.arguments?.getString("bucket") ?: "images"
            FileListScreen(
                bucket = bucket,
                onBack = { navController.popBackStack() }
            )
        }    }
}
