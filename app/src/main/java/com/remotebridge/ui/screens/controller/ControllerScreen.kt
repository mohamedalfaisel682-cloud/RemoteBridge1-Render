package com.remotebridge.ui.screens.controller

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.controller.SavedSessionsStore
import com.remotebridge.core.model.HostInfo
import com.remotebridge.core.signaling.SignalingState
import com.remotebridge.core.webrtc.PeerState
import com.remotebridge.ui.viewmodel.ControllerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * محتوى وضع جهاز التحكم (بدون هيكل Scaffold) حتى يمكن إلصاصه كتبويب
 * داخل الواجهة الرئيسية للمضيف أو استخدامه شاشة مستقلة.
 */
@Composable
fun ControllerContent(
    onConnected: () -> Unit,
    onDisconnectClick: () -> Unit,
    viewModel: ControllerViewModel
) {
    val state by viewModel.state.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val lastSeenMap by viewModel.lastSeen.collectAsState()

    // مضيف نافذة الحالة المفتوحة (null = مغلقة)
    var statusHost by remember { mutableStateOf<HostInfo?>(null) }

    // اتصل بخادم الإشارات عند ظهور المحتوى
    LaunchedEffect(Unit) {
        viewModel.connect()
    }

    // انتقل للوحة التحكم عند اكتمال الاتصال المباشر
    LaunchedEffect(state.peerState) {
        if (state.peerState == PeerState.Connected) {
            onConnected()
        }
    }

    // الاتصال التلقائي بالمضيف المتاح دون الحاجة للنقر عليه:
    // إذا وُجد مضيف نشط ومتصل ولم نكن مرتبطين بشيء صالح، نرتبط تلقائياً
    // بأول مضيف متصل — وعند انقطاع المضيف الحالي ننتقل تلقائياً إلى المضيف التالي.
    LaunchedEffect(
        state.signalingState,
        state.connectedHost?.deviceId,
        hosts.map { it.deviceId to it.online }
    ) {
        if (state.signalingState != SignalingState.Connected) return@LaunchedEffect
        val onlineHosts = hosts.filter { it.online }
        if (onlineHosts.isEmpty()) return@LaunchedEffect
        val current = state.connectedHost
        val currentStillOnline = current != null && onlineHosts.any { it.deviceId == current.deviceId }
        if (currentStillOnline) return@LaunchedEffect
        // لا نكرر العروض أثناء محاولة اتصال جارية
        if (state.peerState == PeerState.Connecting) return@LaunchedEffect
        viewModel.connectToHost(onlineHosts.first())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // مؤشر حالة الاتصال
        ConnectionStatusCard(state.signalingState, state.peerState)

        // البث الحي عبر الترحيل (الوسيلة البديلة — تعمل دون WebRTC)
        LiveViewerCard(viewModel)

        // قائمة الأجهزة المضيفة
        Text(
            text = stringResource(R.string.available_hosts),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        when {
            state.signalingState != SignalingState.Connected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.scanning),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            hosts.isEmpty() -> {
                Text(
                    text = stringResource(R.string.no_hosts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(hosts, key = { it.deviceId }) { host ->
                        HostCard(
                            host = host,
                            isConnecting = state.peerState == PeerState.Connecting,
                            lastSeenMs = lastSeenMap[host.deviceId],
                            onConnect = { viewModel.connectToHost(host) },
                            onStatusClick = { statusHost = host }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onDisconnectClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.disconnect))
        }
    }

    // نافذة الحالة عند النقر على دائرة الحالة
    statusHost?.let { host ->
        LaunchedEffect(host.deviceId) {
            // نطلب من الخادم آخر ظهور متاحاً للجهاز (من ذاكرة رندر)
            try {
                viewModel.requestLastSeen()
            } catch (_: Throwable) {
            }
        }
        StatusDialog(
            host = host,
            lastSeenMs = lastSeenMap[host.deviceId],
            onDismiss = { statusHost = null }
        )
    }
}

/**
 * شاشة جهاز التحكم: تعرض الأجهزة المضيفة المتاحة وتبثر الاتصال بها.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ControllerViewModel = viewModel(factory = ControllerViewModel.Factory)
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.controller_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
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
            ControllerContent(
                onConnected = onConnected,
                onDisconnectClick = {
                    viewModel.disconnect()
                    onBack()
                },
                viewModel = viewModel
            )
        }
    }
}

/**
 * بطاقة البث الحي عبر الترحيل (الوسيلة البديلة): تختار جهازاً مضيفاً وتشغّل
 * بث الكاميرا (خلفية/أمامية) أو الشاشة، ويُعرض أحدث إطار JPEG وارد عبر
 * خادم الإشارات — تعمل على أي شبكة تصل فيها رسائل النظام (دون ICE/TURN).
 */
@Composable
private fun LiveViewerCard(viewModel: ControllerViewModel) {
    val hosts by viewModel.hosts.collectAsState()
    val frame by viewModel.liveFrame.collectAsState()

    // حالة التشغيل المحلية (تحدد شارة الحالة والنص)
    var liveOn by remember { mutableStateOf(false) }
    var selectedHost by remember { mutableStateOf<HostInfo?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "البث الحي عبر الخادم",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (liveOn) "نشط (ترحيل)" else "موقوف",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (liveOn) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // مساحة العرض الحي
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = frame
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "إطار البث الحي",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = if (liveOn) "بانتظار الإطارات من الهاتف المضيف..." else "لا يوجد بث نشط",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // اختيار الجهاز المضيف المطلوب بثّه
            val onlineHosts = hosts.filter { it.online }
            if (onlineHosts.isEmpty()) {
                Text(
                    text = "لا توجد أجهزة مضيفة متصلة الآن",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    onlineHosts.forEach { host ->
                        OutlinedButton(onClick = { selectedHost = host }) {
                            Text(
                                text = if (selectedHost?.deviceId == host.deviceId)
                                    "✓ ${host.deviceName}" else host.deviceName,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // أزرار التشغيل للجهاز المُختار
            val target = selectedHost ?: onlineHosts.firstOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        target?.let {
                            liveOn = true
                            viewModel.startLiveCamera(it.deviceId, front = false)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("كاميرا خلفية", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        target?.let {
                            liveOn = true
                            viewModel.startLiveCamera(it.deviceId, front = true)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("كاميرا أمامية", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        target?.let {
                            liveOn = true
                            viewModel.startLiveScreen(it.deviceId)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("الشاشة", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        target?.let { viewModel.switchLiveLens(it.deviceId) }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تبديل العدسة", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = {
                        target?.let {
                            viewModel.stopLive(it.deviceId)
                            viewModel.clearLiveView()
                        }
                        liveOn = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("إيقاف البث", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }

            Text(
                text = "وسيلة بديلة موثوقة: الإطارات تُرحَّل عبر خادم الإشارات نفسه، فتعمل عبر أي شبكة تمرر فيها رسائل النظام. بث الشاشة يحتاج تأكيد إذن MediaProjection على الهاتف المضيف (ضغطة واحدة لكل جلسة).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    signalingState: SignalingState,
    peerState: PeerState
) {    val (text, color) = when {
        peerState == PeerState.Connected ->
            stringResource(R.string.peer_connected) to MaterialTheme.colorScheme.primary
        peerState == PeerState.Connecting ->
            stringResource(R.string.peer_connecting) to MaterialTheme.colorScheme.tertiary
        peerState == PeerState.Failed ->
            stringResource(R.string.peer_failed) to MaterialTheme.colorScheme.error
        signalingState == SignalingState.Connected ->
            stringResource(R.string.state_connected) to MaterialTheme.colorScheme.primary
        signalingState == SignalingState.Connecting ->
            stringResource(R.string.state_connecting) to MaterialTheme.colorScheme.tertiary
        signalingState == SignalingState.Failed ->
            stringResource(R.string.state_failed) to MaterialTheme.colorScheme.error
        else ->
            stringResource(R.string.state_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (signalingState == SignalingState.Connected)
                    Icons.Default.Link else Icons.Default.LinkOff,
                contentDescription = null,
                tint = color
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = color
            )
        }
    }
}

/** ألوان دوائر حالة الأجهزة: أخضر متصل، برتقالي غير متصل مع بيانات سابقة، أحمر لا شيء معروف. */
private enum class StatusKind(val color: Color) {
    ONLINE(Color(0xFF22C55E)),
    SEEN_BEFORE(Color(0xFFF59E0B)),
    NEVER(Color(0xFFEF4444))
}

private fun statusKindOf(host: HostInfo): StatusKind = when {
    host.online -> StatusKind.ONLINE
    host.lastSeenMs != null -> StatusKind.SEEN_BEFORE
    else -> StatusKind.NEVER
}

@Composable
private fun HostCard(
    host: HostInfo,
    isConnecting: Boolean,
    lastSeenMs: Long?,
    onConnect: () -> Unit,
    onStatusClick: () -> Unit
) {
    val kind = statusKindOf(host)

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 16.dp, 16.dp, 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = host.deviceName,
                    style = MaterialTheme.typography.titleMedium
                )
                // دائرة الحالة فقط — دون أي نص حالة، والنقر عليها يفتح نافذة الحالة
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(kind.color, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onStatusClick
                        )
                )
            }

            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Button(onClick = onConnect) {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

/**
 * نافذة الحالة: آخر ظهور مسجّل في ذاكرة الخادم (رندر) وأيضاً محلياً،
 * وأيقونة لتصفح ملفات الجلسات المحفوظة تلقائياً في ملفات المتحكم.
 */
@Composable
private fun StatusDialog(
    host: HostInfo,
    lastSeenMs: Long?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var sessionFiles by remember { mutableStateOf<List<SavedSessionsStore.SavedFile>>(emptyList()) }

    // قراءة الملفات عند فتح النافذة
    LaunchedEffect(host.deviceId) {
        sessionFiles = try {
            SavedSessionsStore.list(context)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusKindOf(host).color, CircleShape)
                    )
                    Text(host.deviceName)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (host.online) "الحالة: متصل الآن" else "الحالة: غير متصل",
                    style = MaterialTheme.typography.bodyMedium
                )
                // أولوية للبيانات المرتجعة من ذاكرة الخادم، وإلا وقت القائمة المحلي
                val seen = lastSeenMs ?: host.lastSeenMs
                Text(
                    text = if (seen != null) {
                        "آخر ظهور: " + SimpleDateFormat(
                            "yyyy/MM/dd HH:mm:ss", Locale.getDefault()
                        ).format(Date(seen))
                    } else {
                        "آخر ظهور: لا توجد معلومات موثّقة بعد"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))

                // أيقونة تصفح البيانات المحفوظة (سجلات الجلسات على هذا الجهاز)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(onClick = {
                        sessionFiles = try {
                            SavedSessionsStore.list(context)
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "تصفح الملفات المحفوظة",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = if (sessionFiles.isEmpty()) {
                            "لا توجد ملفات جلسات محفوظة بعد"
                        } else {
                            sessionFiles.joinToString("\n") { f ->
                                val date = SimpleDateFormat(
                                    "yyyy/MM/dd HH:mm", Locale.getDefault()
                                ).format(Date(f.lastModifiedMs))
                                "• ${f.name} ($date)"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 5
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
