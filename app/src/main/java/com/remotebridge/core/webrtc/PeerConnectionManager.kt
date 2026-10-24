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
        val conn = connection
        if (conn != null && conn.signalingState() != PeerConnection.SignalingState.STABLE && conn.signalingState() != PeerConnection.SignalingState.CLOSED) {
            Log.w(TAG, "إعادة تهيئة PeerConnection لقبول عرض جديد (حالة الإشارات السابقة: ${conn.signalingState()})")
            try { conn.close(); conn.dispose() } catch (_: Throwable) {}
            connection = null
        }
        val connection = ensureConnection() ?: run {
            onFailure("فشل تهيئة الاتصال")
            return
        }
        // 🔧 إعادة إرفاق مسارات البث الحية إن وجدت — من دونها تصل الإجابة بلا مسارات
        // (تظهر نافذة البث سوداء/فارغة على المتحكم رغم أن التقاط الشاشة يعمل)
        reattachExistingTracks(connection)
        val type = if (sdpType.equals("OFFER", ignoreCase = true))
            SessionDescription.Type.OFFER else SessionDescription.Type.OFFER

        connection.setRemoteDescription(
            SimpleSdpObserver {
                drainPendingIceCandidates()
                connection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            onFailure("إجابة فارغة")
                            return
                        }
                        connection.setLocalDescription(SimpleSdpObserver {
                            onSuccess(sdp.description)
                        }, sdp)
                    }

                    override fun onCreateFailure(error: String?) {
                        onFailure(error ?: "فشل إنشاء الإجابة")
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            },
            SessionDescription(type, sdp)
        )
    }

    /**
     * 🔧 عند إعادة بناء PeerConnection أثناء تفاوض جديد (حالة الإشارات أو "التعارض الصاخب")
     * تُفقد مسارات البث الحية؛ هذه الدالة تربطها ثانية بالإجابة الجديدة فتستمر الصورة.
     */
    private fun reattachExistingTracks(pc: PeerConnection) {
        try {
            var attached = 0
            val vt = videoTrack
            if (vt != null) {
                val videoTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                }
                if (videoTr != null) videoTr.sender.setTrack(vt, true)
                else pc.addTrack(vt, listOf("screenStream"))
                attached++
            }
            val at = audioTrack
            if (at != null) {
                val audioTr = pc.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                }
                if (audioTr != null) audioTr.sender.setTrack(at, true)
                else pc.addTrack(at, listOf("screenStream"))
                attached++
            }
            if (attached > 0) Log.d(TAG, "🔁 أُعيد إرفاق ${attached} مسار (بث) بعد إعادة بناء الاتصال")
        } catch (t: Throwable) {
            Log.w(TAG, "فشل إعادة إرفاق المسارات: ${t.message}")
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
        val connection = this.connection ?: return false
        if (videoTrack != null) return true

        try {
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

            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource!!)
            connection.addTrack(videoTrack, listOf("screenStream"))

            // صوت الشاشة: يُنشأ الميكروفون مساراً مكتوماً افتراضياً (السياسة الجديدة)
            // ليستطيع المتحكم تفعيله لاحقاً بنقرة على مكبر الصوت خلال بث الشاشة.
            try {
                val audioConstraints = MediaConstraints()
                val audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
                isAudioStreamingEnabled = false
                audioTrack?.setEnabled(false)
                connection.addTrack(audioTrack, listOf("screenStream"))
            } catch (t: Throwable) {
                Log.w(TAG, "تعذّر تهيئة مسار الصوت مع بث الشاشة (سيبقى البث صامتاً): ${t.message}")
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
        val connection = this.connection ?: return false
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
            capturer.startCapture(1280, 720, 30)

            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource!!)
            val videoTransceiver = connection.transceivers.firstOrNull {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            }
            if (videoTransceiver != null) {
                videoTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                videoTransceiver.sender.setTrack(videoTrack, true)
            } else {
                connection.addTrack(videoTrack, listOf("mediaStream"))
            }

            if (withAudio) {
                val audioConstraints = MediaConstraints()
                val audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
                isAudioStreamingEnabled = true
                val audioTransceiver = connection.transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                }
                if (audioTransceiver != null) {
                    audioTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                    audioTransceiver.sender.setTrack(audioTrack, true)
                } else {
                    connection.addTrack(audioTrack, listOf("mediaStream"))
                }
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
        val connection = this.connection ?: return false
        stopStreaming()

        try {
            val audioConstraints = MediaConstraints()
            val audioSource = factory.createAudioSource(audioConstraints)
            audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
            isAudioStreamingEnabled = true
            val audioTransceiver = connection.transceivers.firstOrNull {
                it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            }
            if (audioTransceiver != null) {
                audioTransceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                audioTransceiver.sender.setTrack(audioTrack, true)
            } else {
                connection.addTrack(audioTrack, listOf("mediaStream"))
            }

            Log.i(TAG, "تم بدء بث الصوت فقط من الميكروفون بنجاح")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بدء بث الصوت فقط", t)
            return false
        }
    }

    /**
     * التبديل السريع بين الكاميرا الأمامية والخلفية أثناء البث الحي.
     */
    fun switchCamera(onComplete: ((Boolean) -> Unit)? = null) {
        val capturer = videoCapturer
        if (capturer is CameraVideoCapturer) {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    isFrontFacingCamera = isFront
                    Log.i(TAG, "تم تبديل الكاميرا بنجاح. أمامية: $isFront")
                    onComplete?.invoke(isFront)
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.e(TAG, "فشل تبديل الكاميرا: $errorDescription")
                    onComplete?.invoke(isFrontFacingCamera)
                }
            })
        }
    }

    /**
     * التحكم بتشغيل أو كتم الميكروفون والصوت أثناء البث.
     */
    fun setAudioEnabled(enabled: Boolean) {
        isAudioStreamingEnabled = enabled
        audioTrack?.setEnabled(enabled)
        Log.i(TAG, "حالة الميكروفون الآن: $enabled")
    }

    fun isCameraStreaming(): Boolean = videoTrack != null
    fun isUsingFrontCamera(): Boolean = isFrontFacingCamera
    fun isAudioTrackEnabled(): Boolean = isAudioStreamingEnabled

    fun stopStreaming() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Throwable) {
        }
        videoCapturer?.dispose()
        videoCapturer = null
        videoSource?.dispose()
        videoSource = null
        videoTrack?.dispose()
        videoTrack = null
        audioTrack?.dispose()
        audioTrack = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
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
