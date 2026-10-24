package com.remotebridge.host.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE
            )
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
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        }
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
                val ok = manager?.startCameraStreaming(front, audio) ?: false
                // === سياسة الكتم الافتراضي: أي بث مرئي يبدأ صوته مكتوماً حتى لو طُلب تفعيله ===
                if (ok) manager?.setAudioEnabled(false)
                cameraStreamingState.value = CameraStreamingState(
                    isStreaming = ok,
                    isFrontCamera = manager?.isUsingFrontCamera() ?: false,
                    isAudioEnabled = false
                )
                buildJsonObject {
                    put("cmd", Commands.START_CAMERA_STREAM)
                    put("started", ok)
                    put("mode", "camera")
                    put("isFront", manager?.isUsingFrontCamera() ?: false)
                    put("audioEnabled", false)
                    if (!ok) {
                        put("error", "تعذر بدء بث الكاميرا. يرجى التأكد من اكتمال اتصال WebRTC ومنح إذن الكاميرا على الهاتف المضيف")
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
                manager?.stopStreaming()
                cameraStreamingState.value = CameraStreamingState(isStreaming = false)
                buildJsonObject {
                    put("cmd", Commands.STOP_CAMERA_STREAM)
                    put("stopped", true)
                }
            }

            Commands.SWITCH_CAMERA -> {
                var frontResult = manager?.isUsingFrontCamera() ?: false
                manager?.switchCamera { isFront ->
                    frontResult = isFront
                    cameraStreamingState.value = cameraStreamingState.value.copy(isFrontCamera = isFront)
                }
                buildJsonObject {
                    put("cmd", Commands.SWITCH_CAMERA)
                    put("switched", true)
                    put("isFront", frontResult)
                }
            }

            Commands.START_SCREEN_STREAM -> {
                val intent = screenCaptureIntent
                if (intent != null && manager != null) {
                    val ok = manager.startScreenStreaming(intent)
                    cameraStreamingState.value = CameraStreamingState(
                        isStreaming = ok,
                        isFrontCamera = false,
                        isAudioEnabled = false
                    )
                    buildJsonObject {
                        put("cmd", Commands.START_SCREEN_STREAM)
                        put("started", ok)
                        put("mode", "screen")
                        put("audioEnabled", false)
                    }
                } else {
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
     * يوقف كل شيء بأمان.
     */
    private fun stopHost() {
        activeInstance = null
        cameraStreamingState.value = CameraStreamingState(isStreaming = false)
        stopLiveEventPush()
        requestJob?.cancel()
        peerManager?.close()
        peerManager = null
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
        scope.cancel()
    }
}
