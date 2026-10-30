package com.remotebridge.host.capture

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * وحدة الأوامر المباشرة على المضيف: إرسال رسالة، إجراء مكالمة، إظهار نص
 * على الشاشة (نافذة عائمة)، إضافة اسم في الأسماء، إضافة وسائط (نُقلت معرّفة
 * Base64 من المتحكم)، ومحاولات إطفاء/تصفير عبر صلاحيات الجذر فقط.
 */
object RemoteCommandsModule {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun granted(context: Context, perm: String): Boolean =
        android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm)

    /** إرسال رسالة نصية من شريحة الهاتف المضيف. */
    fun sendSms(context: Context, number: String, text: String): Boolean {
        if (!granted(context, Manifest.permission.SEND_SMS)) return false
        if (number.isBlank() || text.isBlank()) return false
        return try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            val parts = sm.divideMessage(text)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sm.sendTextMessage(number, null, text, null, null)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** إجراء مكالمة مباشرة من شريحة المضيف (يتطلب إذن CALL_PHONE). */
    fun makeCall(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        return try {
            val action = if (granted(context, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            context.startActivity(
                Intent(action, Uri.parse("tel:" + number.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** إظهار نص عائم أعلى الشاشة لمدة محددة (يتطلب إذن عرض فوق التطبيقات). */
    fun showOnScreen(context: Context, text: String, seconds: Int): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (text.isBlank()) return false
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }

            val label = TextView(context).apply {
                this.text = text
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 2f, Color.BLACK)
                setBackgroundColor(0xAA101828L.toInt())
                setPadding(48, 36, 48, 36)
            }
            wm.addView(label, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(label) } catch (_: Throwable) {}
            }, seconds.coerceIn(1, 3600) * 1000L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** إضافة اسم وجهة اتصال (اسم + رقم) إلى دفتر الأسماء. */
    fun addContact(context: Context, name: String, number: String): Boolean {
        if (name.isBlank() || number.isBlank()) return false
        if (!granted(context, Manifest.permission.WRITE_CONTACTS)) return false
        return try {
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * إضافة وسائط (صورة/فيديو/صوت) مرسلة Base64 من المتحكم إلى مكتبة المضيف.
     * kind: image | video | audio
     */
    suspend fun addMedia(
        context: Context,
        kind: String,
        fileNameIn: String?,
        base64Data: String
    ): RemoteCaptureModule.ShotResult? = withContext(Dispatchers.IO) {
        val bytes = try {
            Base64.getMimeDecoder().decode(base64Data)
        } catch (_: Throwable) {
            return@withContext null
        }
        if (bytes.isEmpty()) return@withContext null

        val ext = mapOf(
            "image" to "jpg", "video" to "mp4", "audio" to "m4a"
        )[kind] ?: return@withContext null
        val mime = mapOf(
            "image" to "image/jpeg", "video" to "video/mp4", "audio" to "audio/mp4"
        )[kind] ?: return@withContext null
        val directory = mapOf(
            "image" to "Pictures/RemoteBridge/Assistant",
            "video" to "Movies/RemoteBridge/Assistant",
            "audio" to "Music/RemoteBridge"
        )[kind] ?: return@withContext null
        val msType = when (kind) {
            "image" -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            "video" -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            else -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        }
        val clean = (fileNameIn ?: "").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = if (clean.endsWith(".$ext")) clean
            else if (clean.isNotBlank() && clean.contains('.')) clean
            else "RBAdd_${stamp.format(Date())}.$ext"

        // الحفظ عبر أداة الإدراج الموحدة
        ReflectInsert.insert(context, msType, directory, name, mime, bytes, 0)
    }

    /**
     * إطفاء الهاتف أو تصفير المصنع: محاولة عبر صلاحيات الجذر (su) فقط.
     * بدون جذر: تعود false مع رسالة واضحة (النظام لا يسمح للتطبيقات العادية).
     */
    fun powerCommand(context: Context, reset: Boolean): Pair<Boolean, String> {
        val command = if (reset) "recovery --wipe_data" else "reboot -p"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val code = p.waitFor()
            if (code == 0) {
                true to "تم تنفيذ الأمر عبر صلاحية الجذر"
            } else {
                false to "تعذر التنفيذ: يتطلب صلاحية جذر (root) على الهاتف المضيف — النظام يمنع التطبيقات العادية من الإطفاء والتصفير"
            }
        } catch (_: Throwable) {
            false to "تعذر التنفيذ: يتطلب صلاحية جذر (root) على الهاتف المضيف — النظام يمنع التطبيقات العادية من الإطفاء والتصفير"
        }
    }
}

/** أداة إدراج موحّدة داخل MediaStore (مستقلة عن الوحدة الأولى لتقليل الترابط). */
internal object ReflectInsert {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun insert(
        context: Context,
        msType: Int,
        relativeSub: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        durationMs: Int
    ): RemoteCaptureModule.ShotResult? {
        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val collection = when (msType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (isQ) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSub)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs.toLong())
            } else {
                val base = Environment.getExternalStoragePublicDirectory(
                    when (msType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Environment.DIRECTORY_PICTURES
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_MUSIC
                    }
                )
                val dir = File(base, relativeSub.substringAfter("RemoteBridge").trim('/'))
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        if (isQ) {
            val done = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        val realPath = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) { null }
        val size = realPath?.let { File(it).length() } ?: bytes.size.toLong()
        return RemoteCaptureModule.ShotResult(displayName, realPath ?: "$relativeSub/$displayName", size)
    }
}
