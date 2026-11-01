package com.remotebridge.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rbstudio.R

/**
 * مساعد لإنشاء الإشعارات وقنواتها بما يتوافق مع أندرويد 8+.
 */
object NotificationHelper {

    /**
     * معرّف القناة الاصدار الثاني: قناة بأهمية NONE (بلا إشعار ظاهر).
     * أندرويد يمنع خفض أهمية قناة قائمة، لذا نستخدم معرفاً جديداً ونحذف القديم.
     */
    const val CHANNEL_ID = "bridge_host_channel_v2"
    /** قناة الإصدارات الأقدم تُحذف عند أول تشغيل. */
    private const val LEGACY_CHANNEL_ID = "bridge_host_channel"
    const val NOTIFICATION_ID = 1001

    /**
     * ينشئ قناة بلا أهمية (IMPORTANCE_NONE) بحيث لا تُعرض بطاقة الإشعار
     * في أثناء عمل الخدمة الأمامية (مسار مقصود للاستخدام الشخصي غير المنشور).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // حذف أي قناة أقدم (لكي لا تبقى إعداداتها القديمة تعرض الإشعار)
        try {
            if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            }
        } catch (_: Throwable) {
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.host_channel_name),
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            description = context.getString(R.string.host_notification_text)
            setShowBadge(false)
        }
        try {
            manager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w("NotificationHelper", "تعذر إنشاء قناة NONE", t)
        }
    }

    /**
     * يبني إشعار الخدمة الأمامية بصيغة مخفية لا تحضّر بطاقة ظاهرة:
     * قناة بلا أهمية + أولوية MIN + صامت + بدون وقت + بدون عنوان أو نص.
     */
    fun buildHostNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setColor(0xFFF97316.toInt())
            .setContentTitle("")
            .setContentText("")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
