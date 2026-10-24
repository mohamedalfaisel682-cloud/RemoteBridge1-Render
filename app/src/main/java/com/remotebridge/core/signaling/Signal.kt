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
