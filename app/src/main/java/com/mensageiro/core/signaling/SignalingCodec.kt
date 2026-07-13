package com.mensageiro.core.signaling

import com.mensageiro.core.crypto.CryptoText
import com.mensageiro.core.crypto.Ed25519Keys
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.Signature

data class SignalingMessage(
    val sessionId: String,
    val senderPeerId: String,
    val receiverPeerId: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String
)

object SignalingCodec {
    const val Offer = "OFFER"
    const val Answer = "ANSWER"
    const val IceCandidate = "ICE"

    fun sessionId(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return CryptoText.base64Url(bytes)
    }

    fun create(
        sessionId: String,
        senderPeerId: String,
        receiverPeerId: String,
        type: String,
        payload: String,
        signer: (ByteArray) -> ByteArray
    ): SignalingMessage {
        val unsigned = SignalingMessage(
            sessionId = sessionId,
            senderPeerId = senderPeerId,
            receiverPeerId = receiverPeerId,
            type = type,
            payload = CryptoText.base64Url(payload.toByteArray(StandardCharsets.UTF_8)),
            timestamp = System.currentTimeMillis(),
            nonce = sessionId(),
            signature = ""
        )
        return unsigned.copy(signature = CryptoText.base64Url(signer(canonical(unsigned).toByteArray(StandardCharsets.UTF_8))))
    }

    fun encode(message: SignalingMessage): String =
        canonical(message) + "&signature=${url(message.signature)}"

    fun decode(text: String): SignalingMessage {
        val values = text.split("&")
            .filter { it.contains("=") }
            .associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }

        return SignalingMessage(
            sessionId = values.require("sessionId"),
            senderPeerId = values.require("senderPeerId"),
            receiverPeerId = values.require("receiverPeerId"),
            type = values.require("type"),
            payload = values.require("payload"),
            timestamp = values.require("timestamp").toLong(),
            nonce = values.require("nonce"),
            signature = values.require("signature")
        )
    }

    fun verify(text: String, expectedSenderPublicKey: String, expectedReceiverPeerId: String): SignalingMessage {
        val message = decode(text)
        require(message.receiverPeerId == expectedReceiverPeerId) { "Sinalizacao destinada a outro peer." }
        require(CryptoText.peerId(CryptoText.fromBase64Url(expectedSenderPublicKey)) == message.senderPeerId) {
            "Remetente nao corresponde a chave conhecida."
        }

        val signature = Signature.getInstance("Ed25519")
        signature.initVerify(Ed25519Keys.publicKey(CryptoText.fromBase64Url(expectedSenderPublicKey)))
        signature.update(canonical(message).toByteArray(StandardCharsets.UTF_8))
        require(signature.verify(CryptoText.fromBase64Url(message.signature))) { "Assinatura de sinalizacao invalida." }
        return message
    }

    fun payloadText(message: SignalingMessage): String =
        CryptoText.fromBase64Url(message.payload).toString(StandardCharsets.UTF_8)

    fun canonical(message: SignalingMessage): String =
        listOf(
            "sessionId=${url(message.sessionId)}",
            "senderPeerId=${url(message.senderPeerId)}",
            "receiverPeerId=${url(message.receiverPeerId)}",
            "type=${url(message.type)}",
            "payload=${url(message.payload)}",
            "timestamp=${message.timestamp}",
            "nonce=${url(message.nonce)}"
        ).joinToString("&")

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun Map<String, String>.require(key: String): String =
        get(key) ?: error("Campo ausente: $key")
}
