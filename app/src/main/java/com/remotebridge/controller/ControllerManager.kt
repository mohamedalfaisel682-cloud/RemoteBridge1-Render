package com.remotebridge.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.remotebridge.core.data.DownloadsSaver
import com.remotebridge.core.data.SettingsSyncManager
import com.remotebridge.core.model.BridgeRequest
import com.remotebridge.core.model.BridgeResponse
import com.remotebridge.core.model.HostInfo
import com.remotebridge.core.signaling.Signal
import com.remotebridge.core.signaling.SignalingClient
import com.remotebridge.core.signaling.SignalingState
import com.remotebridge.core.data.SettingsRepository
import com.remotebridge.core.webrtc.PeerConnectionManager
import com.remotebridge.core.webrtc.PeerState
import com.remotebridge.host.service.DeviceIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * حالة جهاز التحكم في لحظة معينة.
 */
data class ControllerState(
    val signalingState: SignalingState = SignalingState.Disconnected,
    val peerState: PeerState = PeerState.Idle,
    val connectedHost: HostInfo? = null,
    val hosts: List<HostInfo> = emptyList()
)

/**
 * يدير اتصال جهاز التحكم بالأجهزة المضيفة.
 *
 * المسؤوليات:
 * 1. الاتصال بخادم الإشارات بدور "تحكم".
 * 2. اكتشاف الأجهزة المضيفة المتاحة.
 * 3. بدء اتصال WebRTC مع مضيف محدد.
 * 4. إرسال الطلبات واستقبال الردود عبر قناة البيانات.
 */
class ControllerManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "ControllerManager"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val requestIdCounter = AtomicInteger(0)

    /** أجهزة سُجّلت جلسة متصل في هذه النظرة (منع تكديس سجلات كل ثانية). */
    private val _sessionRecordedIds =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private var signaling: SignalingClient? = null
    private var peerManager: PeerConnectionManager? = null
    private var signalJob: Job? = null
    private var myDeviceId: String = ""

    /**
     * الردود الواردة مفهرسة بمعرّف الطلب لمطابقتها.
     */
    private val pendingResponses = ConcurrentHashMap<Int, MutableSharedFlow<BridgeResponse>>()

    /**
     * طلبات تنزيل الملفات: تُبقي تسجيلها بعد الرد الأول (البداية) حتى الرد
     * الختامي {completed:true} — على عكس الطلبات القصيرة التكاليف البسيطة.
     */
    private val downloadFlows = ConcurrentHashMap<Int, MutableSharedFlow<BridgeResponse>>()

    /** حالة تنزيل الملف تحت التشغيل (إظهار بانة التقدم في الواجهة). */
    data class FileDownloadProgress(
        val fileName: String,
        val receivedBytes: Long,
        val finished: Boolean = false,
        val folderLabel: String = ""
    )

    private val _downloadProgress = MutableStateFlow<FileDownloadProgress?>(null)
    val downloadProgress: StateFlow<FileDownloadProgress?> = _downloadProgress.asStateFlow()

    /**
     * آخر إطار بث حي مُرسَل عبر الترحيل (الوسيلة البديلة MJPEG-over-Relay)
     * — جاهز للعرض مباشرة في واجهة Compose عبر liveFrame.
     */
    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    /** معلومات البث الحي المرجّل النشط: نوعه (camera/screen) ومصدره. */
    data class LiveStreamInfo(val stream: String, val fromHost: String, val startedAt: Long)

    private val _liveStreamInfo = MutableStateFlow<LiveStreamInfo?>(null)
    val liveStreamInfo: StateFlow<LiveStreamInfo?> = _liveStreamInfo.asStateFlow()

    private var liveFrameHostId: String = ""

    /** يفرّغ آخر إطار معروض وكذلك معلومات البث (بعد إيقاف البث مثلاً). */
    fun clearLiveFrame() {
        liveFrameHostId = ""
        _liveFrame.value = null
        _liveStreamInfo.value = null
    }

    data class DownloadResult(
        val ok: Boolean,
        val fileLabel: String = "",
        val size: Long = 0,
        val error: String? = null
    )

    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    private val _hosts = MutableStateFlow<List<HostInfo>>(emptyList())
    val hosts: StateFlow<List<HostInfo>> = _hosts.asStateFlow()

    /**
     * خريطة "آخر ظهور" (deviceId -> millis) تجمع ما يأتي من الخادم وما يسجّله
     * هذا الجهاز محلياً — مصدر الدوائر البرتقالية وبيانات نافذة الحالة.
     */
    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen.asStateFlow()

    private val _binaryChunks = MutableSharedFlow<PeerConnectionManager.BinaryChunk>(
        extraBufferCapacity = 32
    )
    val binaryChunks: SharedFlow<PeerConnectionManager.BinaryChunk> =
        _binaryChunks.asSharedFlow()

    /**
     * يتصل بخادم الإشارات ويبدأ الاستماع للأجهزة المضيفة.
     */
    suspend fun connect(serverUrl: String, deviceName: String) {
        if (signaling != null) return
        myDeviceId = DeviceIdentifier.getOrGenerate(context)

        // نحمّل خريطة آخر ظهور المحفوظة (تغذي الدوائر البرتقالية فور فتح القائمة)
        scope.launch {
            try {
                val saved = SettingsRepository(context).loadLastSeenMap()
                if (saved.isNotEmpty() && _lastSeen.value.isEmpty()) {
                    _lastSeen.value = saved
                    // نعلم الأجهزة المحفوظة الغائبة عن القائمة الحية
                    val restored = _hosts.value.map { h ->
                        if (!h.online && h.lastSeenMs == null) {
                            val ms = saved[h.deviceId]
                            if (ms != null) h.copy(online = false, lastSeenMs = ms) else h
                        } else h
                    }
                    if (restored.size != _hosts.value.size || restored != _hosts.value) {
                        _hosts.value = restored
                        _state.value = _state.value.copy(hosts = restored)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        peerManager = PeerConnectionManager(context, scope).also { it.start() }

        signaling = SignalingClient(
            url = serverUrl,
            deviceId = myDeviceId,
            role = "controller",
            deviceName = deviceName,
            scope = scope,
            onMessage = { handleSignal(it) },
            onState = { signalingState ->
                _state.value = _state.value.copy(signalingState = signalingState)
            }
        ).also { it.connect() }

        collectPeerEvents()
    }

    /**
     * يعالج رسالة إشارات واردة.
     */
    private fun handleSignal(signal: Signal) {
        when (signal) {
            is Signal.Hosts -> {
                // الدمج: نحفظ الجهازات المعروفة سابقاً (غير متصلة) لعرض دائرتها
                // البرتقالية/الحمراء بدلاً من اختفائها من القائمة.
                val offlineKnown = _hosts.value
                    .filter { !it.online }
                    .filterNot { known -> signal.hosts.any { it.deviceId == known.deviceId } }
                val merged = signal.hosts + offlineKnown
                _hosts.value = merged
                _state.value = _state.value.copy(hosts = merged)
            }

            is Signal.HostOnline -> {
                val updated = _hosts.value
                    .filterNot { it.deviceId == signal.host.deviceId } + signal.host
                _hosts.value = updated
                _state.value = _state.value.copy(hosts = updated)
                _lastSeen.value = _lastSeen.value + (signal.host.deviceId to System.currentTimeMillis())
            }

            is Signal.HostOffline -> {
                val seenAt = System.currentTimeMillis()
                _lastSeen.value = _lastSeen.value + (signal.deviceId to seenAt)
                val updated = _hosts.value.map {
                    if (it.deviceId == signal.deviceId) {
                        it.copy(online = false, lastSeenMs = seenAt)
                    } else it
                }
                _hosts.value = updated
                _state.value = _state.value.copy(hosts = updated)
                scope.launch {
                    try {
                        SettingsRepository(context).saveLastSeenMap(_lastSeen.value)
                    } catch (_: Throwable) {
                    }
                }
            }

            is Signal.LastSeen -> {
                val serverItems = signal.items
                val merged = _lastSeen.value + serverItems
                _lastSeen.value = merged
                scope.launch {
                    try {
                        SettingsRepository(context).saveLastSeenMap(merged)
                    } catch (_: Throwable) {
                    }
                }
                // الأجهزة الغائبة عن لقطة الخادم الحية نعلمها مع وقت آخر ظهور
                val refreshed = _hosts.value.map { h ->
                    if (!h.online && h.lastSeenMs == null) {
                        val ms = serverItems[h.deviceId]
                        h.copy(online = false, lastSeenMs = ms)
                    } else h
                }
                _hosts.value = refreshed
                _state.value = _state.value.copy(hosts = refreshed)
            }

            is Signal.Answer -> {
                // المضيف قبل اتصالنا وردّ بالإجابة
                peerManager?.setRemoteAnswer(signal.sdp, signal.sdpType)
            }

            is Signal.Ice -> {
                peerManager?.addIceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.sdp)
            }

            is Signal.Reject -> {
                Log.w(TAG, "رفض المضيف الاتصال: ${signal.reason}")
                _state.value = _state.value.copy(peerState = PeerState.Failed)
            }

            is Signal.RpcResponse -> {
                // ردود الأوامر المرحّلة عبر خادم الإشارات (الناقل البديل عن قناة البيانات)
                val rid = signal.requestId
                val response = BridgeResponse(
                    id = rid,
                    ok = signal.ok,
                    error = signal.error,
                    data = signal.data
                )
                if (downloadFlows.containsKey(rid)) {
                    downloadFlows[rid]?.tryEmit(response)
                } else {
                    pendingResponses.remove(rid)?.tryEmit(response)
                }
            }

            is Signal.LiveFrame -> {
                // إطارات البث الحي عبر الترحيل: فكّ base64 ثم فكّ JPEG ثم نعرض
                // أحدث إطار فقط (يبقى الأخير ظاهراً حتى يأتي تالياً)
                if (signal.from == myDeviceId) return
                if (liveFrameHostId != signal.from) {
                    liveFrameHostId = signal.from
                    _liveStreamInfo.value = LiveStreamInfo(
                        stream = signal.stream,
                        fromHost = signal.from,
                        startedAt = System.currentTimeMillis()
                    )
                }
                scope.launch(Dispatchers.Default) {
                    try {
                        val bytes = Base64.decode(signal.b64, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            // لا نستدعي recycle على القديمة: قد تكون Compose لا
                            // تزال ترسمها في اللحظة نفسها — يتحرر عبر جامع الفضلات
                            _liveFrame.value = bitmap
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "تجاهل إطار بث مرحّل: ${t.message}")
                    }
                }
            }

            is Signal.Pong -> { /* نبض القلب */ }
            is Signal.Registered -> {
                Log.d(TAG, "تم تسجيل جهاز التحكم: ${signal.ok}")
                if (signal.ok) {
                    // نسأل الخادم عن "آخر ظهور" لكل الأجهزة المعروفة
                    try {
                        signaling?.send(Signal.GetLastSeen(emptyList()))
                    } catch (_: Throwable) {
                    }
                }
            }

            is Signal.SettingsSync -> {
                if (signal.from == myDeviceId) return
                scope.launch {
                    val result = SettingsSyncManager.applyIncoming(context, signal.settings)
                    if (result?.adopted == true) {
                        Log.i(
                            TAG,
                            "تم تبنّي إعدادات جديدة من المضيف ${signal.from} (ts=${result.newTs})"
                        )
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * يجمع أحداث قناة WebRTC.
     */
    private fun collectPeerEvents() {
        val manager = peerManager ?: return
        signalJob?.cancel()
        signalJob = scope.launch {
            // أرسل مرشّحات ICE المحلية للمضيف
            launch {
                manager.iceCandidates.collectLatest { candidate ->
                    val hostId = _state.value.connectedHost?.deviceId ?: return@collectLatest
                    signaling?.send(
                        Signal.Ice(
                            from = myDeviceId,
                            to = hostId,
                            sdp = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                }
            }

            // وجّه الردود الواردة إلى الـ Flow المنتظر لها (مع دعم التنزيلات)
            launch {
                manager.incomingResponses.collectLatest { response ->
                    if (downloadFlows.containsKey(response.id)) {
                        downloadFlows[response.id]?.tryEmit(response)
                    } else {
                        pendingResponses.remove(response.id)?.tryEmit(response)
                    }
                }
            }

            // وجّه القطع الثنائية الواردة
            launch {
                manager.incomingBinary.collectLatest { chunk ->
                    _binaryChunks.tryEmit(chunk)
                }
            }

            // راقب حالة قناة البيانات
            launch {
                manager.state.collectLatest { peerState ->
                    _state.value = _state.value.copy(peerState = peerState)
                    // عند اكتمال الاتصال المباشر: "رأينا" الجهاز + نسجل جلسة
                    // تلقائياً في ملفات المتحكم للتصفح لاحقاً (حفظ تلقائي).
                    if (peerState == PeerState.Connected) {
                        val host = _state.value.connectedHost
                        if (host != null && !_sessionRecordedIds.contains(host.deviceId)) {
                            _sessionRecordedIds.add(host.deviceId)
                            _lastSeen.value = _lastSeen.value + (host.deviceId to System.currentTimeMillis())
                            scope.launch {
                                try {
                                    SessionRecorder.recordSession(context, host)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "فشل تسجيل جلسة تلقائي", t)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * يستعلم الخادم عن "آخر ظهور" (يُستدعى من نافذة الحالة أيضاً).
     */
    fun requestLastSeen() {
        try {
            signaling?.send(Signal.GetLastSeen(emptyList()))
        } catch (_: Throwable) {
        }
    }

    /**
     * يبدأ اتصالاً مع جهاز مضيف محدد.
     */
    fun connectToHost(host: HostInfo) {
        val manager = peerManager ?: return
        _state.value = _state.value.copy(
            connectedHost = host,
            peerState = PeerState.Connecting
        )
        manager.createOffer(
            onSuccess = { offerSdp ->
                signaling?.send(
                    Signal.Offer(
                        from = myDeviceId,
                        to = host.deviceId,
                        sdp = offerSdp,
                        sdpType = "OFFER"
                    )
                )
                // مزامنة الإعدادات: نرسل لقطتنا للمضيف (تُبنى إن كانت أحدث)
                scope.launch {
                    try {
                        signaling?.send(
                            Signal.SettingsSync(
                                from = myDeviceId,
                                to = host.deviceId,
                                ts = System.currentTimeMillis(),
                                settings = SettingsSyncManager.buildSnapshot(context)
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "تعذر إرسال لقطة إعدادت المتحكم للمضيف", t)
                    }
                }
            },
            onFailure = { error ->
                Log.e(TAG, "فشل إنشاء العرض: $error")
                _state.value = _state.value.copy(peerState = PeerState.Failed)
            }
        )
    }

    /**
     * يرسل طلباً للمضيف ويعيد Flow يصدر رداً واحداً عند وصوله.
     */
    fun sendRequest(cmd: String, params: Map<String, String> = emptyMap()): MutableSharedFlow<BridgeResponse> {
        val id = requestIdCounter.incrementAndGet()
        val responseFlow = MutableSharedFlow<BridgeResponse>(extraBufferCapacity = 1)
        pendingResponses[id] = responseFlow
        val request = BridgeRequest(
            id = id,
            cmd = cmd,
            params = buildParamsJson(params)
        )
        val manager = peerManager
        if (manager == null || !manager.sendRequest(request)) {
            responseFlow.tryEmit(BridgeResponse(id, false, "الاتصال غير جاهز", null))
            pendingResponses.remove(id)
        }
        return responseFlow
    }

    /**
     * يرسل أمراً للمضيف عبر ترحيل خادم الإشارات مباشرةً (ناقل مستقل عن
     * قناة بيانات WebRTC) — الوسيلة البديلة للبث الحي تعتمد هذا الناقل
     * وتعمل على أي شبكة تصل فيها رسائل النظام على الإطلاق.
     */
    fun sendRequestRelay(
        hostId: String,
        cmd: String,
        params: Map<String, String> = emptyMap()
    ): MutableSharedFlow<BridgeResponse> {
        val id = requestIdCounter.incrementAndGet()
        val responseFlow = MutableSharedFlow<BridgeResponse>(extraBufferCapacity = 1)
        pendingResponses[id] = responseFlow
        val sent = try {
            signaling?.send(
                Signal.RpcRequest(
                    from = myDeviceId,
                    to = hostId,
                    requestId = id,
                    cmd = cmd,
                    params = buildParamsJson(params)
                )
            ) ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "فشل إرسال أمر مرحّل: ${t.message}")
            false
        }
        if (!sent) {
            responseFlow.tryEmit(BridgeResponse(id, false, "خادم الإشارات غير متاح للترحيل", null))
            pendingResponses.remove(id)
        }
        return responseFlow
    }

    /**
     * تنزيل ملف من المضيف عبر قطع قناة البيانات وحفظه في مجلد التحميلات
     * المختار (إعدادات التطبيق) أو مجلد التطبيق الخاص.
     * تقدم التنزيل يرد عبر [downloadProgress] للواجهة.
     */
    suspend fun downloadFile(fileId: Long, bucket: String, fileName: String): DownloadResult {
        val manager = peerManager
        if (manager == null) {
            return DownloadResult(false, error = "الاتصال غير جاهز")
        }
        _downloadProgress.value = FileDownloadProgress(fileName, 0)
        try {
            // معالجة حتى 10 دقائق لأي ملف
            return withTimeout(10 * 60_000L) {
                executeDownload(manager, fileId, bucket, fileName)
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            _downloadProgress.value = _downloadProgress.value?.copy(finished = true)
            return DownloadResult(false, error = "انتهت مهلة التنزيل")
        } catch (t: Throwable) {
            _downloadProgress.value = _downloadProgress.value?.copy(finished = true)
            return DownloadResult(false, error = t.message ?: "فشل التنزيل")
        }
    }

    /** تنفيذ التنزيل الفعلي (خلال المهلة) عبر قناة البيانات. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun executeDownload(
        manager: PeerConnectionManager,
        fileId: Long,
        bucket: String,
        fileName: String
    ): DownloadResult {
        val id = requestIdCounter.incrementAndGet()
        // replay=1: ضماناً لأن اللقطة الختامية {completed:true} لا تضيع لو وصلت
        // قبل لحظة الاشتراك في [flow.first] (القناة أسرع من الجدولة أحياناً)
        val flow = MutableSharedFlow<BridgeResponse>(replay = 1, extraBufferCapacity = 16)
        downloadFlows[id] = flow

        var receivedBytes = 0L
        var folderLabel = ""

        // مستمع القطع الثنائية: نضمن اكتمال الاشتراك قبل إرسال الطلب (لا أول قطعة
        // تضيع) — عبر onSubscription وإشارة اكتمال.
        val collectorSubscribed = CompletableDeferred<Unit>()
        val chunkCollector = scope.launch {
            // onSubscription مرتبطة بـ SharedFlow فقط (وليس Flow بعد filter) —
            // لذا نطبقها أولاً على التدفق الأصلي ثم نفصل القطع بالمعرّف يدوياً.
            manager.incomingBinary
                .onSubscription { collectorSubscribed.complete(Unit) }
                .collect { chunk ->
                    if (chunk.messageId == id) {
                        writtenStream?.let { out ->
                            synchronized(out) { out.write(chunk.data) }
                            receivedBytes += chunk.data.size
                            _downloadProgress.value = FileDownloadProgress(
                                fileName, receivedBytes, finished = false, folderLabel = folderLabel
                            )
                        }
                    }
                }
        }
        collectorSubscribed.await()

        try {
            val request = BridgeRequest(
                id = id,
                cmd = "file_open",
                params = buildParamsJson(
                    mapOf("fileId" to fileId.toString(), "bucket" to bucket)
                )
            )
            // نفتح ملف الحفظ مباشرة قبل إرسال الطلب (قد تصل القطع الثنائية فوراً)
            val opened = DownloadsSaver.openOutput(context, fileName, useRecordingFolder = false)
            folderLabel = opened.folderLabel
            writtenStream = opened.stream
            try {
                if (!manager.sendRequest(request)) {
                    return DownloadResult(false, error = "فشل إرسال طلب التنزيل (قناة البيانات غير جاهزة)")
                }

                // ننتظر الرد الختامي {completed:true} لنرى نهاية النقل بكامل تدفقه
                var finalResponse: BridgeResponse? = null
                val terminal = flow.first { response ->
                    if (!response.ok) {
                        finalResponse = response
                        true
                    } else {
                        response.data?.containsKey("completed") == true
                    }
                }

                // أغلق ستريم الكتابة
                try {
                    writtenStream?.flush()
                    writtenStream?.close()
                } catch (_: Throwable) {
                }
                writtenStream = null

                if (!terminal.ok) {
                    return DownloadResult(
                        false,
                        folderLabel,
                        receivedBytes,
                        terminal.error ?: "فشل تنزيل الملف"
                    )
                }
                _downloadProgress.value = FileDownloadProgress(
                    fileName, receivedBytes, finished = true, folderLabel = folderLabel
                )
                return DownloadResult(
                    true,
                    folderLabel,
                    receivedBytes,
                    null
                )
            } finally {
                // نغلق ستريم الكتابة في حال أي انقطاع (أمان)
                try {
                    if (writtenStream != null) {
                        writtenStream?.close()
                        writtenStream = null
                    }
                } catch (_: Throwable) {
                }
                downloadFlows.remove(id)
                chunkCollector.cancel()
            }
        } catch (t: Throwable) {
            try {
                writtenStream?.close()
                writtenStream = null
            } catch (_: Throwable) {
            }
            downloadFlows.remove(id)
            chunkCollector.cancel()
            throw t
        }
    }

    private var writtenStream: java.io.OutputStream? = null

    /**
     * يحوّل خريطة المعاملات إلى JsonObject.
     */
    private fun buildParamsJson(params: Map<String, String>): kotlinx.serialization.json.JsonObject {
        val obj = kotlinx.serialization.json.buildJsonObject {
            params.forEach { (key, value) ->
                put(
                    key,
                    kotlinx.serialization.json.JsonPrimitive(value)
                )
            }
        }
        return obj
    }

    /**
     * يقطع الاتصال مع المضيف الحالي.
     */
    fun disconnectFromHost() {
        peerManager?.close()
        peerManager = null
        _state.value = _state.value.copy(
            connectedHost = null,
            peerState = PeerState.Idle
        )
        // أعد إنشاء المدير لاتصال لاحق
        peerManager = PeerConnectionManager(context, scope).also { it.start() }
        collectPeerEvents()
    }

    /**
     * يقطع كل الاتصالات ويحرّر الموارد.
     */
    fun disconnect() {
        signalJob?.cancel()
        peerManager?.close()
        peerManager = null
        signaling?.disconnect()
        signaling = null
        pendingResponses.clear()
        // تنظيف حالة البث الحي المرحّل
        liveFrameHostId = ""
        _liveFrame.value = null
        _liveStreamInfo.value = null
        _state.value = ControllerState()
        _hosts.value = emptyList()
    }
}
