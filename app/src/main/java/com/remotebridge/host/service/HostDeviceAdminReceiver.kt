package com.remotebridge.host.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * B19/B26 — متلقّي مدير الجهاز لجهاز المضيف (النسخة المدمجة).
 *
 * تمكينه يمنح التطبيق تنفيذ سياسات الإدارة ضمن `device_admin.xml`
 * (قفل الشاشة force-lock، سياسات كلمة المرور، إيقاف الكاميرا، المسح…)
 * ويُستخدم كبديل بلا جذر لأمر إطفاء/إغلاق الشاشة عن بُعد عبر
 * [HostForegroundService] → `com.remotebridge.host.capture.RemoteCommandsModule.powerCommand`.
 */
class HostDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "مدير الجهاز مُفعّل على المضيف — قفل الشاشة عن بُعد متاح")
        Toast.makeText(
            context,
            "فعّلنا مدير الجهاز لخدمة المضيف بنجاح ✓",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "مدير الجهاز عُطِّل على المضيف")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "إيقاف مدير الجهاز يفقد الهاتف إمكانات التحكم الكامل (قفل الشاشة من المتحكم وسياسات الحماية) — تواصل بعد التأكيد."
    }

    companion object {
        private const val TAG = "DeviceAdmin"

        /** هل مدير الجهاز مُفعّل حالياً لهذا التطبيق؟ */
        fun isActive(context: Context): Boolean {
            return try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(ComponentName(context, HostDeviceAdminReceiver::class.java))
            } catch (_: Throwable) {
                false
            }
        }

        /** قفل شاشة الجهاز فوراً إن كان المدير مُفعّلاً؛ يعيد نجاح التنفيذ. */
        fun lockScreenIfPossible(context: Context): Boolean {
            try {
                if (!isActive(context)) return false
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
                Log.i(TAG, "تم قفل الشاشة عبر مدير الجهاز عن بُعد")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "تعذر قفل الشاشة عبر مدير الجهاز: ${t.message}")
                return false
            }
        }
    }
}
