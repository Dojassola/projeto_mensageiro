package com.mensageiro.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.Signature

class CryptoTextTest {
    @Test
    fun publicIdentityRoundTripVerifiesContact() {
        val local = Ed25519Keys.generate()
        val remote = Ed25519Keys.generate()
        val identity = CryptoText.sign(remote.public, "Ana") { bytes ->
            val signature = Signature.getInstance("Ed25519")
            signature.initSign(remote.private)
            signature.update(bytes)
            signature.sign()
        }

        val contact = CryptoText.verifyContact(
            CryptoText.encode(identity),
            CryptoText.base64Url(local.public.encoded)
        )

        assertEquals(identity.peerId, contact.peerId)
        assertEquals("Ana", contact.displayName)
        assertNotEquals("", contact.fingerprint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedIdentityIsRejected() {
        val pair = Ed25519Keys.generate()
        val identity = CryptoText.sign(pair.public, "Ana") { bytes ->
            val signature = Signature.getInstance("Ed25519")
            signature.initSign(pair.private)
            signature.update(bytes)
            signature.sign()
        }

        CryptoText.verifyContact(
            CryptoText.encode(identity).replace("Ana", "Bia"),
            identity.publicKey
        )
    }
}
