package com.mensageiro.core.webrtc

import java.util.UUID
import org.json.JSONObject

enum class CallAction { INVITE, ACCEPT, REJECT, CANCEL, END, BUSY, OFFER, ANSWER, ICE }

enum class CallState { IDLE, CALLING, RINGING, CONNECTING, ACTIVE, RECONNECTING }

data class CallControl(
    val callId: String,
    val action: CallAction,
    val payload: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object CallControlCodec {
    fun encode(control: CallControl): String = JSONObject()
        .put("version", 1)
        .put("callId", control.callId)
        .put("action", control.action.name)
        .put("payload", control.payload)
        .put("timestamp", control.timestamp)
        .toString()

    fun decode(text: String): CallControl {
        val value = JSONObject(text)
        require(value.getInt("version") == 1) { "Versao de chamada invalida." }
        val callId = value.getString("callId")
        UUID.fromString(callId)
        val timestamp = value.getLong("timestamp")
        require(timestamp > 0) { "Horario de chamada invalido." }
        val payload = value.optString("payload").takeIf { it.isNotBlank() && it != "null" }
        return CallControl(callId, CallAction.valueOf(value.getString("action")), payload, timestamp)
    }
}
