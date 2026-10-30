package com.remotebridge.controller

import android.content.Context
import android.util.Log
import com.remotebridge.core.data.DownloadsSaver
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.core.model.HostInfo
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مسجّل الجلسات على جانب المتحكم: عندما يكون المضيف والمتحكمان متصلين في
 * نفس الجلسة (اكتمل قناة WebRTC)، يُكتب ملف JSON تلقائي في مجلد التحميلات
 * المختار لعرضه لاحقاً من نافذة حالة الجهاز.
 *
 * المحتوى: بيانات الجلسة (الوقت، المعرّفات) + ملخص إعدادات الحظ — بلا أي
 * بيانات خاصة تُستخرج من الجهاز؛ لكل بيانات المستخدم مساره الخاص (ملفات/رسائل).
 */
object SessionRecorder {

    private const val TAG = "SessionRecorder"
    private const val PREFIX = "RB_Session"

    fun buildName(hostName: String, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))
        val safe = (hostName.ifBlank { "host" }).replace(Regex("[^A-Za-z0-9_\\- ]"), "_").take(24).trim()
        return PREFIX + "_" + safe + "_" + stamp + ".json"
    }

    /** يكتب ملف جلسة في مجلد التحميلات (أو مجلد التطبيق الاحتياطي). */
    suspend fun recordSession(context: Context, host: HostInfo) {
        val repo = SettingsRepository(context)
        val startedAt = System.currentTimeMillis()
        val json = buildJsonObject {
            put("kind", "rb_session")
            put("capturedAt", startedAt)
            put("hostId", host.deviceId)
            put("hostName", host.deviceName)
            put("controllerDeviceName", repo.deviceName.firstOrNull() ?: "")
            put("connectionProfile", repo.connectionProfile.firstOrNull() ?: "")
            put("serverUrl", repo.serverUrl.firstOrNull() ?: "")
            put("hostOnline", host.online)
        }
        val jsonString = json.toString()
        writeJson(context, buildName(host.deviceName), jsonString)
        Log.i(TAG, "تم حفظ ملف جلسة تلقائي للمضيف: ${host.deviceName}")
    }

    private suspend fun writeJson(context: Context, fileName: String, content: String) {
        val opened = DownloadsSaver.openOutput(context, fileName, useRecordingFolder = false)
        writeAndClose(opened.stream, content)
        Log.d(TAG, "ملف الجلسة: ${opened.file?.name ?: fileName} في ${opened.folderLabel}")
    }

    private fun writeAndClose(stream: OutputStream, content: String) {
        try {
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (t: Throwable) {
            Log.w(TAG, "فشل كتابة ملف الجلسة", t)
        }
    }
}
