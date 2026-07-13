package com.mensageiro.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalIdentity(
    val peerId: String,
    val publicKey: String,
    val encryptionPublicKey: String,
    val displayName: String,
    val publicPayload: String
)

data class IdentityBackup(
    val peerId: String,
    val publicKey: String,
    val privateKey: String,
    val encryptionPublicKey: String,
    val encryptionPrivateKey: String,
    val displayName: String
)

class IdentityStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    fun getOrCreate(displayName: String = ""): LocalIdentity {
        val publicKey = prefs.getString("publicKey", null)
        val peerId = prefs.getString("peerId", null)
        val payload = prefs.getString("publicPayload", null)
        val encryptionPublicKey = prefs.getString("encryptionPublicKey", null)
        if (publicKey != null && peerId != null && payload != null && encryptionPublicKey != null) {
            val savedName = runCatching { CryptoText.decode(payload).displayName }.getOrDefault("")
            return LocalIdentity(peerId, publicKey, encryptionPublicKey, savedName, payload)
        }

        val pair = if (publicKey == null) Ed25519Keys.generate() else null
        val identityPublicKey = pair?.public ?: Ed25519Keys.publicKey(CryptoText.fromBase64Url(publicKey!!))
        val encryptionPair = SessionCrypto.ephemeralKeyPair()
        val signer: (ByteArray) -> ByteArray = pair?.private?.let { privateKey ->
            { bytes -> sign(privateKey, bytes) }
        } ?: ::sign
        val signed = CryptoText.sign(identityPublicKey, encryptionPair.public, displayName, signer)
        val identity = LocalIdentity(
            peerId = signed.peerId,
            publicKey = signed.publicKey,
            encryptionPublicKey = signed.encryptionPublicKey,
            displayName = signed.displayName,
            publicPayload = CryptoText.encode(signed)
        )

        val encryptedEncryptionKey = encrypt(encryptionPair.private.encoded)
        val editor = prefs.edit()
            .putString("peerId", identity.peerId)
            .putString("publicKey", identity.publicKey)
            .putString("encryptionPublicKey", identity.encryptionPublicKey)
            .putString("publicPayload", identity.publicPayload)
            .putString("encryptionPrivateKey", CryptoText.base64Url(encryptedEncryptionKey.ciphertext))
            .putString("encryptionPrivateKeyIv", CryptoText.base64Url(encryptedEncryptionKey.iv))

        pair?.private?.encoded?.let {
            val encryptedPrivateKey = encrypt(it)
            editor.putString("privateKey", CryptoText.base64Url(encryptedPrivateKey.ciphertext))
                .putString("privateKeyIv", CryptoText.base64Url(encryptedPrivateKey.iv))
        }
        editor
            .apply()

        return identity
    }

    fun sign(bytes: ByteArray): ByteArray =
        sign(decryptPrivateKey(), bytes)

    fun messageKey(remotePublicKey: String, context: String): ByteArray =
        SessionCrypto.sessionKey(
            decryptEncryptionPrivateKey(),
            SessionCrypto.publicKey(CryptoText.fromBase64Url(remotePublicKey)),
            context
        )

    fun rename(displayName: String): LocalIdentity {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Informe seu nome." }
        require(name.length <= 40) { "O nome excede 40 caracteres." }
        val identity = getOrCreate()
        val signed = CryptoText.sign(
            Ed25519Keys.publicKey(CryptoText.fromBase64Url(identity.publicKey)),
            SessionCrypto.publicKey(CryptoText.fromBase64Url(identity.encryptionPublicKey)),
            name,
            ::sign
        )
        return identity.copy(displayName = name, publicPayload = CryptoText.encode(signed)).also {
            prefs.edit().putString("publicPayload", it.publicPayload).apply()
            AutomaticBackup.request(context)
        }
    }

    fun exportBackup(): IdentityBackup {
        val identity = getOrCreate()
        return IdentityBackup(
            identity.peerId,
            identity.publicKey,
            CryptoText.base64Url(decryptPrivateKey().encoded),
            identity.encryptionPublicKey,
            CryptoText.base64Url(decryptEncryptionPrivateKey().encoded),
            identity.displayName
        )
    }

    fun validateBackup(backup: IdentityBackup): LocalIdentity {
        require(backup.displayName.length <= 40) { "Nome do perfil invalido." }
        val publicKey = Ed25519Keys.publicKey(CryptoText.fromBase64Url(backup.publicKey))
        val privateKey = Ed25519Keys.privateKey(CryptoText.fromBase64Url(backup.privateKey))
        require(CryptoText.peerId(publicKey.encoded) == backup.peerId) { "Peer ID do backup invalido." }

        val proof = "mensageiro-backup-identity".toByteArray()
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(proof)
        require(verifier.verify(sign(privateKey, proof))) { "Chave de assinatura do backup invalida." }

        val encryptionPublicKey = SessionCrypto.publicKey(CryptoText.fromBase64Url(backup.encryptionPublicKey))
        val encryptionPrivateKey = SessionCrypto.privateKey(CryptoText.fromBase64Url(backup.encryptionPrivateKey))
        val temporary = SessionCrypto.ephemeralKeyPair()
        val context = "mensageiro-backup-key-check"
        val first = SessionCrypto.sessionKey(encryptionPrivateKey, temporary.public, context)
        val second = SessionCrypto.sessionKey(temporary.private, encryptionPublicKey, context)
        require(first.contentEquals(second)) { "Chave de criptografia do backup invalida." }

        val signed = CryptoText.sign(publicKey, encryptionPublicKey, backup.displayName) { sign(privateKey, it) }
        return LocalIdentity(
            signed.peerId,
            signed.publicKey,
            signed.encryptionPublicKey,
            signed.displayName,
            CryptoText.encode(signed)
        )
    }

    fun restoreBackup(backup: IdentityBackup): LocalIdentity {
        val identity = validateBackup(backup)
        val privateKey = encrypt(CryptoText.fromBase64Url(backup.privateKey))
        val encryptionPrivateKey = encrypt(CryptoText.fromBase64Url(backup.encryptionPrivateKey))
        prefs.edit().clear()
            .putString("peerId", identity.peerId)
            .putString("publicKey", identity.publicKey)
            .putString("privateKey", CryptoText.base64Url(privateKey.ciphertext))
            .putString("privateKeyIv", CryptoText.base64Url(privateKey.iv))
            .putString("encryptionPublicKey", identity.encryptionPublicKey)
            .putString("encryptionPrivateKey", CryptoText.base64Url(encryptionPrivateKey.ciphertext))
            .putString("encryptionPrivateKeyIv", CryptoText.base64Url(encryptionPrivateKey.iv))
            .putString("publicPayload", identity.publicPayload)
            .apply()
        AutomaticBackup.request(context)
        return identity
    }

    fun protect(text: String): String = encrypt(text.toByteArray()).let {
        "${CryptoText.base64Url(it.iv)}.${CryptoText.base64Url(it.ciphertext)}"
    }

    fun unprotect(text: String): String {
        val parts = text.split('.', limit = 2)
        require(parts.size == 2) { "Registro local invalido." }
        return decrypt(CryptoText.fromBase64Url(parts[1]), CryptoText.fromBase64Url(parts[0])).decodeToString()
    }

    private fun sign(privateKey: PrivateKey, bytes: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(bytes)
        return signature.sign()
    }

    private fun decryptPrivateKey() =
        Ed25519Keys.privateKey(
            decrypt(
                CryptoText.fromBase64Url(prefs.getString("privateKey", "")!!),
                CryptoText.fromBase64Url(prefs.getString("privateKeyIv", "")!!)
            )
        )

    private fun decryptEncryptionPrivateKey() =
        SessionCrypto.privateKey(
            decrypt(
                CryptoText.fromBase64Url(prefs.getString("encryptionPrivateKey", "")!!),
                CryptoText.fromBase64Url(prefs.getString("encryptionPrivateKeyIv", "")!!)
            )
        )

    private fun encrypt(bytes: ByteArray): EncryptedBytes {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedBytes(cipher.doFinal(bytes), cipher.iv)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KeyAlias, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private data class EncryptedBytes(val ciphertext: ByteArray, val iv: ByteArray)

    private companion object {
        const val KeyAlias = "mensageiro.identity.v1"
    }
}
