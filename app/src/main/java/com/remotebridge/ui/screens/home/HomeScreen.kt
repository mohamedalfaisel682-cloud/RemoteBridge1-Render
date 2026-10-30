package com.remotebridge.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rbstudio.R

/**
 * الشاشة الرئيسية لنظام "جسر عن بعد": واجهة أنيقة، عصرية ومصممة بدقة لشاشات الهواتف بلمسات زجاجية وألوان Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onHostClick: () -> Unit,
    onControllerClick: () -> Unit,
    onWebDashboardClick: () -> Unit
) {
    // خيار «تفعيل الوصول البعيد»: عند تفعيله، أيقونة «التحكم المباشر» تفتح واجهة المتحكم (لوحة الويب).
    // قبل تفعيله تظهر رسالة توجيهية إلى خيار الإعدادات. (مطفأ افتراضياً)
    val context = androidx.compose.ui.platform.LocalContext.current
    var remoteUiEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            remoteUiEnabled = com.remotebridge.core.data.SettingsRepository(context)
                .remoteUiAccess
                .first()
        } catch (_: Throwable) {
            remoteUiEnabled = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 20.sp
                            )
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Banner Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "PRO ENTERPRISE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Text(
                            text = "WebRTC P2P Bridge",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        text = "ربط مباشر، استكشاف الذاكرة، بث الكاميرا والصوت، وإدارة الرسائل والمكالمات بسرعة فائقة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Text(
                text = "اختر وضع التشغيل المفضل",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )

            // بطاقة الجهاز المضيف (الهاتف البعيد)
            ModernRoleCard(
                icon = Icons.Default.Smartphone,
                badgeText = "هاتف المضيف",
                title = stringResource(R.string.mode_host),
                description = stringResource(R.string.mode_host_desc),
                accentColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                onClick = onHostClick
            )

            // بطاقة جهاز التحكم الأصلي (Native Controller)
            ModernRoleCard(
                icon = Icons.Default.Computer,
                badgeText = "التحكم المباشر",
                title = stringResource(R.string.mode_controller),
                description = stringResource(R.string.mode_controller_desc),
                accentColor = MaterialTheme.colorScheme.secondary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                onClick = {
                    if (remoteUiEnabled) {
                        // خيار «تفعيل الوصول البعيد» مفعّل: فتح واجهة المتحكم (لوحة الويب) مباشرة
                        onWebDashboardClick()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "الوصول البعيد غير مفعّل — فعّل خيار «تفعيل الوصول البعيد» من الإعدادات أولاً",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )

            // بطاقة لوحة الويب المدمجة (فتح واجهة ويب المتحكم من داخل التطبيق)
            ModernRoleCard(
                icon = Icons.Default.Language,
                badgeText = "واجهة ويب المتحكم",
                title = stringResource(R.string.mode_web_controller),
                description = stringResource(R.string.mode_web_controller_desc),
                accentColor = MaterialTheme.colorScheme.tertiary,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                onClick = onWebDashboardClick
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModernRoleCard(
    icon: ImageVector,
    badgeText: String,
    title: String,
    description: String,
    accentColor: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(22.dp)),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = accentColor
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = accentColor
                )
            }
        }
    }
}
