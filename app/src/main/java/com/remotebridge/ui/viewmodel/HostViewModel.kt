package com.remotebridge.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remotebridge.BridgeApplication
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.host.service.HostForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * نموذج عرض شاشة المضيف. يتحكم في بدء/إيقاف الخدمة الأمامية.
 */
class HostViewModel(
    application: Application,
    private val settings: SettingsRepository
) : AndroidViewModel(application) {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _permissionsChecked = MutableStateFlow(false)
    val permissionsChecked: StateFlow<Boolean> = _permissionsChecked.asStateFlow()

    private val _missingPermissions = MutableStateFlow(emptyList<String>())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()

    val serverUrl: StateFlow<String> = settings.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val stunServer: StateFlow<String> = settings.stunServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "stun:stun.l.google.com:19302")

    val connectionProfile: StateFlow<String> = settings.connectionProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "HYBRID")

    val deviceName: StateFlow<String> = settings.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val deviceId: StateFlow<String> = kotlinx.coroutines.flow.flow {
        emit(com.remotebridge.host.service.DeviceIdentifier.getOrGenerate(getApplication()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * يبدأ خدمة المضيف الأمامية بعد التحقق من الأذونات.
     */
    fun startHost() {
        viewModelScope.launch {
            // التحقق من الأذونات قبل بدء الخدمة
            val missing = HostForegroundService.getMissingPermissions(getApplication())
            _missingPermissions.value = missing

            if (missing.isNotEmpty()) {
                Log.w("HostViewModel", "أذونات ناقصة: ${missing.joinToString(",")}")
                _permissionsChecked.value = false
                return@launch
            }

            _permissionsChecked.value = true

            val url = serverUrl.value.ifBlank { SettingsRepository.DEFAULT_RENDER_URL }
            val name = deviceName.value.ifBlank { "جهاز مضيف" }
            val intent = Intent(getApplication(), HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_START
                putExtra(HostForegroundService.EXTRA_SERVER_URL, url)
                putExtra(HostForegroundService.EXTRA_DEVICE_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
            _serviceRunning.value = true
        }
    }

    val cameraState = HostForegroundService.cameraStreamingState

    fun startCamera(front: Boolean, audio: Boolean = true) {
        HostForegroundService.startCamera(front, audio)
    }

    fun switchCamera() {
        HostForegroundService.switchCamera()
    }

    fun toggleAudio(enable: Boolean) {
        HostForegroundService.toggleAudio(enable)
    }

    fun stopCamera() {
        HostForegroundService.stopCamera()
    }

    /** حفظ بيانات الاتصال الافتراضية من واجهة المضيف (الخادم وSTUN والاسم ونمط النقل). */
    fun saveConnectionData(url: String, stun: String, name: String, profile: String) {
        viewModelScope.launch {
            settings.setServerUrl(url.trim())
            settings.setStunServer(stun.trim())
            settings.setDeviceName(name.trim())
            settings.setConnectionProfile(profile.trim())
        }
    }

    /**
     * يوقف خدمة المضيف.
     */
    fun stopHost() {
        HostForegroundService.stopCamera()
        val intent = Intent(getApplication(), HostForegroundService::class.java).apply {
            action = HostForegroundService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _serviceRunning.value = false
    }

    companion object {
        // عنوان افتراضي للاختبار المحلي (يجب استبداله بخادمك)
        const val DEFAULT_LOCAL_SERVER = "ws://10.0.2.2:8080"

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = BridgeApplication.instance
                val repository = SettingsRepository(app)
                return HostViewModel(app, repository) as T
            }
        }
    }
}
