package com.remotebridge.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        // B11 — تفعيل واجهة الويب في شاشة المضيف
        val WEB_UI_ENABLED = booleanPreferencesKey("web_ui_enabled")
        // B12 — وضع الإخفاء: إشعار خدمة بأدنى أهمية ومحتوى محايد (الافتراضي: مفعّل)
        val STEALTH_NOTIFICATION = booleanPreferencesKey("stealth_notification")
        // B26 — مزامنة الإعدادات (التعديل الأحدث يفوز): طابع زمني + سلوك الاستمرارية
        val SETTINGS_SYNC_TIMESTAMP = longPreferencesKey("settings_sync_timestamp")
        val RESTART_ON_KILL = booleanPreferencesKey("restart_on_kill")
        val RESTART_DELAY_MINUTES = intPreferencesKey("restart_delay_minutes")
        // B26 — مجلدا حفظ التنزيلات والتسجيلات عبر SAF (محليان: لا يُزامن across الأجهزة)
        val DOWNLOADS_DIR_URI = stringPreferencesKey("downloads_dir_uri")
        val RECORDINGS_DIR_URI = stringPreferencesKey("recordings_dir_uri")
    }

    companion object {
        const val DEFAULT_FLY_URL = "wss://my-ai-bridge-app.fly.dev/ws"
        const val DEFAULT_RENDER_URL = "wss://remotebridge1.onrender.com/ws"
        const val DEFAULT_LOCAL_URL = "ws://10.0.2.2:10000/ws"
        const val DEFAULT_SERVER_URL = DEFAULT_RENDER_URL
        const val DEFAULT_STUN_SERVER = "stun:stun.l.google.com:19302"
        const val DEFAULT_PROFILE = "HYBRID"
        const val DEFAULT_DEVICE_NAME = "Galaxy Note 20 Ultra"
        /** B24 — صفحة واجهة المتحكم النهائية المدمجة على الخوادم المستضافة (الاحتياط إلى الأساس داخل الخادم). */
        const val B24_DASHBOARD_PAGE = "indexMergeFull.html"
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
    // B11 — حالة تفعيل واجهة الويب (تظهر بموجبها أيقونتا واجهة المتحكم في الرئيسية)
    val webUiEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.WEB_UI_ENABLED] ?: false
    }
    // B12 — وضع الإخفاء: إشعار خدمة شبه غير مرئي ومحتوى محايد
    val stealthNotification: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.STEALTH_NOTIFICATION] ?: true
    }
    // B26 — مزامنة الإعدادات: طابع التبديل الزمني (التعديل الأحدث يفوز) وسلوك الاستمرارية
    val settingsSyncTimestamp: Flow<Long> = context.settingsDataStore.data.map {
        it[Keys.SETTINGS_SYNC_TIMESTAMP] ?: 0L
    }
    val restartOnKill: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.RESTART_ON_KILL] ?: true
    }
    val restartDelayMinutes: Flow<Int> = context.settingsDataStore.data.map {
        it[Keys.RESTART_DELAY_MINUTES] ?: 1
    }
    // B26 — مجلد حفظ التنزيلات (SAF tree URI أو فارغ للاحتياط داخل ملفات التطبيق)
    val downloadsDirUri: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.DOWNLOADS_DIR_URI] ?: ""
    }
    // B26 — مجلد حفظ التسجيلات (SAF tree URI أو فارغ للاحتياط داخل ملفات التطبيق)
    val recordingsDirUri: Flow<String> = context.settingsDataStore.data.map {
        it[Keys.RECORDINGS_DIR_URI] ?: ""
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.SERVER_URL] = url }
    }

    suspend fun setStunServer(stunUrl: String) {
        context.settingsDataStore.edit { it[Keys.STUN_SERVER] = stunUrl }
    }

    suspend fun setConnectionProfile(profile: String) {
        context.settingsDataStore.edit { it[Keys.CONNECTION_PROFILE] = profile }
    }

    suspend fun setDeviceName(name: String) {
        context.settingsDataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    suspend fun setRole(role: String) {
        context.settingsDataStore.edit { it[Keys.ROLE] = role }
    }

    suspend fun setAutoStartHost(autoStart: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_START] = autoStart }
    }

    suspend fun setWebUiEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WEB_UI_ENABLED] = enabled }
    }

    /** B12 — تبديل وضع الإخفاء (يُطبَّق عند بدء الخدمة التالي). */
    suspend fun setStealthNotification(stealth: Boolean) {
        context.settingsDataStore.edit { it[Keys.STEALTH_NOTIFICATION] = stealth }
    }

    /** B26 — ضبط الطابع الزمني للمزامنة (يُستدعى عند تبنّي لقطة أحدث أو تعديل محلي). */
    suspend fun setSyncTimestamp(ts: Long) {
        context.settingsDataStore.edit { it[Keys.SETTINGS_SYNC_TIMESTAMP] = ts }
    }

    suspend fun setRestartOnKill(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.RESTART_ON_KILL] = enabled }
    }

    suspend fun setRestartDelayMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.RESTART_DELAY_MINUTES] = minutes.coerceIn(1, 240) }
    }

    /** B26 — ضبط مجلد التنزيلات (إعداد محلي: لا يرفع طابع المزامنة). */
    suspend fun setDownloadsDirUri(uri: String) {
        context.settingsDataStore.edit { it[Keys.DOWNLOADS_DIR_URI] = uri }
    }

    /** B26 — ضبط مجلد التسجيلات (إعداد محلي: لا يرفع طابع المزامنة). */
    suspend fun setRecordingsDirUri(uri: String) {
        context.settingsDataStore.edit { it[Keys.RECORDINGS_DIR_URI] = uri }
    }

    /**
     * B11 — مشتق عنوان لوحة الويب (واجهة المتحكم) من عنوان خادم الإشارة:
     * wss://host/ws → https://host/ ، ws://host:port/ws → http://host:port/
     * يعيد "" إن تعذر الاشتقاق.
     */
    fun deriveDashboardUrl(serverUrl: String?): String {
        val raw = (serverUrl ?: "").trim()
        if (raw.isBlank()) return ""
        return try {
            val replaced = when {
                raw.startsWith("wss://", ignoreCase = true) -> "https://" + raw.substring(6)
                raw.startsWith("ws://", ignoreCase = true) -> "http://" + raw.substring(5)
                else -> raw
            }
            val uri = java.net.URI(replaced)
            val scheme = uri.scheme?.lowercase() ?: return ""
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host ?: return ""
            val port = if (uri.port > 0) uri.port else -1
            val base = if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
            // B24 — على المضيفين السحابيين المعتمدين تُفتح الواجهة النهائية المدمجة،
            // ومعالج الملفات الثابتة في الخادم يجعل الاحتياطَ إلى اللوحة الأساس تلقائياً.
            val mergedHost = host.endsWith("onrender.com", ignoreCase = true) ||
                host.endsWith("fly.dev", ignoreCase = true)
            (base.trimEnd('/') + "/" + (if (mergedHost) B24_DASHBOARD_PAGE else "")).trimEnd('/')
        } catch (t: Throwable) {
            ""
        }
    }

    /** B24 — Flow جاهز لعنوان لوحة الويب (النهائية المدمجة على السحابي، والمشتق أساساً في غيره؛ فارغ عند فشل الاشتقاق). */
    val dashboardUrl: Flow<String> = serverUrl.map { deriveDashboardUrl(it) }
}
