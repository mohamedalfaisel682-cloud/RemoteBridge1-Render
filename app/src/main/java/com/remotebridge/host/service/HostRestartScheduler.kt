package com.remotebridge.host.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * منظومة الاستمرارية (Persistence) لخدمة المضيف على أساس WorkManager الرسمي،
 * وهو الوسيلة المعتمدة والمسموحة من نظام أندرويد:
 *
 * 1) مراقب دوري (كل 15 دقيقة) يتحقق أن الخدمة تعمل ويشغّلها إن صمتت.
 * 2) إعادة تشغيل مجدولة بعد مهلة من إعدادات المستخدم (1 دقيقة / 10 دقائق /
 *    ساعة / يوم) عند انهيار أو إزالة مهمة الخدمة دون طلب من المستخدم.
 * 3) تُلغى كامل المنظومة احتراماً لقرار المستخدم عندما يوقف الخدمة يدوياً.
 *
 * حدود النظام (الواقع الفني): الإيقاف القسري من إعدادات النظام (Force Stop)
 * يلغي فوراً كل مهام WorkManager والمهلات، ولا توجد آلية غير مروّتة يمكنها
 * إحياء التطبيق بعدها في أندرويد الحديث. المنظومة تغطي:
 * - إزالة المهمة من قائمة التطبيقات (swipe away) والقتل من نظام إدارة
 *   الذاكرة أو من تطبيقات المُهمّمات غير القسرية.
 * - العمل التلقائي بعد إقلاع الجهاز عبر BootReceiver.
 */
object HostRestartScheduler {

    private const val TAG = "HostRestartScheduler"

    private const val ONE_TIME_RESTART_WORK = "host_restart_one_time"
    private const val PERIODIC_WATCHDOG_WORK = "host_watchdog_periodic"

    /**
     * يفحص إعدادات الاستمرارية ويجدول إعادة تشغيل الخدمة بعد فترة (بالدقائق)
     * عند انهيار الخدمة أو إزالة مهمتها دون طلب من المستخدم.
     */
    fun scheduleRestart(context: Context, delayMinutes: Long) {
        val restartRequest = OneTimeWorkRequestBuilder<HostRestartWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag("host_restart")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_RESTART_WORK,
            ExistingWorkPolicy.REPLACE,
            restartRequest
        )
        Log.i(TAG, "تم جدولة إعادة تشغيل الخدمة بعد $delayMinutes دقيقة")
    }

    /**
     * إعادة تشغيل سريعة (ثوانٍ) — تُستخدم من onTaskRemoved أو عند تغيير
     * عنوان الخادم من لوحة المتحكم أثناء عمل الخدمة.
     */
    fun scheduleQuickRestart(context: Context, delaySeconds: Long = 3L) {
        val restartRequest = OneTimeWorkRequestBuilder<HostRestartWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .addTag("host_restart")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_RESTART_WORK,
            ExistingWorkPolicy.REPLACE,
            restartRequest
        )
    }

    /**
     * مراقب دوري يضمن استمرار الخدمة إن عملت النظام أو انقطاع الذاكرة بقتلها
     * (كل 15 دقيقة، عند عدم انخفاض البطارية في جيل البطارة).
     */
    fun schedulePeriodicWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<HostWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("host_watchdog")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WATCHDOG_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** يوقف المراقب الدوري (يستعملها عند الإيقاف اليدوي من المستخدم). */
    fun cancelWatchdog(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WATCHDOG_WORK)
        } catch (t: Throwable) {
            Log.e(TAG, "فشل إلغاء المراقب الدوري", t)
        }
    }

    /** يلغي إعادة التشغيل المجدولة المعلقة. */
    fun cancelRestart(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_RESTART_WORK)
        } catch (t: Throwable) {
            Log.e(TAG, "فشل إلغاء إعادة التشغيل المجدولة", t)
        }
    }
}

/**
 * عامل إعادة التشغيل: يفحص إعدادات الاستمرارية ويشغّل الخدمة إن صمتت.
 */
class HostRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        return HostServiceRevival.run(applicationContext)
    }
}

/**
 * عامل المراقبة الدوري: نفس فحص استمرار الخدمة كل 15 دقيقة.
 */
class HostWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result = HostServiceRevival.run(applicationContext)
}

/** منطق إحياء الخدمة مشترك بين الأعمال (يحكم بسيط: إن ثبتت لا تفعل شيئاً). */
internal object HostServiceRevival {
    suspend fun run(ctx: Context): ListenableWorker.Result {
        val repo = SettingsRepository(ctx)
        return try {
            val restartOnKill = repo.restartOnKill.first()
            val autoStartHost = repo.autoStartHost.first()
            if (!restartOnKill && !autoStartHost) {
                return ListenableWorker.Result.success()
            }
            if (HostForegroundService.serviceState.value.running) {
                return ListenableWorker.Result.success()
            }
            val deviceName = repo.deviceName.first()
            val intent = Intent(ctx, HostForegroundService::class.java).apply {
                action = HostForegroundService.ACTION_START
                putExtra(HostForegroundService.EXTRA_SERVER_URL, "")
                putExtra(HostForegroundService.EXTRA_DEVICE_NAME, deviceName)
            }
            ContextCompat.startForegroundService(ctx, intent)
            ListenableWorker.Result.success()
        } catch (t: Throwable) {
            Log.e("HostServiceRevival", "فشل فحص/إعادة تشغيل الخدمة", t)
            ListenableWorker.Result.success()
        }
    }
}
