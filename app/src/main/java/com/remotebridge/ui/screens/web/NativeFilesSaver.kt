package com.remotebridge.ui.screens.web

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.remotebridge.core.data.DownloadsSaver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * جسر JavaScript بين لوحة ويب المتحكم (المدمّجة داخل التطبيق) ونظام حفظ
 * الملفات على أندرويد.
 *
 * سلسلة الاستدعاءات من لوحة الويب:
 * 1. saveStart(messageId, fileName) — بدء التحميل.
 * 2. saveChunk(messageId, base64) — كل قطعة 16KB من قناة البيانات.
 * 3. saveComplete(messageId) — الختم النهائي: تُحفظ البايتات في مجلد
 *    المستخدم المحدّد (إعدادات التطبيق) أو تُلغى.
 * 4. getSaveDirLabel() — لعرض اسم مجلد الحفظ داخل واجهة الويب.
 *
 * هذه الواجهة يفحصها الـ JS المتصفح تلقائياً: window.RBNative موجود فقط
 * داخل التطبيق، بينما في المتصفح العادي تغطي خاصية Blob/إلغاء بديلة.
 */
class NativeFilesSaver(
    private val appContext: Context
) {

    companion object {
        private const val TAG = "NativeFilesSaver"
    }

    private val buffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val names = ConcurrentHashMap<Int, String>()

    /** تعرضها لوحة الويب للتحقق من أنّ الحفظ التلقائي على أندرويد خالص. */
    @JavascriptInterface
    fun canSave(): Boolean = true

    /** اسم مجلد الحفظ لعرضه داخل واجهة الويب. */
    @JavascriptInterface
    fun getSaveDirLabel(forRecordings: Boolean): String = runBlockingLabel(forRecordings)

    @JavascriptInterface
    fun saveStart(messageId: Int, fileName: String) {
        names[messageId] = fileName
        buffers[messageId] = ByteArrayOutputStream()
    }

    /** تغذي قطعة من البيانات القادمة من WebRTC (شيفرة base64). */
    @JavascriptInterface
    fun saveChunk(messageId: Int, base64Chunk: String) {
        val buffer = buffers[messageId] ?: return
        try {
            buffer.write(Base64.getMimeDecoder().decode(base64Chunk))
        } catch (t: Throwable) {
            Log.e(TAG, "فشل فكّ قطعة من قناة البيانات", t)
        }
    }

    /** الختم: يفرّغ المخزن في ملف على القرص ويُعيد وصف موقع الحفظ. */
    @JavascriptInterface
    fun saveComplete(messageId: Int): String {
        val name = names.remove(messageId) ?: return ""
        val buffer = buffers.remove(messageId) ?: return ""
        return try {
            val opened = kotlinx.coroutines.runBlocking {
                DownloadsSaver.openOutput(appContext, name, useRecordingFolder = false)
            }
            opened.stream.use { it.write(buffer.toByteArray()) }
            "${opened.folderLabel}/${name}"
        } catch (t: Throwable) {
            Log.e(TAG, "فشل حفظ ملف من واجهة الويب: $name", t)
            ""
        }
    }

    @JavascriptInterface
    fun saveFailed(messageId: Int) {
        names.remove(messageId)
        buffers.remove(messageId)
    }

    /**
     * يسرد ملفات الجلسات المحفوظة تلقائياً (RB_Session_*.json) التي يكتبها
     * التطبيق اثناء وجود المضيف والمتحكمان متصلين في نفس الجلسة، لعرضها
     * من نافذة الحالة في لوحة الويب. تُعاد مصفوفة JSON نصية.
     */
    @JavascriptInterface
    fun listSavedSessions(): String {
        return try {
            val files = com.remotebridge.controller.SavedSessionsStore.list(appContext)
            files.joinToString(
                separator = ",",
                prefix = "[",
                postfix = "]"
            ) { f ->
                buildJsonObject {
                    put("name", f.name)
                    put("size", f.sizeBytes)
                    put("modified", f.lastModifiedMs)
                    put("source", if (f.isSaf) "saf" else "app")
                }.toString()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل سرد ملفات الجلسات", t)
            "[]"
        }
    }

    /** يقرأ محتوى ملف جلسة (نص JSON) للعرض داخل نافذة الحالة. */
    @JavascriptInterface
    fun readSavedSession(name: String): String {
        return try {
            val file = com.remotebridge.controller.SavedSessionsStore.list(appContext)
                .firstOrNull { it.name == name } ?: return ""
            com.remotebridge.controller.SavedSessionsStore.readText(appContext, file) ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "فشل قراءة ملف جلسة: $name", t)
            ""
        }
    }

    /** حساب مسار الحفظ (يعمل داخل موضوع JS — متزامن وقصير). */
    private fun runBlockingLabel(forRecordings: Boolean): String =
        kotlinx.coroutines.runBlocking { DownloadsSaver.describe(appContext, forRecordings) }
}
