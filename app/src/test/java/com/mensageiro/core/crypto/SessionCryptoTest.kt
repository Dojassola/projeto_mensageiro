package com.mensageiro.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCryptoTest {
    @Test
    fun peersDeriveSameSessionKeyAndDecryptMessage() {
        val alice = SessionCrypto.ephemeralKeyPair()
        val bob = SessionCrypto.ephemeralKeyPair()
        val context = "alice:bob"

        val aliceKey = SessionCrypto.sessionKey(alice.private, bob.public, context)
        val bobKey = SessionCrypto.sessionKey(bob.private, alice.public, context)
        val encrypted = SessionCrypto.encrypt(aliceKey, "oi")

        assertArrayEquals(aliceKey, bobKey)
        assertEquals("oi", SessionCrypto.decrypt(bobKey, encrypted))
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun wrongSessionKeyCannotDecryptMessage() {
        val alice = SessionCrypto.ephemeralKeyPair()
        val bob = SessionCrypto.ephemeralKeyPair()
        val mallory = SessionCrypto.ephemeralKeyPair()
        val encrypted = SessionCrypto.encrypt(
            SessionCrypto.sessionKey(alice.private, bob.public, "alice:bob"),
            "segredo"
        )

        SessionCrypto.decrypt(
            SessionCrypto.sessionKey(mallory.private, bob.public, "alice:bob"),
            encrypted
        )
    }
}
