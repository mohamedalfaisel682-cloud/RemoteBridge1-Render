package com.remotebridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remotebridge.BridgeApplication
import com.remotebridge.controller.ControllerManager
import com.remotebridge.controller.RemoteRepository
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.core.model.HostInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * نموذج عرض شاشة جهاز التحكم.
 * يدير الاتصال بخادم الإشارات وقائمة الأجهزة المضيفة.
 */
class ControllerViewModel(
    private val settings: SettingsRepository
) : ViewModel() {

    val controllerManager = RepositoryHolder.repository.manager
    val remoteRepository = RepositoryHolder.repository

    val serverUrl: StateFlow<String> = settings.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val deviceName: StateFlow<String> = settings.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val state = controllerManager.state

    val hosts = controllerManager.hosts

    /** خريطة "آخر ظهور" (deviceId -> millis) لحساب الدوائر البرتقالية. */
    val lastSeen = controllerManager.lastSeen

    /** آخر إطار بث حي مرحّل (الوسيلة البديلة MJPEG-over-Relay) للعرض في الواجهة. */
    val liveFrame = controllerManager.liveFrame

    /** معلومات البث الحي المرحّل النشط (نوعه + مصدره). */
    val liveStreamInfo = controllerManager.liveStreamInfo

    /**
     * بدء بث الكاميرا الحي عبر الترحيل (يعمل على أي شبكة — بلا شرط ICE).
     * الرد يُستهلك هنا: نجاحه يظهَر في [liveFrame] لاحقاً وسيأتي بالإطارات.
     */
    fun startLiveCamera(hostId: String, front: Boolean, audio: Boolean = false) {
        controllerManager.sendRequestRelay(
            hostId = hostId,
            cmd = com.remotebridge.core.model.Commands.START_CAMERA_STREAM,
            params = mapOf(
                "front" to front.toString(),
                "audio" to audio.toString(),
                "mode" to "relay"
            )
        )
    }

    /** بث الشاشة الحي عبر الترحيل (يتطلب إذن MediaProjection على المضيف). */
    fun startLiveScreen(hostId: String) {
        controllerManager.sendRequestRelay(
            hostId = hostId,
            cmd = com.remotebridge.core.model.Commands.START_SCREEN_STREAM,
            params = mapOf("mode" to "relay")
        )
    }

    /** إيقاف البث الحي (يوقف مسار الترحيل ومسار WebRTC معاً في المضيف). */
    fun stopLive(hostId: String) {
        controllerManager.sendRequestRelay(
            hostId = hostId,
            cmd = com.remotebridge.core.model.Commands.STOP_CAMERA_STREAM
        )
    }

    /** تبديل عدسة الكاميرا في البث المرحّل. */
    fun switchLiveLens(hostId: String) {
        controllerManager.sendRequestRelay(
            hostId = hostId,
            cmd = com.remotebridge.core.model.Commands.SWITCH_CAMERA
        )
    }

    /** تفريغ عارض البث المرحّل (بعد الإيقاف). */
    fun clearLiveView() {
        controllerManager.clearLiveFrame()
    }

    /**
     * يستعلم الخادم عن "آخر ظهور" (يُستدعى عند فتح نافذة الحالة).
     */
    fun requestLastSeen() {
        controllerManager.requestLastSeen()
    }

    /**
     * يتصل بخادم الإشارات.
     */
    fun connect() {
        viewModelScope.launch {
            val url = serverUrl.value.ifBlank { HostViewModel.DEFAULT_LOCAL_SERVER }
            val name = deviceName.value.ifBlank { "جهاز تحكم" }
            controllerManager.connect(url, name)
        }
    }

    /**
     * يتصل بمضيف محدد.
     */
    fun connectToHost(host: HostInfo) {
        controllerManager.connectToHost(host)
    }

    /**
     * يقطع الاتصال.
     */
    fun disconnect() {
        controllerManager.disconnect()
    }

    override fun onCleared() {
        controllerManager.disconnect()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = SettingsRepository(BridgeApplication.instance)
                return ControllerViewModel(repository) as T
            }
        }
    }
}
