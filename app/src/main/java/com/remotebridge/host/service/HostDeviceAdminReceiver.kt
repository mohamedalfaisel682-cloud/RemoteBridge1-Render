package com.remotebridge.host.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * B19 — متلقّي مدير الجهاز لجهاز المضيف.
 *
 * تمكينه يمنح التطبيق تنفيذ سياسات الإدارة ضمن `device_admin.xml`
 * (قفل الشاشة، تغيير قيود القفل، إيقاف الكاميرا، المسح عن بُعد)
 * ويخدم طلب التفويض من شاشة إعدادات المضيف.
 */
class HostDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "فعّلنا مدير الجهاز لخدمة المضيف بنجاح ✓",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "إيقاف مدير الجهاز يفقد الهاتف إمكانات التحكم الكامل (قفل الشاشة من المتحكم وسياسات الحماية) — تواصل بعد التأكيد."
    }
}
