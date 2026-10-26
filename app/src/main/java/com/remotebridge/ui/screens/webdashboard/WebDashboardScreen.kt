package com.remotebridge.ui.screens.webdashboard

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import org.json.JSONObject

/**
 * 🔧 B11 — واجهة المتحكم الويب مدمجة داخل تطبيق الهاتف المضيف.
 * تفتح لوحة الويب (web-dashboard) بكامل الشاشة داخل WebView مع جسر واجهة
 * window.RBAndroid الذي يمكّن حفظ تنزيلات واجهة المتحكم في مجلد الهاتف
 * (Download/RBstudioPRO) لأن WebView لا يستطيع تنزيل عناصر blob: عبر مدير التنزيلات.
 */

/**
 * جسر حفظ ملفات واجهة المتحكم في مجلدات الهاتف (يُستدعى من JS باسم RBAndroid).
 */
class RbWebBridge(
    private val appContext: Context,
    private val deliver: (id: String, result: String) -> Unit
) {
    @JavascriptInterface
    fun saveFile(id: String, name: String, base64: String, mime: String) {
        val result = try {
            if (id.isBlank() || name.isBlank() || base64.isBlank()) {
                "err:معطيات الحفظ غير مكتملة"
            } else {
                val data = Base64.decode(base64, Base64.DEFAULT)
                writeSafe(appContext, name, data, mime)
            }
        } catch (t: Throwable) {
            "err:" + (t.message ?: "فشل الحفظ في مجلد الهاتف")
        }
        try {
            deliver(id, result)
        } catch (_: Throwable) {
        }
    }

    /**
     * كتابة ملف التنزيل: Android 10+ عبر MediaStore إلى Download/RBstudioPRO
     * (تظهر في التنزيلات العامة للهاتف)، وإقدم من ذلك مجلد ملفات التطبيق.
     */
    private fun writeSafe(context: Context, name: String, data: ByteArray, mime: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").ifBlank { "rb_file" }
        val targetMime = mime.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, targetMime)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/RBstudioPRO"
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return "err:تعذر إنشاء ملف التنزيل"
            val out = context.contentResolver.openOutputStream(uri)
                ?: return "err:تعذر فتح مجرى الكتابة"
            out.use { it.write(data) }
            return "Download/RBstudioPRO/" + safeName
        }
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return "err:مجلد ملفات التطبيق غير متوفر"
        val folder = java.io.File(baseDir, "RBstudioPRO").apply { mkdirs() }
        val f = java.io.File(folder, safeName)
        f.writeBytes(data)
        return f.absolutePath
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDashboardScreen(onBack: () -> Unit = {}) {
    val appContext = LocalContext.current.applicationContext
    val repo = remember(appContext) { SettingsRepository(appContext) }
    var dashboardUrl by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(repo) {
        repo.dashboardUrl.collect { dashboardUrl = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.web_dashboard_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (webViewRef != null) {
                        IconButton(onClick = { webViewRef?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.surface
        ) {
            if (dashboardUrl.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.web_dashboard_missing_url),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                DashboardWebView(
                    url = dashboardUrl,
                    onExit = onBack,
                    onWebViewReady = { webViewRef = it }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun DashboardWebView(
    url: String,
    onExit: () -> Unit,
    onWebViewReady: (WebView?) -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(webViewRef) { onWebViewReady(webViewRef) }

    BackHandler {
        val w = webViewRef
        if (w != null && w.canGoBack()) w.goBack() else onExit()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { androidContext ->
            val webView = WebView(androidContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // واجهة المتحكم تبقى داخل التطبيق (يقبل http/https فقط)
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "http" && scheme != "https"
                    }
                }
                addJavascriptInterface(
                    RbWebBridge(context) { id, result ->
                        post {
                            evaluateJavascript(
                                "try{var f=window.__rbSaves&&window.__rbSaves[" +
                                    JSONObject.quote(id) +
                                    "];if(f){f(" + JSONObject.quote(result) + ")}" +
                                    "delete window.__rbSaves[" + JSONObject.quote(id) + "];}catch(e){}",
                                null
                            )
                        }
                    },
                    "RBAndroid"
                )
                loadUrl(url)
            }
            webViewRef = webView
            webView
        },
        update = { w ->
            val fresh = w.url ?: ""
            if (url.isNotBlank() && fresh.isNotEmpty() && fresh.startsWith("http") && !fresh.startsWith(url)) {
                w.loadUrl(url)
            }
        }
    )
}
