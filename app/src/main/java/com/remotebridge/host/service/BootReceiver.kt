package com.remotebridge.host.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * يعيد تشغيل خدمة المضيف بعد إقلاع الجهاز (إذا فعّل المستخدم التشغيل التلقائي).
 *
 * على أندرويد 8+ يجب استخدام startForegroundService ضمن نافذة زمنية محدودة،
 * لذا نشغل الخدمة فوراً ونتركها تعرض الإشعار الأمامي.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val serviceIntent = Intent(context, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_START
                putExtra(HostForegroundService.EXTRA_SERVER_URL, "")
                putExtra(HostForegroundService.EXTRA_DEVICE_NAME, Build.MODEL ?: "جهاز مضيف")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل إعادة تشغيل الخدمة بعد الإقلاع", t)
        }
    }
}
