package com.remotebridge.ui.viewmodel

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
 * نموذج عرض الإعدادات. يقرأ ويحفظ الإعدادات من DataStore.
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

    // B11 — حالة تفعيل واجهة الويب + عنوان لوحة الويب المشتق
    val webUiEnabled: StateFlow<Boolean> = repository.webUiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dashboardUrl: StateFlow<String> = repository.dashboardUrl
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
     * B11 — يبدّل حالة تفعيل واجهة الويب.
     */
    fun setWebUiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setWebUiEnabled(enabled)
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
