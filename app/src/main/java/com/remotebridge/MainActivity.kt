package com.remotebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.remotebridge.ui.navigation.BridgeNavHost
import com.remotebridge.ui.theme.BridgeTheme
import com.remotebridge.ui.theme.UiThemePrefs
import com.remotebridge.core.data.AppModePrefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // B4 — استعادة نمط العرض المحفوظ (داكن/فاتح/النظام) قبل التركيب
        UiThemePrefs.init(this)
        AppModePrefs.init(this)
        setContent {
            // B4 — نمط العرض يُتحكَّم به من قسم «العرض والواجهة» في الإعدادات
            val themeMode by UiThemePrefs.mode.collectAsState()
            BridgeTheme(
                darkTheme = when (themeMode) {
                    UiThemePrefs.MODE_DARK -> true
                    UiThemePrefs.MODE_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BridgeNavHost()
                }
            }
        }
    }
}
