package com.mensageiro.core.crypto

import android.content.Context

class ContactStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    fun all(): List<VerifiedContact> =
        prefs.all.values.mapNotNull { it as? String }.mapNotNull(::decode)

    fun importContact(payload: String, localPublicKey: String): VerifiedContact {
        val contact = CryptoText.verifyContact(payload.trim(), localPublicKey)
        prefs.edit()
            .putString(contact.peerId, encode(contact))
            .apply()
        AutomaticBackup.request(context)
        return contact
    }

    fun rename(peerId: String, displayName: String): VerifiedContact {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Informe o nome do contato." }
        require(name.length <= 40) { "O nome excede 40 caracteres." }
        val contact = requireNotNull(all().firstOrNull { it.peerId == peerId }) { "Contato nao encontrado." }
            .copy(displayName = name)
        prefs.edit().putString(peerId, encode(contact)).apply()
        AutomaticBackup.request(context)
        return contact
    }

    fun updateSharePayload(peerId: String, payload: String, localPublicKey: String): VerifiedContact {
        val shared = CryptoText.verifyContact(payload.trim(), localPublicKey)
        val contact = requireNotNull(all().firstOrNull { it.peerId == peerId }) { "Contato nao encontrado." }
        require(
            shared.peerId == contact.peerId && shared.publicKey == contact.publicKey &&
                shared.encryptionPublicKey == contact.encryptionPublicKey
        ) { "Identidade compartilhada nao corresponde ao contato." }
        if (contact.sharePayload == shared.sharePayload) return contact
        return contact.copy(sharePayload = shared.sharePayload).also {
            prefs.edit().putString(peerId, encode(it)).apply()
            AutomaticBackup.request(context)
        }
    }

    fun validateForRestore(contacts: List<VerifiedContact>, localPublicKey: String): List<VerifiedContact> {
        require(contacts.map { it.peerId }.distinct().size == contacts.size) { "Contatos duplicados no backup." }
        return contacts.map { contact ->
            require(contact.displayName.isNotBlank() && contact.displayName.length <= 40) { "Nome de contato invalido." }
            val publicBytes = CryptoText.fromBase64Url(contact.publicKey)
            Ed25519Keys.publicKey(publicBytes)
            require(CryptoText.peerId(publicBytes) == contact.peerId) { "Peer ID de contato invalido." }
            SessionCrypto.publicKey(CryptoText.fromBase64Url(contact.encryptionPublicKey))
            if (contact.sharePayload.isNotBlank()) {
                val shared = CryptoText.verifyContact(contact.sharePayload, localPublicKey)
                require(
                    shared.peerId == contact.peerId && shared.publicKey == contact.publicKey &&
                        shared.encryptionPublicKey == contact.encryptionPublicKey
                ) { "Identidade compartilhada de contato invalida." }
            }
            contact.copy(fingerprint = CryptoText.fingerprint(localPublicKey, contact.publicKey))
        }
    }

    fun replaceAll(contacts: List<VerifiedContact>, localPublicKey: String) {
        val valid = validateForRestore(contacts, localPublicKey)
        val editor = prefs.edit().clear()
        valid.forEach { editor.putString(it.peerId, encode(it)) }
        editor.apply()
        AutomaticBackup.request(context)
    }

    private fun encode(contact: VerifiedContact): String =
        listOf(
            contact.peerId,
            contact.publicKey,
            contact.encryptionPublicKey,
            contact.displayName,
            contact.fingerprint,
            contact.sharePayload
        )
            .joinToString("\n")

    private fun decode(text: String): VerifiedContact? {
        val parts = text.split("\n", limit = 6)
        if (parts.size < 5) return null
        return VerifiedContact(parts[0], parts[1], parts[2], parts[3], parts[4], parts.getOrElse(5) { "" })
    }
}
