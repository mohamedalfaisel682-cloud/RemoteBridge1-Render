package com.remotebridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotebridge.controller.ControllerManager
import com.remotebridge.controller.RemoteRepository
import com.remotebridge.core.model.FileItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * نموذج عرض شاشة قائمة الملفات + تنزيل الملفات على الجهاز.
 */
class FileListViewModel(
    private val repository: RemoteRepository
) : ViewModel() {

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** تقدم التنزيل الحالي (اسم/حجم) أو null. */
    val downloadProgress = repository.manager.downloadProgress

    /**
     * يجلب قائمة الملفات من الجهاز البعيد.
     */
    fun loadFiles(bucket: String) {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _files.value = repository.getFiles(bucket)
            } catch (t: Throwable) {
                _error.value = t.message ?: "خطأ غير معروف"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * ينزّل ملفاً على الجهاز الحالي ويحفظه في مجلد التحميلات المختار.
     */
    fun downloadFile(file: FileItem, onDone: (ok: Boolean, label: String?, error: String?) -> Unit) {
        _error.value = null
        viewModelScope.launch {
            val result = repository.downloadFile(file.id, file.bucket, file.name)
            onDone(result.ok, result.fileLabel.ifBlank { null }, result.error)
        }
    }
}
