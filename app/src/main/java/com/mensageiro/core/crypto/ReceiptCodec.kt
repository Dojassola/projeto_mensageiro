package com.mensageiro.core.crypto

import java.nio.charset.StandardCharsets
import java.security.Signature

data class MessageReceipt(val messageId: String, val status: MessageStatus)
data class RemotePresence(val active: Boolean, val timestamp: Long)

object ReceiptCodec {
    private const val Version = "mensageiro-receipt-v1"

    fun create(
        identity: LocalIdentity,
        contact: VerifiedContact,
        messageId: String,
        status: MessageStatus,
        signer: (ByteArray) -> ByteArray
    ): String {
        require(status == MessageStatus.DELIVERED || status == MessageStatus.READ)
        val canonical = listOf(
            Version,
            messageId,
            identity.peerId,
            contact.peerId,
            status.name,
            System.currentTimeMillis().toString()
        ).joinToString(".")
        return canonical + "." + CryptoText.base64Url(signer(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    fun verify(wire: String, identity: LocalIdentity, contact: VerifiedContact): MessageReceipt {
        val fields = wire.trim().split('.', limit = 7)
        require(fields.size == 7 && fields[0] == Version) { "Formato de confirmacao invalido." }
        require(fields[2] == contact.peerId && fields[3] == identity.peerId) { "Confirmacao destinada a outro aparelho." }
        val status = MessageStatus.valueOf(fields[4])
        require(status == MessageStatus.DELIVERED || status == MessageStatus.READ) { "Status de confirmacao invalido." }
        require(fields[5].toLong() > 0) { "Timestamp de confirmacao invalido." }

        val canonical = fields.take(6).joinToString(".")
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(Ed25519Keys.publicKey(CryptoText.fromBase64Url(contact.publicKey)))
        verifier.update(canonical.toByteArray(StandardCharsets.UTF_8))
        require(verifier.verify(CryptoText.fromBase64Url(fields[6]))) { "Assinatura da confirmacao invalida." }
        return MessageReceipt(fields[1], status)
    }
}

object PresenceCodec {
    private const val Version = "mensageiro-presence-v1"

    fun create(
        identity: LocalIdentity,
        contact: VerifiedContact,
        active: Boolean,
        signer: (ByteArray) -> ByteArray
    ): String {
        val canonical = listOf(
            Version,
            identity.peerId,
            contact.peerId,
            active.toString(),
            System.currentTimeMillis().toString()
        ).joinToString(".")
        return canonical + "." + CryptoText.base64Url(signer(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    fun verify(wire: String, identity: LocalIdentity, contact: VerifiedContact): RemotePresence {
        val fields = wire.trim().split('.', limit = 6)
        require(fields.size == 6 && fields[0] == Version) { "Formato de presenca invalido." }
        require(fields[1] == contact.peerId && fields[2] == identity.peerId) { "Presenca destinada a outro aparelho." }
        val active = fields[3].toBooleanStrict()
        val timestamp = fields[4].toLong()
        require(timestamp > 0) { "Timestamp de presenca invalido." }

        val canonical = fields.take(5).joinToString(".")
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(Ed25519Keys.publicKey(CryptoText.fromBase64Url(contact.publicKey)))
        verifier.update(canonical.toByteArray(StandardCharsets.UTF_8))
        require(verifier.verify(CryptoText.fromBase64Url(fields[5]))) { "Assinatura da presenca invalida." }
        return RemotePresence(active, timestamp)
    }
}
