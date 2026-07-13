package com.mensageiro.core.crypto

import java.nio.charset.StandardCharsets
import java.security.Signature
import java.util.UUID

data class ReceivedMessage(val id: String, val text: String, val timestamp: Long, val replyToId: String? = null)

object MessageCodec {
    private const val Version = "mensageiro-message-v1"

    fun create(
        identity: LocalIdentity,
        contact: VerifiedContact,
        text: String,
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        signer: (ByteArray) -> ByteArray,
        maxLength: Int = 4_000
    ): String {
        val body = text.trim()
        require(body.isNotEmpty()) { "A mensagem esta vazia." }
        require(body.length <= maxLength) { "A mensagem excede o limite permitido." }

        val ephemeral = SessionCrypto.ephemeralKeyPair()
        val key = SessionCrypto.sessionKey(
            ephemeral.private,
            SessionCrypto.publicKey(CryptoText.fromBase64Url(contact.encryptionPublicKey)),
            context(id, identity.peerId, contact.peerId)
        )
        val encrypted = SessionCrypto.encrypt(key, body)
        val fields = listOf(
            Version,
            id,
            identity.peerId,
            contact.peerId,
            CryptoText.base64Url(ephemeral.public.encoded),
            timestamp.toString(),
            encrypted.nonce,
            encrypted.ciphertext
        )
        val canonical = fields.joinToString(".")
        return canonical + "." + CryptoText.base64Url(signer(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    fun open(
        wire: String,
        identity: LocalIdentity,
        contact: VerifiedContact,
        keyDeriver: (String, String) -> ByteArray
    ): ReceivedMessage {
        val value = wire.trim()
        require(value.length <= 64_000) { "Pacote de mensagem muito grande." }
        val fields = value.split(".", limit = 9)
        require(fields.size == 9 && fields[0] == Version) { "Formato de mensagem invalido." }
        require(fields[2] == contact.peerId) { "A mensagem nao veio do contato selecionado." }
        require(fields[3] == identity.peerId) { "A mensagem foi destinada a outro aparelho." }

        val canonical = fields.take(8).joinToString(".")
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(Ed25519Keys.publicKey(CryptoText.fromBase64Url(contact.publicKey)))
        verifier.update(canonical.toByteArray(StandardCharsets.UTF_8))
        require(verifier.verify(CryptoText.fromBase64Url(fields[8]))) { "Assinatura da mensagem invalida." }

        val key = keyDeriver(fields[4], context(fields[1], fields[2], fields[3]))
        val timestamp = fields[5].toLong()
        require(timestamp > 0) { "Timestamp invalido." }
        return ReceivedMessage(fields[1], SessionCrypto.decrypt(key, EncryptedMessage(fields[6], fields[7])), timestamp)
    }

    fun id(wire: String): String = wire.substringAfter('.').substringBefore('.')

    fun timestamp(wire: String): Long = wire.split('.', limit = 7)[5].toLong()

    private fun context(id: String, sender: String, recipient: String) = "$id|$sender|$recipient"
}
