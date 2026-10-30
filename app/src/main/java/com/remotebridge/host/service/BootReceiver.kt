package com.remotebridge.host.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * يعيد تشغيل خدمة المضيف بعد إقلاع الجهاز — فقط إذا فعّل المستخدم مسبقاً
 * "تشغيل المضيف تلقائياً بعد الإقلاع" من إعدادات التطبيق.
 *
 * على أندرويد 8+ يجب استخدام startForegroundService ضمن نافذة زمنية محدودة،
 * فنستخدم goAsync لقراءة إعداد المستخدم من DataStore ثم نشغّل الخدمة،
 * وتُعرض الإشعار الأمامي (المنظومة الرسمية والمتناسقة مع أندرويد).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SettingsRepository(context)
                val autoStart = repo.autoStartHost.firstOrNull() ?: false
                if (!autoStart) {
                    Log.i(TAG, "التشغيل التلقائي يعطل في إعدادات المستخدم — لا نشغّل الخدمة")
                    HostRestartScheduler.cancelRestart(context)
                    HostRestartScheduler.cancelWatchdog(context)
                    return@launch
                }
                val deviceName = repo.deviceName.firstOrNull() ?: "جهاز مضيف"
                val serviceIntent = Intent(context, HostForegroundService::class.java).apply {
                    action = HostForegroundService.ACTION_START
                    putExtra(HostForegroundService.EXTRA_SERVER_URL, "")
                    putExtra(HostForegroundService.EXTRA_DEVICE_NAME, deviceName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                // نجدول أيضاً المراقب الدوري لضمان استمرار الخدمة
                HostRestartScheduler.schedulePeriodicWatchdog(context)
                Log.i(TAG, "تم بدء خدمات المضيف بعد الإقلاع بنجاح")
            } catch (t: Throwable) {
                Log.e(TAG, "فشل إعادة تشغيل الخدمة بعد الإقلاع", t)
            } finally {
                result.finish()
            }
        }
    }
}
