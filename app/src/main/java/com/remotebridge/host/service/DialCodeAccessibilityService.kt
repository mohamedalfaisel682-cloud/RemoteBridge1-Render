package com.remotebridge.host.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * مراقبة حقل الاتصال (لوحة مفاتيح تطبيق الهاتف) للتعرف على الكود الحرفي:
 *     *82473#
 * يفتح التطبيق فوراً ويظهر أيقونته. الكود الحرفي بهذه الصيغة يُرسل كطلب
 * USSD إلى الشبكة ولا يمكن لأي تطبيق عادٍ التقاطه ببثّ نظام، لذلك
 * هذه الخدمة هي المسار الثانوي (الأساسي: *#*#82473#*#* أو SecretCodeReceiver).
 *
 * يجب تفعيله مرة واحدة يدوياً من:
 * إعدادات النظام → إمكانية الوصول → RemoteBridge
 * زر الانتقال موجود في بطاقة الاستمرارية داخل إعدادات التطبيق.
 */
class DialCodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DialCodeAccessibility"
        private const val DIAL_PATTERN = "*${ComponentStateManager.DIAL_CODE}#"
        private const val MIN_LAUNCH_INTERVAL_MS = 3000L

        /** هل الخدمة مفعّلة إجمالاً في إعدادات النظام؟ (لعرض الحالة في الإعدادات) */
        fun isEnabled(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val flat = "${context.packageName}/${DialCodeAccessibilityService::class.qualifiedName}"
                enabled.split(':').any { it.equals(flat, ignoreCase = true) }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private var lastLaunchAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "مراقبة لوحة الاتصال مفعّلة لالتقاط $DIAL_PATTERN")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val raw = event.text?.firstOrNull()?.toString() ?: return
        val normalized = raw.replace(" ", "").trim()
        if (!normalized.contains(DIAL_PATTERN)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < MIN_LAUNCH_INTERVAL_MS) return
        lastLaunchAt = now

        Log.i(TAG, "تطابق الكود الحرفي في لوحة الاتصال — فتح التطبيق")
        ComponentStateManager.launchFromDialCode(this)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }
}
