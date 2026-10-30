package com.remotebridge.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.host.service.ComponentStateManager
import com.remotebridge.host.service.DialCodeAccessibilityService
import com.remotebridge.ui.viewmodel.SettingsViewModel

/**
 * شاشة الإعدادات المتقدمة: ثلاث خيارات سريعة مصفوفة بصف واحد، بحث تلقائي عن الخادم المحلي، وحفظ تلقائي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val context = LocalContext.current
    val serverUrl by viewModel.serverUrl.collectAsState()
    val stunServer by viewModel.stunServer.collectAsState()
    val connectionProfile by viewModel.connectionProfile.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val autoStart by viewModel.autoStartHost.collectAsState()
    val restartOnKill by viewModel.restartOnKill.collectAsState()
    val restartDelayMinutes by viewModel.restartDelayMinutes.collectAsState()
    val hideIconWhenHostActive by viewModel.hideIconWhenHostActive.collectAsState()
    val remoteUiAccess by viewModel.remoteUiAccess.collectAsState()
    val downloadsDirUri by viewModel.downloadsDirUri.collectAsState()
    val recordingsDirUri by viewModel.recordingsDirUri.collectAsState()

    var urlInput by remember { mutableStateOf("") }
    var stunInput by remember { mutableStateOf("") }
    var profileInput by remember { mutableStateOf("HYBRID") }
    var nameInput by remember { mutableStateOf("") }
    var isSearchingLocal by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrl, stunServer, connectionProfile, deviceName) {
        urlInput = serverUrl.ifBlank { SettingsRepository.DEFAULT_RENDER_URL }
        stunInput = stunServer.ifBlank { SettingsRepository.DEFAULT_STUN_SERVER }
        profileInput = connectionProfile.ifBlank { "HYBRID" }
        nameInput = deviceName.ifBlank { SettingsRepository.DEFAULT_DEVICE_NAME }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
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
            // قسم خادم الإشارات
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_address),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            viewModel.saveSettings(it, nameInput)
                        },
                        placeholder = { Text("ws://<Local-IP>:10000/ws أو wss://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text(
                        text = "خيارات الربط السريعة (3 خيارات بصف واحد):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // الصف الواحد للاختيارات الثلاثة
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. خيار Render
                        AssistChip(
                            onClick = {
                                urlInput = SettingsRepository.DEFAULT_RENDER_URL
                                viewModel.saveSettings(urlInput, nameInput)
                                Toast.makeText(context, "تم اختيار Render السحابي تلقائياً", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("Render Cloud", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (urlInput.contains("render")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            )
                        )

                        // 2. خيار Fly.io
                        AssistChip(
                            onClick = {
                                urlInput = SettingsRepository.DEFAULT_FLY_URL
                                viewModel.saveSettings(urlInput, nameInput)
                                Toast.makeText(context, "تم اختيار Fly.io السحابي تلقائياً", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("Fly.io Cloud", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (urlInput.contains("fly")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            )
                        )

                        // 3. خيار محلي مع بحث تلقائي
                        AssistChip(
                            onClick = {
                                isSearchingLocal = true
                                viewModel.discoverLocalServer(
                                    onFound = { foundUrl ->
                                        isSearchingLocal = false
                                        urlInput = foundUrl
                                        Toast.makeText(context, "تم اكتشاف الخادم المحلي: $foundUrl", Toast.LENGTH_LONG).show()
                                    },
                                    onNotFound = {
                                        isSearchingLocal = false
                                        urlInput = SettingsRepository.DEFAULT_LOCAL_URL
                                        viewModel.saveSettings(urlInput, nameInput)
                                        Toast.makeText(context, "لم يتم العثور على سيرفر تلقائي، تم اختيار المنفذ المحلي 10000", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            label = {
                                if (isSearchingLocal) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                        Text("جاري الفحص...", style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    Text("محلي LAN/Port", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (urlInput.contains("10.0.2") || urlInput.contains("192.168")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            )
                        )
                    }

                    Text(
                        text = "ملاحظة: عند الاتصال المحلي بالشبكة يُكتب رقم المنفذPort تلقائياً بالصيغة: ws://<Local-IP>:10000/ws",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // قسم خادم الـ STUN الخاص بـ WebRTC
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🌐 خادم الـ STUN الخاص بـ WebRTC",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = stunInput,
                        onValueChange = {
                            stunInput = it
                            viewModel.saveSettings(urlInput, nameInput, it, profileInput)
                        },
                        placeholder = { Text("stun:stun.l.google.com:19302") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text(
                        text = "يُستخدم خادم Google STUN لاستكشاف عناوين الـ IP المناسبة لمنافذ UDP وتمكين قنوات البث الحي P2P عبر خادم ر_render.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // قسم بروفايلات ووسائط الاتصال (Connection Profiles)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "⚡ بروفايلات وأنماط نقل البيانات والاتصال",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "اختر النمط التشغيلي المناسب لنقل الرسائل والسجلات والبث المباشر والملفات:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1. HYBRID Profile
                        AssistChip(
                            onClick = {
                                profileInput = "HYBRID"
                                urlInput = SettingsRepository.DEFAULT_RENDER_URL
                                viewModel.saveSettings(urlInput, nameInput, stunInput, profileInput)
                                Toast.makeText(context, "تم اختيار النمط الهجين المتكامل (WebRTC P2P + Render)", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("1. هجين P2P + Render (الرسائل على Render والبث على WebRTC)", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (profileInput == "HYBRID") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 2. RENDER_ONLY Profile
                        AssistChip(
                            onClick = {
                                profileInput = "RENDER_ONLY"
                                urlInput = SettingsRepository.DEFAULT_RENDER_URL
                                viewModel.saveSettings(urlInput, nameInput, stunInput, profileInput)
                                Toast.makeText(context, "تم اختيار سيرفر Render فقط لجميع العمليات والبث", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("2. خادم Render فقط (للاتصال والإشارات والبث والبيانات معاً)", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (profileInput == "RENDER_ONLY") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 3. FLY_ONLY Profile
                        AssistChip(
                            onClick = {
                                profileInput = "FLY_ONLY"
                                urlInput = SettingsRepository.DEFAULT_FLY_URL
                                viewModel.saveSettings(urlInput, nameInput, stunInput, profileInput)
                                Toast.makeText(context, "تم اختيار سيرفر Fly.io فقط لجميع العمليات والبث", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("3. خادم Fly.io فقط (للاتصال والإشارات والبث والبيانات معاً)", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (profileInput == "FLY_ONLY") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 4. LOCAL_LAN Profile
                        AssistChip(
                            onClick = {
                                profileInput = "LOCAL_LAN"
                                urlInput = SettingsRepository.DEFAULT_LOCAL_URL
                                viewModel.saveSettings(urlInput, nameInput, stunInput, profileInput)
                                Toast.makeText(context, "تم اختيار الاتصال المحلي LAN عبر الشبكة الداخلية", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text("4. شبكة محلية LAN فقط (بدون إنترنت، اتصال مباشر داخلي)", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (profileInput == "LOCAL_LAN") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // قسم إعدادات اسم الجهاز
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.device_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            viewModel.saveSettings(urlInput, it)
                        },
                        placeholder = { Text(SettingsRepository.DEFAULT_DEVICE_NAME) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Smartphone, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // 📂 قسم مجلدات حفظ الملفات والتسجيلات
            val downloadsFolderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Throwable) {
                        // بعض أجهزة الرومات لا تمنحها — نقبل URI كما هو
                    }
                    viewModel.setDownloadsDir(uri)
                } // الإلغاء: لا نغيّر الاختيار الحالي
            }

            val recordingsFolderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Throwable) {
                    }
                    viewModel.setRecordingsDir(uri)
                } // الإلغاء: لا نغيّر الاختيار الحالي
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📁 مجلدات حفظ الملفات والمسجلات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "مثال مفيد: بيانات العرض والتنزيلات والحفظ التلقائي للتسجيلات تُكتب في مجلدك المختار، أو في مجلد خاص للتطبيق إن تركته فارغاً.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { downloadsFolderPicker.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("مجلد التحميلات", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { recordingsFolderPicker.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("مجلد التسجيلات", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text(
                        text = "المجلد المحدد للتحميلات: ${
                            downloadsDirUri.ifBlank { "مجلد خاص داخل التطبيق (افتراضي)" }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "المجلد المحدد للتسجيلات: ${
                            recordingsDirUri.ifBlank { "مجلد خاص داخل التطبيق (افتراضي)" }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ملاحظة: مجلد التحميلات هو مقر حفظ الملفات المنزّلة من لوحة الويب المتحكم؛ وتركه فارغاً يضمن الحفظ في مجلد خاص داخل التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // ⏱ قسم الاستمرارية في الخلفية (مكافحة الإيقاف)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "إعادة التشغيل التلقائي بعد التوقف",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "إذا أُغلقت الخدمة من النظام أو انهارت بسبب نقص الذاكرة، تُجرى إعادة تشغيلها تلقائياً بعد المهلة المحددة أدناه.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = restartOnKill,
                            onCheckedChange = { viewModel.setRestartOnKill(it) }
                        )
                    }

                    Text(
                        text = "مهلة إعادة التشغيل من لحظة الإيقاف:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            1 to "دقيقة",
                            10 to "10 دقائق",
                            60 to "ساعة",
                            1440 to "يوم"
                        ).forEach { (minutes, label) ->
                            AssistChip(
                                onClick = {
                                    viewModel.setRestartDelayMinutes(minutes)
                                    Toast.makeText(context, "مهلة الإعادة: $label", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (restartDelayMinutes == minutes)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }

                    // 🎭 إخفاء الأيقونة + كود الدخول + أدوات البطارية وتقييد الخلفية
                    var iconHidden by remember { mutableStateOf(ComponentStateManager.isLauncherHidden(context)) }
                    var batteryIgnored by remember {
                        mutableStateOf(
                            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                                ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                        )
                    }
                    var listenerGranted by remember {
                        mutableStateOf(
                            NotificationManagerCompat.getEnabledListenerPackages(context)
                                .contains(context.packageName)
                        )
                    }
                    var accessibilityGranted by remember {
                        mutableStateOf(DialCodeAccessibilityService.isEnabled(context))
                    }
                    fun refreshStatuses() {
                        iconHidden = ComponentStateManager.isLauncherHidden(context)
                        batteryIgnored = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                        listenerGranted = NotificationManagerCompat
                            .getEnabledListenerPackages(context).contains(context.packageName)
                        accessibilityGranted = DialCodeAccessibilityService.isEnabled(context)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "إخفاء أيقونة التطبيق عند تشغيل المضيف",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "عند تشغيل خدمة المضيف تُخفى أيقونة التطبيق من قائمة البرامج تلقائياً. الاسترجاع بكود الاتصال أدناه.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hideIconWhenHostActive,
                            onCheckedChange = {
                                viewModel.setHideIconWhenHostActive(it)
                                if (!it) {
                                    // منع حبس المستخدم: إيقاف الخيار يعيد الأيقونة فوراً
                                    ComponentStateManager.showLauncherIcon(context)
                                    Toast.makeText(context, "أُعيدت أيقونة التطبيق إلى القائمة", Toast.LENGTH_SHORT).show()
                                    refreshStatuses()
                                }
                            }
                        )
                    }

                    // خيار «تفعيل الوصول البعيد»: فتح واجهة المتحكم من أيقونة «التحكم المباشر» (مطفأ افتراضياً)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تفعيل الوصول البعيد",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "عند تفعيله: النقر على أيقونة «التحكم المباشر» يفتح واجهة المتحكم (لوحة الويب) داخل هذا الهاتف. يكون الخيار مطفأ افتراضياً لخصوصية هاتف المضيف.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = remoteUiAccess,
                            onCheckedChange = {
                                viewModel.setRemoteUiAccess(it)
                                Toast.makeText(
                                    context,
                                    if (it) "فعّل الوصول البعيد: أيقونة «التحكم المباشر» تفتح واجهة المتحكم الآن"
                                    else "طُفئ الوصول البعيد: أيقونة «التحكم المباشر» تعود لفتح شاشة المتحكم الأصلية",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (iconHidden) "الأيقونة الآن: مخفية" else "الأيقونة الآن: ظاهرة",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (iconHidden) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            enabled = iconHidden,
                            onClick = {
                                ComponentStateManager.showLauncherIcon(context)
                                Toast.makeText(context, "تم إظهار أيقونة التطبيق", Toast.LENGTH_SHORT).show()
                                refreshStatuses()
                            }
                        ) {
                            Text("إظهار الآن", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text(
                        text = "كود الدخول من لوحة مفاتيح الاتصال: الصيغة النظامية  *#*#82473#*#*  تعمل مباشرة بلا أي إعدادات، والصيغة الحرفية  *82473#  تحتاج تفعيل خدمة المراقبة أدناه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "مراقبة الكود الحرفي *82473#",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (accessibilityGranted) "✓ الخدمة مفعّلة (إمكانية الوصول)"
                                else "غير مفعّلة — يتطلب تفعيل إمكانية الوصول مرة واحدة",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accessibilityGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    Toast.makeText(context, "اختر RemoteBridge ثم فعّل خدمة المراقبة", Toast.LENGTH_LONG).show()
                                } catch (_: Throwable) {
                                }
                            }
                        ) {
                            Text("الانتقال", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تجاهل تحسينات البطارية",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (batteryIgnored) "✓ يُحفظ استمرار المضيف بلا تدخّل نظام البطارية"
                                else "غير مجدول — يُنصح بطلبه لكي لا يقيد النظام الخدمة على أندرويد 8 وما بعده",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (batteryIgnored) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(Uri.parse("package:${context.packageName}"))
                                    )
                                } catch (_: Throwable) {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        )
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (batteryIgnored) "مجدول بالفعل" else "طلب الآن",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.parse("package:${context.packageName}"))
                                    )
                                } catch (_: Throwable) {
                                }
                            }
                        ) {
                            Text("فحص قيد الخلفية", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !listenerGranted,
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                    )
                                } catch (_: Throwable) {
                                }
                            }
                        ) {
                            Text(
                                if (listenerGranted) "إشعاراته محظورة ✓" else "سلطة منع الإشعارات",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    AssistChip(
                        onClick = { refreshStatuses() },
                        label = {
                            Text("تحديث الحالة", style = MaterialTheme.typography.labelSmall)
                        }
                    )

                    Text(
                        text = "ملاحظة تقنية: الإيقاف القسري الكامل من إعدادات النظام (Force Stop) يلغي كل مهام WorkManager في أندرويد الحديث، ولا توجد آلية رسمية لإحياء التطبيق بعده. ما تغطيه هذه المنظومة: إزالة المهمة (swipe away)، إدارة الذاكرة، انهيارات الخدمة، تحديثات التطبيق، وإعادة إقلاع الجهاز (عند تفعيل التشغيل التلقائي).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // قسم التشغيل التلقائي
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_start_host),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "بدء تشغيل خدمة المضيف في الخلفية تلقائياً عند إقلاع الجهاز",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { viewModel.setAutoStartHost(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // زر الحفظ التأكيدي
            Button(
                onClick = {
                    viewModel.saveSettings(urlInput, nameInput)
                    Toast.makeText(context, "تم حفظ البيانات تلقائياً بنجاح!", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}
