package com.remotebridge.host.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * B19 — خدمة إمكانية الوصول لجهاز المضيف.
 *
 * الغرض: تجاوز حواجز النظام عند التحكم عن بُعد عبر تنفيذ الإجراءات العامة
 * (رجوع، الشاشة الرئيسة، فتح الإشعارات) ونقر مخصَّص بأي إحداثي للشاشة.
 * الخدمة ترصد الأحداث بهدوء، وتفرد أوامرها المباشرة إلى خدمة المضيف.
 */
class HostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HostAccSvc"

        @Volatile
        private var instance: HostAccessibilityService? = null

        /** هل الخدمة موصولة وفاعلة الآن؟ */
        fun isActive(): Boolean = instance != null

        /** تنفيذ إجراء نظام عام: `GLOBAL_ACTION_BACK` و `GLOBAL_ACTION_HOME` وما شابهه. */
        fun performAction(actionId: Int): Boolean {
            val current = instance ?: return false
            return try {
                current.performGlobalAction(actionId)
            } catch (t: Throwable) {
                Log.e(TAG, "تعذر تنفيذ إجراء النظام العام", t)
                false
            }
        }

        /** نقر مخصَّص في إحداثيّ مطلق للشاشة عبر `dispatchGesture`. */
        fun tap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
            val current = instance ?: return false
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            return try {
                current.dispatchGesture(gesture, null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "تعذر تنفيذ نقرة الشاشة", t)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "تم تسجيل خدمة إمكانية الوصول لجهاز المضيف بقوة")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // رصد الأحداث فقط في هذه الدفعة؛ التوجيه الفعلي من خدمة المضيف يُوَصَّل لاحقاً.
    }

    override fun onInterrupt() {
        // قطع مؤقت غير حرج للحدث الذي كان يعالجه.
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "فُكّت خدمة إمكانية الوصول لجهاز المضيف")
        super.onDestroy()
    }
}
