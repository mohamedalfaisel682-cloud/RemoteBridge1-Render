package com.remotebridge.host.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * مستقبِل مدير الجهاز: يُفعّله المستخدم من واجهة المضيف بعد منحه نظاماً
 * «مشرف الجهاز» لهذا التطبيق. السياسات المعلنة (res/xml/device_admin.xml):
 * قفل الشاشة (force-lock) وسياسات كلمة المرور — بلا مسح بيانات.
 *
 * استخدامه الرئيسي: بديل بدون جذر لأمر إطفاء الشاشة عن بُعد (dpm.lockNow())
 * — راجِع [com.remotebridge.host.capture.RemoteCommandsModule.powerCommand].
 */
class HostDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "مدير الجهاز مُفعّل على المضيف — قفل الشاشة عن بُعد متاح")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "مدير الجهاز عُطِّل على المضيف")
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
