package com.remotebridge.core.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.remotebridge.core.model.BridgeRequest
import com.remotebridge.core.model.BridgeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.webrtc.AudioSource

import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class PeerState { Idle, Connecting, Connected, Failed, Closed }

class PeerConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS
) {
    companion object {
        private const val TAG = "PeerManager"
        private const val DATA_CHANNEL_ID = 42

        val DEFAULT_ICE_SERVERS = listOf(
            // STUN servers (لاكتشاف العناوين العامة عبر خادم رندر)
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isFrontFacingCamera: Boolean = false
    private var isAudioStreamingEnabled: Boolean = true
    private var iceRestartInProgress: Boolean = false
    // 🔧 B17 — مرجع مصدر الصوت محفوظ حتى لا يُجَمَّع للقمامة أثناء البث الحي (كان ضائعاً)
    private var audioSource: AudioSource? = null
    // 🔧 B17 — قفل متزامن لتبديل الكاميرا: يمنع أوامر التبديل المتلاحقة من كسر الحركة
    @Volatile
    private var cameraSwitchBusy: Boolean = false
    // 🔧 B21 — قفل هندسي لتدفق البث: يمنع استعارة/تلف بنية المسارات من خيطَي الإيقاف
    // المتوازيين (مثل أرشفة التلافي والإيقاع) فيُعمّ دون لمسات متزامنة على الأغلفة الحية.
    private val streamingMutex = Any()

    private val _state = MutableStateFlow(PeerState.Idle)
    val state: StateFlow<PeerState> = _state.asStateFlow()

    private val _incomingRequests = MutableSharedFlow<BridgeRequest>(extraBufferCapacity = 64)
    val incomingRequests: SharedFlow<BridgeRequest> = _incomingRequests.asSharedFlow()

    private val _incomingResponses = MutableSharedFlow<BridgeResponse>(extraBufferCapacity = 64)
    val incomingResponses: SharedFlow<BridgeResponse> = _incomingResponses.asSharedFlow()

    private val _incomingBinary = MutableSharedFlow<BinaryChunk>(extraBufferCapacity = 16)
    val incomingBinary: SharedFlow<BinaryChunk> = _incomingBinary.asSharedFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private val iceCandidateCallbacks = mutableMapOf<String, (Boolean) -> Unit>()

    data class BinaryChunk(
        val messageId: Int,
        val seq: Int,
        val total: Int,
        val data: ByteArray
    )

    fun start() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val audioDevice = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    EglBaseProvider.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(EglBaseProvider.eglBaseContext)
            )
            .createPeerConnectionFactory()
    }

    /**
     * إعادة تشغيل ICE مفروضة للتغلب على فشل الاتصال خلف NAT
     */
    fun triggerIceRestart(onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        if (iceRestartInProgress) {
            onFailure("ICE restart قيد التنفيذ بالفعل")
            return
        }
        iceRestartInProgress = true

        val conn = ensureConnection() ?: run {
            iceRestartInProgress = false
            onFailure("فشل تهيئة الاتصال")
            return
        }

        Log.d(TAG, "بدء ICE restart...")

        conn.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    iceRestartInProgress = false
                    onFailure("عرض فارغ بعد ICE restart")
                    return
                }
                // تعديل SDP لإجبار تحديث ufrag و pwd (ICE restart)
                val modifiedSdp = modifySdpForIceRestart(sdp.description)
                val newSdp = SessionDescription(sdp.type, modifiedSdp)
                conn.setLocalDescription(SimpleSdpObserver {
                    iceRestartInProgress = false
                    onSuccess(modifiedSdp)
                }, newSdp)
            }

            override fun onCreateFailure(error: String?) {
                iceRestartInProgress = false
                onFailure(error ?: "فشل إنشاء عرض ICE restart")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        })
    }

    private fun modifySdpForIceRestart(sdp: String): String {
        val randomUfrag = generateRandomString(8)
        val randomPwd = generateRandomString(22)
        return sdp
            .replace(Regex("a=ice-ufrag:[^\r]*\r\n"), "a=ice-ufrag:$randomUfrag\r\n")
            .replace(Regex("a=ice-pwd:[^\r]*\r\n"), "a=ice-pwd:$randomPwd\r\n")
    }

    private fun generateRandomString(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    fun createOffer(onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        val connection = ensureConnection() ?: run {
            onFailure("فشل تهيئة الاتصال")
            return
        }
        createDataChannel()

        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    onFailure("عرض فارغ")
                    return
                }
                connection.setLocalDescription(SimpleSdpObserver {
                    onSuccess(sdp.description)
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                onFailure(error ?: "فشل إنشاء العرض")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        })
    }

    fun acceptOffer(
        sdp: String,
        sdpType: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // 🔧 B5 — منع تخريب تفاوض قائم بعرض جديد (عاصفة العروض/Glare):
        // يُغلق الاتصال القديم فقط إذا كان تفاوضه غير مكتمل (Have-Local/Remote-Offer)؛
        // أما الاتصال المفاوض المستقر فيقبض العرض الجديد كإعادة تفاوض على نفس الاتصال.
        val current = connection
        if (current != null && current.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
            current != null && current.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER
        ) {
            Log.w(TAG, "إعادة تهيئة PeerConnection لقبول عرض جديد (تفاوض سابق غير مكتمل)")
            try { current.close(); current.dispose() } catch (_: Throwable) {}
            connection = null
        }
        val conn = ensureConnection() ?: run {
            onFailure("فشل تهيئة الاتصال")
            return
        }

        val type = if (sdpType.equals("ANSWER", ignoreCase = true))
            SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER

        // 🔧 B5 — الترتيب الصحيح: تُطبَّق وصفة العرض البعيد أولاً (فينشأ مسارا تفاوض
        // للفيديو/الصوت من العرض نفسه)، ثم تُرفق المسارات الحالية على
        // مرسِلي المسارات — عوضاً عن addTrack الذي كان ينشئ سطر m-line إضافياً
        // فتنتهج الإجابة بعدد أسطر أكبر من العرض فيرفضها setLocalDescription
        // بصمت (onSetFailure كان يُهمل!) فلا تصل الإجابة للمتحكم أبداً.
        conn.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "B5: طُبّق العرض البعيد — إرفاق المسارات على مسارات التفاوض")
                attachTracksToNegotiatedTransceivers(conn)
                conn.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) {
                            onFailure("إجابة فارغة")
                            return
                        }
                        conn.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onCreateFailure(error: String?) {}
                            override fun onSetSuccess() {
                                Log.d(TAG, "B5: الإجابة جاهزة — إرسالها إلى المتحكم")
                                onSuccess(answer.description)
                            }

                            override fun onSetFailure(error: String?) {
                                // 🔧 لم يعد يُهمل: فشل التطبيق المحلي يجب أن يعلن
                                Log.e(TAG, "B5: فشل ضبط الإجابة محلياً: $error")
                                onFailure("فشل ضبط الإجابة محلياً: ${error ?: "سبب غير معروف"}")
                            }
                        }, answer)
                    }

                    override fun onCreateFailure(error: String?) {
                        onFailure(error ?: "فشل إنشاء الإجابة")
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }

            override fun onCreateFailure(error: String?) {
                onFailure(error ?: "خطأ غير متوقع")
            }

            override fun onSetFailure(error: String?) {
                // 🔧 إبلاغ صريح بدل الصمت — يعرف المتحكم السبب عبر Reject
                Log.e(TAG, "B5: فشل تطبيق العرض البعيد: $error")
                onFailure("فشل تطبيق العرض البعيد: ${error ?: "سبب غير معروف"}")
            }
        }, SessionDescription(type, sdp))
    }

    /**
     * 🔧 B5 — إرفاق مسارات البث الحية (الموجودة بالفعل أو بعد إعادة بناء الاتصال)
     * على مسارات تفاوض (Transceivers) أُنشئت من العرض البعيد نفسه.
     *
     * هذه هي الوسيلة الوحيدة الصحيحة في Unified-Plan هنا: لا يُستخدم addTrack
     * لأنه ينشئ مساراً جديداً خارج عرض المتحكم (فيتجاوز عدد أسطر العرض) ويكسر الإجابة.
     * تغيير الاتجاه إلى SEND_RECV آمن قبل الإجابة (تطبق مكتبة WebRTC التقاطع تلقائياً)،
     * وبعد الإجابة يبقى setTrack في المرسِل مشغلاً بالاتجاه المتفاوض عليه (dispatch sendrecv/sendonly).
     */
    private fun attachTracksToNegotiatedTransceivers(pc: PeerConnection) {
        try {
            var attached = 0
            val vt = videoTrack
            if (vt != null) {
                val videoTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                }
                if (videoTr != null) {
                    try { videoTr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV } catch (_: Throwable) {}
                    videoTr.sender.setTrack(vt, true)
                    attached++
                } else {
                    Log.w(TAG, "لا مسار مفاوض للفيديو في العرض — سيُرفق عند التفاوض القادم")
                }
            } else {
                // 🔧 B17 — بعد إيقاف الفيديو نُنزع مسار الوقف (disposed) من المرسل
                // حتى لا تستمر قناة الفيديو في إرسال مسارٍ ملغى فتُسقط التفاوض التالي
                val videoTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                }
                if (videoTr != null) {
                    try { videoTr.sender.setTrack(null, true) } catch (_: Throwable) {}
                }
            }
            val at = audioTrack
            if (at != null) {
                val audioTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                }
                if (audioTr != null) {
                    try { audioTr.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV } catch (_: Throwable) {}
                    audioTr.sender.setTrack(at, true)
                    attached++
                } else {
                    Log.w(TAG, "لا مسار مفاوض للصوت في العرض — سيُرفق عند التفاوض القادم")
                }
            } else {
                // 🔧 B17 — نفس التنظيف للصوت عبر المرسل حين تغيب قناة الصوت
                val audioTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                }
                if (audioTr != null) {
                    try { audioTr.sender.setTrack(null, true) } catch (_: Throwable) {}
                }
            }
            if (attached > 0) Log.d(TAG, "🔁 أُرفق $attached مساراً بمسارات التفاوض (بلا تفاوض إضافي)")
        } catch (t: Throwable) {
            Log.w(TAG, "فشل إرفاق المسارات: ${t.message}")
        }
    }

    fun setRemoteAnswer(sdp: String, sdpType: String) {
        val connection = this.connection ?: return
        val type = if (sdpType.equals("ANSWER", ignoreCase = true))
            SessionDescription.Type.ANSWER else SessionDescription.Type.ANSWER
        connection.setRemoteDescription(
            SimpleSdpObserver {
                drainPendingIceCandidates()
            },
            SessionDescription(type, sdp)
        )
    }

    fun addIceCandidate(mid: String, mLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(mid, mLineIndex, sdp)
        val conn = connection
        if (conn != null && conn.remoteDescription != null) {
            try {
                conn.addIceCandidate(candidate)
            } catch (t: Throwable) {
                Log.w(TAG, "فشل إضافة ICE: ${t.message}")
            }
        } else {
            synchronized(pendingRemoteIceCandidates) {
                pendingRemoteIceCandidates.add(candidate)
            }
            Log.d(TAG, "تأجيل إضافة مرشح ICE لحين تطبيق RemoteDescription")
        }
    }

    private fun drainPendingIceCandidates() {
        val conn = connection ?: return
        synchronized(pendingRemoteIceCandidates) {
            if (pendingRemoteIceCandidates.isNotEmpty()) {
                Log.d(TAG, "تطبيق (${pendingRemoteIceCandidates.size}) مرشحات ICE معلقة...")
                for (cand in pendingRemoteIceCandidates) {
                    try {
                        conn.addIceCandidate(cand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "خطأ إضافة مرشح معلق: ${t.message}")
                    }
                }
                pendingRemoteIceCandidates.clear()
            }
        }
    }

    fun startScreenStreaming(mediaProjectionPermissionResultData: Intent): Boolean {
        val factory = this.factory ?: return false

        try {
            // 🔧 B5 — إيقاف أي بث سابق (كاميرا/شاشة) قبل الفتح؛ كان الشرط القديم
            // `videoTrack != null → return true` يلغي التحويل من كاميرا إلى شاشة تماماً
            stopStreaming()

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                EglBaseProvider.eglBase.eglBaseContext
            )
            videoCapturer = ScreenCapturerAndroid(
                mediaProjectionPermissionResultData,
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "توقفت مشاركة الشاشة")
                    }
                }
            )
            videoSource = factory.createVideoSource(true)
            videoCapturer?.initialize(
                surfaceTextureHelper,
                context,
                videoSource!!.capturerObserver
            )
            videoCapturer?.startCapture(1280, 720, 30)

            videoTrack = factory.createVideoTrack(nextVideoTrackId(), videoSource!!)
            attachOrPrepareTracks()

            // 🔧 B21 — صوت الشاشة: يبدأ الميكروفون مساراً مفعّلاً افتراضياً (بلا سياسة صمت قسرية)،
            // والمتحكم يستطيع كتمه بنقرة مكبر الصوت متى شاء. بستةٍ وسياسة الصمت السابقة لم يسمع الحيّ.
            try {
                val audioConstraints = MediaConstraints()
                val audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack(nextAudioTrackId(), audioSource)
                isAudioStreamingEnabled = true
                audioTrack?.setEnabled(true)
                attachOrPrepareTracks()
            } catch (t: Throwable) {
                Log.w(TAG, "تعذّر تهيئة مسار الصوت مع بث الشاشة (سيستمر البث المرئي بلا صوت): ${t.message}")
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بدء بث الشاشة", t)
            return false
        }
    }

    /**
     * بدء البث الحي لكاميرا الهاتف (أمامية أو خلفية) مع خيار تفعيل الميكروفون.
     */
    fun startCameraStreaming(frontCamera: Boolean, withAudio: Boolean = true): Boolean {
        val factory = this.factory ?: return false
        // 🔧 B5 — لم يعد يتطلب اتصالاً مفاوضاً: تُحضَّر المصادر ويُرفق المسارات
        // تلقائياً عند اكتمال ربط WebRTC (معالجة ذاتية بدل رفض الأمر)
        stopStreaming()

        try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            var selectedDevice: String? = null

            for (name in deviceNames) {
                if (frontCamera && enumerator.isFrontFacing(name)) {
                    selectedDevice = name
                    break
                } else if (!frontCamera && enumerator.isBackFacing(name)) {
                    selectedDevice = name
                    break
                }
            }

            if (selectedDevice == null && deviceNames.isNotEmpty()) {
                selectedDevice = deviceNames[0]
            }

            if (selectedDevice == null) {
                Log.e(TAG, "لم يتم العثور على أجهزة كاميرا متاحة")
                return false
            }

            isFrontFacingCamera = enumerator.isFrontFacing(selectedDevice)

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CameraCaptureThread",
                EglBaseProvider.eglBase.eglBaseContext
            )
            val capturer = enumerator.createCapturer(selectedDevice, null) ?: return false
            videoCapturer = capturer
            videoSource = factory.createVideoSource(false)
            capturer.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            // 🧩 B20 — إعادة محاولة واحدة عند التعثر: بعد الإيقاف مباشرة قد ترفض `Camera2`
            // فتح الجهاز لحظات قليلة (لأنه لم يتحرر بعد من التسوية السابقة) فيفشل بدء البث
            // كله؛ الهدوء القصير ثم المحاولة الثانية يعالجان العلة المتكررة.
            try {
                capturer.startCapture(1280, 720, 30)
            } catch (first: Throwable) {
                Log.w(TAG, "تعثر أول التقاط للكاميرا (${first.message}) — إعادة المحاولة بعد 350 م.ث")
                try { Thread.sleep(350L) } catch (_: Throwable) { }
                try {
                    capturer.startCapture(1280, 720, 30)
                } catch (second: Throwable) {
                    Log.e(TAG, "فشل بدء بث الكاميرا حتى بعد إعادة المحاولة", second)
                    try { capturer.dispose() } catch (_: Throwable) { }
                    videoCapturer = null
                    videoSource?.dispose()
                    videoSource = null
                    surfaceTextureHelper?.dispose()
                    surfaceTextureHelper = null
                    return false
                }
            }

            videoTrack = factory.createVideoTrack(nextVideoTrackId(), videoSource!!)
            attachOrPrepareTracks()

            if (withAudio) {
                val audioConstraints = MediaConstraints()
                val audioSourceNew = factory.createAudioSource(audioConstraints)
                audioSource = audioSourceNew
                audioTrack = factory.createAudioTrack(nextAudioTrackId(), audioSourceNew)
                isAudioStreamingEnabled = true
                attachOrPrepareTracks()
            }

            Log.i(TAG, "تم بدء بث الكاميرا بنجاح: front=$isFrontFacingCamera, audio=$withAudio")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بدء بث الكاميرا", t)
            return false
        }
    }

    /**
     * بدء البث الحي للصوت فقط من الميكروفون مع إيقاف الكاميرا.
     */
    fun startAudioOnlyStreaming(): Boolean {
        val factory = this.factory ?: return false
        stopStreaming()

        try {
            val audioConstraints = MediaConstraints()
            val audioSourceNew = factory.createAudioSource(audioConstraints)
            audioSource = audioSourceNew
            audioTrack = factory.createAudioTrack(nextAudioTrackId(), audioSourceNew)
            isAudioStreamingEnabled = true
            attachOrPrepareTracks()

            Log.i(TAG, "تم بدء بث الصوت فقط من الميكروفون بنجاح")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بدء بث الصوت فقط", t)
            return false
        }
    }

    /**
     * 🔧 B5 — ربط المسارات الحالية على مسارات التفاوض إن كان الاتصال مفاوضاً
     * (تعمل فوراً بلا تفاوض إضافي لأن اتجاه الأسطور العرض sendrecv)،
     * وإلا تبقى المسارات معدة وتُرفق تلقائياً لحظة إجابة التفاوض التالية.
     */
    private fun attachOrPrepareTracks() {
        val conn = connection
        if (conn != null && conn.signalingState() == PeerConnection.SignalingState.STABLE &&
            conn.getRemoteDescription() != null
        ) {
            attachTracksToNegotiatedTransceivers(conn)
        }
    }

    /**
     * التبديل السريع بين الكاميرا الأمامية والخلفية أثناء البث الحي.
     * 🔧 B17 — يعمل بالتبديل الداخلي (Camera2 switch) مع استمرار البث والمشغّل:
     * •بلا أي إيقاف أو تفاوض، وقفل متزامن يمنع النقرات المتلاحقة من كسر الحركة.
     * 🔧 B21 — يردّ بمؤشر نجاح/فشل حقيقيين صادقين: ينتظر جواب قافلة `Camera2`
     * إلى حدّ الساعة ونصف، فلا يخرج «تم» كاذبة للمتحكم على قلب مستلمٍ أبداً.
     */
    fun switchCamera(onComplete: ((Boolean, String?) -> Unit)? = null) {
        val capturer = videoCapturer
        if (cameraSwitchBusy) {
            Log.w(TAG, "تبديل الكاميرا قيد التنفيذ بالفعل — تجاهل الطلب المتزامن بأمان")
            onComplete?.invoke(false, "تبديل الكاميرا قيد التنفيذ بالفعل — انتظر لحظة وأعد المحاولة")
            return
        }
        if (capturer is CameraVideoCapturer) {
            cameraSwitchBusy = true
            val switchLatch = java.util.concurrent.CountDownLatch(1)
            var switchSucceeded = false
            var switchFailedReason: String? = null
            var switchAnswered = false
            val switchLock = Any()
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    isFrontFacingCamera = isFront
                    cameraSwitchBusy = false
                    Log.i(TAG, "تم تبديل الكاميرا بنجاح. أمامية: $isFront")
                    synchronized(switchLock) { switchSucceeded = true }
                    switchLatch.countDown()
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.e(TAG, "فشل تبديل الكاميرا: $errorDescription")
                    cameraSwitchBusy = false
                    synchronized(switchLock) {
                        if (switchFailedReason == null) {
                            switchFailedReason = errorDescription ?: "تعذر تبديل الكاميرا على الهاتف المضيف — أعد المحاولة"
                        }
                    }
                    switchLatch.countDown()
                }
            })
            try {
                switchLatch.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            synchronized(switchLock) {
                if (!switchAnswered) {
                    switchAnswered = true
                    if (switchSucceeded) {
                        onComplete?.invoke(true, null)
                    } else {
                        onComplete?.invoke(
                            false,
                            switchFailedReason ?: "انقضى زمن تبديل الكاميرا دون جواب — أعد المحاولة"
                        )
                    }
                }
            }
        } else {
            Log.w(TAG, "لا يوجد التقاط كاميرا نشط الآن — لا تبديل ممكن (بذون آمن مهما كان)")
            onComplete?.invoke(false, "لا يوجد بث كاميرا نشط للتبديل — ابدأ بث الكاميرا أولاً ثم ظبّل العدسة")
        }
    }

    /**
     * التحكم بتشغيل أو كتم الميكروفون والصوت أثناء البث.
     * 🔧 B17 — بلا أي إيقاف للاتصال: كتم/تفعيل المسار الفاعل فقط،
     * وعند التفعيل مع غياب المسار يُنشأ مصدر صوت جديد ويُرفق عند الطلب.
     */
    fun setAudioEnabled(enabled: Boolean) {
        try {
            val track = audioTrack
            if (track != null) {
                isAudioStreamingEnabled = enabled
                track.setEnabled(enabled)
                Log.i(TAG, "حالة الميكروفون الآن: $enabled")
                return
            }
            if (!enabled) {
                isAudioStreamingEnabled = false
                Log.i(TAG, "طلب كتم الصوت ولا مسار صوت فعلي — لا شيء يُغيَّر")
                return
            }
            // 🔧 B17 — تفعيل الصوت مع غياب المسار: إنشاء مصدر صوت جديد وعالج بالرفاق
            val factory = this.factory
            if (factory != null) {
                try {
                    val audioSourceNew = factory.createAudioSource(MediaConstraints())
                    audioSource = audioSourceNew
                    audioTrack = factory.createAudioTrack(nextAudioTrackId(), audioSourceNew)
                    isAudioStreamingEnabled = true
                    attachOrPrepareTracks()
                    audioTrack?.setEnabled(true)
                    Log.i(TAG, "أُنشئ مسار الصوت عند الطلب وأُرفق بالبناء بلا تفاوض إضافي")
                } catch (t: Throwable) {
                    Log.e(TAG, "فشل إنشاء مسار الصوت عند الطلب", t)
                    isAudioStreamingEnabled = false
                }
            } else {
                isAudioStreamingEnabled = false
                Log.w(TAG, "طلب تفعيل الصوت والاتصال غير مهيأ بعد — سيُلبي عند بدء البث")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل ضبط حالة الصوت: ${t.message}")
        }
    }

    fun isCameraStreaming(): Boolean = videoTrack != null
    fun isUsingFrontCamera(): Boolean = isFrontFacingCamera
    fun isAudioTrackEnabled(): Boolean = isAudioStreamingEnabled

    // 🧩 B20 — عدّاد معرّفات مسارات فريدة: كان `ARDAMSv0` يُعاد استخدامه في كل بدء
    // فيتعثّر تمييز المسار الجديد لدى مستقبِل المتحكم؛ كل بدء يولّد معرّفين جديدين.
    private var trackSeq = 0
    private fun nextVideoTrackId(): String { trackSeq++; return "RBV$trackSeq" }
    private fun nextAudioTrackId(): String { trackSeq++; return "RBA$trackSeq" }

    fun stopStreaming() {
        // 🔧 B21 — سورة هندسية: الإيقاف وبدء الجيل التالي لا يتسارعان على بنية المسارات
        // أبداً — القفل يعزل تسلسل (تفريغ المرسلين → تلف الأغلفة) عن أي إيقاف موازٍ.
        synchronized(streamingMutex) {
            try {
                videoCapturer?.stopCapture()
            } catch (_: Throwable) {
            }
            // 🧩 B21 — فصل المرسلين قبل تلف المسارات **بلا لمس غلاف المسار إطلاقاً**:
            // قراءة `sender.track()` كانت تعيد غلافاً مخبّأً قد يكون مستلفاً لتلف مسار
            // الجيل السابق، فتُرمى عند اللمس اللاحق «MediaStreamTrack has been disposed»
            // ويفشل البث الثاني دائماً. لذّا نُنفّذ `setTrack(null, true)` مبكراً كحدٍّ
            // للتسويّة الساذجة لكل المرسلين (غير مأذون مرتين — العملية سمحية الهوام).
            try {
                connection?.transceivers?.forEach { tr ->
                    try { tr.sender.setTrack(null, true) } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                connection?.senders?.forEach { s ->
                    try { s.setTrack(null, true) } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            videoCapturer?.dispose()
            videoCapturer = null
            videoSource?.dispose()
            videoSource = null
            videoTrack?.dispose()
            videoTrack = null
            audioSource?.dispose()
            audioSource = null
            audioTrack?.dispose()
            audioTrack = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            cameraSwitchBusy = false
        }
    }

    fun sendRequest(request: BridgeRequest): Boolean {
        val channel = dataChannel ?: return false
        val payload = json.encodeToString(BridgeRequest.serializer(), request)
        return try {
            val byteBuffer = java.nio.ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8))
            channel.send(DataChannel.Buffer(byteBuffer, false))
        } catch (t: Throwable) {
            false
        }
    }

    fun sendResponse(response: BridgeResponse): Boolean {
        val channel = dataChannel ?: return false
        val payload = json.encodeToString(BridgeResponse.serializer(), response)
        return try {
            val byteBuffer = java.nio.ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8))
            channel.send(DataChannel.Buffer(byteBuffer, false))
        } catch (t: Throwable) {
            false
        }
    }

    fun sendBinaryChunk(
        messageId: Int,
        seq: Int,
        total: Int,
        chunk: ByteArray,
        offset: Int,
        length: Int
    ): Boolean {
        val channel = dataChannel ?: return false
        return try {
            channel.send(
                DataChannel.Buffer(
                    FrameProtocol.buildBinaryChunk(messageId, seq, total, chunk, offset, length),
                    false
                )
            )
        } catch (t: Throwable) {
            false
        }
    }

    fun close() {
        stopStreaming()
        try {
            dataChannel?.close()
            dataChannel?.dispose()
        } catch (_: Throwable) {
        }
        dataChannel = null
        try {
            connection?.close()
            connection?.dispose()
        } catch (_: Throwable) {
        }
        connection = null
        try {
            factory?.dispose()
        } catch (_: Throwable) {
        }
        factory = null
        updateState(PeerState.Closed)
    }

    private fun ensureConnection(): PeerConnection? {
        val currentConn = connection
        if (currentConn != null && currentConn.signalingState() != PeerConnection.SignalingState.CLOSED) {
            return currentConn
        }
        try {
            connection?.close()
            connection?.dispose()
        } catch (_: Throwable) {
        }
        connection = null
        val factory = this.factory ?: return null
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "WebRTC onIceCandidate: mid=${candidate?.sdpMid}, index=${candidate?.sdpMLineIndex}, sdp=${candidate?.sdp}")
                candidate?.let { _iceCandidates.tryEmit(it) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "WebRTC onIceCandidatesRemoved: count=${candidates?.size}")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "WebRTC onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "WebRTC onIceConnectionChange: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> updateState(PeerState.Connected)
                    PeerConnection.IceConnectionState.FAILED -> updateState(PeerState.Failed)
                    PeerConnection.IceConnectionState.CLOSED -> updateState(PeerState.Closed)
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "WebRTC onIceConnectionReceivingChange: $receiving")
            }
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "WebRTC onRenegotiationNeeded")
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "WebRTC onIceGatheringChange: $state")
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                channel?.let {
                    this@PeerConnectionManager.dataChannel = it
                    it.registerObserver(ChannelObserver())
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onIceCandidateError(error: IceCandidateErrorEvent?) {}
        })
        return connection
    }

    private fun createDataChannel() {
        if (dataChannel != null) return
        val connection = this.connection ?: return
        val init = DataChannel.Init().apply {
            ordered = true
        }
        dataChannel = connection.createDataChannel("bridgeData", init)
        dataChannel?.registerObserver(ChannelObserver())
    }

    private val _iceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 64)
    val iceCandidates: SharedFlow<IceCandidate> = _iceCandidates.asSharedFlow()

    private inner class ChannelObserver : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            if (dataChannel?.state() == DataChannel.State.OPEN) {
                updateState(PeerState.Connected)
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            val data = buffer?.data ?: return
            val frame = FrameProtocol.parse(data)
            if (frame != null) {
                when (frame.type) {
                    FrameProtocol.TYPE_JSON -> {
                        val bytes = ByteArray(frame.payload.remaining())
                        frame.payload.get(bytes)
                        parseJsonMessage(String(bytes, Charsets.UTF_8))
                    }
                    FrameProtocol.TYPE_BINARY -> {
                        val bytes = ByteArray(frame.payload.remaining())
                        frame.payload.get(bytes)
                        _incomingBinary.tryEmit(
                            BinaryChunk(frame.messageId, frame.seq, frame.total, bytes)
                        )
                    }
                }
            } else {
                try {
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    parseJsonMessage(text)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun parseJsonMessage(text: String) {
        try {
            val request = json.decodeFromString(BridgeRequest.serializer(), text)
            _incomingRequests.tryEmit(request)
        } catch (_: Throwable) {
            try {
                val response = json.decodeFromString(BridgeResponse.serializer(), text)
                _incomingResponses.tryEmit(response)
            } catch (_: Throwable) {}
        }
    }

    private fun updateState(newState: PeerState) {
        _state.value = newState
    }
}
