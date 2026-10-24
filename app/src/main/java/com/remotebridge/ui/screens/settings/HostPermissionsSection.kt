package com.remotebridge.ui.screens.settings

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.rbstudio.R

/**
 * B4 — قسم «حالة أذونات الهاتف المضيف» في شاشة الإعدادات.
 *
 * هذا القسم منتقل من شاشة المضيف (طلب المستخدم: نقل حالة الأذونات إلى الإعدادات)
 * ويحمل نفس مرئيات التأكيد الأخضر (إطار أخضر فاتح جداً + صح ✓ نابضة عند المنح)
 * وإعادة الفحص الآلي عند العودة من إعدادات النظام (ON_RESUME).
 */
@Composable
internal fun HostPermissionsSection() {
    val context = LocalContext.current
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
    val hasLocationPerm = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val hasNotificationListener = NotificationManagerCompat
        .getEnabledListenerPackages(context)
        .contains(context.packageName)

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    // ملخص علوي: X/6 ممنوحة
    val grantedCount = listOf(
        hasDataPerms, hasCameraPerm && hasMicPerm, isIgnoringBattery,
        hasAllFiles, hasNotificationListener, hasLocationPerm
    ).count { it }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "🛡️ حالة أذونات الهاتف المضيف (منتقلة من شاشة المضيف)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "ممنوحة $grantedCount من 6 — انقر أي بطاقة لمنح الإذن، والتأكيد الأخضر يظهر فوراً عند المنح",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // بطاقة الوصول للإشعارات (فحص حقيقي لحالة مستمع الإشعارات)
        PermissionItemCard(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.grant_notification_access),
            description = stringResource(R.string.grant_notification_access_desc),
            isGranted = hasNotificationListener,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        // بطاقة إذن الموقع (تتبع سجل الفيولي + الخريطة الحية في اللوحة)
        PermissionItemCard(
            icon = Icons.Default.LocationOn,
            title = stringResource(R.string.grant_location),
            description = stringResource(R.string.location_desc),
            isGranted = hasLocationPerm,
            onClick = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )
    }
}

// 🎨 لوحة تأكيد المنح: إطار أخضر فاتح فاتح جداً + خلفية خضراء فاتحة
// + علامة صح ✓ في دائرة خضراء تظهر بنبضة لطيفة لحظة المنح مباشرة.
internal val GrantedFrameVeryLightGreen = Color(0xFFB9F0C6)
internal val GrantedBackgroundExtremelyLightGreen = Color(0xFFF1F8E9)
internal val GrantedConfirmGreen = Color(0xFF4CAF50)
internal val GrantedTitleDeepGreen = Color(0xFF2E7D32)

@Composable
internal fun PermissionItemCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    // حركة انتقال ناعمة للإطار والخلفية لحظة منح الإذن مباشرة
    val frameColor by animateColorAsState(
        targetValue = if (isGranted) GrantedFrameVeryLightGreen
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "grantFrame"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isGranted) GrantedBackgroundExtremelyLightGreen
        else MaterialTheme.colorScheme.surface,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "grantBg"
    )
    // نبضة ظهور علامة الصح (قفزة زنبركية خفيفة)
    val checkScale by animateFloatAsState(
        targetValue = if (isGranted) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "grantCheck"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isGranted) 2.dp else 1.dp,
                color = frameColor,
                shape = MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor)
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
                tint = if (isGranted) GrantedConfirmGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isGranted) GrantedTitleDeepGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        if (isGranted) R.string.permission_granted else R.string.permission_pending
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isGranted) GrantedConfirmGreen else MaterialTheme.colorScheme.outline
                )
            }

            // علامة الصح للتأكيد داخل دائرة خضراء بنبضة عند المنح
            Box(
                modifier = Modifier
                    .size(if (isGranted) 26.dp else 22.dp)
                    .scale(checkScale)
                    .background(
                        color = if (isGranted) GrantedConfirmGreen else Color.Transparent,
                        shape = CircleShape
                    )
                    .border(
                        width = if (isGranted) 0.dp else 1.5.dp,
                        color = if (isGranted) Color.Transparent
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted && checkScale > 0.55f) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.permission_granted),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
