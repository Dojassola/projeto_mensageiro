package com.mensageiro.core.signaling

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SupabaseSignaling {
    fun publish(message: SignalingMessage) {
        request(
            "publish_signal",
            JSONObject()
                .put("p_session_id", message.sessionId)
                .put("p_sender_peer_id", message.senderPeerId)
                .put("p_receiver_peer_id", message.receiverPeerId)
                .put("p_type", message.type)
                .put("p_payload", SignalingCodec.encode(message))
        )
    }

    fun read(receiverPeerId: String): List<Pair<Long, String>> {
        val response = request(
            "read_signals",
            JSONObject().put("p_receiver_peer_id", receiverPeerId)
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { index ->
            rows.getJSONObject(index).let { it.getLong("id") to it.getString("payload") }
        }
    }

    private fun request(function: String, body: JSONObject): String {
        val connection = URL("${RestUrl}rpc/$function").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("apikey", PublishableKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Supabase $code: $response" }
            response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val RestUrl = "https://mxyqzxaaorrlanneyggu.supabase.co/rest/v1/"
        const val PublishableKey = "sb_publishable_Dncw2GKaKM2S9aluiyUuQA_RS_IDePi"
    }
}
