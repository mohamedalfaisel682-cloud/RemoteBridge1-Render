package com.remotebridge.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.core.model.Buckets
import com.remotebridge.ui.viewmodel.DashboardViewModel
import com.remotebridge.ui.viewmodel.DashboardViewModelFactory
import com.remotebridge.ui.viewmodel.RepositoryHolder

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

    LaunchedEffect(Unit) {
        viewModel.loadDeviceInfo()
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
                DeviceInfoCard(deviceInfo, loading)
            }

            // الأقسام
            items(categories) { category ->
                CategoryCard(
                    icon = category.icon,
                    title = category.title,
                    onClick = {
                        category.bucket?.let { onFilesClick(it) }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    deviceInfo: com.remotebridge.core.model.DeviceInfo?,
    loading: Boolean
) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${deviceInfo.batteryLevel}% • " +
                                if (deviceInfo.isCharging) "يشحن" else "بطارية",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
 * يحوّل البايتات إلى نص مقروء — B4: أرقام إنجليزية ثابتة بغض النظر عن لغة الجهاز.
 */
private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f غيغابايت", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f ميغابايت", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f كيلوبايت", kb)
        else -> "$bytes بايت"
    }
}
