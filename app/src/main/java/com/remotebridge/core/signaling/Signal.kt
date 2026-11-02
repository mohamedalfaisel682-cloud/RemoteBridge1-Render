package com.remotebridge.core.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * رسائل تبادل معلومات الاتصال (SDP / ICE) بين الجهازين عبر خادم الإشارات.
 * خادم الإشارات يعيد توجيه هذه الرسائل فقط ولا يطّلع على محتواها.
 */
@Serializable
sealed class Signal {

    @Serializable
    @SerialName("register")
    data class Register(
        val deviceId: String,
        val role: String,
        val deviceName: String,
        val token: String? = null
    ) : Signal()

    @Serializable
    @SerialName("registered")
    data class Registered(
        val ok: Boolean,
        val error: String? = null,
        val token: String? = null
    ) : Signal()

    @Serializable
    @SerialName("hosts")
    data class Hosts(
        val hosts: List<com.remotebridge.core.model.HostInfo>
    ) : Signal()

    @Serializable
    @SerialName("host_online")
    data class HostOnline(
        val host: com.remotebridge.core.model.HostInfo
    ) : Signal()

    @Serializable
    @SerialName("host_offline")
    data class HostOffline(
        val deviceId: String
    ) : Signal()

    @Serializable
    @SerialName("offer")
    data class Offer(
        val from: String,
        val to: String,
        val sdp: String,
        val sdpType: String
    ) : Signal()

    @Serializable
    @SerialName("answer")
    data class Answer(
        val from: String,
        val to: String,
        val sdp: String,
        val sdpType: String
    ) : Signal()

    @Serializable
    @SerialName("ice")
    data class Ice(
        val from: String,
        val to: String,
        val sdp: String,
        val sdpMid: String,
        val sdpMLineIndex: Int
    ) : Signal()

    @Serializable
    @SerialName("reject")
    data class Reject(
        val from: String,
        val to: String,
        val reason: String
    ) : Signal()

    @Serializable
    @SerialName("rpc_request")
    data class RpcRequest(
        val from: String,
        val to: String,
        val requestId: Int,
        val cmd: String,
        val params: kotlinx.serialization.json.JsonObject? = null
    ) : Signal()

    @Serializable
    @SerialName("rpc_response")
    data class RpcResponse(
        val from: String,
        val to: String,
        val requestId: Int,
        val cmd: String? = null,
        val ok: Boolean,
        val error: String? = null,
        val data: kotlinx.serialization.json.JsonObject? = null
    ) : Signal()

    /**
     * حدث حي يدفعه المضيف للمتحكم فور وصول إشعار/رسالة/مكالمة على الهاتف.
     * [kind] أحد: notification | sms | call
     */
    @Serializable
    @SerialName("push_event")
    data class PushEvent(
        val from: String,
        val to: String,
        val kind: String,
        val payload: kotlinx.serialization.json.JsonObject? = null
    ) : Signal()

    /**
     * B26 — مزامنة الإعدادات بين المضيف والمتحكم (واجهة الويب أو التطبيق)،
     * منقولة حالياً من واجهة الإنتاج. إلى = null يعني بثاً عاماً أو طلب سحب،
     * وطابع `ts` = 0 يعني طلب سحب لقطة كاملة (pull)، والتعديل الأحدث يفوز.
     */
    @Serializable
    @SerialName("settings_sync")
    data class SettingsSync(
        val from: String,
        val to: String? = null,
        val ts: Long = 0,
        val settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
    ) : Signal()

    /**
     * B26 — إطار بث حي بوسيلة الترحيل (MJPEG-over-Relay): صورة JPEG مضغوطة
     * مرمّزة Base64 تُرحَّل عبر خادم الإشارات من المضيف إلى جهاز العرض
     * (متحكم / لوحة ويب) — تعمل على أي شبكة دون أي شرط ICE/TURN.
     */
    @Serializable
    @SerialName("live_frame")
    data class LiveFrame(
        val from: String,
        val to: String,
        val stream: String, // camera | screen
        val seq: Long,
        val w: Int = 0,
        val h: Int = 0,
        val ts: Long = 0,
        val b64: String
    ) : Signal()

    @Serializable
    @SerialName("ping")
    data class Ping(
        val t: Long
    ) : Signal()

    @Serializable
    @SerialName("pong")
    data class Pong(
        val t: Long
    ) : Signal()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String
    ) : Signal()
}
