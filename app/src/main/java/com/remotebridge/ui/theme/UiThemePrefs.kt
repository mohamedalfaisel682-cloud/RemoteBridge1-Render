package com.remotebridge.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * B4 — تفضيلات العرض والواجهة.
 *
 * يخزن نمط العرض (system / dark / light) في SharedPreferences ليُطبق فوراً
 * من شاشة الإعدادات (قسم «العرض والواجهة») ويُعاد إلى حالته عند بدء التطبيق.
 */
object UiThemePrefs {

    private const val PREFS = "rb_ui_prefs"
    private const val KEY_MODE = "ui_theme_mode"

    private val _mode = MutableStateFlow(MODE_SYSTEM)
    val mode: StateFlow<String> = _mode.asStateFlow()

    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    fun init(context: Context) {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
        _mode.value = when (v) {
            MODE_DARK, MODE_LIGHT -> v
            else -> MODE_SYSTEM
        }
    }

    fun set(context: Context, value: String) {
        val safe = when (value) {
            MODE_DARK, MODE_LIGHT -> value
            else -> MODE_SYSTEM
        }
        _mode.value = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, safe).apply()
    }
}
