package com.remotebridge.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
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
     * يبدأ خدمة المضيف الأمامية.
     *
     * 🔧 إصلاح عدم استجابة زر بدء المضيف: لم نعد نمنع البدء بصمت عند نقص الأذونات.
     * الخدمة تبدأ دائماً من أول نقرة، والنقص يُعرض للمستخدم في تحذير واضح داخل الشاشة
     * مع إشعار Toast فوري — فكل نقرة تُحسّ دائماً حتى لو كانت أذونات البيانات ناقصة،
     * إذ تعاد الميزات المرتبطة بأذونات ناقصة بأخطاء لطيفة بدل انهيار التطبيق.
     */
    fun startHost() {
        viewModelScope.launch {
            // نحصي الأذونات الناقصة فقط للعرض والتحذير — لا نستخدمها كحاجز صامت
            val missing = HostForegroundService.getMissingPermissions(getApplication())
            _missingPermissions.value = missing
            _permissionsChecked.value = missing.isEmpty()

            val url = serverUrl.value.ifBlank { SettingsRepository.DEFAULT_RENDER_URL }
            val name = deviceName.value.ifBlank { "جهاز مضيف" }
            val intent = Intent(getApplication(), HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_START
                putExtra(HostForegroundService.EXTRA_SERVER_URL, url)
                putExtra(HostForegroundService.EXTRA_DEVICE_NAME, name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplication<Application>().startForegroundService(intent)
                } else {
                    getApplication<Application>().startService(intent)
                }
                _serviceRunning.value = true
            } catch (t: Throwable) {
                Log.e("HostViewModel", "فشل إطلاق خدمة المضيف", t)
                _serviceRunning.value = false
                Toast.makeText(
                    getApplication(),
                    "⚠️ تعذر بدء خدمة المضيف: ${t.message ?: "خطأ غير معروف"}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // إشعار مرئي فوري بنتيجة النقرة لكل الأذونات الممكنة
            if (missing.isEmpty()) {
                Toast.makeText(
                    getApplication(),
                    "✅ بدأ المضيف بنجاح — كل الأذونات مكتملة",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    getApplication(),
                    "✅ بدأ المضيف — لكن هناك ${missing.size} أذونات ناقصة، اسمح بها من بطاقات الصلاحيات بالأسفل",
                    Toast.LENGTH_LONG
                ).show()
            }
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

    /**
     * يوقف خدمة المضيف.
     */
    fun stopHost() {
        HostForegroundService.stopCamera()
        try {
            val intent = Intent(getApplication(), HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_STOP
            }
            getApplication<Application>().startService(intent)
        } catch (t: Throwable) {
            // احتياط مباشر في حال رفض النظام startService خارج نافذة الإطلاق
            try {
                getApplication<Application>().stopService(
                    Intent(getApplication(), HostForegroundService::class.java)
                )
            } catch (_: Throwable) { }
        }
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
