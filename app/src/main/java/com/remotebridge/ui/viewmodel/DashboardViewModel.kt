package com.remotebridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remotebridge.BridgeApplication
import com.remotebridge.controller.RemoteRepository
import com.remotebridge.core.model.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * نموذج عرض لوحة التحكم. يجلب معلومات الجهاز البعيد كملخص سريع.
 */
class DashboardViewModel(
    val repository: RemoteRepository
) : ViewModel() {

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** لقطة الموقع الحالية للمضيف (null = لا إذن/لا بيانات). */
    private val _locationSnapshot = MutableStateFlow<com.remotebridge.core.model.LocationSnapshot?>(null)
    val locationSnapshot = _locationSnapshot.asStateFlow()

    /** مسار الحركة (نقاط مرتبة زمنياً). */
    private val _locationTrail = MutableStateFlow<List<com.remotebridge.core.model.LocationPoint>>(emptyList())
    val locationTrail = _locationTrail.asStateFlow()

    private val _locationLoading = MutableStateFlow(false)
    val locationLoading = _locationLoading.asStateFlow()

    /** آخر لحظة "رأينا" فيها المضيف (millis) — تُحدّث عند نجاح جلب المعلومات. */
    private val _seenAtMs = MutableStateFlow<Long?>(null)
    val seenAtMs = _seenAtMs.asStateFlow()

    /**
     * يجلب معلومات الجهاز البعيد.
     */
    fun loadDeviceInfo() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _deviceInfo.value = repository.getDeviceInfo()
                _seenAtMs.value = System.currentTimeMillis()
            } catch (t: Throwable) {
                _error.value = t.message ?: "خطأ غير معروف"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * يجلب لقطة الموقع ومسار الحركة من المضيف (يُستدعى عند فتح الشاشة
     * وقبل عرض الخريطة).
     */
    fun loadLocation() {
        if (_locationLoading.value) return
        _locationLoading.value = true
        viewModelScope.launch {
            try {
                repository.getLocationSnapshot()?.let { _locationSnapshot.value = it }
                _locationTrail.value = repository.getLocationTrail()
            } catch (_: Throwable) {
                // الموقع اختياري: صمت عند الفشل (لا إذن، المضيف قديم...)
            } finally {
                _locationLoading.value = false
            }
        }
    }
}

/**
 * مصنع يعيد استخدام نفس RemoteRepository من ControllerViewModel.
 * ملاحظة: في تطبيق كامل يُفضّل استخدام حاوية DI (Hilt).
 */
object DashboardViewModelFactory {
    @Suppress("UNCHECKED_CAST")
    inline fun create(repository: RemoteRepository): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(repository) as T
            }
        }
}
