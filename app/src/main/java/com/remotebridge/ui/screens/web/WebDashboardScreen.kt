package com.remotebridge.ui.screens.web

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.net.URLEncoder

/** عنوان أساس لوحة الويب المدمجة عبر WebViewAssetLoader. */
private const val DASHBOARD_URL =
    "https://appassets.androidplatform.net/assets/dashboard/index.html"

/**
 * لوحة الويب المدمجة: تعرض واجهة ويب المتحكم (web-dashboard) داخل التطبيق
 * عبر WebView مع `WebViewAssetLoader` (الأصول الرسمية عبر نطاق https محلي)،
 * وتمكّن حفظ الملفات المنزّلة على الجهاز عبر جسر [NativeFilesSaver].
 *
 * إعدادات التطبيق (عنوان خادمه واسم الجهاز) تُمرَّر للمتصفح عبر معاملات
 * URL كاقتراح أولي، فيعتمدها المتصفح المحلي ضمن localStorage عند أول
 * تشغيل فقط (لن يطغى على ما حفظه المستخدم بنفسه).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDashboardScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val webview = remember { WebView(appContext) }
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
    }

    // عنوان الخادم واسم الجهاز من إعدادات التطبيق (تُقرأ مرة عند فتح الشاشة)
    var dashboardUrl by remember { mutableStateOf(DASHBOARD_URL) }
    LaunchedEffect(Unit) {
        val repo = SettingsRepository(appContext)
        val serverUrl = repo.serverUrl.first()
        val deviceName = repo.deviceName.first()
        val deviceId = com.remotebridge.host.service.DeviceIdentifier.getOrGenerate(appContext)
        val query = buildString {
            append("?hostServerUrl=")
            append(URLEncoder.encode(serverUrl, "UTF-8"))
            append("&hostDeviceName=")
            append(URLEncoder.encode(deviceName, "UTF-8"))
            append("&hostDeviceId=")
            append(URLEncoder.encode(deviceId, "UTF-8"))
        }
        dashboardUrl = DASHBOARD_URL + query
        webview.loadUrl(dashboardUrl)
    }

    BackHandler(enabled = webview.canGoBack()) {
        webview.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.web_dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webview.reload() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reload_web_dashboard)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            factory = { ctx ->
                webview.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    addJavascriptInterface(
                        NativeFilesSaver(ctx.applicationContext),
                        "RBNative"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url
                            val isAssetHost =
                                (url.scheme == "https" &&
                                    url.host == "appassets.androidplatform.net")
                            if (!isAssetHost) {
                                // الروابط الخارجية تفتح في المتصفح المستقل
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
                                    return true
                                } catch (_: Throwable) {
                                    // لا يوجد متصفح؟ نتجاوز
                                }
                            }
                            return false
                        }
                    }
                }
            }
        )
    }
}
