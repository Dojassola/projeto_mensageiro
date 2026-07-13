package com.mensageiro.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object Ed25519Keys {
    fun generate(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun publicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
}
