package com.mensageiro.core.crypto

import org.json.JSONObject

data class ChatPayload(val text: String, val replyToId: String?)

object ChatPayloadCodec {
    private const val Prefix = "mensageiro-chat-v1\n"

    fun encode(text: String, replyToId: String?): String {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 4_000)
        if (replyToId == null) return body
        require(replyToId.isNotBlank() && replyToId.length <= 200)
        return Prefix + JSONObject()
            .put("version", 1)
            .put("text", body)
            .put("replyToId", replyToId)
            .toString()
    }

    fun decode(value: String): ChatPayload {
        if (!value.startsWith(Prefix)) return ChatPayload(value, null)
        val data = JSONObject(value.substring(Prefix.length))
        require(data.getInt("version") == 1)
        val text = data.getString("text")
        val replyToId = data.getString("replyToId")
        require(text.isNotEmpty() && text.length <= 4_000)
        require(replyToId.isNotBlank() && replyToId.length <= 200)
        return ChatPayload(text, replyToId)
    }
}

enum class MessageActionType { EDIT, DELETE }

data class MessageAction(
    val type: MessageActionType,
    val targetId: String,
    val text: String,
    val timestamp: Long
)

object MessageActionCodec {
    fun encode(action: MessageAction): String = JSONObject()
        .put("version", 1)
        .put("type", action.type.name)
        .put("targetId", action.targetId)
        .put("text", action.text)
        .put("timestamp", action.timestamp)
        .toString()

    fun decode(value: String): MessageAction {
        val data = JSONObject(value)
        require(data.getInt("version") == 1)
        val action = MessageAction(
            MessageActionType.valueOf(data.getString("type")),
            data.getString("targetId"),
            data.optString("text"),
            data.getLong("timestamp")
        )
        require(action.targetId.isNotBlank() && action.targetId.length <= 200 && action.timestamp > 0)
        if (action.type == MessageActionType.EDIT) {
            require(action.text.isNotEmpty() && action.text.length <= 4_000)
        }
        return action
    }
}
