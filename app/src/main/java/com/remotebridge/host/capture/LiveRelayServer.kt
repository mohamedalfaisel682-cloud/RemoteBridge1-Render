package com.remotebridge.host.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * مولّد إطارات البث البديل عبر الترحيل (MJPEG-over-Relay).
 *
 * الفلسفة: نفس المسار الذي تعمل فيه كل رسائل النظام (ترحيل عبر خادم الإشارات)
 * يحمل أيضاً إطارات الصورة ضغطاً JPEG وترميزاً base64 — وبذلك يشتغل البث الحي
 * على أي شبكة تصل فيها رسائل RPC على الإطلاق (شبكات الشركات/الخلوي/CGNAT)
 * دون أي اعتماد على ICE/STUN/TURN/SDP.
 *
 * مصدّران:
 *  1) الكاميرا: Camera2 + ImageReader بصيغة JPEG مباشرة (الاستشعار يشفّر JPEG
 *     عتادياً — دون المرور بتحويل YUV يدوياً) مع تخنق المعدل عبر فاصل زمني.
 *  2) الشاشة: MediaProjection (إذن النظام نفسه المعتمد في المسار القديم) +
 *     VirtualDisplay + ImageReader (RGBA) → Bitmap → JPEG.
 *
 * الاستهلاك التقريبي بعرض 640-720 وجودة 50-60: إطار 40-120KB (base64 ~1.37x)
 * بمعدل 3-5 إطارات/ثانية — قابل للمعايرة عبر المعاملات.
 */
class LiveRelayServer(
    private val context: Context,
    private val streamScope: CoroutineScope
) {
    companion object {
        private const val TAG = "LiveRelayServer"
        /** مهلة انتظار فتح الكاميرا (مللي ثانية) قبل تقرير الفشل. */
        private const val CAMERA_OPEN_TIMEOUT_MS = 5000L
    }

    // ———————————————————————— الحالة الداخلية ————————————————————————

    /** البث النشط حالياً: "camera" | "screen" | null */
    private val activeStream = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val currentStream: kotlinx.coroutines.flow.StateFlow<String?> = activeStream

    /** اتجاه المستشعر للعدسة النشطة (0/90/180/270) — يُقرأ من `SENSOR_ORIENTATION` ويُستخدم لتصحيح دوران JPEG برمجياً (v2). */
    private var sensorOrientationDeg = 90

    /** آخر خطأ وصفي (يُعرض لجهاز التحكم في الردود). */
    var lastError: String? = null
        private set

    // — الكاميرا —
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraReader: ImageReader? = null
    private var isFrontCamera = false
    private var cameraStopped = false

    // — الشاشة —
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var screenReader: ImageReader? = null
    private var screenPollJob: Job? = null
    private var screenStopped = false

    private val lastCameraFrameMs = AtomicLong(0)
    private val lastScreenFrameMs = AtomicLong(0)

    // ———————————————————————— الكاميرا ————————————————————————

    /**
     * يبدأ التقاط الكاميرا بخيط آمن مخصص، واختيار العدسة (أمامية/خلفية)
     * عبر معامل [front]، مع واجهة إيقاف متزامنة آمنة.
     */
    @SuppressLint("MissingPermission")
    suspend fun startCamera(
        front: Boolean,
        width: Int = 1280,
        height: Int = 720,
        intervalMs: Long = 300,
        onFrame: (jpegBytes: ByteArray, w: Int, h: Int) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        if (activeStream.value != null) {
            lastError = "هناك بث نشط حالياً — أوقفه أولاً"
            return@withContext false
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "يتطلب إذن الكاميرا على الجهاز المضيف"
            return@withContext false
        }
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wantedFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                               else CameraCharacteristics.LENS_FACING_BACK
            var chosenId: String? = null
            var chosenFacing: Int? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == wantedFacing) {
                    chosenId = id
                    chosenFacing = facing
                    break
                }
            }
            if (chosenId == null) {
                chosenId = cameraManager.cameraIdList.firstOrNull()
                chosenFacing = chosenId?.let {
                    cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
                }
            }
            if (chosenId == null) {
                lastError = "لا توجد كاميرا متاحة على الجهاز"
                return@withContext false
            }
            isFrontCamera = front || chosenFacing == CameraCharacteristics.LENS_FACING_FRONT

            val thread = HandlerThread("LiveRelayCamera").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
            cameraStopped = false

            val opened = CompletableDeferred<Boolean>()
            cameraManager.openCamera(chosenId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (cameraStopped) { device.close(); opened.complete(false); return }
                    cameraDevice = device
                    configureCameraSession(device, width, height, intervalMs, onFrame)
                    opened.complete(true)
                }

                override fun onDisconnected(device: CameraDevice) {
                    try { device.close() } catch (_: Throwable) {}
                    if (cameraDevice === device) cameraDevice = null
                    opened.complete(false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.w(TAG, "خطأ فتح الكاميرا: code=$error")
                    try { device.close() } catch (_: Throwable) {}
                    cameraDevice = null
                    lastError = "تعذر فتح الكاميرا (رمز الخطأ: $error)"
                    opened.complete(false)
                }
            }, cameraHandler)

            val ok = withTimeoutOrNull(CAMERA_OPEN_TIMEOUT_MS) { opened.await() } ?: false
            if (!ok) {
                if (lastError == null) lastError = "انتهت مهلة فتح الكاميرا"
                stopCameraInternalSafely()
                false
            } else true
        } catch (t: Throwable) {
            lastError = "فشل بدء بث الكاميرا عبر الترحيل: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, lastError ?: "", t)
            stopCameraInternalSafely()
            false
        }
    }

    private fun configureCameraSession(
        device: CameraDevice,
        width: Int,
        height: Int,
        intervalMs: Long,
        onFrame: (jpegBytes: ByteArray, w: Int, h: Int) -> Unit
    ) {
        if (cameraStopped || cameraReader != null) return
        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        cameraReader = reader
        val handler = cameraHandler ?: Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCameraFrameMs.get() < intervalMs) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastCameraFrameMs.set(now)
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val w = image.width
                val h = image.height
                if (activeStream.value == "camera") {
                    publishRotatedCameraFrame(bytes, w, h, onFrame)
                }
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
        }, handler)

        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraStopped || cameraDevice !== device) {
                    try { session.close() } catch (_: Throwable) {}
                    return
                }
                captureSession = session
                try {
                    val chars = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                        .getCameraCharacteristics(device.id)
                    val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    sensorOrientationDeg = sensorOrient
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        // v2: نطلب JPEG بلا دوران من HAL (0 = مخرج المستشعر الخام) ثم نصحح
                        // الدوران برمجياً بعد الفك بمساعدة publishRotatedCameraFrame —
                        // فيصبح الاتجاه محسوماً على كل الأجهزة بدل اختلاف تنفيذ
                        // الأجهزة لإشارة JPEG_ORIENTATION (سبب الصورة المدورة سابقاً).
                        set(CaptureRequest.JPEG_ORIENTATION, 0)
                    }.build()
                    session.setRepeatingRequest(request, null, handler)
                    activeStream.value = "camera"
                    lastError = null
                    Log.i(TAG, "بدء بث كاميرا عبر الترحيل ${width}x${height} عدسة=${if (isFrontCamera) "أمامية" else "خلفية"}")
                } catch (t: Throwable) {
                    Log.w(TAG, "فشل تفعيل الالتقاط المتكرر: ${t.message}")
                    lastError = "تعذر تشغيل التقاط الكاميرا: ${t.message}"
                    stopCameraInternalSafely()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.w(TAG, "فشل تهيئة جلسة الكاميرا للبث البديل")
                lastError = "تعذر تهيئة جلسة الكاميرا"
                stopCameraInternalSafely()
            }
        }, handler)
    }

    /** هل الكاميرا في وضع "أمامية"؟ يُعرض لجهاز التحكم عند تبديل العدسة. */
    fun isCameraFront(): Boolean = isFrontCamera

    /**
     * نشر إطار الكاميرا بعد تصحيح الدوران فعلياً (إصلاح v2):
     * 1) فك JPEG الخام من المستشعر.
     * 2) تدوير البتومات بزاوية `SENSOR_ORIENTATION` المخزّنة للعدسة النشطة
     *    (بلا انطباق مرايا — الترميز للمراقبة عن بُعد يُظهر ما تراه العدسة حرفياً).
     * 3) إعادة ضغط JPEG جودة 72 ونشره مع الأبعاد بعد الدوران (تبدّل عرض×طول عند 90/270).
     * عند فشل الفك/التدوير يُرسل الإطار الخام كما هو (سقوط آمن إلى سلوك v1).
     */
    private fun publishRotatedCameraFrame(
        raw: ByteArray,
        rawW: Int,
        rawH: Int,
        onFrame: (jpegBytes: ByteArray, w: Int, h: Int) -> Unit
    ) {
        streamScope.launch(Dispatchers.Default) {
            try {
                val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (decoded == null) {
                    onFrame(raw, rawW, rawH)
                    return@launch
                }
                val matrix = Matrix().apply { postRotate(sensorOrientationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                val outW = rotated.width
                val outH = rotated.height
                val out = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, 72, out)
                if (rotated !== decoded) decoded.recycle()
                rotated.recycle()
                onFrame(out.toByteArray(), outW, outH)
            } catch (t: Throwable) {
                Log.w(TAG, "تجاهل إطار كاميرا: ${t.message}")
            }
        }
    }

    /** يوقف الكاميرا فقط (الشاشة تبقى نشطة إن كانت تعمل). */
    fun stopCamera() = synchronized(this) {
        if (activeStream.value == "camera") {
            activeStream.value = null
        }
        stopCameraInternalSafely()
    }

    private fun stopCameraInternalSafely() {
        cameraStopped = true
        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { cameraReader?.close() } catch (_: Throwable) {}
        cameraReader = null
        try { cameraThread?.quitSafely() } catch (_: Throwable) {}
        cameraThread = null
        cameraHandler = null
    }

    // ———————————————————————— الشاشة ————————————————————————

    /**
     * يبدأ التقاط الشاشة عبر إذن MediaProjection (يُمنح من نشاط الإذن الشفاف
     * الموجود — [com.remotebridge.host.service.ScreenCapturePermissionActivity]).
     */
    suspend fun startScreen(
        permissionIntent: Intent,
        intervalMs: Long = 320,
        quality: Int = 50,
        maxWidth: Int = 640,
        onFrame: (jpegBytes: ByteArray, w: Int, h: Int) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        if (activeStream.value != null) {
            lastError = "هناك بث نشط حالياً — أوقفه أولاً"
            return@withContext false
        }
        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // النمط النظامي: getMediaProjection(RESULT_OK, intent) — نفس نمط libwebrtc
            // getMediaProjection قد تُعيد null في SDK الجديد — نتعامل معها صراحةً
            val projection = mpm.getMediaProjection(Activity.RESULT_OK, permissionIntent)
                ?: run {
                    lastError = "تعذّر إنشاء جلسة مشاركة الشاشة"
                    stopScreenInternal()
                    return@withContext false
                }
            mediaProjection = projection
            screenStopped = false

            val stopCb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "توقفت مشاركة الشاشة (من المستخدم أو النظام)")
                    screenStopped = true
                    stopScreenInternal()
                    if (activeStream.value == "screen") activeStream.value = null
                }
            }
            projectionCallback = stopCb
            // إلزامي على Android 14+ قبل createVirtualDisplay
            try {
                projection.registerCallback(stopCb, Handler(Looper.getMainLooper()))
            } catch (_: Throwable) {}

            // أبعاد الشاشة عبر DisplayMetrics (نمط codebase الموحّد)
            val metrics = context.resources.displayMetrics
            val realW = metrics.widthPixels
            val realH = metrics.heightPixels
            val dpi = metrics.densityDpi

            val reader = ImageReader.newInstance(realW, realH, PixelFormat.RGBA_8888, 3)
            screenReader = reader

            val vDisplay = projection.createVirtualDisplay(
                "rb_relay_screen",
                realW, realH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null, null
            )
            virtualDisplay = vDisplay

            // حلقة سحب وضغط مدفوعة بالفاصل المحدد
            screenPollJob = streamScope.launch(Dispatchers.IO) {
                while (isActive && !screenStopped && screenReader != null) {
                    try {
                        val img = screenReader?.acquireLatestImage()
                        if (img != null) {
                            try {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastScreenFrameMs.get() >= intervalMs) {
                                    lastScreenFrameMs.set(now)
                                    val jpeg = compressScreenImage(img, maxWidth, quality)
                                    if (jpeg != null && activeStream.value == "screen") {
                                        onFrame(jpeg, img.width, img.height)
                                    }
                                }
                            } finally {
                                try { img.close() } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {
                        // أحياناً يرمي acquireLatestImage إن كان الـ reader مغلقاً — نتجاهل بهدوء
                    }
                    delay(intervalMs)
                }
            }

            activeStream.value = "screen"
            lastError = null
            Log.i(TAG, "بدء بث شاشة عبر الترحيل ${realW}x${realH} جودة=$quality فاصل=$intervalMs")
            true
        } catch (t: Throwable) {
            lastError = "فشل بدء بث الشاشة عبر الترحيل: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, lastError ?: "", t)
            stopScreenInternal()
            false
        }
    }

    /** يحوّل صورة الشاشة RGBA إلى JPEG مضغوط، مع تقليص العرض إلى [maxWidth]. */
    private fun compressScreenImage(img: android.media.Image, maxWidth: Int, quality: Int): ByteArray? {
        return try {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val source: Bitmap
            if (rowStride == w * 4) {
                source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buffer.rewind()
                source.copyPixelsFromBuffer(buffer)
            } else {
                // مسار الحشو (rowStride لا يساوي العرض×4) — نسخ بكسل-بكسل لكل سطر
                source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val srcArr = IntArray(w * h)
                buffer.rewind()
                for (y in 0 until h) {
                    buffer.position(y * rowStride)
                    for (x in 0 until w) {
                        srcArr[y * w + x] = buffer.int
                    }
                }
                source.setPixels(srcArr, 0, w, 0, 0, w, h)
            }
            var out = source
            if (w > maxWidth) {
                val scaledH = (h.toLong() * maxWidth / w).toInt()
                out = Bitmap.createScaledBitmap(source, maxWidth, scaledH, true)
            }
            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val jpegBytes = baos.toByteArray()
            if (out !== source) out.recycle()
            source.recycle()
            jpegBytes
        } catch (t: Throwable) {
            Log.w(TAG, "تجاهل ضغط إطار الشاشة: ${t.message}")
            null
        }
    }

    /** يوقف الشاشة فقط (الكاميرا تبقى نشطة إن كانت تعمل). */
    fun stopScreen() = synchronized(this) {
        if (activeStream.value == "screen") {
            activeStream.value = null
        }
        stopScreenInternal()
    }

    private fun stopScreenInternal() {
        screenStopped = true
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { screenReader?.close() } catch (_: Throwable) {}
        screenReader = null
        try { projectionCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) } } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        projectionCallback = null
        screenPollJob?.cancel()
        screenPollJob = null
    }

    // ———————————————————————— إدارة عامة ————————————————————————

    /** يوقف كل مصادر البث البديل (كاميرا + شاشة). */
    fun stopAll() = synchronized(this) {
        activeStream.value = null
        stopCameraInternalSafely()
        stopScreenInternal()
    }

    /** نوع البث النشط حالياً أو null. */
    fun activeType(): String? = activeStream.value
}
