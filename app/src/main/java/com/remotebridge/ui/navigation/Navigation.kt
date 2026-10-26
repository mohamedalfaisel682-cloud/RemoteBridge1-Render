package com.remotebridge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.ui.screens.controller.ControllerScreen
import com.remotebridge.ui.screens.dashboard.DashboardScreen
import com.remotebridge.ui.screens.files.FileListScreen
import com.remotebridge.ui.screens.home.HomeScreen
import com.remotebridge.ui.screens.host.HostScreen
import com.remotebridge.ui.screens.settings.SettingsScreen
import com.remotebridge.ui.screens.webdashboard.WebDashboardScreen

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
    // B11 — لوحة الويب المدمجة داخل التطبيق
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
            // 🔧 B11 — قراءة حالة واجهة الويب وعنوان لوحة الويب المشتق من الإعدادات
            val context = LocalContext.current
            val settings = remember(context) { SettingsRepository(context.applicationContext) }
            val webUiEnabled by settings.webUiEnabled.collectAsState(initial = false)
            val dashUrl by settings.dashboardUrl.collectAsState(initial = "")
            HomeScreen(
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onHostClick = { navController.navigate(Routes.HOST) },
                webUiEnabled = webUiEnabled,
                onWebUiEmbeddedClick = { navController.navigate(Routes.WEB_DASHBOARD) },
                onWebUiBrowserClick = {
                    if (dashUrl.isBlank()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.webui_no_dashboard_url),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dashUrl)))
                        } catch (t: Throwable) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.webui_no_browser),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
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
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
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
        }

        // 🔧 B11 — واجهة المتحكم الويب المدمجة (WebView بكامل مساحة التطبيق)
        composable(Routes.WEB_DASHBOARD) {
            WebDashboardScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
