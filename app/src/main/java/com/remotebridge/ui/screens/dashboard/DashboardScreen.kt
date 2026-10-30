package com.remotebridge.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.core.model.Buckets
import com.remotebridge.core.model.LocationPoint
import com.remotebridge.ui.viewmodel.DashboardViewModel
import com.remotebridge.ui.viewmodel.DashboardViewModelFactory
import com.remotebridge.ui.viewmodel.RepositoryHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * عنصر قسم في لوحة التحكم.
 */
private data class CategoryItem(
    val icon: ImageVector,
    val title: String,
    val bucket: String? = null
)

/**
 * لوحة تحكم جهاز التحكم: تعرض أقسام بيانات الجهاز البعيد.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onFilesClick: (String) -> Unit,
    viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory.create(RepositoryHolder.repository)
    )
) {
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val locationSnapshot by viewModel.locationSnapshot.collectAsState()
    val locationTrail by viewModel.locationTrail.collectAsState()
    val seenAtMs by viewModel.seenAtMs.collectAsState()

    // مركز رؤية سابق محفوظ محلياً (لكي يعمل عدّاد "منذ آخر رؤية" حتى بعد
    // إغلاق التطبيق) + استمرار القيمة الجديدة عند نجاح الجلب.
    val context = LocalContext.current
    var persistedSeen by remember { mutableStateOf(0L) }
    var showMap by remember { mutableStateOf(false) }
    var showSystemInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        persistedSeen = SettingsRepository(context).dashboardLastSeen.first()
        viewModel.loadDeviceInfo()
        viewModel.loadLocation()
    }
    LaunchedEffect(seenAtMs) {
        val ms = seenAtMs ?: return@LaunchedEffect
        persistedSeen = ms
        try {
            SettingsRepository(context).setDashboardLastSeen(ms)
        } catch (_: Throwable) {
        }
    }

    val categories = listOf(
        CategoryItem(Icons.Default.Image, stringResource(R.string.bucket_images), Buckets.IMAGES),
        CategoryItem(Icons.Default.Devices, stringResource(R.string.bucket_videos), Buckets.VIDEOS),
        CategoryItem(Icons.Default.SdStorage, stringResource(R.string.bucket_audio), Buckets.AUDIO),
        CategoryItem(Icons.Default.Download, stringResource(R.string.bucket_downloads), Buckets.DOWNLOADS),
        CategoryItem(Icons.Default.Message, stringResource(R.string.category_messages)),
        CategoryItem(Icons.Default.ContactPhone, stringResource(R.string.category_contacts)),
        CategoryItem(Icons.Default.Notifications, stringResource(R.string.category_notifications)),
        CategoryItem(Icons.Default.Info, stringResource(R.string.category_device))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ملخص معلومات الجهاز
            item {
                val effectiveSeen = seenAtMs
                    ?: persistedSeen.takeIf { it > 0 }
                DeviceInfoCard(
                    deviceInfo = deviceInfo,
                    loading = loading,
                    seenAtMs = effectiveSeen,
                    locationSnapshot = locationSnapshot,
                    onClickMap = { showMap = true }
                )
            }

            // بطاقة نظام التشغيل والبطارية (تفتح من بطاقة «معلومات الجهاز»)
            if (showSystemInfo) {
                item {
                    SystemInfoCard(
                        deviceInfo = deviceInfo,
                        loading = loading
                    )
                }
            }

            // الأقسام
            items(categories) { category ->
                CategoryCard(
                    icon = category.icon,
                    title = category.title,
                    onClick = {
                        if (category.bucket != null) {
                            onFilesClick(category.bucket)
                        } else if (category.icon == Icons.Default.Info) {
                            // بطاقة «معلومات الجهاز» تفتح/تُغلق تفاصيل النظام والبطارية
                            showSystemInfo = !showSystemInfo
                        }
                    }
                )
            }
        }
    }

    if (showMap) {
        val trailJson = remember(locationTrail) {
            Json.encodeToString(
                ListSerializer(LocationPoint.serializer()),
                locationTrail
            )
        }
        LocationMapDialog(trailJson) { showMap = false }
    }
}

/**
 * بطاقة ملخص الجهاز مقسّمة إلى نصفين:
 * نصف (مستوى البطارية + عدّاد متصل "منذ آخر رؤية") والنصف الآخر نصف (الموقع)
 * النصف (الموقع) قابل للنقر لفتح خريطة حقيقية (أقمار صناعية وشوارع).
 */
@Composable
private fun DeviceInfoCard(
    deviceInfo: com.remotebridge.core.model.DeviceInfo?,
    loading: Boolean,
    seenAtMs: Long?,
    locationSnapshot: com.remotebridge.core.model.LocationSnapshot?,
    onClickMap: () -> Unit
) {
    // عدّاد يعمل كل ثانية لتحديث "الوقت المنقضي من آخر رؤية" بطريقة متصلة
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (loading || deviceInfo == null) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = "${deviceInfo.manufacturer} ${deviceInfo.model}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // —— التقسيم إلى نصفين: الموقع | منذ آخر رؤية ——
                // (سعة البطارية انتقلت إلى مساحة "معلومات الجهاز" أدناه لتوسيع
                // هذين النصفين وعرض بياناتهما بوضوح بالعين).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // نصف 1: الموقع (آخر موقع موسع + زر الخريطة الحقيقية)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "الموقع",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = coordinateText(locationSnapshot),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2
                        )
                        Text(
                            text = fixText(locationSnapshot),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2
                        )
                        OutlinedButton(
                            onClick = onClickMap,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp, vertical = 4.dp
                            )
                        ) {
                            Text("فتح الخريطة", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // نصف 2: منذ آخر رؤية (عدّاد متصّل كبير + اللحظة المطلقة)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "منذ آخر رؤية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (seenAtMs != null) formatElapsed(nowMs - seenAtMs) else "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (seenAtMs != null) {
                                "في: " + formatTime(seenAtMs)
                            } else "لا توجد معلومات موثّقة بعد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "يعمل العدّاد باستمرار منذ آخر لحظة وصول",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2
                        )
                    }
                }

                Text(
                    text = "أندرويد ${deviceInfo.androidVersion} (API ${deviceInfo.sdkInt})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "مساحة فارغة: ${
                        formatBytes(deviceInfo.storageFreeBytes)
                    } من ${formatBytes(deviceInfo.storageTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** إحداثيات بيّنة في نصف الموقع (سطران). */
private fun coordinateText(snapshot: com.remotebridge.core.model.LocationSnapshot?): String {
    val lat = snapshot?.lat
    val lng = snapshot?.lng
    if (lat == null || lng == null) return "لا بيانات موقع بعد"
    return String.format(java.util.Locale.US, "%.5f\n%.5f", lat, lng)
}

/** مصدر الإصلاح الحالي. */
private fun fixText(snapshot: com.remotebridge.core.model.LocationSnapshot?): String {
    if (snapshot == null) return "بانتظار أول إصلاح من المضيف"
    val source = snapshot.src ?: "gps"
    return String.format(java.util.Locale.US, "المصدر: %s • دقة: %s", source, "تقريبية")
}

/** توقيت مطلق للإضافة تحت عدّاد آخر رؤية. */
private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * بطاقة "معلومات الجهاز" (التبويب الذي تفتحه بطاقة «معلومات الجهاز» في
 * قائمة الأقسام): نظام التشغيل + التخزين + تفاصيل سعة البطارية مع أيقونة
 * «جاري الشحن» عندما يكون الشاحن متصلاً.
 */
@Composable
private fun SystemInfoCard(
    deviceInfo: com.remotebridge.core.model.DeviceInfo?,
    loading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "نظام التشغيل والبطارية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (loading || deviceInfo == null) {
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "أندرويد ${deviceInfo.androidVersion} (API ${deviceInfo.sdkInt})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "الجهاز: ${deviceInfo.manufacturer} ${deviceInfo.model}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "التخزين: ${formatBytes(deviceInfo.storageFreeBytes)} فارغة من ${formatBytes(deviceInfo.storageTotalBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // سعة البطارية + أيقونة جاري الشحن إذا كان الشاحن متصلاً
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "سعة البطارية: ${deviceInfo.batteryLevel}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (deviceInfo.isCharging) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = "جاري الشحن",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "جاري الشحن",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/** نص نصف الموقع داخل البطاقة (إحداثيات + مصدر الإصلاح). */

/** صياغة المدة المنقضية: أيام/ساعات/دقائق/ثوان. */
private fun formatElapsed(ms: Long): String {
    if (ms < 0) return "—"
    val totalSeconds = ms / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "$days يوم $hours س"
        hours > 0 -> "$hours س $minutes د"
        minutes > 0 -> "$minutes د $seconds ث"
        else -> "$seconds ث"
    }
}

/**
 * خريطة حقيقية داخل نافذة: WebView يحمل ملف assets/map/map.html
 * (Leaflet + صور طبقات الأقمار الصناعية والشوارع) ويعرض مسار السير
 * من نقطة البداية إلى نقطة النهاية.
 */
@Composable
private fun LocationMapDialog(
    trailJson: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "خريطة خط السير (قمر صناعي وشوارع)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                }
                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(
                                    view: android.webkit.WebView,
                                    url: String?
                                ) {
                                    view.evaluateJavascript(
                                        "window.applyTrail($trailJson)", null
                                    )
                                }
                            }
                            loadUrl("file:///android_asset/map/map.html")
                        }
                    },
                    update = { webView ->
                        if (webView.tag != trailJson) {
                            webView.tag = trailJson
                            webView.evaluateJavascript(
                                "window.applyTrail($trailJson)", null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * يحوّل البايتات إلى نص مقروء.
 */
private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.1f غيغابايت", gb)
        mb >= 1.0 -> String.format("%.1f ميغابايت", mb)
        kb >= 1.0 -> String.format("%.0f كيلوبايت", kb)
        else -> "$bytes بايت"
    }
}
