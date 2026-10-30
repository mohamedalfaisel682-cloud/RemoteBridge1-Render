package com.remotebridge.controller

import com.remotebridge.core.model.BridgeResponse
import com.remotebridge.core.model.CallLogItem
import com.remotebridge.core.model.Commands
import com.remotebridge.core.model.ContactItem
import com.remotebridge.core.model.DeviceInfo
import com.remotebridge.core.model.FileItem
import com.remotebridge.core.model.SmsMessage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * مستودع يقدم واجهة نظيفة لطبقة العرض للوصول لبيانات الجهاز البعيد.
 *
 * كل دالة ترسل طلباً عبر [ControllerManager] وتنتظر الرد،
 * ثم تحوّل JSON إلى نماذج Kotlin.
 */
class RemoteRepository(val manager: ControllerManager) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * يرسل طلباً وينتظر رده.
     */
    private suspend fun request(cmd: String, params: Map<String, String> = emptyMap()): BridgeResponse {
        return manager.sendRequest(cmd, params).first()
    }

    /**
     * يستخرج قائمة من حقل "items" في الرد.
     */
    private inline fun <reified T> BridgeResponse.itemsList(deserializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val items = this.data?.get("items") ?: return emptyList()
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(deserializer),
            items.toString()
        )
    }

    /**
     * اختبار سريع للاتصال.
     */
    suspend fun ping(): Boolean {
        val response = request(Commands.PING)
        return response.ok
    }

    /**
     * يجلب معلومات الجهاز البعيد.
     */
    suspend fun getDeviceInfo(): DeviceInfo? {
        val response = request(Commands.DEVICE_INFO)
        if (!response.ok) return null
        return json.decodeFromString(DeviceInfo.serializer(), response.data.toString())
    }

    /**
     * يجلب لقطة الموقع الحالية من الجهاز المضيف (قد تكون null إن لم يتوفر إذن).
     */
    suspend fun getLocationSnapshot(): com.remotebridge.core.model.LocationSnapshot? {
        val response = request(Commands.LOCATION_SNAPSHOT)
        if (!response.ok) return null
        val snap = response.data?.get("snapshot") ?: return null
        return try {
            json.decodeFromString(
                com.remotebridge.core.model.LocationSnapshot.serializer(),
                snap.toString()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * يجلب مسار حركة المضيف (نقاط مرتبة زمنياً).
     */
    suspend fun getLocationTrail(): List<com.remotebridge.core.model.LocationPoint> {
        val response = request(Commands.LOCATION_SNAPSHOT)
        if (!response.ok) return emptyList()
        val arr = response.data?.get("trail") ?: return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    com.remotebridge.core.model.LocationPoint.serializer()
                ),
                arr.toString()
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * يجلب قائمة الملفات من صندوق تخزين محدد.
     */
    suspend fun getFiles(bucket: String): List<FileItem> {
        val response = request(Commands.FILE_LIST, mapOf("bucket" to bucket))
        if (!response.ok) return emptyList()
        return response.itemsList(FileItem.serializer())
    }

    /**
     * يجلب الرسائل النصية.
     */
    suspend fun getSmsMessages(): List<SmsMessage> {
        val response = request(Commands.SMS_LIST)
        if (!response.ok) return emptyList()
        return response.itemsList(SmsMessage.serializer())
    }

    /**
     * يجلب جهات الاتصال.
     */
    suspend fun getContacts(): List<ContactItem> {
        val response = request(Commands.CONTACTS_LIST)
        if (!response.ok) return emptyList()
        return response.itemsList(ContactItem.serializer())
    }

    /**
     * يجلب سجل المكالمات.
     */
    suspend fun getCallLog(): List<CallLogItem> {
        val response = request(Commands.CALLS_LIST)
        if (!response.ok) return emptyList()
        return response.itemsList(CallLogItem.serializer())
    }

    /**
     * يطلب فحص اتصال الإشعارات (يتطلب تفعيل مستمع الإشعارات على المضيف).
     */
    suspend fun getNotificationsStatus(): Boolean {
        val response = request(Commands.NOTIFICATIONS_LIST)
        return response.ok
    }

    /**
     * يطلب من المضيف بدء إرسال ملف عبر القطع الثنائية.
     * القطع الواردة تُجمع عبر [ControllerManager.binaryChunks].
     */
    suspend fun requestFile(fileId: Long, bucket: String): Boolean {
        val response = request(
            Commands.FILE_OPEN,
            mapOf("fileId" to fileId.toString(), "bucket" to bucket)
        )
        return response.ok
    }

    /**
     * تنزيل ملف كامل إلى التخزين المحلي (مجلد التحميلات المختار أو خاص التطبيق)
     * عبر قناة البيانات مباشرة، مع مؤشر تقدم عبر [ControllerManager.downloadProgress].
     */
    suspend fun downloadFile(fileId: Long, bucket: String, fileName: String): ControllerManager.DownloadResult {
        return manager.downloadFile(fileId, bucket, fileName)
    }
}
