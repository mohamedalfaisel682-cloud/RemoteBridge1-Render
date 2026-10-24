package com.remotebridge.host.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 🎥 محرك التسجيل واللقطة للهاتف المضيف (RemoteBridge):
 *  - تسجيل الشاشة: MediaProjection المخزنة + MediaRecorder (H264/MPEG4) بمقاطع متتابعة مجدولة.
 *  - تسجيل الكاميرا: Camera2 + MediaRecorder (أمامية/خلفية).
 *  - تسجيل الصوت: ميكروفون AAC (m4a).
 *  - لقطة شاشة فورية عبر ImageReader (إطار واحد) + حفظ JPEG وإرجاع Data URL.
 *  - أرشيف في مجلد التطبيق الخاص (RemoteBridgeMedia) قابل للسرد للمتحكم.
 *  - الجدولة: تأخير إطلاق + مدة كل مقطع + فاصل بين المقاطع + عدد مقاطع (0 = مستمر حتى الإيقاف).
 *  كل العمليات محمية وترجع أخطاء عربية صادقة.
 */
class RecordingEngine(private val context: Context) {

    companion object {
        private const val TAG = "RecordingEngine"
        const val KIND_SCREEN = "screen"
        const val KIND_CAMERA = "camera"
        const val KIND_AUDIO = "audio"
        const val KIND_SHOT = "screenshot"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private class Session {
        var kind = ""
        var recorder: MediaRecorder? = null
        var projection: MediaProjection? = null
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
        var camera: CameraDevice? = null
        var camSession: CameraCaptureSession? = null
        var file: File? = null
        var segmentSec: Long = 60L
        var intervalSec: Long = 0L
        var runsLeft = -1          // -1 = مستمر حتى الإيقاف
        var wantMic = false
        var frontCam = false
        var startedAtMs = 0L
        var stopRunnable: Runnable? = null
        // بيانات إعادة الاستخدام لكل مقطع
        var permissionIntent: Intent? = null
    }

    private val active = HashMap<String, Session>()

    /** يُستدعى عند انتهاء سلسلة تسجيل نوع باتمامها — ليعيد الخدمة إحياء البث الحي الموقوف مؤقتاً */
    var onRecordingSeriesEnded: ((kind: String) -> Unit)? = null

    // ==========================================================
    //                    مسارات الحفظ
    // ==========================================================
    fun mediaBaseDir(): File {
        val base = File(context.getExternalFilesDir(null), "RemoteBridgeMedia")
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun dirFor(kind: String): File {
        val sub = when (kind) {
            KIND_SCREEN -> "screen"
            KIND_CAMERA -> "camera"
            KIND_AUDIO -> "audio"
            KIND_SHOT -> "screenshot"
            else -> "other"
        }
        val d = File(mediaBaseDir(), sub)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    // ==========================================================
    //                    ال API العام
    // ==========================================================
    fun isRecording(kind: String): Boolean = active[kind] != null

    fun activeKinds(): List<String> = active.keys.toList()

    /**
     * يبدأ سلسلة تسجيل. يعيد null للنجاح أو رسالة عربية للخطأ.
     * [repeatCount] > 0 يع.Attribute number of segments; 0 = مستمر.
     */
    fun startRecording(
        kind: String,
        permissionIntent: Intent?,
        frontCamera: Boolean,
        wantAudio: Boolean,
        delaySec: Long,
        segmentSec: Long,
        intervalSec: Long,
        repeatCount: Int
    ): String? {
        if (active.containsKey(kind)) return "تسجيل $kind يعمل مسبقاً — أوقفه أولاً"
        val ses = Session().apply {
            this.kind = kind
            this.permissionIntent = permissionIntent
            this.frontCam = frontCamera
            this.wantMic = wantAudio
            this.segmentSec = segmentSec.coerceIn(3L, 90L * 60L)
            this.intervalSec = intervalSec.coerceIn(0L, 6L * 60L * 60L)
            this.runsLeft = if (repeatCount > 0) repeatCount else -1
        }
        active[kind] = ses
        if (delaySec > 0) {
            mainHandler.postDelayed({ runSegment(ses) }, delaySec * 1000L)
        } else {
            mainHandler.post { runSegment(ses) }
        }
        return null
    }

    fun stopRecording(kind: String): Boolean {
        // إلغاء أي جدولة معلقة ثم إنهاء
        val running = active.remove(kind) ?: run {
            mainHandler.removeCallbacksAndMessages(null)
            return false
        }
        mainHandler.removeCallbacksAndMessages(null)   // نوقف كل الجدولة (المحرك يخدم نوعاً نشطاً أساساً)
        finishSegment(running, final = true)
        return true
    }

    fun stopAll() {
        for (k in active.keys.toList()) stopRecording(k)
    }

    // ==========================================================
    //                    تشغيل مقطع واحد
    // ==========================================================
    private fun runSegment(ses: Session) {
        if (active[ses.kind] !== ses) return   // أُلغيت
        try {
            val err = when (ses.kind) {
                KIND_SCREEN -> startScreenSegment(ses)
                KIND_CAMERA -> startCameraSegment(ses)
                KIND_AUDIO -> startAudioSegment(ses)
                else -> "نوع تسجيل غير معروف"
            }
            if (err != null) {
                Log.w(TAG, "فشل مقطع تسجيل ${ses.kind}: $err")
                // إذا كان الإذن ناقصاً نوقف السلسلة بكل الأحوال
                finishSegment(ses, final = true, failMessage = err)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ مقطع تسجيل ${ses.kind}", t)
            finishSegment(ses, final = true, failMessage = "فشل التسجيل: ${t.message}")
        }
    }

    private fun scheduleSegmentEnd(ses: Session) {
        val r = Runnable {
            if (active[ses.kind] !== ses) return@Runnable
            if (ses.runsLeft > 0) ses.runsLeft--
            finishSegment(ses, final = (ses.runsLeft == 0))
            if (ses.runsLeft != 0) {
                mainHandler.postDelayed({ runSegment(ses) }, ses.intervalSec * 1000L)
            }
        }
        ses.stopRunnable = r
        mainHandler.postDelayed(r, ses.segmentSec * 1000L)
    }

    private fun finishSegment(ses: Session, final: Boolean, failMessage: String? = null) {
        try { ses.stopRunnable?.let { mainHandler.removeCallbacks(it) } } catch (_: Throwable) {}
        try { ses.recorder?.stop() } catch (_: Throwable) { failMessage ?: "إيقاف" }
        try { ses.recorder?.reset() } catch (_: Throwable) {}
        try { ses.recorder?.release() } catch (_: Throwable) {}
        ses.recorder = null
        try { ses.camSession?.close() } catch (_: Throwable) {}
        ses.camSession = null
        try { ses.camera?.close() } catch (_: Throwable) {}
        ses.camera = null
        try { ses.virtualDisplay?.release() } catch (_: Throwable) {}
        ses.virtualDisplay = null
        try { ses.projection?.stop() } catch (_: Throwable) {}
        ses.projection = null
        val savedName = ses.file?.name
        val wasSeries = final
        Log.i(TAG, "مقطع ${ses.kind} انتهى: $savedName  نهائي=$final فشل=${failMessage != null}")
        if (final) {
            active.remove(ses.kind)
            if (wasSeries) {
                try { onRecordingSeriesEnded?.invoke(ses.kind) } catch (_: Throwable) {}
            }
        }
    }

    // ==========================================================
    //                    الشاشة
    // ==========================================================
    private fun startScreenSegment(ses: Session): String? {
        val intent = ses.permissionIntent
        if (intent == null) return "لم يُمنح إذن التقاط الشاشة بعد — اضغط بث الشاشة مرة واحدة لمنح الإذن ثم أعد التسجيل"
        val code = intent.getIntExtra("android.intent.extra.RESULT_CODE", android.app.Activity.RESULT_OK)
        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val (w, h, dpi) = screenMetrics()
        try {
            val projection = pm.getMediaProjection(code, intent) ?: return "تعذر إنشاء جلسة الإسقاط — أعد منح الإذن ثم أعد التسجيل"
            ses.projection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopRecording(ses.kind) }
                }
            }, mainHandler)

            val recorder = MediaRecorder()
            ses.recorder = recorder
            if (ses.wantMic) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(128_000)
                    recorder.setAudioSamplingRate(44_100)
                } catch (t: Throwable) {
                    Log.w(TAG, "تعذر إضافة صوت الميكروفون للتسجيل: ${t.message}")
                }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val f = File(dirFor(KIND_SCREEN), "screen_${stamp()}.mp4")
            ses.file = f
            recorder.setOutputFile(f.absolutePath)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(w, h)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(3_000_000)
            recorder.prepare()
            val vd = projection.createVirtualDisplay(
                "RBRec", w, h, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, mainHandler
            )
            ses.virtualDisplay = vd
            recorder.start()
            ses.startedAtMs = System.currentTimeMillis()
            scheduleSegmentEnd(ses)
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "فشل تسجيل الشاشة", t)
            return if (t is SecurityException) "الإسقاط مشغول بالبث/تسجيل آخر — أوقفه ثم أعد المحاولة"
            else "فشل تسجيل الشاشة: ${t.message}"
        }
    }

    // ==========================================================
    //                    الكاميرا
    // ==========================================================
    private fun startCameraSegment(ses: Session): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "إذن الكاميرا غير ممنوح على الهاتف المضيف"
        }
        if (ses.wantMic && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "إذن الميكروفون غير ممنوح — أعد التسجيل بدون صوت"
        }
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = cm.cameraIdList.firstOrNull { id ->
                try {
                    val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    if (ses.frontCam) facing == CameraCharacteristics.LENS_FACING_FRONT
                    else facing == CameraCharacteristics.LENS_FACING_BACK
                } catch (_: Throwable) { false }
            } ?: cm.cameraIdList.firstOrNull()
            if (camId == null) return "لا توجد كاميرا متاحة"
            val ch = cm.getCameraCharacteristics(camId)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return "تعذر قراءة مقاسات الكاميرا"
            val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            val size = sizes.firstOrNull { it.width <= 1280 && it.height <= 720 }
                ?: sizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                ?: sizes.getOrNull(0)
            if (size == null) return "لا مقاس تسجيل مدعوم"
            val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val recorder = MediaRecorder()
            ses.recorder = recorder
            if (ses.wantMic) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioSamplingRate(44_100)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val f = File(dirFor(KIND_CAMERA), "camera_${stamp()}.mp4")
            ses.file = f
            recorder.setOutputFile(f.absolutePath)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(3_500_000)
            // تعديال الاتجاه: مطابقة الحساس مع كاميرا أمامية معكوسة
            val hint = if (ses.frontCam) (360 + sensorOrientation) % 360 else (90 + sensorOrientation) % 360
            recorder.setOrientationHint(hint)
            recorder.prepare()

            val recSurface = recorder.surface
            if (recSurface == null) return "تعذر تجهيز سطح تسجيل الكاميرا"
            val latch = CountDownLatch(1)
            var openErr: String? = null
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    ses.camera = camera
                    try {
                        camera.createCaptureSession(listOf(recSurface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                ses.camSession = session
                                try {
                                    val rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    rb.addTarget(recSurface)
                                    session.setRepeatingRequest(rb.build(), null, mainHandler)
                                } catch (t: Throwable) {
                                    openErr = "إعداد الطلب فشل: ${t.message}"
                                } finally {
                                    latch.countDown()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                openErr = "فشل تهيئة جلسة الكاميرا"
                                latch.countDown()
                            }
                        }, mainHandler)
                    } catch (t: Throwable) {
                        openErr = "إنشاء الجلسة فشل: ${t.message}"
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) { latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) {
                    openErr = "خطأ فتح الكاميرا: $error"
                    latch.countDown()
                }
            }, mainHandler)

            if (!latch.await(4, TimeUnit.SECONDS)) {
                return "انتهت مهلة فتح الكاميرا"
            }
            (openErr)?.let { return it }
            recorder.start()
            ses.startedAtMs = System.currentTimeMillis()
            scheduleSegmentEnd(ses)
            return null
        } catch (t: Throwable) {
            Log.e(TAG, "فشل تسجيل الكاميرا", t)
            return "فشل تسجيل الكاميرا: ${t.message}"
        }
    }

    // ==========================================================
    //                    الصوت
    // ==========================================================
    private fun startAudioSegment(ses: Session): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "إذن الميكروفون غير ممنوح على الهاتف المضيف"
        }
        return try {
            val recorder = MediaRecorder()
            ses.recorder = recorder
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val f = File(dirFor(KIND_AUDIO), "audio_${stamp()}.m4a")
            ses.file = f
            recorder.setOutputFile(f.absolutePath)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioChannels(2)
            recorder.prepare()
            recorder.start()
            ses.startedAtMs = System.currentTimeMillis()
            scheduleSegmentEnd(ses)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "فشل تسجيل الصوت", t)
            "فشل تسجيل الصوت: ${t.message}"
        }
    }

    // ==========================================================
    //                    لقطة شاشة
    // ==========================================================
    /**
     * يلتقط لقطة واحدة ويحفظها ويعيد (dataUrl, خطأ) — الفشل يظهر بنص عربي.
     */
    fun takeScreenshot(permissionIntent: Intent?): Pair<String?, String?> {
        if (permissionIntent == null) {
            return null to "لم يُمنح إذن التقاط الشاشة — ابدأ بث الشاشة مرة لمنح الإذن ثم أعد اللقطة"
        }
        val code = permissionIntent.getIntExtra("android.intent.extra.RESULT_CODE", android.app.Activity.RESULT_OK)
        var projection: MediaProjection? = null
        var display: android.hardware.display.VirtualDisplay? = null
        var reader: android.media.ImageReader? = null
        return try {
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val pr = pm.getMediaProjection(code, permissionIntent) ?: return null to "تعذر إنشاء جلسة الإسقاط — أعد منح الإذن"
            projection = pr
            val (w, h, dpi) = screenMetricsPortrait(1080, 1920)
            reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
            val latch = CountDownLatch(1)
            reader.setOnImageAvailableListener({ latch.countDown() }, mainHandler)
            display = pr.createVirtualDisplay(
                "RBShot", w, h, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, mainHandler
            )
            if (!latch.await(3, TimeUnit.SECONDS)) return null to "انتهت مهلة التقاط إطار الشاشة"
            val image = reader.acquireLatestImage() ?: return null to "لم يُلتقط إطار بعد"
            val plane = image.planes[0]
            val stride = plane.rowStride
            val buffer: java.nio.ByteBuffer = if (stride == w * 4) {
                plane.buffer
            } else {
                val b = java.nio.ByteBuffer.allocate(w * 4 * h)
                var row = 0
                while (row < h) {
                    plane.buffer.position(row * stride)
                    plane.buffer.get(b.array(), row * w * 4, w * 4)
                    row++
                }
                b
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()

            val f = File(dirFor(KIND_SHOT), "shot_${stamp()}.jpg")
            val fos = java.io.FileOutputStream(f)
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            fos.close()
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            dataUrl to null
        } catch (t: Throwable) {
            Log.e(TAG, "فشل لقطة الشاشة", t)
            if (t is SecurityException || t.message?.contains("SecurityException", true) == true) {
                null to "الإسقاط مشغول بالبث الحي — أوقف البث ثم أعد اللقطة"
            } else {
                null to "فشل لقطة الشاشة: ${t.message}"
            }
        } finally {
            try { display?.release() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            try { projection?.stop() } catch (_: Throwable) {}
        }
    }

    // ==========================================================
    //                    الأرشيف
    // ==========================================================
    fun listRecordings(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (sub in listOf("screen", "camera", "audio", "screenshot")) {
            try {
                val dir = File(mediaBaseDir(), sub)
                val files = dir.listFiles() ?: continue
                val kind = when (sub) {
                    "screen" -> KIND_SCREEN
                    "camera" -> KIND_CAMERA
                    "audio" -> KIND_AUDIO
                    else -> KIND_SHOT
                }
                for (f in files.sortedByDescending { it.lastModified() }.take(200)) {
                    out.add(
                        buildJsonObject {
                            put("kind", if (kind == KIND_SHOT) "image" else if (kind == KIND_AUDIO) "audio" else "video")
                            put("recKind", kind)
                            put("name", f.name)
                            put("size", f.length())
                            put("dateMs", f.lastModified())
                            put("path", f.absolutePath)
                            put("folder", sub)
                        }
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "سرد التسجيلات فشل: ${t.message}")
            }
        }
        return out.sortedByDescending {
            (it["dateMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        }
    }

    // ==========================================================
    //                    أدوات القياس
    // ==========================================================
    /** أبعاد الشاشة الحقيقية (تطابق حتى لترميز فيديو صحيح) + كثافة الشاشة */
    private fun screenMetrics(): Triple<Int, Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Triple((m.widthPixels / 2) * 2, (m.heightPixels / 2) * 2, m.densityDpi)
        } catch (_: Throwable) {
            Triple(720, 1280, 320)
        }
    }

    /** أبعاد محدودة بقيم قصوى (مثبّتة) لقطة الشاشة */
    private fun screenMetricsPortrait(maxW: Int, maxH: Int): Triple<Int, Int, Int> {
        val (w, h, dpi) = screenMetrics()
        val ow = kotlin.math.max(1, w.coerceAtMost(maxW) / 2 * 2)
        val oh = kotlin.math.max(1, h.coerceAtMost(maxH) / 2 * 2)
        return Triple(ow, oh, dpi)
    }
}
