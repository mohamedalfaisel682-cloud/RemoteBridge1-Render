package com.remotebridge.host.provider

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import com.remotebridge.core.model.Buckets
import com.remotebridge.core.model.CallLogItem
import com.remotebridge.core.model.ContactItem
import com.remotebridge.core.model.DeviceInfo
import com.remotebridge.core.model.FileItem
import com.remotebridge.core.model.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * يقرأ بيانات الجهاز المحلية (الجهاز المضيف) من مزوّدي المحتوى الرسميين.
 *
 * ملاحظة أمنية: كل الاستعلامات تستخدم ContentResolver الرسمي مع الأذونات
 * الممنوحة في AndroidManifest. لا يُستخدم Root ولا واجهات غير رسمية.
 */
class HostDataProvider(private val context: Context) {

    companion object {
        private const val TAG = "HostDataProvider"
        private const val MAX_RESULTS = 500
        const val REL_COL = "relative_path"
    }

    /**
     * يقرأ قائمة الملفات من صندوق تخزين محدد.
     */
    suspend fun readFiles(bucket: String): List<FileItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection: Uri
        val projection: Array<String>
        when (bucket) {
            Buckets.IMAGES -> {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                )
            }

            Buckets.VIDEOS -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DURATION
                )
            }

            Buckets.AUDIO -> {
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION
                )
            }

            Buckets.DOCUMENTS -> {
                collection = MediaStore.Files.getContentUri("external")
                projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
                )
            }

            else -> {
                collection = MediaStore.Files.getContentUri("external")
                projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
                )
            }
        }

        val items = mutableListOf<FileItem>()
        try {
            resolver.query(
                collection,
                projection,
                selectionForBucket(bucket),
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $MAX_RESULTS"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val bucketIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durationIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val bucketName = if (bucketIdx >= 0)
                        cursor.getString(bucketIdx) ?: bucket else bucket
                    items.add(
                        FileItem(
                            id = cursor.getLong(idIdx),
                            name = name,
                            size = cursor.getLong(sizeIdx),
                            mimeType = cursor.getString(mimeIdx) ?: "*/*",
                            dateAdded = cursor.getLong(dateIdx) * 1000L,
                            bucket = bucketName,
                            width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0,
                            height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0,
                            durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل قراءة الملفات من $bucket", t)
        }
        items
    }

    /**
     * استكشاف ذاكرة الهاتف والمجلدات بنظام الملفات المباشر (Direct Storage & Memory Explorer).
     */
    suspend fun exploreStorage(relativePath: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
        val rootDir = Environment.getExternalStorageDirectory()
        val targetDir = if (relativePath.isNullOrBlank() || relativePath == "/") {
            rootDir
        } else {
            java.io.File(rootDir, relativePath.trimStart('/'))
        }

        val items = mutableListOf<FileItem>()
        try {
            val files = targetDir.listFiles()
            if (files != null) {
                // ترتيب المجلدات أولاً ثم الملفات
                val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for ((index, file) in sorted.take(MAX_RESULTS).withIndex()) {
                    val isDir = file.isDirectory
                    val mimeType = if (isDir) "inode/directory" else getMimeType(file.name)
                    val size = if (isDir) 0L else file.length()
                    items.add(
                        FileItem(
                            id = (targetDir.hashCode() * 31L) + index,
                            name = file.name,
                            size = size,
                            mimeType = mimeType,
                            dateAdded = file.lastModified(),
                            bucket = if (isDir) "folder" else "file",
                            width = 0,
                            height = 0,
                            durationMs = 0
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل استكشاف الذاكرة في $relativePath", t)
        }
        items
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "txt" -> "text/plain"
            "zip", "rar", "7z" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    /**
     * شرط الاستعلام لفلترة التنزيلات فقط.
     */
    private fun selectionForBucket(bucket: String): String? {
        return when (bucket) {
            Buckets.DOWNLOADS ->
                "${MediaStore.Files.FileColumns.DATA} LIKE '%/Download/%'"
            Buckets.DOCUMENTS ->
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '%pdf%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '%text%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '%document%'"
            else -> null
        }
    }

    /**
     * يفتح InputStream لملف محدد بمعرّفه أو مساره. يعيد null عند الفشل.
     */
    suspend fun openFile(fileId: Long, bucket: String): java.io.InputStream? =
        withContext(Dispatchers.IO) {
            try {
                val baseUri = when (bucket) {
                    Buckets.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    Buckets.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    Buckets.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                val itemUri = android.content.ContentUris.withAppendedId(baseUri, fileId)
                context.contentResolver.openInputStream(itemUri)
            } catch (t: Throwable) {
                Log.e(TAG, "فشل فتح الملف $fileId", t)
                null
            }
        }

    /**
     * يقرأ الرسائل النصية من مزوّر Telephony.
     */
    suspend fun readSms(limit: Int = MAX_RESULTS): List<SmsMessage> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SmsMessage>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (cursor.moveToNext()) {
                    items.add(
                        SmsMessage(
                            id = cursor.getLong(idIdx),
                            threadId = cursor.getLong(threadIdx),
                            address = cursor.getString(addressIdx) ?: "",
                            body = cursor.getString(bodyIdx) ?: "",
                            date = cursor.getLong(dateIdx),
                            type = cursor.getInt(typeIdx),
                            contactName = null
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل قراءة الرسائل", t)
        }
        items
    }

    /**
     * يقرأ جهات الاتصال.
     */
    suspend fun readContacts(limit: Int = MAX_RESULTS): List<ContactItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<ContactItem>()
            try {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME
                    ),
                    null,
                    null,
                    "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $limit"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIdx =
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val contactId = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        items.add(
                            ContactItem(
                                id = contactId,
                                displayName = name,
                                phoneNumbers = readPhoneNumbers(contactId),
                                emails = readEmails(contactId)
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "فشل قراءة جهات الاتصال", t)
            }
            items
        }

    /**
     * يقرأ أرقام الهاتف لجهة اتصال محددة.
     */
    private fun readPhoneNumbers(contactId: Long): List<String> {
        val numbers = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            while (cursor.moveToNext()) {
                cursor.getString(numberIdx)?.let { numbers.add(it) }
            }
        }
        return numbers
    }

    /**
     * يقرأ عناوين البريد لجهة اتصال محددة.
     */
    private fun readEmails(contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DATA),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            val emailIdx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Email.DATA
            )
            while (cursor.moveToNext()) {
                cursor.getString(emailIdx)?.let { emails.add(it) }
            }
        }
        return emails
    }

    /**
     * يقرأ سجل المكالمات.
     */
    suspend fun readCallLog(limit: Int = MAX_RESULTS): List<CallLogItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<CallLogItem>()
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.TYPE
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC LIMIT $limit"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                    while (cursor.moveToNext()) {
                        items.add(
                            CallLogItem(
                                id = cursor.getLong(idIdx),
                                number = cursor.getString(numberIdx) ?: "",
                                date = cursor.getLong(dateIdx),
                                durationSec = cursor.getLong(durationIdx),
                                type = cursor.getInt(typeIdx)
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "فشل قراءة سجل المكالمات", t)
            }
            items
        }

    /**
     * يقرأ معلومات الجهاز (البطارية، التخزين، الإصدار) بتوافق كامل مع أندرويد 13 (API 33).
     */
    suspend fun readDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        
        // 1. القراءة المباشرة لمستوى البطارية عبر BatteryManager المتوافقة مع أندرويد 13+
        val capacityProperty = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Throwable) {
            -1
        }
        val statusProperty = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        } catch (_: Throwable) {
            -1
        }

        // 2. قراءة إضافية احتياطية عبر Intent.ACTION_BATTERY_CHANGED
        val batteryIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val intentPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        // تحديد النسبة المئوية الموثوقة: أولوية لخاصية النظام المباشرة في أندرويد 13
        val batteryPct = when {
            capacityProperty in 0..100 -> capacityProperty
            intentPct in 0..100 -> intentPct
            else -> 0
        }

        val intentStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && batteryManager != null) {
            try {
                batteryManager.isCharging
            } catch (_: Throwable) {
                statusProperty == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusProperty == BatteryManager.BATTERY_STATUS_FULL ||
                intentStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                intentStatus == BatteryManager.BATTERY_STATUS_FULL
            }
        } else {
            statusProperty == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusProperty == BatteryManager.BATTERY_STATUS_FULL ||
            intentStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            intentStatus == BatteryManager.BATTERY_STATUS_FULL
        }

        val stat = StatFs(Environment.getDataDirectory().path)

        DeviceInfo(
            deviceName = Build.DEVICE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            batteryLevel = batteryPct,
            isCharging = isCharging,
            storageTotalBytes = stat.totalBytes,
            storageFreeBytes = stat.freeBytes
        )
    }

    /**
     * يقرأ أحدث رسالة نصية (لأحداث الدفع الحي Live Push).
     */
    suspend fun readLatestSms(): SmsMessage? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    SmsMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                        address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "",
                        body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                        date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                        contactName = null
                    )
                } else null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل قراءة أحدث رسالة", t)
            null
        }
    }

    /**
     * يقرأ آخر سجل مكالمة (لأحداث الدفع الحي Live Push).
     */
    suspend fun readLatestCall(): CallLogItem? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    CallLogItem(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)),
                        number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "",
                        date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)),
                        durationSec = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)),
                        type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    )
                } else null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل قراءة أحدث مكالمة", t)
            null
        }
    }

    /**
     * يُرجع مصغرة صورة/فيديو/صوت حقيقية مرمّزة Base64 Data URL.
     * [kind] على قيمته: image-[صور] / video-[فيديو] / audio-[غلاف صوتي] — فارغ = محاولة القوّمين العامين.
     * [sizePx] حجم المصغرة المطلوب (0 = الافتراضي 160، large = 480، small = 96).
     */
    suspend fun readThumbnailDataUrl(
        fileId: Long,
        filePath: String? = null,
        kind: String? = null,
        sizePx: Int = 0
    ): String? = withContext(Dispatchers.IO) {
        try {
            var bitmap: android.graphics.Bitmap? = null
            val wanted = when (sizePx) {
                480 -> 480
                96 -> 96
                in 64..1024 -> sizePx
                else -> 160
            }
            val jpegQuality = if (sizePx >= 320) 80 else 60

            if (Build.VERSION.SDK_INT >= 29 && (kind.isNullOrBlank() || kind == "image" || kind == "video")) {
                listOf(
                    Pair("image", MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                    Pair("video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                ).forEach { (kindName, base) ->
                    if (bitmap == null && (kind.isNullOrBlank() || kind == kindName)) {
                        try {
                            bitmap = context.contentResolver.loadThumbnail(
                                android.content.ContentUris.withAppendedId(base, fileId),
                                android.util.Size(wanted, wanted),
                                null
                            )
                        } catch (_: Throwable) { }
                    }
                }
            }

            // محاولة غلاف الصوت عند طلب audio صراحة
            if (bitmap == null && kind == "audio" && Build.VERSION.SDK_INT >= 29) {
                try {
                    bitmap = context.contentResolver.loadThumbnail(
                        android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fileId),
                        android.util.Size(wanted, wanted),
                        null
                    )
                } catch (_: Throwable) { }
            }

            if (bitmap == null && (kind.isNullOrBlank() || kind == "image")) {
                try {
                    bitmap = MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, fileId,
                        MediaStore.Images.Thumbnails.MICRO_KIND, null
                    )
                } catch (_: Throwable) { }
            }

            if (bitmap == null && filePath != null) {
                try {
                    val file = java.io.File(filePath)
                    val mime = getMimeType(file.name)
                    if (mime.startsWith(kind?.let { k -> if (k == "video") "video/" else "image/" } ?: "image/")
                        || (kind.isNullOrBlank() && mime.startsWith("image/"))) {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(filePath, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            var sample = 1
                            while (bounds.outWidth / (sample * 2) >= wanted || bounds.outHeight / (sample * 2) >= wanted) {
                                sample *= 2
                            }
                            bitmap = android.graphics.BitmapFactory.decodeFile(
                                filePath,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                            )
                        }
                    } else if (mime.startsWith("video/")) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(filePath)
                            bitmap = retriever.getFrameAtTime(1_000_000L)
                        } finally {
                            try { retriever.release() } catch (_: Throwable) { }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "فشل توليد مصغرة من المسار: ${t.message}")
                }
            }

            val bmp = bitmap ?: return@withContext null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, out)
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "فشل توليد المصغرة", t)
            null
        }
    }

    /**
     * 🎬 الاستوديو — مجلدات الوسائط الحاوية على صور/فيديو/صوت مع الأعداد وآخر تاريخ.
     * يعيد قائمة JsonObjects: {path,label,img,vid,aud,lastMs} مرتبة الأحدث أولاً.
     */
    suspend fun listMediaFolders(): List<kotlinx.serialization.json.JsonObject> =
        withContext(Dispatchers.IO) {
            class Agg {
                var img = 0
                var vid = 0
                var aud = 0
                var last = 0L
            }
            val map = LinkedHashMap<String, Agg>()

            fun relativePathFor(dataIdx: Int, relIdx: Int, cursor: android.database.Cursor): String {
                if (relIdx >= 0) {
                    val r = cursor.getString(relIdx)
                    if (!r.isNullOrBlank()) return r.trim('/')
                }
                val data = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                if (data.isNullOrBlank()) return ""
                val parent = data.substringBeforeLast('/')
                val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                return parent.removePrefix(root).trim('/')
            }

            val config = listOf(Pair("image", MediaStore.Images.Media.EXTERNAL_CONTENT_URI), Pair("video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI), Pair("audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI))
            for ((kindName, uri) in config) {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.MIME_TYPE,
                            MediaStore.MediaColumns.DATE_ADDED,
                            "relative_path"
                        ),
                        null,
                        null,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 1200"
                    )?.use { cursor ->
                        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        val relIdx = cursor.getColumnIndex("relative_path")
                        while (cursor.moveToNext()) {
                            val path = relativePathFor(dataIdx, relIdx, cursor)
                            if (path.isBlank()) continue
                            val agg = map.getOrPut(path) { Agg() }
                            when (kindName) {
                                "image" -> agg.img++
                                "video" -> agg.vid++
                                else -> agg.aud++
                            }
                            val d = (cursor.getLong(dateIdx) * 1000L)
                            if (d > agg.last) agg.last = d
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "فشل فهرسة الوسائط من $kindName", t)
                }
            }

            map.entries
                .map { (path, agg) ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("path", path)
                        put("label", path.substringAfterLast('/').ifBlank { path })
                        put("img", agg.img)
                        put("vid", agg.vid)
                        put("aud", agg.aud)
                        put("lastMs", agg.last)
                    }
                }
                .sortedByDescending { (it["lastMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L }
        }

    /**
     * 🎬 الاستوديو — عناصر الوسائط من مسارات (مجلدات) محددة مع مصغرات جاهزة.
     */
    suspend fun readMediaItems(folders: List<String>, limit: Int = 240): List<kotlinx.serialization.json.JsonObject> =
        withContext(Dispatchers.IO) {
            val wanted = folders.filter { it.isNotBlank() }.take(24)
            if (wanted.isEmpty()) return@withContext emptyList()
            val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val thumbBudget = 60

            fun considerStore(
                uri: android.net.Uri,
                kindName: String,
                durationCol: String?
            ) {
                try {
                    val projection = mutableListOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        REL_COL
                    )
                    durationCol?.let { projection.add(it) }
                    context.contentResolver.query(
                        uri,
                        projection.toTypedArray(),
                        null,
                        null,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 900"
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        val relIdx = cursor.getColumnIndex(REL_COL)
                        val durIdx = durationCol?.let { cursor.getColumnIndex(it) } ?: -1
                        while (cursor.moveToNext()) {
                            if (relIdx >= 0) {
                                val rel = cursor.getString(relIdx)?.trim('/') ?: ""
                                if (wanted.none { w -> rel.equals(w, ignoreCase = true) }) continue
                            } else continue
                            val item = kotlinx.serialization.json.buildJsonObject {
                                put("id", cursor.getLong(idIdx))
                                put("kind", kindName)
                                put("name", cursor.getString(nameIdx) ?: "")
                                put("dateMs", cursor.getLong(dateIdx) * 1000L)
                                put("size", cursor.getLong(sizeIdx))
                                put("mime", cursor.getString(mimeIdx) ?: "")
                                if (durIdx >= 0) put("durationMs", cursor.getLong(durIdx))
                            }
                            out.add(item)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "استوديو: فشل قراءة $kindName", t)
                }
            }

            considerStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", null)
            considerStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", MediaStore.Video.Media.DURATION)
            considerStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", MediaStore.Audio.Media.DURATION)

            out.sortByDescending { (it["dateMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L }
            val trimmed = out.take(limit)

            // مصغرّات جاهزة لأول N عنصر (صور/فيديو فقط)
            var budget = thumbBudget
            val withThumbs = trimmed.map { item ->
                val kind = (item["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (budget <= 0 || (kind != "image" && kind != "video")) return@map item
                val id = (item["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@map item
                val thumb = readThumbnailDataUrl(id, null, kind, 96)
                budget--
                if (thumb != null) {
                    val merged = LinkedHashMap(item)
                    merged["thumb"] = kotlinx.serialization.json.JsonPrimitive(thumb)
                    kotlinx.serialization.json.JsonObject(merged)
                } else item
            }
            withThumbs.sortedByDescending { (it["dateMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L }
        }
}
