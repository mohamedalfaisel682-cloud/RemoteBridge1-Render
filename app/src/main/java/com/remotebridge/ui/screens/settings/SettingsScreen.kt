package com.remotebridge.ui.screens.settings

import android.widget.Toast
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.ui.theme.UiThemePrefs
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

            // 🎨 B4 — قسم العرض والواجهة: نمط العرض داكن/فاتح/النظام
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "🎨 العرض والواجهة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "اختر نمط العرض المفضل للتطبيق (داكن - فاتح - حسب النظام)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeMode by UiThemePrefs.mode.collectAsState()
                    val modes = listOf(
                        Triple(UiThemePrefs.MODE_SYSTEM, "🖥️ حسب النظام", themeMode == UiThemePrefs.MODE_SYSTEM),
                        Triple(UiThemePrefs.MODE_DARK, "🌙 داكن", themeMode == UiThemePrefs.MODE_DARK),
                        Triple(UiThemePrefs.MODE_LIGHT, "☀️ فاتح", themeMode == UiThemePrefs.MODE_LIGHT),
                    )
                    modes.forEach { (value, label, selected) ->
                        Card(
                            onClick = {
                                UiThemePrefs.set(context, value)
                                Toast.makeText(
                                    context,
                                    if (value == UiThemePrefs.MODE_LIGHT) "تم تفعيل النمط الفاتح ☀️"
                                    else if (value == UiThemePrefs.MODE_DARK) "تم تفعيل النمط الداكن 🌙"
                                    else "تم عودة الألوان حسب النظام 🖥️",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (selected) "$label ✓" else label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }

            // 🛡️ B4 — حالة أذونات الهاتف المضيف (منتقلة من شاشة المضيف إلى الإعدادات)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HostPermissionsSection()
                }
            }

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
