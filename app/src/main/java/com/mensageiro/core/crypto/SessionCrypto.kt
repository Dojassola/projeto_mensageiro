package com.mensageiro.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMessage(
    val nonce: String,
    val ciphertext: String
)

object SessionCrypto {
    fun ephemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun publicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(encoded))

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun sessionKey(privateKey: PrivateKey, remotePublicKey: PublicKey, context: String): ByteArray {
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(remotePublicKey, true)
        return hkdf(agreement.generateSecret(), context.toByteArray(), 32)
    }

    fun encrypt(sessionKey: ByteArray, plaintext: String): EncryptedMessage {
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        return EncryptedMessage(
            nonce = CryptoText.base64Url(nonce),
            ciphertext = CryptoText.base64Url(cipher.doFinal(plaintext.toByteArray()))
        )
    }

    fun decrypt(sessionKey: ByteArray, message: EncryptedMessage): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(128, CryptoText.fromBase64Url(message.nonce))
        )
        return cipher.doFinal(CryptoText.fromBase64Url(message.ciphertext)).decodeToString()
    }

    private fun hkdf(secret: ByteArray, info: ByteArray, size: Int): ByteArray {
        val salt = "mensageiro-session-v1".toByteArray()
        val prk = hmac(salt, secret)
        val okm = hmac(prk, info + 1.toByte())
        return okm.copyOf(size)
    }

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(bytes)
    }
}
