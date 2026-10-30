package com.remotebridge.host.capture

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * وحدة الالتقاط المباشر على المضيف: لقطات صور متعددة بفاصل زمني، وفيديو
 * بمدة محددة، وتسجيل صوت من الميكروفون. كل المخرجات تُسجّل في MediaStore
 * (معركة الوسائط) تحت مجلدات RemoteBridge المنظّمة داخل الأرشيف:
 *  - الصور    : Pictures/RemoteBridge/Front أو .../Back
 *  - الفيديوهات: Movies/RemoteBridge/Front أو .../Back
 *  - الصوت    : Music/RemoteBridge
 */
object RemoteCaptureModule {

    data class ShotResult(val name: String, val path: String, val size: Long)

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun mediaInsert(
        context: Context,
        kind: Int,                       // MediaStore.Images / Video / Audio
        relativeSub: String,             // مثل "Pictures/RemoteBridge/Front"
        displayName: String,
        mimeType: String,
        bytes: ByteArray? = null,
        durationMs: Int = 0
    ): ShotResult? {
        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val uriCollection =
            if (kind == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else if (kind == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (isQ) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSub)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (durationMs > 0) put(MediaStore.Video.Media.DURATION, durationMs.toLong())
            } else {
                // أجهزة أقدم من أندرويد 10: مسار مباشر في الخارجي
                val base = Environment.getExternalStoragePublicDirectory(
                    if (kind == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) Environment.DIRECTORY_PICTURES
                    else if (kind == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) Environment.DIRECTORY_MOVIES
                    else Environment.DIRECTORY_MUSIC
                )
                val dir = File(base, relativeSub.substringAfter("RemoteBridge").trim('/'))
                dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(uriCollection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> bytes?.let { out.write(it) } }
        if (isQ) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        val realPath = queryRealPath(resolver, uri)
        val size = realPath?.let { File(it).length() } ?: (bytes?.size?.toLong() ?: 0L)
        return ShotResult(displayName, realPath ?: relativeSub + "/" + displayName, size)
    }

    private fun queryRealPath(resolver: android.content.ContentResolver, uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }

    private fun cameraId(context: Context, front: Boolean): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    private fun baseName(prefix: String, ext: String): String =
        "${prefix}_${stamp.format(Date())}_${(100..999).random()}.$ext"

    /** حفظ لقطة شاشة (تُملأ من خدمة الإسقاط في المضيف) في Pictures/RemoteBridge/Screen. */
    fun saveScreenShot(context: Context, bytes: ByteArray): ShotResult? =
        mediaInsert(
            context,
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            "Pictures/RemoteBridge/Screen",
            baseName("RBScr", "jpg"),
            "image/jpeg",
            bytes
        )

    /** حفظ تسجيل شاشة في Movies/RemoteBridge/Screen. */
    fun saveScreenVideo(context: Context, bytes: ByteArray, durationSec: Int): ShotResult? =
        mediaInsert(
            context,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            "Movies/RemoteBridge/Screen",
            baseName("RBScrVid", "mp4"),
            "video/mp4",
            bytes,
            durationSec * 1000
        )

    /** حفظ تسجيل صوت مكالمة في Music/RemoteBridge/Calls (يُعرض في سجل المكالمات). */
    fun saveCallAudio(context: Context, bytes: ByteArray): ShotResult? =
        mediaInsert(
            context,
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
            "Music/RemoteBridge/Calls",
            baseName("RBCall", "m4a"),
            "audio/mp4",
            bytes
        )

    /**
     * لقطة كاميرا واحدة (Camera2 أمر واحد). تُعيد البايتات جاهزة الحفظ.
     */
    suspend fun capturePhoto(context: Context, front: Boolean): ShotResult? =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cameraId(context, front) ?: return@withContext null
            val latch = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
            var device: CameraDevice? = null
            var reader: android.media.ImageReader? = null

            try {
                // افتح الكاميرا عبر واجهة العدسة في سلسلة الواجهة الرئيسية ثم انتظر الجهاز
                val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
                val cb2 = object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) { device = d; opened.complete(true) }
                    override fun onDisconnected(d: CameraDevice) { d.close(); opened.complete(false) }
                    override fun onError(d: CameraDevice, error: Int) { d.close(); opened.complete(false) }
                }

                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasPermission) return@withContext null

                // الواجهة القديمة: openCamera يتطلب معالج تسليم الحدث
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                cm.openCamera(id, cb2, handler)
                val gotDevice = withTimeoutOrNullSafe(6000) { opened.await() }
                val d = device
                if (gotDevice != true || d == null) return@withContext null

                val chars = cm.getCameraCharacteristics(id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val size = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: android.util.Size(1280, 720)

                val imgReader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
                reader = imgReader
                imgReader.setOnImageAvailableListener({ r ->
                    val img = try { r.acquireLatestImage() } catch (_: Throwable) { null }
                    if (img == null) { latch.complete(null); return@setOnImageAvailableListener }
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        latch.complete(bytes)
                    } finally {
                        img.close()
                    }
                }, handler)

                val sessionLatch = kotlinx.coroutines.CompletableDeferred<Boolean>()
                d.createCaptureSession(listOf(imgReader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        try {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                .apply {
                                    addTarget(imgReader.surface)
                                    set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                                }.build()
                            s.stopRepeating()
                            s.capture(req, null, handler)
                            sessionLatch.complete(true)
                        } catch (_: Throwable) {
                            sessionLatch.complete(false)
                            latch.complete(null)
                        }
                    }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        sessionLatch.complete(false)
                        latch.complete(null)
                    }
                }, handler)

                val configured = withTimeoutOrNullSafe(6000) { sessionLatch.await() }
                if (configured != true) return@withContext null

                val bytes = withTimeoutOrNullSafe(10000) { latch.await() }
                if (bytes == null || bytes.isEmpty()) return@withContext null

                val folderLabel = if (front) "Front" else "Back"
                val name = baseName(if (front) "RBFront" else "RBBack", "jpg")
                mediaInsert(
                    context,
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                    "Pictures/RemoteBridge/$folderLabel",
                    name,
                    "image/jpeg",
                    bytes
                )
            } catch (_: Throwable) {
                null
            } finally {
                try { reader?.close() } catch (_: Throwable) {}
                try { device?.close() } catch (_: Throwable) {}
            }
        }

    /**
     * لقطات متعددة بفاصل زمني (ثواني): 5/10/30/60/300/600/3600
     */
    suspend fun capturePhotos(
        context: Context,
        front: Boolean,
        count: Int,
        intervalSeconds: Int,
        onEach: (Int, Int, ShotResult?) -> Unit = { _, _, _ -> }
    ): List<ShotResult> {
        val results = mutableListOf<ShotResult>()
        val total = count.coerceIn(1, 120)
        val interval = intervalSeconds.coerceIn(0, 3600)
        for (i in 1..total) {
            val shot = capturePhoto(context, front)
            if (shot != null) results.add(shot)
            onEach(i, total, shot)
            if (i < total && interval > 0) delay(interval * 1000L)
        }
        return results
    }

    /** تسجيل الفيديو بمدة محددة (ثواني) كليب واحد في Movies/RemoteBridge. */
    suspend fun captureVideo(
        context: Context,
        front: Boolean,
        durationSeconds: Int
    ): ShotResult? = withContext(Dispatchers.IO) {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return@withContext null

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId(context, front) ?: return@withContext null
        val tmpFile = File(context.cacheDir, baseName("RBVid", "mp4")).absolutePath
        var camera: CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
        try {
            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { camera = d; opened.complete(true) }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.complete(false) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); opened.complete(false) }
            }
            cm.openCamera(id, cb, handler)
            if (withTimeoutOrNullSafe(6000) { opened.await() } != true) return@withContext null

            val chars = cm.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@withContext null
            val size = map.getOutputSizes(android.media.MediaRecorder::class.java)
                .filter { it.width <= 1280 && it.height <= 720 }
                .maxByOrNull { it.width * it.height } ?: android.util.Size(1280, 720)

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = rec
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(tmpFile)
            rec.setVideoEncodingBitRate(4_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(size.width, size.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(96_000)
            rec.setMaxDuration(durationSeconds.coerceIn(1, 600) * 1000)

            val surfaces = listOf(rec.surface)
            val sessionReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
            camera?.createCaptureSession(surfaces, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                    session = s
                    try {
                        val preview = camera?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.apply {
                            addTarget(rec.surface)
                        }?.build()
                        if (preview != null) s.setRepeatingRequest(preview, null, handler)
                        sessionReady.complete(true)
                    } catch (_: Throwable) {
                        sessionReady.complete(false)
                    }
                }
                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                    sessionReady.complete(false)
                }
            }, handler)
            if (withTimeoutOrNullSafe(6000) { sessionReady.await() } != true) return@withContext null

            rec.prepare()
            rec.start()
            delay(durationSeconds.coerceIn(1, 600) * 1000L)
            try { rec.stop() } catch (_: Throwable) {}
            val bytes = File(tmpFile).readBytes()
            if (bytes.isEmpty()) return@withContext null
            val name = baseName(if (front) "RBFrontVid" else "RBBackVid", "mp4")
            mediaInsert(
                context,
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                "Movies/RemoteBridge/${if (front) "Front" else "Back"}",
                name,
                "video/mp4",
                bytes,
                durationSeconds * 1000
            )
        } catch (_: Throwable) {
            null
        } finally {
            try { recorder?.release() } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
            try { camera?.close() } catch (_: Throwable) {}
            try { File(tmpFile).delete() } catch (_: Throwable) {}
        }
    }

    /** تسجيل الصوت من الميكروفون بمدة محددة في Music/RemoteBridge. */
    suspend fun recordAudio(
        context: Context,
        durationSeconds: Int
    ): ShotResult? = withContext(Dispatchers.IO) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext null

        val tmpFile = File(context.cacheDir, baseName("RBAudio", "m4a")).absolutePath
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setMaxDuration(durationSeconds.coerceIn(1, 3600) * 1000)
            rec.setOutputFile(tmpFile)
            rec.prepare()
            rec.start()
            delay(durationSeconds.coerceIn(1, 3600) * 1000L)
            try { rec.stop() } catch (_: Throwable) {}
            val bytes = File(tmpFile).readBytes()
            if (bytes.isEmpty()) return@withContext null
            val name = baseName("RBAud", "m4a")
            mediaInsert(
                context,
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
                "Music/RemoteBridge",
                name,
                "audio/mp4",
                bytes
            )
        } catch (_: Throwable) {
            null
        } finally {
            try { rec.release() } catch (_: Throwable) {}
            try { File(tmpFile).delete() } catch (_: Throwable) {}
        }
    }

    private suspend fun <T> withTimeoutOrNullSafe(ms: Long, block: suspend () -> T): T? =
        try {
            kotlinx.coroutines.withTimeout(ms) { block() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
}
