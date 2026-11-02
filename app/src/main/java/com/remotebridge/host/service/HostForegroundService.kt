package com.remotebridge.host.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
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
import com.remotebridge.host.capture.LiveRelayServer
import com.remotebridge.host.capture.RemoteCaptureModule
import com.remotebridge.host.capture.RemoteCommandsModule
import com.remotebridge.host.capture.CommandSchedulerStore
import com.remotebridge.core.data.SettingsSyncManager
import com.remotebridge.host.service.HostRestartScheduler
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import com.remotebridge.core.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

        // قيمة إعدادات STUN تُستبدل عند بدء الخدمة من الإعدادات المخزنة
        var stunServerValue: String = "stun:stun.l.google.com:19302"

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
            // 🔧 B21 — موقّع النداء الجديد: مؤشر نجاح/فشل + بيان فاعل الفشل
            inst.peerManager?.switchCamera { success, _ ->
                if (success) {
                    cameraStreamingState.value = cameraStreamingState.value.copy(
                        isFrontCamera = inst.peerManager?.isUsingFrontCamera() ?: false
                    )
                }
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
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            // B19 — أذونات شرطية حسب إصدار النظام: الرد على المكالمات (28+) والبلوتوث (31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                required.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                required.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                required.add(Manifest.permission.READ_MEDIA_IMAGES)
                required.add(Manifest.permission.READ_MEDIA_VIDEO)
                required.add(Manifest.permission.READ_MEDIA_AUDIO)
                required.add(Manifest.permission.POST_NOTIFICATIONS)
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
    private var requestJob: Job? = null
    private val pendingLocalIceCandidates = mutableListOf<org.webrtc.IceCandidate>()
    // 🎬 محرك التسجيل واللقطة + 📍 تتبع الموقع + 🌐 مزامنة الحالة مع الخادم
    private var recordingEngine: RecordingEngine? = null
    private var locationTracker: LocationTracker? = null
    private var lastStreamMode: String? = null
    private var pausedStreamMode: String? = null
    private var pausedFrontCamera: Boolean = false
    private var pausedAudioEnabled: Boolean = false
    private var statePosterJob: Job? = null
    private var serverApiBase: String = ""
    private var hostDeviceName: String = ""
    // 🔐 B4: الخزنة المشفرة المسمدة + مسجل المكالمات الهاتفية (حفظ مشفر + رفع إلى Render)
    private var vaultManager: HostVaultManager? = null

    /// B8 — حد أقصى لحجم نقل محتوى الملف/الخزنة عبر قناة البيانات (48MB)
    private val MAX_CHUNK_FILE_BYTES: Long = 48L * 1024L * 1024L
    private var callRecorder: HostCallRecorder? = null
    // 🔁 B12 — مراقب عودة الشبكة لإعادة الاتصال الفوري
    private var netAliveCb: android.net.ConnectivityManager.NetworkCallback? = null

    // ===================== B26 — وحدات الدمج الكامل (ApkFull) =====================
    /** بث الترحيل MJPEG: كاميرا/شاشة كإطارات live_frame عبر قناة الإشارات دون ICE/TURN */
    private var liveRelay: LiveRelayServer? = null
    /** جهاز العرض المستهدف لإطارات الترحيل (يُحدَّد من طالب البث) */
    private var liveTargetId: String? = null
    private val liveFrameSeq = AtomicInteger(0)
    private var liveSendFailures = 0
    /** دورة تنفيذ الأوامر المجدولة كل 10 ثوان */
    private var schedulerJob: Job? = null
    // أوامر الشاشة المؤقتة (لقطة/تسجيل واحد تُنفَّذ فور منح إذن التقاط)
    private var pendingScreenAction: String? = null
    private var pendingScreenDuration = 0
    private var pendingScreenRequestId = -1

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
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
     *
     * 🔧 إصلاح عدم استجابة زر بدء المضيف: لم نعد نوقف البدء عند نقص أذونات حرجة بشكل صامت،
     * بل نكمل التشغيل دائماً؛ الميزات المرتبطة بأذونات ناقصة ترجع رسائل خطأ لطيفة
     * عبر الأوامر بدل الانهيار، والنقص يُعرض في واجهة شاشة المضيف.
     */
    private fun startHost(serverUrl: String, deviceName: String) {
        // تسجيل الأذونات الحرجة للسجل فقط — المتابعة دائماً (بدون حاجز صامت)
        if (!checkCriticalPermissions()) {
            Log.w(TAG, "استكمال بدء المضيف رغم نقص بعض الأذونات الأساسية — الميزات الناقصة سترجع أخطاء لطيفة")
        }

        try {
            // 💤 B12 — وضع الإخفاء مفعّل افتراضياً: قناة بأدنى أهمية + محتوى محايد
            // (لا أيقونة في شريط الحالة، لا صوت، لا نص يوحي بنشاط التتبع)
            var stealth = true
            try {
                stealth = kotlinx.coroutines.runBlocking {
                    SettingsRepository(applicationContext).stealthNotification.firstOrNull() ?: true
                }
            } catch (_: Throwable) { stealth = true }
            NotificationHelper.createChannel(this, stealth)
            val notification = NotificationHelper.buildHostNotification(this, stealth)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // 🔧 أنواع الخدمة تُحسب ديناميكياً حسب الأذونات الممنوحة فعلياً حتى لا يرمي
                    // startForeground استثناء صمت على أندرويد 14+. نوع mediaProjection لا يُطلب
                    // عند الإقلاع (يستلزم موافقة مستخدم نشطة) بل يُرفَع لاحقاً لحظة الموافقة.
                    var serviceTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val hasCameraPrem =
                            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        val hasMicPrem =
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasCameraPrem) {
                            serviceTypes = serviceTypes or
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        }
                        if (hasMicPrem) {
                            serviceTypes = serviceTypes or
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        }
                        val hasLocationPrem =
                            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasLocationPrem) {
                            serviceTypes = serviceTypes or
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        }
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

        // ===== B26 — مح بث الترحيل MJPEG + دورة الأوامر المجدولة (كل 10 ثوان) =====
        try {
            liveRelay = LiveRelayServer(applicationContext, scope)
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر تهيئة وحدة بث الترحيل: ${t.message}")
        }
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                runScheduledDueCommands()
            }
        }

        // بناء قائمة ICE servers من الإعدادات (STUN فقط عبر خادم رندر)
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers.add(PeerConnection.IceServer.builder(stunServerValue).createIceServer())

        peerManager = PeerConnectionManager(
            applicationContext,
            scope,
            iceServers
        ).also { manager ->
            manager.start()
        }

        activeInstance = this
        serviceState.value = serviceState.value.copy(running = true)
        // 🔁 B12 — إعادة الاتصال فور عودة الشبكة (خدمة البقاء مفعّلة دائماً)
        registerNetworkAliveWatcher()

        scope.launch {
            val deviceId = DeviceIdentifier.getOrGenerate(this@HostForegroundService)
            hostDeviceId = deviceId
            val settingsRepo = SettingsRepository(applicationContext)
            val storedUrl = settingsRepo.serverUrl.firstOrNull() ?: SettingsRepository.DEFAULT_RENDER_URL
            val finalUrl = serverUrl.ifBlank { storedUrl }

            // قراءة إعدادات STUN من DataStore
            stunServerValue = settingsRepo.stunServer.firstOrNull() ?: SettingsRepository.DEFAULT_STUN_SERVER

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

            startLiveEventPush()
            collectPeerEvents()

            // 📍 تتبع الفيولي للهاتف (كل 3 دقائق نبضة) + مزامنة الحالة الصغيرة إلى الخادم
            ensureLocationTracker()
            serverApiBase = normalizeServerApiBase(finalUrl)

            // 🔐 B4: تهيئة الخزنة المشفرة + مسجل المكالمات (مفعّل حسب الإعداد المحفوظ)
            try {
                vaultManager = HostVaultManager(applicationContext)
                callRecorder = HostCallRecorder(
                    applicationContext,
                    onSavedCall = { kind, name, plain ->
                        val vm = vaultManager
                        if (vm != null && plain.isNotEmpty()) {
                            vm.storeEncryptedBlob(kind, name, plain)
                            // محاولات رفع لطيفة بعد كل مكالمة إن كان الشبكة متاحة
                            scope.launch { runCatching { vm.flushUploads(serverApiBase, hostDeviceId) { isNetworkOnline() } } }
                        }
                    },
                    onError = { err ->
                        Log.w(TAG, "مسجل المكالمات: $err")
                        pushLiveEvent(
                            "call_record_error",
                            buildJsonObject { put("error", err); put("ts", System.currentTimeMillis()) }
                        )
                    }
                ).also { rec ->
                    if (rec.isEnabled) rec.attach()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "تهيئة الخزنة/مسجل المكالمات فشلت: ${t.message}")
            }

            startStatePoster()
        }
    }

    /**
     * 🔁 B12 — مراقب صلابة الاتصال: أي(collection) عودة للإنترنت (WiFi/بيانات)
     * تلقّي إعادة الاتصال بالخادم فوراً بدل انتظار انتهاء تأخير التراجع —
     * أساس ضمان بقاء الهاتف المضيف متصلاً بالخادم بعد إغلاق واجهته.
     */
    private fun registerNetworkAliveWatcher() {
        try {
            if (netAliveCb != null) return
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.i(TAG, "عادت الشبكة — إعادة اتصال فورية بالخادم")
                    try { signaling?.forceReconnect() } catch (_: Throwable) {}
                }
            }
            cm.registerNetworkCallback(req, cb)
            netAliveCb = cb
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر تسجيل مراقب الشبكة: ${t.message}")
        }
    }

    private fun unregisterNetworkAliveWatcher() {
        try {
            netAliveCb?.let { cb ->
                getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Throwable) {}
        netAliveCb = null
    }

    /**
     * (وحدت الدالة إلى نسخة الـ companion المدرِكة للإصدارات في أعلى الفئة.)
     */

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
                        // B26 — دفع لقطة إعدادات المضيف للمتحكم فور تماسك الاتصال (مزامنة أولية بالأحدث يفوز)
                        scope.launch {
                            try {
                                signaling?.send(
                                    Signal.SettingsSync(
                                        from = hostDeviceId.ifBlank { signal.to },
                                        to = signal.from,
                                        ts = System.currentTimeMillis(),
                                        settings = SettingsSyncManager.buildSnapshot(applicationContext)
                                    )
                                )
                            } catch (t: Throwable) {
                                Log.w(TAG, "تعذر دفع لقطة الإعدادات للمتحكم: ${t.message}")
                            }
                        }
                        Log.d(TAG, "إرسال إجابة WebRTC Answer إلى المتحكم: ${signal.from} من: $myId")
                        signaling?.send(
                            Signal.Answer(
                                from = myId,
                                to = signal.from,
                                sdp = answerSdp,
                                sdpType = "ANSWER"
                            )
                        )
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

            is Signal.Answer -> {
                // B5 — توافق مستقبلي: إن أصدر المضيف عرضاً (تفاوض) يُطبَّق الرد هنا
                Log.d(TAG, "استقبال إجابة WebRTC من المتحكم ${signal.from} — تطبيقها")
                peerManager?.setRemoteAnswer(signal.sdp, signal.sdpType)
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
                // B26 — تحديد جهاز العرض المستهدف لإطارات البث البديل عبر الترحيل
                if (signal.cmd == Commands.START_CAMERA_STREAM || signal.cmd == Commands.START_SCREEN_STREAM) {
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
                // B26 — مزامنة الإعدادات: ts=0 سحب (pull) يردّ بلقطة كاملة، وts>0 تعديل يُتبنّى إن كان أحدث
                scope.launch {
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
                        Log.d(TAG, "أُرسلت لقطة إعدادات عند طلب السحب من ${signal.from}")
                    } else {
                        val result = SettingsSyncManager.applyIncoming(applicationContext, signal.settings)
                        if (result != null) {
                            Log.i(TAG, "لُقطة إعدادات واردة من ${signal.from}: اعتماد=${result.adopted}، تغيير الخادم=${result.serverUrlChanged}، ts=${result.newTs}")
                            if (result.adopted) {
                                val myId = hostDeviceId.ifBlank { DeviceIdentifier.getOrGenerate(this@HostForegroundService) }
                                // نرد بالمُحدَّث لضبط الاتجاهية: يصبح الطرفان متطابقين
                                signaling?.send(
                                    Signal.SettingsSync(
                                        from = myId,
                                        to = signal.from,
                                        ts = result.newTs,
                                        settings = SettingsSyncManager.buildSnapshot(applicationContext)
                                    )
                                )
                                if (result.serverUrlChanged && serviceState.value.running) {
                                    Log.w(TAG, "تغيّر عنوان الخادم من لوحة المتحكم — إعادة تشغيل سريعة مجدولة")
                                    HostRestartScheduler.scheduleQuickRestart(applicationContext, delaySeconds = 2)
                                }
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
                if (fileId != null) {
                    sendFileToController(manager, request.id, fileId, bucket)
                }
                return
            }

            // 🔧 B8 — محتوى حقيقي للمشغّل الاحترافي ورفع Drive:
            // 1) ملف تخزين عبر مسار كامل (متصفح المجلدات/الاستوديو)
            if (request.cmd == Commands.STORAGE_OPEN) {
                val path = request.params?.get("path")?.jsonPrimitive?.contentOrNull
                if (!path.isNullOrBlank()) {
                    sendPathToController(manager, request.id, path)
                } else {
                    manager.sendResponse(BridgeResponse(request.id, false, "مسار الملف مفقود", null))
                }
                return
            }

            // 2) عنصر خزنة مشفرة يُفكّ تشفيره في الذاكرة ثم يُرسل قطعاً (استدعاء تسجيلات وكاميرا وصوت)
            if (request.cmd == Commands.VAULT_OPEN) {
                val kind = request.params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
                val name = request.params?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                if (kind.isNotBlank() && name.isNotBlank()) {
                    sendVaultToController(manager, request.id, kind, name)
                } else {
                    manager.sendResponse(BridgeResponse(request.id, false, "معرفات الخزنة مفقودة", null))
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

    // ==========================================================
    //          Phase 2 — الأحداث الحية (Push Events)
    // ==========================================================
    private var liveWatchers: LiveEventWatchers? = null

    private fun startLiveEventPush() {
        val provider = dataProvider ?: return
        if (liveWatchers != null) return
        liveWatchers = LiveEventWatchers(applicationContext, scope, provider) { kind, payload ->
            pushLiveEvent(kind, payload)
        }.also { it.start() }
    }

    private fun stopLiveEventPush() {
        liveWatchers?.stop()
        liveWatchers = null
    }

    /**
     * يدفع حدثاً حياً (إشعار / رسالة / مكالمة) إلى المتحكم النشط حالياً.
     */
    private fun pushLiveEvent(kind: String, payload: Any?) {
        val target = activeControllerId
        if (target.isNullOrBlank()) return
        val myId = hostDeviceId
        if (myId.isBlank()) return
        val jsonPayload = when (payload) {
            is com.remotebridge.core.model.NotificationItem -> buildJsonObject {
                put("id", payload.id)
                put("packageName", payload.packageName)
                put("title", payload.title)
                put("text", payload.text)
                put("postTime", payload.postTime)
            }
            is SmsMessage -> buildJsonObject {
                put("id", payload.id)
                put("threadId", payload.threadId)
                put("address", payload.address)
                put("body", payload.body)
                put("date", payload.date)
                put("type", payload.type)
            }
            is CallLogItem -> buildJsonObject {
                put("id", payload.id)
                put("number", payload.number)
                put("date", payload.date)
                put("durationSec", payload.durationSec)
                put("type", payload.type)
            }
            // دفع JSON خام مباشرة (موقع / نبض بدء البث ...)
            is kotlinx.serialization.json.JsonObject -> payload
            else -> return
        }
        scope.launch {
            try {
                val sent = signaling?.send(
                    Signal.PushEvent(from = myId, to = target, kind = kind, payload = jsonPayload)
                ) ?: false
                Log.d(TAG, "دفع حدث حي [$kind] إلى $target: $sent")
            } catch (t: Throwable) {
                Log.w(TAG, "فشل دفع حدث حي [$kind]: ${t.message}")
            }
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

            // 🔧 B5 — إصلاح استيراد سجل المكالمات: رسالة تحذير إذن أوضح (كان الرد يوصل والمتحكم يعرض القائمة الفارغة فقط)
            Commands.CALLS_LIST -> {
                val calls = provider.readCallLog()
                buildJsonObject {
                    put("cmd", Commands.CALLS_LIST)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(CallLogItem.serializer()),
                            calls
                        )
                    )
                    if (calls.isEmpty()) {
                        val missing = getMissingPermissions(this@HostForegroundService)
                        if (missing.contains(Manifest.permission.READ_CALL_LOG)) {
                            put("permissionWarning", "إذن قراءة سجل المكالمات (READ_CALL_LOG) غير ممنوح للجسر — اسمحه من معالج الأذونات في التطبيق ثم أعد الجلب")
                        } else if (missing.isEmpty()) {
                            put("note", "سجل المكالمات فارغ أو مقيد على هذا الإصدار من أندرويد")
                        }
                    }
                }
            }

            Commands.STORAGE_EXPLORE -> {
                val path = request.params?.get("path")?.jsonPrimitive?.contentOrNull
                // B11 — Android 11+: بدون إذن «كل الملفات» تعود قوائم المجلدات فارغة؛
                // يُعلَم المتحكم ليوجّه المستخدم لمنح الإذن بدلاً من فراغ غامض
                val allFilesMissing = android.os.Build.VERSION.SDK_INT >= 30 &&
                    !android.os.Environment.isExternalStorageManager()
                buildJsonObject {
                    put("cmd", Commands.STORAGE_EXPLORE)
                    put("currentPath", path ?: "/")
                    if (allFilesMissing) put("needsAllFilesAccess", true)
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
                // 🔒 حماية لطيفة: رسالة إذن واضحة بدل فشل عام إذا لم يُمنح الكاميرا بعد
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.START_CAMERA_STREAM)
                        put("started", false)
                        put("permissionMissing", true)
                        put("error", "إذن الكاميرا غير ممنوح على الهاتف المضيف — افتح تطبيق الاستوديو واسمح بها من بطاقة «أذونات الكاميرا والميكروفون» ثم أعد المحاولة")
                    }
                } else {
                    val front = request.params?.get("front")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                    val audio = request.params?.get("audio")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
                    // 🔧 B21 — فحص إذن الميكروفون: عند غيابه نبدأ البث المرئي وحده بصدقٍ ونبلّغ
                    // بحقل `audioNoPermission` بدلاً من النجاح الباذخ بلا صوت، ونبلّغ للتلميح.
                    val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val withAudio = audio && micGranted
                    val mode = request.params?.get("mode")?.jsonPrimitive?.contentOrNull ?: ""
                    var transport = ""
                    var relayError: String? = null
                    var ok = false

                    // B26 — الوضع الصريح للترحيل (live_frame عبر قناة الإشارات بلا ICE/TURN)
                    if (mode == "relay") {
                        // نوقف مسار WebRTC أولاً لتفادي فتح الكاميرا مرتين
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
                        } else {
                            relayError = relay?.lastError
                        }
                    }

                    if (!ok) {
                        // مسار WebRTC الأساسي (B17/B21 — أعلى جودة وسياسة الصوت الجديدة)
                        ok = manager?.startCameraStreaming(front, withAudio) ?: false
                        if (ok) transport = "webrtc"
                    }

                    if (!ok) {
                        // B26 — سقوط بديل صادق: عند فشل WebRTC نجرب الترحيل تلقائياً حفاظاً على الحياة
                        val relay = liveRelay
                        ok = relay?.startCamera(
                            front = front,
                            onFrame = { bytes, width, height ->
                                pushLiveFrame("camera", bytes, width, height)
                            }
                        ) ?: false
                        if (ok) transport = "relay" else relayError = relay?.lastError ?: relayError
                    }

                    if (transport == "relay") {
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = ok,
                            isFrontCamera = liveRelay?.isCameraFront() ?: front,
                            isAudioEnabled = false
                        )
                    } else {
                        if (ok) pushStreamStarted("camera", withAudio)
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = ok,
                            isFrontCamera = manager?.isUsingFrontCamera() ?: false,
                            isAudioEnabled = ok && withAudio
                        )
                    }

                    buildJsonObject {
                        put("cmd", Commands.START_CAMERA_STREAM)
                        put("started", ok)
                        put("mode", if (transport == "relay") "camera-relay" else "camera")
                        put("transport", transport)
                        put("isFront", if (transport == "relay") (liveRelay?.isCameraFront() ?: front) else (manager?.isUsingFrontCamera() ?: false))
                        put("audioEnabled", if (transport == "relay") false else ok && withAudio)
                        if (audio && !micGranted && transport != "relay") put("audioNoPermission", true)
                        if (relayError != null) put("relayError", relayError)
                        if (!ok) {
                            put("error", "تعذر بدء بث الكاميرا (فشل مسارا WebRTC والترحيل معاً) — تأكد من إذن الكاميرا على الهاتف المضيف ثم أعد المحاولة")
                        }
                    }
                }
            }

            Commands.START_AUDIO_STREAM -> {
                // 🔒 حماية لطيفة: رسالة إذن واضحة بدل فشل عام إذا لم يُمنح الميكروفون بعد
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.START_AUDIO_STREAM)
                        put("started", false)
                        put("permissionMissing", true)
                        put("error", "إذن الميكروفون غير ممنوح على الهاتف المضيف — افتح تطبيق الاستوديو واسمح به من بطاقة «أذونات الكاميرا والميكروفون» ثم أعد المحاولة")
                    }
                } else {
                    val ok = manager?.startAudioOnlyStreaming() ?: false
                    if (ok) pushStreamStarted("audio", true)
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
            }

            Commands.STOP_CAMERA_STREAM -> {
                // B26 — يوقف الوسيلة البديلة (كاميرا + شاشة عبر الترحيل) ومسار WebRTC معاً
                manager?.stopStreaming()
                liveRelay?.stopAll()
                liveTargetId = null
                liveSendFailures = 0
                lastStreamMode = null
                cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                buildJsonObject {
                    put("cmd", Commands.STOP_CAMERA_STREAM)
                    put("stopped", true)
                }
            }

            Commands.SWITCH_CAMERA -> {
                val relay = liveRelay
                if (relay?.activeType() == "camera") {
                    // B26 — تبديل العدسة في وضع الترحيل: إعادة بدء بتوجيه معاكس مع ردود صادقة
                    val wasFront = relay.isCameraFront()
                    relay.stopCamera()
                    scope.launch {
                        val relayOk = relay.startCamera(
                            front = !wasFront,
                            onFrame = { bytes, width, height ->
                                pushLiveFrame("camera", bytes, width, height)
                            }
                        )
                        cameraStreamingState.value = cameraStreamingState.value.copy(
                            isStreaming = relayOk,
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
                // 🔧 B17/B21 — إجابة صادقة: `switchCamera` ينتظر جواب قافلة `Camera2`
                // داخلياً ويردّ بمؤشر نجاح/فشل حقيقيين، فلا يخرج «تم» كاذبة للمتحكم أبداً.
                val streaming = manager?.isCameraStreaming() == true
                if (streaming) {
                    var switchSucceeded = false
                    var switchFailedReason: String? = null
                    manager?.switchCamera { ok, err ->
                        switchSucceeded = ok
                        switchFailedReason = err
                    }
                    val nowFront = manager?.isUsingFrontCamera() ?: false
                    if (switchSucceeded) {
                        cameraStreamingState.value = cameraStreamingState.value.copy(isFrontCamera = nowFront)
                        buildJsonObject {
                            put("cmd", Commands.SWITCH_CAMERA)
                            put("switched", true)
                            put("isFront", nowFront)
                        }
                    } else {
                        buildJsonObject {
                            put("cmd", Commands.SWITCH_CAMERA)
                            put("switched", false)
                            put("isFront", nowFront)
                            put("error", switchFailedReason ?: "تعذر تبديل الكاميرا على الهاتف المضيف")
                        }
                    }
                } else {
                    buildJsonObject {
                        put("cmd", Commands.SWITCH_CAMERA)
                        put("switched", false)
                        put("isFront", manager?.isUsingFrontCamera() ?: false)
                        put("error", "لا يوجد بث كاميرا نشط للتبديل — ابدأ بث الكاميرا أولاً ثم ظبّل العدسة")
                    }
                }
                }
            }

            Commands.START_SCREEN_STREAM -> {
                val mode = request.params?.get("mode")?.jsonPrimitive?.contentOrNull ?: ""
                val intent = screenCaptureIntent
                if (intent == null) {
                    // إطلاق نشاط طلب الإذن من المستخدم
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
                } else if (mode != "relay" && manager != null) {
                    val ok = manager.startScreenStreaming(intent)
                    if (ok) {
                        // 🔧 B21 — السياسة الجديدة: بث الشاشة يبدأ بصوت الميكروفون مفعّلاً افتراضياً
                        pushStreamStarted("screen", true)
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = ok,
                            isFrontCamera = false,
                            isAudioEnabled = ok
                        )
                        buildJsonObject {
                            put("cmd", Commands.START_SCREEN_STREAM)
                            put("started", ok)
                            put("mode", "screen")
                            put("transport", "webrtc")
                            put("audioEnabled", ok)
                        }
                    } else {
                        // B26 — سقوط بديل: بث الشاشة عبر الترحيل عند فشل WebRTC
                        val relay = liveRelay
                        val relayOk = relay?.startScreen(
                            permissionIntent = intent,
                            onFrame = { bytes, width, height ->
                                pushLiveFrame("screen", bytes, width, height)
                            }
                        ) ?: false
                        if (relayOk) liveSendFailures = 0
                        cameraStreamingState.value = CameraStreamingState(
                            isStreaming = relayOk,
                            isFrontCamera = false,
                            isAudioEnabled = false
                        )
                        buildJsonObject {
                            put("cmd", Commands.START_SCREEN_STREAM)
                            put("started", relayOk)
                            put("mode", "screen-relay")
                            put("transport", "relay")
                            put("audioEnabled", false)
                            if (!relayOk) put("error", "تعذر بدء بث الشاشة: فشل مسارا WebRTC والترحيل معاً")
                        }
                    }
                } else {
                    // B26 — الوضع الصريح: بث الشاشة عبر الترحيل (لا يمكن تشغيل مصدرين معاً)
                    val relay = liveRelay
                    if (relay?.activeType() == "camera") relay.stopCamera()
                    val relayOk = relay?.startScreen(
                        permissionIntent = intent,
                        onFrame = { bytes, width, height ->
                            pushLiveFrame("screen", bytes, width, height)
                        }
                    ) ?: false
                    if (relayOk) liveSendFailures = 0
                    cameraStreamingState.value = CameraStreamingState(
                        isStreaming = relayOk,
                        isFrontCamera = false,
                        isAudioEnabled = false
                    )
                    buildJsonObject {
                        put("cmd", Commands.START_SCREEN_STREAM)
                        put("started", relayOk)
                        put("mode", "screen-relay")
                        put("transport", "relay")
                        put("audioEnabled", false)
                        if (!relayOk) put("error", "تعذر بدء بث الشاشة عبر الترحيل — تأكد من إذن التقاط الشاشة على المضيف")
                    }
                }
            }

            Commands.TOGGLE_AUDIO -> {
                // 🔧 B17 — تفعيل/كتم بلا أي إيقاف للاتصال، ودائماً نرد بالحالة الفعلية من المدير
                val enable = request.params?.get("enable")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
                manager?.setAudioEnabled(enable)
                val actual = manager?.isAudioTrackEnabled() ?: false
                cameraStreamingState.value = cameraStreamingState.value.copy(isAudioEnabled = actual)
                buildJsonObject {
                    put("cmd", Commands.TOGGLE_AUDIO)
                    put("audioEnabled", actual)
                }
            }

            Commands.FILE_THUMBNAIL -> {
                val fileId = request.params?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val path = request.params?.get("path")?.jsonPrimitive?.contentOrNull
                val kind = request.params?.get("kind")?.jsonPrimitive?.contentOrNull
                val sizePx = request.params?.get("size")?.jsonPrimitive?.contentOrNull?.let { s ->
                    when (s.lowercase()) {
                        "large" -> 480
                        "small" -> 96
                        else -> s.toIntOrNull() ?: 0
                    }
                } ?: 0
                val dataUrl = provider.readThumbnailDataUrl(fileId ?: 0L, path, kind, sizePx)
                buildJsonObject {
                    put("cmd", Commands.FILE_THUMBNAIL)
                    put("thumbnail", dataUrl ?: "")
                }
            }

            Commands.MEDIA_FOLDERS -> {
                val folderList = provider.listMediaFolders()
                buildJsonObject {
                    put("cmd", Commands.MEDIA_FOLDERS)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            folderList
                        )
                    )
                }
            }

            Commands.MEDIA_ITEMS -> {
                val folders = request.params?.get("folders")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val limit = request.params?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 240
                val items = provider.readMediaItems(folders, limit)
                buildJsonObject {
                    put("cmd", Commands.MEDIA_ITEMS)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            items
                        )
                    )
                }
            }

            // 📷 لقطة شاشة فورية (يوقف البث مؤقتاً لو كان نشطاً ثم يعيده تلقائياً)
            Commands.SCREENSHOT -> {
                val engine = ensureRecordingEngine()
                if (engine == null) {
                    buildJsonObject {
                        put("cmd", Commands.SCREENSHOT)
                        put("error", "تعذر تهيئة محرك اللقطة على المضيف")
                    }
                } else {
                    val wasStreaming = cameraStreamingState.value.isStreaming
                    if (wasStreaming) {
                        pausedStreamMode = lastStreamMode ?: "screen"
                        pausedFrontCamera = cameraStreamingState.value.isFrontCamera
                        pausedAudioEnabled = cameraStreamingState.value.isAudioEnabled
                        manager?.stopStreaming()
                        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                        lastStreamMode = null
                    }
                    val (dataUrl, err) = engine.takeScreenshot(screenCaptureIntent)
                    if (wasStreaming) restartPausedStream()
                    buildJsonObject {
                        put("cmd", Commands.SCREENSHOT)
                        if (dataUrl != null) {
                            put("image", dataUrl)
                            put("saved", true)
                        } else {
                            put("error", err ?: "فشل التقاط اللقطة")
                        }
                    }
                }
            }

            // ⏺ بدء تسجيل مجدول (شاشة/كاميرا/صوت)
            Commands.REC_START -> {
                val kind = request.params?.get("kind")?.jsonPrimitive?.contentOrNull ?: "screen"
                if (kind != "screen" && kind != "camera" && kind != "audio") {
                    buildJsonObject {
                        put("cmd", Commands.REC_START)
                        put("error", "نوع تسجيل غير مدعوم: $kind")
                    }
                } else {
                    val engine = ensureRecordingEngine()
                    if (engine == null) {
                        buildJsonObject {
                            put("cmd", Commands.REC_START)
                            put("error", "تعذر تهيئة محرك التسجيل على المضيف")
                        }
                    } else {
                        val front = request.params?.get("front")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                        val wantAudio = request.params?.get("audio")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
                        val delaySec = request.params?.get("delaySec")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val segmentSec = request.params?.get("segmentSec")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 180L
                        val intervalSec = request.params?.get("intervalSec")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val repeatCount = request.params?.get("repeatCount")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                        // تفريغ الإسقاط/الجهاز من البث الحي عند تعارض النوعين
                        val conflicts = (kind == "screen" && cameraStreamingState.value.isStreaming) ||
                            (kind == "camera" && lastStreamMode == "camera") ||
                            (kind == "audio" && lastStreamMode == "audio")
                        if (conflicts) {
                            pausedStreamMode = lastStreamMode ?: "screen"
                            pausedFrontCamera = cameraStreamingState.value.isFrontCamera
                            pausedAudioEnabled = cameraStreamingState.value.isAudioEnabled
                            manager?.stopStreaming()
                            cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                            lastStreamMode = null
                        }
                        val err = engine.startRecording(
                            kind,
                            if (kind == "screen") screenCaptureIntent else null,
                            front,
                            wantAudio,
                            delaySec.coerceAtLeast(0L),
                            segmentSec,
                            intervalSec,
                            repeatCount
                        )
                        buildJsonObject {
                            put("cmd", Commands.REC_START)
                            put("kind", kind)
                            if (err == null) {
                                put("recStarted", true)
                                put("scheduled", delaySec > 0L)
                                put("segmentSec", segmentSec)
                                put("intervalSec", intervalSec)
                                put("repeatCount", repeatCount)
                            } else {
                                put("error", err)
                            }
                        }
                    }
                }
            }

            // ⏹ إيقاف تسجيل
            Commands.REC_STOP -> {
                val kind = request.params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
                val engine = recordingEngine
                val stopped = if (kind.isBlank()) {
                    if (engine == null) false else {
                        for (k in engine.activeKinds()) engine.stopRecording(k)
                        true
                    }
                } else {
                    engine?.stopRecording(kind) ?: false
                }
                buildJsonObject {
                    put("cmd", Commands.REC_STOP)
                    put("stopped", stopped)
                    put("kind", kind)
                }
            }

            // 📂 أرشيف التسجيلات واللقطات (مجلدات: screen/camera/audio/screenshot)
            Commands.RECORDINGS_LIST -> {
                val engine = ensureRecordingEngine()
                val items = engine?.listRecordings() ?: emptyList()
                buildJsonObject {
                    put("cmd", Commands.RECORDINGS_LIST)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            items
                        )
                    )
                }
            }

            // 📍 نقاط سجل المواقع المختصرة (تُعرض على خارطة تفاعلية)
            Commands.LOCATIONS_GET -> {
                val limit = request.params?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200
                val sinceTs = request.params?.get("sinceTs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val untilTs = request.params?.get("untilTs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val tracker = locationTracker
                val items = if (sinceTs != null || untilTs != null) {
                    tracker?.queryPoints(sinceTs, untilTs, limit) ?: emptyList()
                } else {
                    tracker?.getPoints(limit) ?: emptyList()
                }
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_GET)
                    put("tracking", tracker?.isRunning() ?: false)
                    put("intervalSec", tracker?.getIntervalSec() ?: 30L)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            items
                        )
                    )
                }
            }

            Commands.LOCATIONS_CLEAR -> {
                locationTracker?.clear()
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_CLEAR)
                    put("cleared", true)
                }
            }

            // 📍 B12 — ضبط فاصل تسجيل النقاط + إدارة الأرشيف الدائم
            Commands.LOCATIONS_INTERVAL_SET -> {
                val sec = request.params?.get("seconds")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
                val tracker = locationTracker
                if (tracker == null || sec <= 0L) {
                    buildJsonObject {
                        put("cmd", Commands.LOCATIONS_INTERVAL_SET)
                        put("ok", false)
                        put("error", "تعذر تطبيق الفاصل — تأكد من تفعيل التتبع ومن قيمة صحيحة")
                    }
                } else {
                    val used = tracker.setInterval(sec)
                    buildJsonObject {
                        put("cmd", Commands.LOCATIONS_INTERVAL_SET)
                        put("ok", true)
                        put("intervalSec", used)
                    }
                }
            }

            Commands.LOCATIONS_ARCHIVE_LIST -> {
                val notes = locationTracker?.archiveList() ?: emptyList()
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_ARCHIVE_LIST)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            notes
                        )
                    )
                }
            }

            Commands.LOCATIONS_ARCHIVE_SAVE -> {
                val name = request.params?.get("name")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val sinceTs = request.params?.get("sinceTs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val untilTs = request.params?.get("untilTs")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val entry = locationTracker?.archiveSave(name.takeIf { it.isNotEmpty() }, sinceTs, untilTs)
                if (entry == null) {
                    buildJsonObject {
                        put("cmd", Commands.LOCATIONS_ARCHIVE_SAVE)
                        put("ok", false)
                        put("error", "لا توجد نقاط قابلة للأرشفة في هذا المدى")
                    }
                } else {
                    buildJsonObject {
                        put("cmd", Commands.LOCATIONS_ARCHIVE_SAVE)
                        put("ok", true)
                        put("archive", entry)
                    }
                }
            }

            Commands.LOCATIONS_ARCHIVE_POINTS -> {
                val id = request.params?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                val pts = locationTracker?.archivePoints(id) ?: emptyList()
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_ARCHIVE_POINTS)
                    put("count", pts.size)
                    put(
                        "items",
                        json.encodeToJsonElement(
                            ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
                            pts
                        )
                    )
                }
            }

            Commands.LOCATIONS_ARCHIVE_RENAME -> {
                val id = request.params?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                val name = request.params?.get("name")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val ok = locationTracker?.archiveRename(id, name) ?: false
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_ARCHIVE_RENAME)
                    put("ok", ok)
                }
            }

            Commands.LOCATIONS_ARCHIVE_DELETE -> {
                val id = request.params?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                val ok = locationTracker?.archiveDelete(id) ?: false
                buildJsonObject {
                    put("cmd", Commands.LOCATIONS_ARCHIVE_DELETE)
                    put("ok", ok)
                }
            }

            // ☎️ B4 — تفعيل/تعطيل تسجيل المكالمات الهاتفية (تُحفظ مشفرة وتُرفع للخادم لما يتاح الإنترنت)
            Commands.CALL_RECORD_SET -> {
                val enabled = request.params?.get("enabled")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                val rec = callRecorder
                if (rec == null) {
                    buildJsonObject {
                        put("cmd", Commands.CALL_RECORD_SET)
                        put("ok", false)
                        put("error", "مسجل المكالمات غير جاهز على المضيف")
                    }
                } else {
                    val attached = try { rec.setEnabled(enabled); true } catch (t: Throwable) { false }
                    buildJsonObject {
                        put("cmd", Commands.CALL_RECORD_SET)
                        put("ok", attached)
                        put("enabled", enabled)
                        if (!attached) put("error", "تعذر ربط مسجل المكالمات — تأكد من إذني الميكروفون وحالة الهاتف على المضيف")
                    }
                }
            }

            // 🔐 B4 — دفعة دفء يدوية: نسخ الخزنة المشفرة + رفع المعلق إلى خادم Render
            Commands.VAULT_FLUSH -> {
                val vault = vaultManager
                if (vault == null) {
                    buildJsonObject {
                        put("cmd", Commands.VAULT_FLUSH)
                        put("ok", false)
                        put("error", "الخزنة غير مهيأة بعد")
                    }
                } else {
                    scope.launch {
                        try {
                            vaultTick()
                        } catch (t: Throwable) {
                            Log.w(TAG, "دفع خزنة يدوي فشل: ${t.message}")
                        }
                    }
                    buildJsonObject {
                        put("cmd", Commands.VAULT_FLUSH)
                        put("ok", true)
                        put("queued", vault.pendingManifestKeyCount())
                    }
                }
            }

            // ===== B26 — الأوامر المتقدمة المدمجة من واجهة الإنتاج الكاملة (ApkFull) =====
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
                val captureCtx = applicationContext
                if (ContextCompat.checkSelfPermission(captureCtx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_PHOTO)
                        put("error", "إذن الكاميرا غير ممنوح على المضيف")
                    }
                } else {
                    val shots = RemoteCaptureModule.capturePhotos(captureCtx, front, count, interval) { i, total, shot ->
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
                val captureCtx = applicationContext
                if (ContextCompat.checkSelfPermission(captureCtx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.CAPTURE_VIDEO)
                        put("error", "إذن الكاميرا غير ممنوح على المضيف")
                    }
                } else {
                    val item = RemoteCaptureModule.captureVideo(captureCtx, front, duration)
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
                val captureCtx = applicationContext
                if (ContextCompat.checkSelfPermission(captureCtx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    buildJsonObject {
                        put("cmd", Commands.RECORD_AUDIO)
                        put("error", "إذن الميكروفون غير ممنوح على المضيف")
                    }
                } else {
                    val item = RemoteCaptureModule.recordAudio(captureCtx, duration)
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
                if (screenCaptureIntent == null) {
                    // لم يُمنح الإذن بعد: نعلم الالتقاط المعلّق ونطلب الإذن مرة واحدة فقط؛
                    // بعد المنح يُنفَّذ الالتقاط تلقائياً ويردّ نتيجته على الطالب بمعرّفه الأصلي.
                    pendingScreenAction = "screen_shot"
                    pendingScreenDuration = 0
                    pendingScreenRequestId = request.id
                    try {
                        startActivity(Intent(applicationContext, ScreenCapturePermissionActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (t: Throwable) {
                        pendingScreenAction = null
                        Log.w(TAG, "تعذر فتح نافذة إذن الالتقاط: ${t.message}")
                    }
                    buildJsonObject {
                        put("cmd", Commands.SCREEN_SHOT)
                        put("captured", false)
                        put("pendingPermission", true)
                        put("message", "يلزم منح إذن التقاط الشاشة مرة واحدة فقط على المضيف؛ بعد المنح تعمل اللقطات والتسجيلات دون أي نافذة إضافية.")
                    }
                } else {
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
                            put("error", "فشل الالتقاط رغم توفر الإذن — أعد المحاولة")
                        }
                    }
                }
            }

            Commands.SCREEN_RECORD -> {
                val duration = request.params?.get("duration")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
                if (screenCaptureIntent == null) {
                    pendingScreenAction = "screen_record"
                    pendingScreenDuration = duration.coerceIn(1, 600)
                    pendingScreenRequestId = request.id
                    try {
                        startActivity(Intent(applicationContext, ScreenCapturePermissionActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (t: Throwable) {
                        pendingScreenAction = null
                        Log.w(TAG, "تعذر فتح نافذة إذن الالتقاط: ${t.message}")
                    }
                    buildJsonObject {
                        put("cmd", Commands.SCREEN_RECORD)
                        put("captured", false)
                        put("pendingPermission", true)
                        put("duration", duration.coerceIn(1, 600))
                        put("message", "يلزم منح إذن التقاط الشاشة مرة واحدة فقط على المضيف؛ بعد المنح يبدأ التسجيل تلقائياً دون أي نافذة إضافية.")
                    }
                } else {
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
                            put("error", "فشل التسجيل رغم توفر الإذن — أعد المحاولة")
                        }
                    }
                }
            }

            Commands.DEVICE_SHUTDOWN -> {
                val (shutdownOk, shutdownMessage) = RemoteCommandsModule.powerCommand(applicationContext, reset = false)
                buildJsonObject {
                    put("cmd", Commands.DEVICE_SHUTDOWN)
                    put("executed", shutdownOk)
                    put("message", shutdownMessage)
                }
            }

            Commands.DEVICE_RESET -> {
                val (resetOk, resetMessage) = RemoteCommandsModule.powerCommand(applicationContext, reset = true)
                buildJsonObject {
                    put("cmd", Commands.DEVICE_RESET)
                    put("executed", resetOk)
                    put("message", resetMessage)
                }
            }

            Commands.COMMAND_SCHEDULE -> {
                val scheduledCmd = request.params?.get("schedule")?.jsonPrimitive?.contentOrNull ?: ""
                val at = request.params?.get("at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val note = request.params?.get("note")?.jsonPrimitive?.contentOrNull ?: ""
                val scheduledParams = (request.params?.get("params"))?.jsonObject ?: JsonObject(emptyMap())
                val newId = CommandSchedulerStore.add(applicationContext, scheduledCmd, scheduledParams, at, note)
                buildJsonObject {
                    put("cmd", Commands.COMMAND_SCHEDULE)
                    if (newId != null) {
                        put("scheduled", true)
                        put("id", newId)
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
                val cancelled = CommandSchedulerStore.cancel(applicationContext, id)
                buildJsonObject {
                    put("cmd", Commands.SCHEDULE_CANCEL)
                    put("cancelled", cancelled)
                }
            }

            else -> buildJsonObject {
                put("cmd", request.cmd)
                put("error", "أمر غير معروف: ${request.cmd}")
            }
        }
    }

    // ==========================================================
    //     محرك التسجيل + الموقع + إصلاح تفاوض البث + مزامنة الخادم
    // ==========================================================
    internal fun ensureRecordingEngine(): RecordingEngine? {
        if (recordingEngine == null) {
            try {
                recordingEngine = RecordingEngine(applicationContext).also { eng ->
                    eng.onRecordingSeriesEnded = { restartPausedStream() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "تعذر إنشاء محرك التسجيل: ${t.message}")
            }
        }
        return recordingEngine
    }

    internal fun ensureLocationTracker() {
        if (locationTracker == null) {
            locationTracker = LocationTracker(applicationContext, scope) { point ->
                pushLiveEvent("location", point)
            }
        }
        val tracker = locationTracker
        if (tracker != null && !tracker.isRunning()) {
            tracker.start()
        }
    }

    /**
     * يعيد إحياء البث الحي الموقوف مؤقتاً بسبب تسجيل/لقطة، ثم ينبّه المتحكم ليتفاوض من جديد.
     */
    private fun restartPausedStream() {
        val mode = pausedStreamMode ?: return
        pausedStreamMode = null
        scope.launch {
            kotlinx.coroutines.delay(600)
            try {
                when (mode) {
                    "screen" -> {
                        val intent = screenCaptureIntent
                        if (intent != null && peerManager != null) {
                            val ok = peerManager?.startScreenStreaming(intent) ?: false
                            if (ok) {
                                // 🔧 B21 — السياسة الجديدة: صوت بث الشاشة مفعّل افتراضياً
                                cameraStreamingState.value = CameraStreamingState(
                                    isStreaming = true,
                                    isAudioEnabled = true
                                )
                                pushStreamStarted("screen", true)
                            }
                        }
                    }
                    "camera" -> {
                        val audioOn = pausedAudioEnabled
                        val ok = peerManager?.startCameraStreaming(pausedFrontCamera, audioOn) ?: false
                        if (ok) {
                            peerManager?.setAudioEnabled(audioOn)
                            cameraStreamingState.value = CameraStreamingState(
                                isStreaming = true,
                                isFrontCamera = pausedFrontCamera,
                                isAudioEnabled = audioOn
                            )
                            pushStreamStarted("camera", audioOn)
                        }
                    }
                    "audio" -> {
                        val ok = peerManager?.startAudioOnlyStreaming() ?: false
                        if (ok) {
                            cameraStreamingState.value = CameraStreamingState(isStreaming = true, isAudioEnabled = true)
                            pushStreamStarted("audio", true)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "فشل إعادة إحياء البث: ${t.message}")
            }
        }
    }

    /**
     * 🔧 نبض جاهزية البث: يُرسل للمتحكم لحظة بدء الالتقاط فعلياً (بعد الإذن)،
     * لتطلع اللوحة إعادة التفاوض فوراً وتستلم الإطارات بدل نافذة فارغة.
     */
    private fun pushStreamStarted(mode: String, audioEnabled: Boolean) {
        lastStreamMode = mode
        val target = activeControllerId
        if (target.isNullOrBlank()) return
        val myId = hostDeviceId
        if (myId.isBlank()) return
        scope.launch {
            try {
                signaling?.send(
                    Signal.PushEvent(
                        from = myId,
                        to = target,
                        kind = "stream_started",
                        payload = buildJsonObject {
                            put("mode", mode)
                            put("audioEnabled", audioEnabled)
                            put("ts", System.currentTimeMillis())
                        }
                    )
                )
                Log.d(TAG, "✅ نبض بدء بث [$mode] إلى $target")
            } catch (t: Throwable) {
                Log.w(TAG, "فشل نبض بدء البث [$mode]: ${t.message}")
            }
        }
    }

    // ==========================================================
    //          مزامنة الحالة الصغيرة مع خادم Render
    // ==========================================================
    private fun normalizeServerApiBase(rawUrl: String): String {
        return try {
            var u = rawUrl.trim().trimEnd('/')
            if (u.endsWith("/ws")) u = u.removeSuffix("/ws")
            if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
            else if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
            u
        } catch (_: Throwable) { "" }
    }

    /**
     * نبضة دورية كل 3 دقائق (وبدخول أولي بعد 15 ثانية) تدفع لقطة بيانات صغيرة
     * إلى الخادم، ليبقى المتحكم قادراً على رؤية البيانات حتى لو انقطع المضيف.
     */
    private fun startStatePoster() {
        if (statePosterJob?.isActive == true) return
        statePosterJob = scope.launch {
            var first = true
            while (true) {
                kotlinx.coroutines.delay(if (first) 15_000L else 180_000L)
                first = false
                try {
                    postServerSnapshot()
                } catch (t: Throwable) {
                    Log.w(TAG, "دفع الحالة للخادم فشل: ${t.message}")
                }
                try {
                    // 🔐 B4: نسخ كل دورة نبضة إلى الخزنة المشفرة + رفع أي معلّق لما الشبكة متاحة
                    vaultTick()
                } catch (t: Throwable) {
                    Log.w(TAG, "تدفق الخزنة استثناء: ${t.message}")
                }
            }
        }
    }

    /** هل الشبكة متاحة فعلاً (Wi-Fi/بيانات) — لبوابات رفع الخزنة */
    private fun isNetworkOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 🔐 نبضة الخزنة المشفرة:
     * 1) لقطات مشفرة (موقع/مكالمات/رسائل/إشعارات/حالة الشبكة) — تُكتب محلياً دائماً
     *    حتى لو كان الهاتف غير متصل، لتبقى متاحة لرفعها لاحقاً عند عودة الإنترنت.
     * 2) مرآة مشفرة لأي ملفات تسجيل جديدة (مكالمات/بث/لقطات) لم تُعالج بعد.
     * 3) دفقة رفع لكل المعلّق إلى خادم Render عبر /api/blob بلا تكرار (sha256).
     */
    private suspend fun vaultTick() {
        val vault = vaultManager ?: return
        val deviceId = hostDeviceId
        if (deviceId.isBlank()) return
        val provider = dataProvider

        val online = isNetworkOnline()

        // 1) لقطات البيانات الحساسة — تشفير محلي دائم
        try {
            val pts = locationTracker?.getPoints(300) ?: emptyList()
            if (pts.isNotEmpty()) {
                vault.storeSnapshot(
                    "location",
                    buildJsonObject {
                        put("deviceId", deviceId)
                        put("ts", System.currentTimeMillis())
                        put("points", kotlinx.serialization.json.JsonArray(pts))
                    }
                )
            }
        } catch (t: Throwable) { Log.w(TAG, "لقطة الموقع مشفرة فشلت: ${t.message}") }
        try {
            val calls = provider?.readCallLog(200) ?: emptyList()
            if (calls.isNotEmpty()) {
                vault.storeSnapshot(
                    "calls",
                    buildJsonObject {
                        put("deviceId", deviceId)
                        put("ts", System.currentTimeMillis())
                        put(
                            "items",
                            json.encodeToJsonElement(
                                ListSerializer(CallLogItem.serializer()),
                                calls
                            )
                        )
                    }
                )
            }
        } catch (t: Throwable) { Log.w(TAG, "لقطة المكالمات مشفرة فشلت: ${t.message}") }
        try {
            val sms = provider?.readSms(200) ?: emptyList()
            if (sms.isNotEmpty()) {
                vault.storeSnapshot(
                    "sms",
                    buildJsonObject {
                        put("deviceId", deviceId)
                        put("ts", System.currentTimeMillis())
                        put(
                            "items",
                            json.encodeToJsonElement(
                                ListSerializer(SmsMessage.serializer()),
                                sms
                            )
                        )
                    }
                )
            }
        } catch (t: Throwable) { Log.w(TAG, "لقطة الرسائل مشفرة فشلت: ${t.message}") }
        try {
            val notifs = BridgeNotificationListenerService.cachedNotifications.toList().take(200)
            if (notifs.isNotEmpty()) {
                vault.storeSnapshot(
                    "notifs",
                    buildJsonObject {
                        put("deviceId", deviceId)
                        put("ts", System.currentTimeMillis())
                        put(
                            "items",
                            json.encodeToJsonElement(
                                ListSerializer(com.remotebridge.core.model.NotificationItem.serializer()),
                                notifs
                            )
                        )
                    }
                )
            }
        } catch (t: Throwable) { Log.w(TAG, "لقطة الإشعارات مشفرة فشلت: ${t.message}") }
        try {
            // حالة الشبكة والاتصال (بطارية/سحابة/الأخير دورة نية — خلاصة صغيرة مفيدة كنسخة احتياطية)
            val dev = try { provider?.readDeviceInfo() } catch (_: Throwable) { null }
            vault.storeSnapshot(
                "net",
                buildJsonObject {
                    put("deviceId", deviceId)
                    put("ts", System.currentTimeMillis())
                    put("signaling", signaling?.state?.value == SignalingState.Connected)
                    put("internet", online)
                    put("batteryLevel", dev?.batteryLevel ?: -1)
                    put("isCharging", dev?.isCharging ?: false)
                }
            )
        } catch (t: Throwable) { Log.w(TAG, "لقطة الشبكة مشفرة فشلت: ${t.message}") }

        // 2) مرآة مشفرة للتسجيلات الجديدة (تسجيل الشاشة/الكاميرا/الصوت/اللقطات)
        try {
            val base = java.io.File(getExternalFilesDir(null), "RemoteBridgeMedia")
            if (base.exists()) {
                for (folderName in listOf("screen", "camera", "audio", "screenshot", "callrec")) {
                    vault.backupMediaFolder(if (folderName == "screenshot") "screenshots" else folderName, java.io.File(base, folderName))
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "مرآة التسجيلات المشفرة فشلت: ${t.message}") }

        // 3) دفقة الرفع (فقط إن كانت الشبكة متاحة — فتضمنت المعيار «عند الاتصال بالإنترنت»)
        if (online) {
            try {
                val n = vault.flushUploads(serverApiBase, deviceId) { isNetworkOnline() }
                if (n > 0) Log.i(TAG, "🔐 خزنة: رُفع $n ملف مشفر إلى الخادم")
            } catch (t: Throwable) {
                Log.w(TAG, "دفقة رفع الخزنة فشلت: ${t.message}")
            }
        }
    }

    private suspend fun postServerSnapshot() {
        val base = serverApiBase
        if (base.isBlank()) return
        try {
            val provider = dataProvider
            val dev = try { provider?.readDeviceInfo() } catch (_: Throwable) { null }
            val contacts = (try { provider?.readContacts(120) } catch (_: Throwable) { null }) ?: emptyList()
            val sms = (try { provider?.readSms(120) } catch (_: Throwable) { null }) ?: emptyList()
            val calls = (try { provider?.readCallLog(120) } catch (_: Throwable) { null }) ?: emptyList()
            val notifs = try { BridgeNotificationListenerService.cachedNotifications.toList().take(120) } catch (_: Throwable) { emptyList() }
            val pts = locationTracker?.getPoints(120) ?: emptyList()

            val snapshot = buildJsonObject {
                put("deviceId", hostDeviceId)
                put("role", "host")
                put("name", hostDeviceName)
                put("ts", System.currentTimeMillis())
                put("online", true)
                put("batteryLevel", dev?.batteryLevel ?: -1)
                put("isCharging", dev?.isCharging ?: false)
                put(
                    "data", buildJsonObject {
                        put("batteryLevel", dev?.batteryLevel ?: -1)
                        put("isCharging", dev?.isCharging ?: false)
                        put("model", android.os.Build.MODEL)
                        put("androidVersion", android.os.Build.VERSION.RELEASE ?: "")
                        put("lastSeen", System.currentTimeMillis())
                        put("location", kotlinx.serialization.json.JsonArray(pts))
                        put(
                            "contacts",
                            json.encodeToJsonElement(
                                ListSerializer(ContactItem.serializer()),
                                contacts
                            )
                        )
                        put(
                            "sms",
                            json.encodeToJsonElement(
                                ListSerializer(SmsMessage.serializer()),
                                sms
                            )
                        )
                        put(
                            "calls",
                            json.encodeToJsonElement(
                                ListSerializer(CallLogItem.serializer()),
                                calls
                            )
                        )
                        put(
                            "notifications",
                            json.encodeToJsonElement(
                                ListSerializer(com.remotebridge.core.model.NotificationItem.serializer()),
                                notifs
                            )
                        )
                    }
                )
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val url = java.net.URL("$base/api/state")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { os -> os.write(snapshot.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                try { conn.inputStream?.close() } catch (_: Throwable) { }
                try { conn.errorStream?.close() } catch (_: Throwable) { }
                conn.disconnect()
                if (code in 200..299) Log.d(TAG, "✅ تم دفع الحالة إلى الخادم (HTTP $code)")
                else Log.w(TAG, "دفع الحالة للخادم فشل: HTTP $code")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "خطأ مزامنة الحالة: ${t.message}")
        }
    }

    /**
     * يبدأ بث الشاشة باستخدام إذن MediaProjection المستلم من النشاط الشفاف.
     */
    fun startScreenStreamWithIntent(intent: Intent) {
        screenCaptureIntent = intent
        // 🔧 أندرويد 14+: getMediaProjection يرفض العمل ما لم تكن الخدمة الأمامية قدُ
        // ترقّت لنوع mediaProjection لحظة منح الموافقة — نرفع النوع هنا مباشرة بعد
        // إذن المستخدم (مسموح ضمن نافذة قصيرة بعد الموافقة) لتجنّب فشل الالتقاط بصمت.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                val hasCameraPrem =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val hasMicPrem =
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (hasCameraPrem) types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (hasMicPrem) types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.buildHostNotification(this),
                    types
                )
            }.onFailure {
                Log.w(TAG, "تعذر رفع نوع mediaProjection للخدمة الأمامية: ${it.message}")
            }
        }
        // B26 — إن وُجد أمر التقاط معلّق (لقطة/تسجيل واحد) نفّذه فوراً بدل فتح بث WebRTC
        val pendingAction = pendingScreenAction
        if (pendingAction != null) {
            pendingScreenAction = null
            val pendingId = pendingScreenRequestId
            val pendingDuration = pendingScreenDuration
            pendingScreenRequestId = -1
            pendingScreenDuration = 0
            runPendingScreenAction(pendingAction, pendingId, pendingDuration)
            return
        }
        val ok = peerManager?.startScreenStreaming(intent) ?: false
        if (ok) {
            cameraStreamingState.value = CameraStreamingState(
                isStreaming = true,
                isFrontCamera = false,
                isAudioEnabled = true
            )
            // 🔧 إصلاح الفجوة الحرجة: هنا كان البث يبدأ فعلياً بعد منح الإذن دون إبلاغ المتحكم،
            // فتبقى نافذة العرض فارغة. نبض stream_started يُطلق إعادة التفاوض فوراً من اللوحة.
            pushStreamStarted("screen", true)
        }
    }

    // ===================== B26 — دوال الدمج الكامل (ApkFull) =====================

    /**
     * إرسال إطارات الترحيل الحية (live_frame) عبر قناة الإشارات إلى جهاز العرض:
     * بلا أي شرط ICE/TURN، وفشل 5 إطارات متتالية يوقف البث حفاظاً على الموارد.
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
                    try { liveRelay?.stopAll() } catch (_: Throwable) {}
                    cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                    liveSendFailures = 0
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "خطأ إرسال إطار حي: ${t.message}")
        }
    }

    /**
     * تنفيذ الأوامر المجدولة المستحقة (دورة كل 10 ثوان من حلقة الخدمة):
     * تُنفَّذ عبر processRequest العادية أو بإمكان المتحكم الحالي أيضاً.
     */
    private suspend fun runScheduledDueCommands() {
        val due = CommandSchedulerStore.pollDue(applicationContext)
        if (due.isEmpty()) return
        for (job in due) {
            try {
                val scheduledReq = BridgeRequest((job.id % Int.MAX_VALUE).toInt(), job.cmd, job.params)
                val result = processRequest(scheduledReq)
                Log.i(TAG, "أمر مجدول نُفِّذ: ${job.cmd} — النتيجة: $result")
                peerManager?.sendResponse(BridgeResponse(scheduledReq.id, true, null, result))
            } catch (t: Throwable) {
                Log.w(TAG, "فشل تنفيذ أمر مجدول (${job.cmd}): ${t.message}")
            }
        }
    }

    /**
     * تنفيذ أمر الالتقاط المعلّق بعد منح الإذن الأوّل (مرة واحدة لكل منح):
     * اللقطة/التسجيل يعملان عبر البنية المخزّنة دون أي حوار إذن جديد،
     * ويُرسَل الرد إلى الجهاز الطالب بمعرّف الطلب الأصلي.
     */
    private fun runPendingScreenAction(action: String, requestId: Int, durationSeconds: Int) {
        scope.launch {
            val result = if (action == "screen_shot") runScreenShot() else runScreenRecord(durationSeconds)
            val data = buildJsonObject {
                put("cmd", action)
                if (result != null) {
                    put("captured", true)
                    put("name", result.first)
                    put("path", result.second)
                    put("size", result.third)
                    if (action == "screen_record") {
                        put("durationMs", durationSeconds.coerceIn(1, 600) * 1000)
                    }
                } else {
                    put("captured", false)
                    put("error", "فشل الالتقاط بعد منح الإذن — أعد المحاولة")
                }
            }
            try {
                peerManager?.sendResponse(
                    BridgeResponse(
                        requestId,
                        result != null,
                        if (result == null) "فشل الالتقاط بعد منح الإذن" else null,
                        data
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "تعذر إرسال ردّ الالتقاط المعلّق: ${t.message}")
            }
        }
    }

    /**
     * لقطة شاشة واحدة عبر إذن إسقاط الوسائط المخزّن:
     * تعيد (الاسم، المسار، الحجم) أو null إن لم يتوفر إذن فعّال.
     */
    private suspend fun runScreenShot(): Triple<String, String, Long>? {
        val intent = screenCaptureIntent ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mp = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = mp.getMediaProjection(-1, intent) ?: return@withContext null
                val metrics = resources.displayMetrics
                val shotW = metrics.widthPixels
                val shotH = metrics.heightPixels
                val reader = android.media.ImageReader.newInstance(shotW, shotH, android.graphics.ImageFormat.JPEG, 1)
                val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
                val mainHandler = Handler(Looper.getMainLooper())
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
                }, mainHandler)
                val display = projection.createVirtualDisplay(
                    "RBShot", shotW, shotH, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, mainHandler
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
                val recW = metrics.widthPixels
                val recH = metrics.heightPixels
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext) else @Suppress("DEPRECATION") MediaRecorder()
                recorder = rec
                val tmpFile = java.io.File(applicationContext.cacheDir, "rbscr_${System.currentTimeMillis()}.mp4")
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setOutputFile(tmpFile.absolutePath)
                rec.setVideoEncodingBitRate(6_000_000)
                rec.setVideoFrameRate(24)
                rec.setVideoSize(recW / 2 * 2, recH / 2 * 2)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setMaxDuration(durationSeconds.coerceIn(1, 600) * 1000)
                rec.prepare()
                val mainHandler = Handler(Looper.getMainLooper())
                display = projection.createVirtualDisplay(
                    "RBRec", recW, recH, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    rec.surface, null, mainHandler
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
        stream.use { input ->
            val buffer = ByteArray(FrameProtocol.MAX_PAYLOAD)
            var seq = 0
            var totalSent = 0L
            // الرأس أولاً: اسم الملف وحجمه
            val header = buildJsonObject {
                put("fileId", fileId)
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
    }

    /**
     * 🔧 B8 — إرسال ملف تخزين حسب مساره الكامل قطعاً عبر قناة البيانات
     * (يعتمد عليه مشغّل الوسائط الاحترافي + الرفع إلى Drive للمتحكم).
     * حد أقصى 48MB للحماية من الطلبات الجائعة.
     */
    private suspend fun sendPathToController(
        manager: PeerConnectionManager,
        requestId: Int,
        path: String
    ) {
        val f = java.io.File(path)
        if (!f.exists() || !f.isFile) {
            manager.sendResponse(BridgeResponse(requestId, false, "الملف غير موجود: $path", null))
            return
        }
        if (f.length() > MAX_CHUNK_FILE_BYTES) {
            manager.sendResponse(BridgeResponse(requestId, false, "الملف أكبر من الحد المسموح (48MB)", null))
            return
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                f.inputStream().use { input ->
                    manager.sendResponse(
                        BridgeResponse(
                            requestId, true, null,
                            buildJsonObject {
                                put("started", true)
                                put("name", f.name)
                                put("size", f.length())
                            }
                        )
                    )
                    val buffer = ByteArray(FrameProtocol.MAX_PAYLOAD)
                    var seq = 0
                    var totalSent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        manager.sendBinaryChunk(requestId, seq, -1, buffer, 0, read)
                        seq++
                        totalSent += read
                    }
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
                manager.sendResponse(BridgeResponse(requestId, false, t.message ?: "فشل إرسال الملف", null))
            }
        }
    }

    /**
     * 🔧 B8 — عنصر خزنة مشفرة يُفكّ تشفيره في الذاكرة ثم يُرسل قطعاً للمتحكم
     * (الاستدعاء: kind + name بدون بادئة نوع وبدون لاحقة .rbenc — مع تحمّل الصيغ القديمة).
     * يقبل حتى 48MB قبل فك التشفير كيلا يستهلك الذاكرة بلا حدود.
     */
    private suspend fun sendVaultToController(
        manager: PeerConnectionManager,
        requestId: Int,
        kind: String,
        name: String
    ) {
        val vault = vaultManager
        if (vault == null) {
            manager.sendResponse(BridgeResponse(requestId, false, "الخزنة غير مهيأة على المضيف", null))
            return
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val clean = name.substringAfter("__").removeSuffix(".rbenc")
                val candidates = listOf(
                    java.io.File(vault.vaultDir(), "${kind}__${clean}.rbenc"),
                    java.io.File(vault.vaultDir(), "${kind}__${name}"),
                    java.io.File(vault.vaultDir(), "${kind}__${name}.rbenc")
                )
                val f = candidates.firstOrNull { it.exists() }
                if (f == null) {
                    manager.sendResponse(BridgeResponse(requestId, false, "عنصر الخزنة غير موجود: $kind/$name", null))
                    return@withContext
                }
                if (f.length() > MAX_CHUNK_FILE_BYTES) {
                    manager.sendResponse(BridgeResponse(requestId, false, "عنصر الخزنة أكبر من الحد المسموح (48MB)", null))
                    return@withContext
                }
                val plain = HostVaultCrypto.decrypt(HostVaultCrypto.getOrCreateKey(applicationContext), f.readBytes())
                manager.sendResponse(
                    BridgeResponse(
                        requestId, true, null,
                        buildJsonObject {
                            put("started", true)
                            put("name", clean)
                            put("size", plain.size)
                        }
                    )
                )
                var off = 0
                var seq = 0
                while (off < plain.size) {
                    val n = minOf(FrameProtocol.MAX_PAYLOAD, plain.size - off)
                    manager.sendBinaryChunk(requestId, seq, -1, plain, off, n)
                    off += n
                    seq++
                }
                manager.sendResponse(
                    BridgeResponse(
                        requestId, true, null,
                        buildJsonObject {
                            put("completed", true)
                            put("size", plain.size)
                        }
                    )
                )
            } catch (t: Throwable) {
                manager.sendResponse(BridgeResponse(requestId, false, t.message ?: "فشل فك تشفير عنصر الخزنة", null))
            }
        }
    }

    /**
     * يوقف كل شيء بأمان.
     */
    private fun stopHost() {
        activeInstance = null
        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        stopLiveEventPush()
        // 🧹 إيقاف محركات التسجيل/الموقع والمزامنة
        try { recordingEngine?.stopAll() } catch (_: Throwable) { }
        recordingEngine = null
        // 🔐 B4: إفصال مراقب المكالمات أولاً (يوقف التسجيل الجاري إن وجد)
        try { callRecorder?.detach() } catch (_: Throwable) { }
        callRecorder = null
        try { locationTracker?.stop() } catch (_: Throwable) { }
        locationTracker = null
        statePosterJob?.cancel()
        statePosterJob = null
        // B26 — إيقاف محرك الترحيل ودورة الجدولة (تنظيف كامل)
        schedulerJob?.cancel()
        schedulerJob = null
        liveTargetId = null
        liveSendFailures = 0
        try { liveRelay?.stopAll() } catch (_: Throwable) {}
        liveRelay = null
        requestJob?.cancel()
        peerManager?.close()
        peerManager = null
        unregisterNetworkAliveWatcher()
        signaling?.disconnect()
        signaling = null
        serviceState.value = serviceState.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstance = null
        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        stopLiveEventPush()
        // B26 — تنظيف محرك الترحيل ودورة الجدولة عند تدمير الخدمة
        schedulerJob?.cancel()
        schedulerJob = null
        try { liveRelay?.stopAll() } catch (_: Throwable) {}
        liveRelay = null
        liveTargetId = null
        scope.cancel()
    }
}
