package com.remotebridge.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remotebridge.BridgeApplication
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * نموذج عرض الإعدادات. يقرأ ويحفظ الإعدادات من DataStore، ويتولى تسوية
 * مجلدات الحفظ (SAF) وخيارات الاستمرارية في الخلفية.
 */
class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    val serverUrl: StateFlow<String> = repository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val stunServer: StateFlow<String> = repository.stunServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val connectionProfile: StateFlow<String> = repository.connectionProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "HYBRID")

    val deviceName: StateFlow<String> = repository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val autoStartHost: StateFlow<Boolean> = repository.autoStartHost
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val restartOnKill: StateFlow<Boolean> = repository.restartOnKill
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val restartDelayMinutes: StateFlow<Int> = repository.restartDelayMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    /** إخفاء أيقونة التطبيق عند تشغيل المضيف (افتراضياً: نعم). */
    val hideIconWhenHostActive: StateFlow<Boolean> = repository.hideIconWhenHostActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** السماح بفتح واجهة المتحكم من أيقونة «التحكم المباشر» (افتراضياً: لا). */
    val remoteUiAccess: StateFlow<Boolean> = repository.remoteUiAccess
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val downloadsDirUri: StateFlow<String> = repository.downloadsDirUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val recordingsDirUri: StateFlow<String> = repository.recordingsDirUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * يحفظ الإعدادات تلقائياً في الخلفية.
     */
    fun saveSettings(serverUrl: String, deviceName: String, stunServer: String = "stun:stun.l.google.com:19302", profile: String = "HYBRID") {
        viewModelScope.launch {
            repository.setServerUrl(serverUrl)
            repository.setDeviceName(deviceName)
            repository.setStunServer(stunServer)
            repository.setConnectionProfile(profile)
        }
    }

    /**
     * يبحث عن خادم الإشارات المحلي تلقائياً وحفظه.
     */
    fun discoverLocalServer(onFound: (String) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch {
            val foundUrl = com.remotebridge.core.discovery.LocalDiscoveryManager.discoverLocalServer(BridgeApplication.instance)
            if (foundUrl != null) {
                repository.setServerUrl(foundUrl)
                onFound(foundUrl)
            } else {
                onNotFound()
            }
        }
    }

    /**
     * يبدّل حالة التشغيل التلقائي.
     */
    fun setAutoStartHost(autoStart: Boolean) {
        viewModelScope.launch {
            repository.setAutoStartHost(autoStart)
        }
    }

    /**
     * يبدّل خاصية إعادة التشغيل التلقائي بعد القتل/الانهيار.
     */
    fun setRestartOnKill(enabled: Boolean) {
        viewModelScope.launch {
            repository.setRestartOnKill(enabled)
        }
    }

    /**
     * يضبط مهلة إعادة التشغيل (1 / 10 / 60 / 1440 دقيقة).
     */
    fun setRestartDelayMinutes(minutes: Int) {
        viewModelScope.launch {
            repository.setRestartDelayMinutes(minutes)
        }
    }

    /**
     * يبدّل خيار إخفاء أيقونة التطبيق عند تشغيل المضيف.
     */
    fun setHideIconWhenHostActive(enabled: Boolean) {
        viewModelScope.launch {
            repository.setHideIconWhenHostActive(enabled)
        }
    }

    /** يبدّل خيار «تفعيل الوصول البعيد»: فتح واجهة المتحكم من أيقونة «التحكم المباشر». */
    fun setRemoteUiAccess(enabled: Boolean) {
        viewModelScope.launch {
            repository.setRemoteUiAccess(enabled)
        }
    }

    /**
     * يحفظ مجلد التحميلات (SAF). الصلاحية المستمرة تؤدى في شاشة الإعدادات
     * مباشرة عند اختيار المجلد.
     */
    fun setDownloadsDir(uri: Uri?) {
        viewModelScope.launch {
            repository.setDownloadsDirUri(uri?.toString() ?: "")
        }
    }

    /**
     * يحفظ مجلد التسجيلات (SAF). الصلاحية المستمرة تؤدى في شاشة الإعدادات
     * مباشرة عند اختيار المجلد.
     */
    fun setRecordingsDir(uri: Uri?) {
        viewModelScope.launch {
            repository.setRecordingsDirUri(uri?.toString() ?: "")
        }
    }

    /** اسم بشري لاختيار مجلد SAF لعرضه في الواجهة. */
    fun folderLabel(uriOrNil: String): String {
        if (uriOrNil.isBlank()) return ""
        return try {
            val context = BridgeApplication.instance
            val uri = Uri.parse(uriOrNil)
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SettingsRepository(BridgeApplication.instance)
                return SettingsViewModel(repository) as T
            }
        }
    }
}
