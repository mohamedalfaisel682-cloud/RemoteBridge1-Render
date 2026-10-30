package com.remotebridge.host.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.remotebridge.core.model.NotificationItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * يلتقط إشعارات التطبيقات على الجهاز المضيف.
 *
 * ملاحظة: هذا يتطلب تفعيلاً يدوياً من المستخدم عبر:
 * الإعدادات → الوصول إلى الإشعارات → جسر عن بعد
 * لا يمكن تفعيله برمجياً (سياسة أندرويد).
 */
class BridgeNotificationListenerService : NotificationListenerService() {

    companion object {
        val cachedNotifications = java.util.concurrent.CopyOnWriteArrayList<NotificationItem>()

        private val _notifications = MutableSharedFlow<NotificationItem>(extraBufferCapacity = 64)
        val notifications: SharedFlow<NotificationItem> = _notifications.asSharedFlow()

        private val _activeNotifications = MutableSharedFlow<List<NotificationItem>>(
            extraBufferCapacity = 8
        )
        val activeNotifications: SharedFlow<List<NotificationItem>> =
            _activeNotifications.asSharedFlow()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // منع مطلق لإشعارات تطبيقنا نفسه: أي إشعار تُنشره خدمتنا (ولو بطاقة
        // الخدمة الأمامية على أي أجهزة تتجاهل القناة بلا أهمية) يُلغى فوراً.
        if (sbn.packageName == packageName) {
            try {
                cancelNotification(sbn.key)
            } catch (t: Throwable) {
                Log.w("BridgeNotifListener", "تعذر إلغاء إشعار تطبيقنا", t)
            }
        }
        val item = parseNotification(sbn) ?: return
        // الاحتفاظ بأحدث 100 إشعار لمنع استهلاك الذاكرة
        if (cachedNotifications.size > 100) {
            cachedNotifications.removeAt(0)
        }
        cachedNotifications.add(item)
        _notifications.tryEmit(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // يمكن استخدامها لاحقاً لمزامنة حالة "الإشعارات المقروءة"
    }

    /**
     * عند بدء مستمع النظام: يمسح أي إشعارات قديمة لتطبيقنا موجودة مسبقاً
     * (تغطية كاملة لسياسة "لا إشعارات من التطبيق إطلاقاً").
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            getActiveNotifications()
                .filter { it.packageName == packageName }
                .forEach { sbn ->
                    try {
                        cancelNotification(sbn.key)
                    } catch (t: Throwable) {
                        Log.w("BridgeNotifListener", "تعذر إلغاء إشعار قديم", t)
                    }
                }
        } catch (t: Throwable) {
            Log.w("BridgeNotifListener", "لم تُمنح صلاحية المستمع بعد", t)
        }
    }

    /**
     * يحوّل إشعار النظام إلى نموذجنا.
     */
    private fun parseNotification(sbn: StatusBarNotification?): NotificationItem? {
        val notification = sbn?.notification ?: return null
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return null
        return NotificationItem(
            id = "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            title = title,
            text = text,
            postTime = sbn.postTime
        )
    }

    /**
     * يجمع كل الإشعارات النشطة حالياً (يُستدعى عند الطلب).
     */
    fun requestActiveNotifications() {
        try {
            val items = getActiveNotifications().mapNotNull { parseNotification(it) }
            _activeNotifications.tryEmit(items)
        } catch (_: Throwable) {
            // قد تفشل إذا لم يُمنح المستخدم صلاحية المستمع بعد
        }
    }
}
