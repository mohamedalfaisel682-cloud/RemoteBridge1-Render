package com.remotebridge.core.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

enum class SignalingState { Disconnected, Connecting, Connected, Failed }

/**
 * عميل اتصال بخادم الإشارات عبر WebSocket.
 *
 * المسؤوليات:
 * 1. تسجيل الجهاز لدى الخادم بدوره (مضيف/تحكم).
 * 2. إعادة الاتصال تلقائياً مع تراجع أُسّي عند انقطاع الشبكة.
 * 3. استقبال رسائل الإشارات (Offer/Answer/ICE) وتمريرها للمحرك.
 * 4. إبقاء الاتصال حياً عبر رسائل Ping دورية (إضافة لـ Ping المدمج في OkHttp).
 */
class SignalingClient(
    private val url: String,
    private val deviceId: String,
    private val role: String,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val onMessage: (Signal) -> Unit,
    private val onState: (SignalingState) -> Unit,
    private val storedToken: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var running = false
    private var attempt = 0
    private var heartbeatJob: Job? = null
    private var connectJob: Job? = null
    private var currentToken: String? = storedToken

    private val _state = MutableStateFlow(SignalingState.Disconnected)
    val state: StateFlow<SignalingState> = _state.asStateFlow()

    /**
     * التوكن الحالي للمصادقة مع الخادم
     */
    val token: String? get() = currentToken

    /**
     * يبدأ الاتصال ويُبقيه حياً. آمن للاستدعاء أكثر من مرة.
     */
    fun connect() {
        if (running) return
        running = true
        attempt = 0
        doConnect()
    }

    /**
     * يوقف الاتصال نهائياً ولا يحاول إعادة الاتصال.
     */
    fun disconnect() {
        running = false
        connectJob?.cancel()
        heartbeatJob?.cancel()
        try {
            socket?.close(1000, "disconnect")
        } catch (_: Throwable) {
        }
        socket = null
        client = null
        updateState(SignalingState.Disconnected)
    }

    /**
     * يرسل رسالة إشارات إلى الخادم. يعيد false إذا كان الاتصال مغلقاً.
     */
    fun send(signal: Signal): Boolean {
        val socket = this.socket ?: return false
        return try {
            socket.send(json.encodeToString(Signal.serializer(), signal))
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * يرسل رسالة مع تضمين التوكن في Register إذا كان موجوداً.
     */
    fun sendRegister(deviceId: String, role: String, deviceName: String): Boolean {
        val socket = this.socket ?: return false
        return try {
            socket.send(json.encodeToString(Signal.serializer(), Signal.Register(
                deviceId = deviceId,
                role = role,
                deviceName = deviceName,
                token = currentToken
            )))
        } catch (_: Throwable) {
            false
        }
    }

    private fun doConnect() {
        if (!running) return
        updateState(SignalingState.Connecting)

        try {
            val sanitizedUrl = sanitizeWebSocketUrl(url)
            val httpClient = OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // اتصال دائم
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
            client = httpClient

            val request = Request.Builder()
                .url(sanitizedUrl)
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    socket = webSocket
                    // سجّل الجهاز فوراً بعد فتح الاتصال مع التوكن إذا كان موجوداً
                    send(Signal.Register(deviceId, role, deviceName, currentToken))
                    startHeartbeat()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    heartbeatJob?.cancel()
                    if (running) {
                        updateState(SignalingState.Failed)
                        scheduleReconnect()
                    } else {
                        updateState(SignalingState.Disconnected)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    heartbeatJob?.cancel()
                    if (running) {
                        updateState(SignalingState.Disconnected)
                        scheduleReconnect()
                    } else {
                        updateState(SignalingState.Disconnected)
                    }
                }
            }

            socket = httpClient.newWebSocket(request, listener)
        } catch (t: Throwable) {
            socket = null
            heartbeatJob?.cancel()
            if (running) {
                updateState(SignalingState.Failed)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        // B12 — إلغاء أي إعادة اتصال مجدولة سابقة (منع مهام مزدوجة)
        try { connectJob?.cancel() } catch (_: Throwable) {}
        // تراجع أُسّي: 2s, 4s, 8s, ... بحد أقصى 30s
        val delayMs = (2000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L)
        attempt++
        connectJob = scope.launch {
            delay(delayMs)
            doConnect()
        }
    }

    /**
     * B12 — إعادة اتصال فورية تستدعى مع عودة الشبكة (NetworkCallback.onAvailable)
     * لضمان بقاء الهاتف المضيف متصلاً بالخادم دائماً بعد إغلاق الواجهة.
     */
    fun forceReconnect() {
        if (!running || socket != null) return
        try { connectJob?.cancel() } catch (_: Throwable) {}
        attempt = 0
        scheduleReconnect()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (running) {
                delay(10_000L)
                send(Signal.Ping(System.currentTimeMillis()))
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val signal = json.decodeFromString(Signal.serializer(), text)
            
            // استخراج التوكن من رد التسجيل وتخزينه
            if (signal is Signal.Registered && signal.token != null) {
                currentToken = signal.token
                Log.d("SignalingClient", "تم تحديث توكن الجلسة: ${signal.token}")
            }
            
            onMessage(signal)
        } catch (_: Throwable) {
            // رسالة غير مفهومة → تجاهلها (الخادم قد يرسل حقولاً إضافية لاحقاً)
        }
    }

    /**
     * يرسل رسالة إشارات إلى الخادم مع تضمين التوكن تلقائياً.
     * يعيد false إذا كان الاتصال مغلقاً.
     */
    fun sendWithToken(signal: Signal): Boolean {
        val socket = this.socket ?: return false
        return try {
            // تضمين التوكن في الرسائل التي تدعمه
            val signalWithToken = when (signal) {
                is Signal.Register -> signal.copy(token = currentToken)
                is Signal.Offer -> signal.copy() // Offer لا يحتوي على token في الموديل الحالي، يتم إضافته عبر envelope
                is Signal.Answer -> signal.copy()
                is Signal.Ice -> signal.copy()
                is Signal.RpcRequest -> signal.copy()
                is Signal.RpcResponse -> signal.copy()
                else -> signal
            }
            socket.send(json.encodeToString(Signal.serializer(), signalWithToken))
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateState(newState: SignalingState) {
        _state.value = newState
        onState(newState)
    }

    companion object {
        fun sanitizeWebSocketUrl(rawUrl: String): String {
            var trimmed = rawUrl.trim()
            if (trimmed.isBlank()) {
                return "wss://remotebridge1.onrender.com/ws"
            }
            if (trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)) {
                return trimmed
            }
            if (trimmed.startsWith("http://", ignoreCase = true)) {
                return "ws://" + trimmed.substring(7)
            }
            if (trimmed.startsWith("https://", ignoreCase = true)) {
                return "wss://" + trimmed.substring(8)
            }
            return if (trimmed.contains("localhost") || trimmed.startsWith("10.") || trimmed.startsWith("192.168.") || trimmed.startsWith("127.")) {
                "ws://$trimmed"
            } else {
                "wss://$trimmed"
            }
        }
    }
}
