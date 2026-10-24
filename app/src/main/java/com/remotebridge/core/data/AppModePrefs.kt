package com.remotebridge.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B4 — آخر وضع مفتوح/نشط من القائمة الرئيسية (host / controller).
 * يُستخدم لتأثير التوهج النابض على البطاقة النشطة في شاشة البداية.
 */
object AppModePrefs {

    private const val PREFS = "rb_ui_prefs"
    private const val KEY_MODE = "last_active_mode"

    private val _mode = MutableStateFlow("")
    val mode: StateFlow<String> = _mode.asStateFlow()

    fun init(context: Context) {
        _mode.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, "") ?: ""
    }

    fun set(context: Context, value: String) {
        _mode.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, value).apply()
    }

    const val MODE_HOST = "host"
    const val MODE_CONTROLLER = "controller"
}
