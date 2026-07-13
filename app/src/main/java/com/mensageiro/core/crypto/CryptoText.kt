package com.mensageiro.core.crypto

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

data class PublicIdentity(
    val protocolVersion: String,
    val peerId: String,
    val publicKey: String,
    val encryptionPublicKey: String,
    val displayName: String,
    val timestamp: Long,
    val signature: String
)

data class VerifiedContact(
    val peerId: String,
    val publicKey: String,
    val encryptionPublicKey: String,
    val displayName: String,
    val fingerprint: String,
    val sharePayload: String = ""
)

object CryptoText {
    const val ProtocolVersion = "mensageiro-identity-v2"

    fun peerId(publicKeyBytes: ByteArray): String =
        "peer-" + base64Url(sha256(publicKeyBytes)).take(32)

    fun fingerprint(firstPublicKey: String, secondPublicKey: String): String {
        val ordered = listOf(firstPublicKey, secondPublicKey).sorted().joinToString(":")
        return sha256(ordered.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .chunked(4)
            .take(6)
            .joinToString(" ")
    }

    fun encode(identity: PublicIdentity): String =
        canonical(identity) + "&signature=${url(identity.signature)}"

    fun decode(text: String): PublicIdentity {
        val values = text.split("&")
            .filter { it.contains("=") }
            .associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }

        return PublicIdentity(
            protocolVersion = values.require("v"),
            peerId = values.require("peerId"),
            publicKey = values.require("publicKey"),
            encryptionPublicKey = values.require("encryptionPublicKey"),
            displayName = values["displayName"].orEmpty(),
            timestamp = values.require("timestamp").toLong(),
            signature = values.require("signature")
        )
    }

    fun canonical(identity: PublicIdentity): String =
        listOf(
            "v=${url(identity.protocolVersion)}",
            "peerId=${url(identity.peerId)}",
            "publicKey=${url(identity.publicKey)}",
            "encryptionPublicKey=${url(identity.encryptionPublicKey)}",
            "displayName=${url(identity.displayName)}",
            "timestamp=${identity.timestamp}"
        ).joinToString("&")

    fun verifyContact(payload: String, localPublicKey: String): VerifiedContact {
        val identity = decode(payload)
        require(identity.protocolVersion == ProtocolVersion) { "Versao de protocolo invalida." }

        val publicKeyBytes = fromBase64Url(identity.publicKey)
        require(peerId(publicKeyBytes) == identity.peerId) { "Peer ID nao corresponde a chave publica." }
        SessionCrypto.publicKey(fromBase64Url(identity.encryptionPublicKey))

        val signature = Signature.getInstance("Ed25519")
        signature.initVerify(Ed25519Keys.publicKey(publicKeyBytes))
        signature.update(canonical(identity).toByteArray(StandardCharsets.UTF_8))
        require(signature.verify(fromBase64Url(identity.signature))) { "Assinatura invalida." }

        return VerifiedContact(
            peerId = identity.peerId,
            publicKey = identity.publicKey,
            encryptionPublicKey = identity.encryptionPublicKey,
            displayName = identity.displayName.ifBlank { identity.peerId.take(12) },
            fingerprint = fingerprint(localPublicKey, identity.publicKey),
            sharePayload = payload.trim()
        )
    }

    fun sign(
        publicKey: PublicKey,
        encryptionPublicKey: PublicKey,
        displayName: String,
        signer: (ByteArray) -> ByteArray
    ): PublicIdentity {
        val unsigned = PublicIdentity(
            protocolVersion = ProtocolVersion,
            peerId = peerId(publicKey.encoded),
            publicKey = base64Url(publicKey.encoded),
            encryptionPublicKey = base64Url(encryptionPublicKey.encoded),
            displayName = displayName.trim(),
            timestamp = System.currentTimeMillis(),
            signature = ""
        )
        val signature = signer(canonical(unsigned).toByteArray(StandardCharsets.UTF_8))
        return unsigned.copy(signature = base64Url(signature))
    }

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun fromBase64Url(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun url(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun Map<String, String>.require(key: String): String =
        get(key) ?: error("Campo ausente: $key")
}
