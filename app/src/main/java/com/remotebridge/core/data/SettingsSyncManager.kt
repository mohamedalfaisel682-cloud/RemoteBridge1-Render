package com.remotebridge.core.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * مدير المزامنة بين إعدادات المضيف (التطبيق) وإعدادات واجهة ويب المتحكم.
 *
 * الفلسفة: "التعديل الأحدث يفوز". كل تعديل محلي على الإعدادات يرفع طابعاً
 * زمنياً مشتركاً ([SettingsRepository.settingsSyncTimestamp])، وعند الاتصال
 * يتبادل الطرفان لقطات JSON للإعدادات عبر رسالة settings_sync؛ الطرف الذي
 * يملك طابعاً أقدم يتبنى لقطة الآخر الأحدث فتتطابق الإعدادات في الموقعين.
 *
 * رسالة settings_sync تدعم حالتين:
 * 1. ts = 0 : طلب سحب (pull) — يرفد المستلم لقطة كاملة للطرف الآخر.
 * 2. ts > 0 : تعديل حديث — يتبناه المستلم إن كان أحدث من طابعه المحلي.
 */
object SettingsSyncManager {

    /** حقول لقطة الإعدادات المتبادلة (كلها قابلة للمزامنة). */
    data class SyncableSettings(
        val ts: Long,
        val serverUrl: String,
        val stunServer: String,
        val connectionProfile: String,
        val deviceName: String,
        val autoStartHost: Boolean,
        val restartOnKill: Boolean,
        val restartDelayMinutes: Int
    )

    data class AdoptResult(
        /** تم تبنّي حقول إعدادات محلياً. */
        val adopted: Boolean,
        /** تم تغيير عنوان خادم الإشارات (قد يتطلب إعادة تشغيل الخدمة). */
        val serverUrlChanged: Boolean,
        /** الطابع الزمني الجديد بعد التبني. */
        val newTs: Long
    )

    /** يبني لقطة الإعدادات الحالية بصيغة JSON المتبادلة. */
    suspend fun buildSnapshot(context: Context): JsonObject {
        val repo = SettingsRepository(context)
        return buildJsonObject {
            put("ts", repo.settingsSyncTimestamp.first())
            put("serverUrl", repo.serverUrl.first())
            put("stunServer", repo.stunServer.first())
            put("connectionProfile", repo.connectionProfile.first())
            put("deviceName", repo.deviceName.first())
            put("autoStartHost", repo.autoStartHost.first())
            put("restartOnKill", repo.restartOnKill.first())
            put("restartDelayMinutes", repo.restartDelayMinutes.first())
        }
    }

    /**
     * يبني [SyncableSettings] من عنصر JSON وارد. يعيد null إن كانت اللقطة
     * غير مكتملة أو غير صالحة.
     */
    fun parseSnapshot(payload: kotlinx.serialization.json.JsonElement?): SyncableSettings? {
        val obj = payload as? JsonObject ?: return null
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        val ts = str("ts")?.toLongOrNull()
        val serverUrl = str("serverUrl")
        val stun = str("stunServer")
        val profile = str("connectionProfile")
        val deviceName = str("deviceName")
        val autoStart = str("autoStartHost")?.toBooleanStrictOrNull()
        val restartOnKill = str("restartOnKill")?.toBooleanStrictOrNull()
        val delay = str("restartDelayMinutes")?.toIntOrNull() ?: return null
        if (ts == null || serverUrl == null || stun == null || profile == null || deviceName == null) return null
        return SyncableSettings(
            ts = ts,
            serverUrl = serverUrl,
            stunServer = stun,
            connectionProfile = profile,
            deviceName = deviceName,
            autoStartHost = autoStart ?: false,
            restartOnKill = restartOnKill ?: true,
            restartDelayMinutes = delay
        )
    }

    /**
     * يتبنى لقطة إعدادات واردة إن كانت أحدث من الطابع المحلي (التعديل الأحدث يفوز).
     */
    suspend fun applyIncoming(context: Context, incoming: JsonObject?): AdoptResult? {
        val parsed = parseSnapshot(incoming) ?: return null
        val repo = SettingsRepository(context)
        val localTs = repo.settingsSyncTimestamp.first()
        if (parsed.ts <= localTs) {
            // الطرف الآخر أقدم منا — نرغب في نقله نحونا لاحقاً (يرد المستلم تلقائياً)
            return AdoptResult(adopted = false, serverUrlChanged = false, newTs = localTs)
        }

        val beforeServer = repo.serverUrl.first()
        repo.setSyncTimestamp(parsed.ts)
        repo.setServerUrl(parsed.serverUrl)
        repo.setStunServer(parsed.stunServer)
        repo.setConnectionProfile(parsed.connectionProfile)
        repo.setDeviceName(parsed.deviceName)
        repo.setAutoStartHost(parsed.autoStartHost)
        repo.setRestartOnKill(parsed.restartOnKill)
        repo.setRestartDelayMinutes(parsed.restartDelayMinutes)
        val afterServer = repo.serverUrl.first()
        return AdoptResult(
            adopted = true,
            serverUrlChanged = beforeServer != afterServer,
            newTs = parsed.ts
        )
    }
}
