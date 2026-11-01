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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.ui.screens.controller.ControllerContent
import com.remotebridge.ui.viewmodel.ControllerViewModel
import com.remotebridge.ui.viewmodel.HostViewModel
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import com.remotebridge.host.service.HostDeviceAdminReceiver

/**
 * الواجهة الرئيسية للمضيف: الآن تحمل المضيف والمتحكم ولوحة الويب المدمجة
 * في نفس الشاشة عبر ثلاثة تبويبات، بحيث يمكن الدخول على واجهة ويب المتحكم
 * من واجهة المضيف مباشرة إن تعذّر فتحها من جهاز آخر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit,
    onWebDashboardClick: () -> Unit,
    onConnectedToHost: () -> Unit,
    viewModel: HostViewModel = viewModel(factory = HostViewModel.Factory)
) {    // نموذج عرض المتحكم بنطاق حياة الشاشة (اتصاله يبقى عند تبديل التبويبات)
    val controllerViewModel: ControllerViewModel = viewModel(factory = ControllerViewModel.Factory)
    var tabIndex by remember { mutableStateOf(0) }

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
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("🖥 المضيف") }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("📱 المتحكم") }
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = { Text("🌐 لوحة الويب") }
                )
            }

            when (tabIndex) {
                0 -> HostMainTab(
                    viewModel = viewModel,
                    onSettingsClick = onSettingsClick
                )
                1 -> HostControllerTab(
                    controllerViewModel = controllerViewModel,
                    onConnectedToHost = onConnectedToHost
                )
                else -> HostWebTab(onOpen = onWebDashboardClick)
            }
        }
    }
}

/** تبويب المضيف: حالة الخدمة، الأزرار، ومركز الصلاحيات. */
@Composable
private fun HostMainTab(
    viewModel: HostViewModel,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val isRunning by viewModel.serviceRunning.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val stunServer by viewModel.stunServer.collectAsState()
    val connectionProfile by viewModel.connectionProfile.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()

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

    // طالب إذن الموقع: عند المنح نعيد بدء التتبع فوراً إن كانت الخدمة تعمل
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                com.remotebridge.host.service.HostForegroundService.restartLocationTracking()
            } catch (_: Throwable) {
            }
        }
        permissionTick++
    }

    val generalPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
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
    var hasLocationPerm: Boolean by remember(permissionTick) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        // بطاقة تعديل بيانات الاتصال الافتراضية (الخادم/STUN/الاسم/نمط النقل)
        HostConnectionDataCard(viewModel = viewModel)

        Text(
            text = "مركز الصلاحيات والأذونات (Android 11+)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // بطاقة أذونات البيانات
        PermissionItemCard(
            icon = Icons.Default.Security,
            title = stringResource(R.string.grant_permissions),
            description = stringResource(R.string.permissions_desc),
            isGranted = hasDataPerms,
            onClick = { dataPermissionsLauncher.launch(generalPermissions) }
        )

        // بطاقة أذونات الكاميرا والميكروفون
        PermissionItemCard(
            icon = Icons.Default.CameraAlt,
            title = stringResource(R.string.grant_camera_mic),
            description = stringResource(R.string.camera_mic_desc),
            isGranted = hasCameraPerm && hasMicPerm,
            onClick = { cameraMicLauncher.launch(cameraMicPermissions) }
        )

        // بطاقة إذن الموقع: تفعيل تتبع حركة المضيف وخط السير على الخريطة
        PermissionItemCard(
            icon = Icons.Default.LocationOn,
            title = "الموقع وخط السير",
            description = "منح إذن الموقع يُفعّل تتبع حركة المضيف وعرض نقطة البداية والنهاية ومسار السير على خريطة حقيقية (أقمار صناعية وشوارع) عند اتصال المتحكم.",
            isGranted = hasLocationPerm,
            onClick = {
                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                hasLocationPerm = false // حتى يعيد التحقق بعد نتيجة النافذة
            }
        )

        // بطاقة العمل بالخلفية وتجاوز تحسين البطارية
        PermissionItemCard(
            icon = Icons.Default.BatteryChargingFull,
            title = stringResource(R.string.grant_battery_opt),
            description = stringResource(R.string.battery_opt_desc),
            isGranted = isIgnoringBattery,
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        )

        // بطاقة الوصول لكامل ذاكرة الجهاز والملفات
        PermissionItemCard(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.grant_all_files),
            description = stringResource(R.string.all_files_desc),
            isGranted = hasAllFiles,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    dataPermissionsLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            }
        )

        // بطاقة الوصول للإشعارات
        PermissionItemCard(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.grant_notification_access),
            description = stringResource(R.string.grant_notification_access_desc),
            isGranted = false,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        // بطاقة صلاحية تجاوز إعدادات النظام (WRITE_SETTINGS)
        PermissionItemCard(
            icon = Icons.Default.Settings,
            title = "تجاوز إعدادات النظام (WRITE_SETTINGS)",
            description = "يجيز تعديل إعدادات النظام على هذا الهاتف برمجياً (السطوع ومواضيع النظام العامة) من طرف المتحكم الموثوق.",
            isGranted = Settings.System.canWrite(context),
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
                } catch (_: Throwable) {
                }
                permissionTick++
            }
        )

        // بطاقة مدير الجهاز: قفل الشاشة وفرض السياسات (بديل الإطفاء بلا جذر)
        val adminActive = remember(permissionTick) {
            HostDeviceAdminReceiver.isActive(context)
        }
        PermissionItemCard(
            icon = Icons.Default.AdminPanelSettings,
            title = "مدير الجهاز (قفل الشاشة والسياسات)",
            description = "تفعيل «مشرف الجهاز» يجيز قفل الشاشة عن بُعد وفرض سياسات كلمة المرور — هو أيضاً بديل الإطفاء حين لا يتوفر جذر.",
            isGranted = adminActive,
            onClick = {
                try {
                    context.startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(
                                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                ComponentName(context, HostDeviceAdminReceiver::class.java)
                            )
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "للاستخدام الشخصي الموثوق: قفل الشاشة عن بُعد وفرض سياسات كلمة المرور على هذا الهاتف."
                            )
                        }
                    )
                } catch (_: Throwable) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    } catch (_: Throwable) {
                    }
                }
                permissionTick++
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onSettingsClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings))
        }
    }
}

@Composable
private fun HostControllerTab(
    controllerViewModel: ControllerViewModel,
    onConnectedToHost: () -> Unit
) {
    ControllerContent(
        onConnected = onConnectedToHost,
        onDisconnectClick = { controllerViewModel.disconnect() },
        viewModel = controllerViewModel
    )
}

/** تبويب لوحة الويب: زر يشغل واجهة ويب المتحكم داخل التطبيق (WebView). */
@Composable
private fun HostWebTab(
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = stringResource(R.string.mode_web_controller),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.mode_web_controller_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "📁 تُحفظ الملفات المنزّلة من لوحة الويب في مجلد التحميلات المختار من إعدادات التطبيق، وإن لم يحدد فهي في مجلد خاص للتطبيق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Icon(imageVector = Icons.Default.Language, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "فتح لوحة الويب المدمجة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionItemCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * بطاقة تعديل بيانات الاتصال الافتراضية من واجهة المضيف مباشرة:
 * عنوان خادم الإشارات، خوادم STUN (أو أكثر مفصولة بفواصل)، اسم الجهاز،
 * ونمط نقل البيانات — بحفظ فوري في DataStore وبدّئ جديد للخدمة يطبقها.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostConnectionDataCard(viewModel: HostViewModel) {
    val serverUrlValue by viewModel.serverUrl.collectAsState()
    val stunValue by viewModel.stunServer.collectAsState()
    val deviceNameValue by viewModel.deviceName.collectAsState()
    val profileValue by viewModel.connectionProfile.collectAsState()

    var serverText by remember(serverUrlValue) { mutableStateOf(serverUrlValue) }
    var stunText by remember(stunValue) { mutableStateOf(stunValue) }
    var nameText by remember(deviceNameValue) { mutableStateOf(deviceNameValue) }
    var selectedProfile by remember(profileValue) { mutableStateOf(profileValue.ifBlank { "HYBRID" }) }
    var savedIndicator = remember { mutableStateOf(false) }

    val profileOptions = listOf(
        "HYBRID" to "هجين: P2P + Render (موصى به)",
        "RENDER_ONLY" to "خادم Render فقط",
        "FLY_ONLY" to "خادم Fly.io فقط",
        "LOCAL_LAN" to "شبكة محلية (LAN) فقط"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = "بيانات الاتصال",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = "بيانات الاتصال الافتراضية (قابلة للتعديل)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "عدّل عنوان الخادم أو خوادم STUN أو اسم الجهاز هنا، ثم اضغط حفظ — أعد تشغيل خدمة المضيف لتطبيق التغييرات.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = serverText,
                onValueChange = { serverText = it },
                label = { Text("عنوان خادم الإشارات (WebSocket)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = stunText,
                onValueChange = { stunText = it },
                label = { Text("خوادم STUN (فاصلة بين عدة خوادم)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("اسم هذا الجهاز الظاهر للواجهات") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "نمط نقل البيانات (Profile):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profileOptions.take(2).forEach { (value, label) ->
                    FilterChip(
                        selected = selectedProfile == value,
                        onClick = { selectedProfile = value },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profileOptions.drop(2).forEach { (value, label) ->
                    FilterChip(
                        selected = selectedProfile == value,
                        onClick = { selectedProfile = value },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.saveConnectionData(serverText, stunText, nameText, selectedProfile)
                    savedIndicator.value = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("حفظ بيانات الاتصال", fontWeight = FontWeight.Bold)
            }

            if (savedIndicator.value) {
                Text(
                    text = "✔ تم الحفظ — أعد تشغيل خدمة المضيف لتطبيق البيانات الجديدة",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
