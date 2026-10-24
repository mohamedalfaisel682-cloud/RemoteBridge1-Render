package com.remotebridge.ui.screens.files

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbstudio.R
import com.remotebridge.core.model.Buckets
import com.remotebridge.core.model.FileItem
import com.remotebridge.ui.viewmodel.FileListViewModel
import com.remotebridge.ui.viewmodel.RepositoryHolder

/**
 * شاشة عرض ملفات الجهاز البعيد مصنّفة حسب الصندوق.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    bucket: String,
    onBack: () -> Unit,
    viewModel: FileListViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return FileListViewModel(RepositoryHolder.repository) as T
            }
        }
    )
) {
    val files by viewModel.files.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(bucket) {
        viewModel.loadFiles(bucket)
    }

    val title = when (bucket) {
        Buckets.IMAGES -> stringResource(R.string.bucket_images)
        Buckets.VIDEOS -> stringResource(R.string.bucket_videos)
        Buckets.AUDIO -> stringResource(R.string.bucket_audio)
        else -> stringResource(R.string.bucket_downloads)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .padding(horizontal = 16.dp)
        ) {
            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(stringResource(R.string.loading))
                    }
                }

                error != null -> {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                files.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.no_files),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files, key = { it.id }) { file ->
                            FileCard(file)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: FileItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when {
                    file.mimeType.startsWith("image") -> Icons.Default.Image
                    file.mimeType.startsWith("video") -> Icons.Default.VideoFile
                    file.mimeType.startsWith("audio") -> Icons.Default.MusicNote
                    else -> Icons.Default.Download
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = formatBytes(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { /* تنزيل الملف */ }) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.save_file)
                )
            }
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
