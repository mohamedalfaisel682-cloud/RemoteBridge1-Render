package com.remotebridge.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * تخزين إعدادات التطبيق الدائمة (عنوان الخادم، اسم الجهاز، الدور).
 * يستخدم DataStore Preferences بدلاً من SharedPreferences لكونه آمناً مع Coroutines.
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bridge_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val STUN_SERVER = stringPreferencesKey("stun_server")
        val CONNECTION_PROFILE = stringPreferencesKey("connection_profile")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val ROLE = stringPreferencesKey("role")
        val AUTO_START = booleanPreferencesKey("auto_start_host")
        val RESTART_ON_KILL = booleanPreferencesKey("restart_on_kill")
        val RESTART_DELAY_MINUTES = intPreferencesKey("restart_delay_minutes")
        val DOWNLOADS_DIR_URI = stringPreferencesKey("downloads_dir_uri")
        val RECORDINGS_DIR_URI = stringPreferencesKey("recordings_dir_uri")
        val HIDE_ICON_WHEN_HOST_ACTIVE = booleanPreferencesKey("hide_icon_when_host_active")
        val REMOTE_UI_ACCESS = booleanPreferencesKey("remote_ui_access")
        val LAST_SEEN_MAP = stringPreferencesKey("last_seen_hosts")
        val DASHBOARD_LAST_SEEN = longPreferencesKey("dashboard_last_seen")
        val SYNC_TIMESTAMP = longPreferencesKey("settings_sync_ts")
    }

    companion object {
        const val DEFAULT_FLY_URL = "wss://my-ai-bridge-app.fly.dev/ws"
        const val DEFAULT_RENDER_URL = "wss://remotebridge1.onrender.com/ws"
        const val DEFAULT_LOCAL_URL = "ws://10.0.2.2:10000/ws"
        const val DEFAULT_SERVER_URL = DEFAULT_RENDER_URL
        const val DEFAULT_STUN_SERVER = "stun:stun.l.google.com:19302"
        const val DEFAULT_PROFILE = "HYBRID"
        const val DEFAULT_DEVICE_NAME = "Galaxy Note 20 Ultra"
    }

    val serverUrl: Flow<String> = context.settingsDataStore.data.map { 
        it[Keys.SERVER_URL]?.takeIf { url -> url.isNotBlank() } ?: DEFAULT_SERVER_URL 
    }
    val stunServer: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.STUN_SERVER]?.takeIf { stun -> stun.isNotBlank() } ?: DEFAULT_STUN_SERVER
    }
    val connectionProfile: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.CONNECTION_PROFILE]?.takeIf { p -> p.isNotBlank() } ?: DEFAULT_PROFILE
    }
    val deviceName: Flow<String> = context.settingsDataStore.data.map { 
        it[Keys.DEVICE_NAME]?.takeIf { name -> name.isNotBlank() } ?: DEFAULT_DEVICE_NAME 
    }
    val role: Flow<String> = context.settingsDataStore.data.map { it[Keys.ROLE] ?: "" }
    val autoStartHost: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.AUTO_START] ?: false
    }
    val restartOnKill: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.RESTART_ON_KILL] ?: true
    }
    val restartDelayMinutes: Flow<Int> = context.settingsDataStore.data.map {
        it[Keys.RESTART_DELAY_MINUTES] ?: 1
    }
    val downloadsDirUri: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.DOWNLOADS_DIR_URI] ?: ""
    }
    val recordingsDirUri: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.RECORDINGS_DIR_URI] ?: ""
    }
    /** هل تُخفى أيقونة التطبيق تلقائياً عند تشغيل المضيف؟ (افتراضياً: نعم) */
    val hideIconWhenHostActive: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.HIDE_ICON_WHEN_HOST_ACTIVE] ?: true
    }

    /** هل يُسمح بفتح واجهة المتحكم من أيقونة «التحكم المباشر» على المضيف؟ (افتراضياً: لا) */
    val remoteUiAccess: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.REMOTE_UI_ACCESS] ?: false
    }
    val settingsSyncTimestamp: Flow<Long> = context.settingsDataStore.data.map {
        it[Keys.SYNC_TIMESTAMP] ?: 0L
    }

    suspend fun setServerUrl(url: String) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.SERVER_URL] = url }
    }

    suspend fun setStunServer(stunUrl: String) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.STUN_SERVER] = stunUrl }
    }

    suspend fun setConnectionProfile(profile: String) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.CONNECTION_PROFILE] = profile }
    }

    suspend fun setDeviceName(name: String) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    suspend fun setRole(role: String) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.ROLE] = role }
    }

    suspend fun setAutoStartHost(autoStart: Boolean) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.AUTO_START] = autoStart }
    }

    suspend fun setRestartOnKill(enabled: Boolean) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.RESTART_ON_KILL] = enabled }
    }

    suspend fun setRestartDelayMinutes(minutes: Int) {
        bumpTimestamp()
        context.settingsDataStore.edit { it[Keys.RESTART_DELAY_MINUTES] = minutes }
    }

    suspend fun setDownloadsDirUri(uri: String) {
        // لا يرفع الطابع الزمني: إعداد محلي خاص بالجهاز ولا يُزامن عبر الاتصال
        context.settingsDataStore.edit { it[Keys.DOWNLOADS_DIR_URI] = uri }
    }

    suspend fun setRecordingsDirUri(uri: String) {
        context.settingsDataStore.edit { it[Keys.RECORDINGS_DIR_URI] = uri }
    }

    /** إعداد محلي خاص بالجهاز ولا يُزامن عبر الاتصال. */
    suspend fun setHideIconWhenHostActive(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HIDE_ICON_WHEN_HOST_ACTIVE] = enabled }
    }

    /** إعداد محلي خاص بالجهاز: السماح بفتح واجهة المتحكم من أيقونة «التحكم المباشر». */
    suspend fun setRemoteUiAccess(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.REMOTE_UI_ACCESS] = enabled }
    }

    /**
     * خريطة "آخر ظهور" للأجهزة المضيفة تُحفظ محلياً على جهاز المتحكم
     * (deviceId -> millis) — لرسم الدوائر البرتقالية بعد الإغلاق.
     */
    suspend fun loadLastSeenMap(): Map<String, Long> {
        val raw = try {
            context.settingsDataStore.data.first()[Keys.LAST_SEEN_MAP] ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (raw.isBlank()) return emptyMap()
        return raw.split("|").mapNotNull { entry ->
            val i = entry.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val id = entry.substring(0, i)
            val ms = entry.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            id to ms
        }.toMap()
    }

    suspend fun saveLastSeenMap(map: Map<String, Long>) {
        val raw = map.entries.joinToString("|") { "${it.key}=${it.value}" }
        context.settingsDataStore.edit { it[Keys.LAST_SEEN_MAP] = raw }
    }

    /** آخر لحظة "رأينا" فيها المضيف المتصل في لوحة المتحكم (millis). */
    val dashboardLastSeen: Flow<Long> = context.settingsDataStore.data.map {
        it[Keys.DASHBOARD_LAST_SEEN] ?: 0L
    }

    suspend fun setDashboardLastSeen(ts: Long) {
        context.settingsDataStore.edit { it[Keys.DASHBOARD_LAST_SEEN] = ts }
    }

    suspend fun setSyncTimestamp(ts: Long) {
        context.settingsDataStore.edit { it[Keys.SYNC_TIMESTAMP] = ts }
    }

    /**
     * يرفع الطابع الزمني للمزامنة قبل أي تعديل محلي جديد، ليكون الحقل الأحدث
     * هو الذائج عند المزامنة مع واجهة ويب المتحكم (التعديل الأحدث يفوز).
     */
    private suspend fun bumpTimestamp() {
        val current = context.settingsDataStore.data.first()[Keys.SYNC_TIMESTAMP] ?: 0L
        val now = System.currentTimeMillis()
        if (now > current) {
            context.settingsDataStore.edit { it[Keys.SYNC_TIMESTAMP] = now }
        }
    }
}
