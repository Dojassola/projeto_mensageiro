package com.mensageiro.core.signaling

import com.mensageiro.core.crypto.CryptoText
import com.mensageiro.core.crypto.Ed25519Keys
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.Signature

class SignalingCodecTest {
    @Test
    fun signedSignalRoundTripVerifiesSenderAndReceiver() {
        val sender = Ed25519Keys.generate()
        val receiver = Ed25519Keys.generate()
        val receiverPeerId = CryptoText.peerId(receiver.public.encoded)

        val message = SignalingCodec.create(
            sessionId = SignalingCodec.sessionId(),
            senderPeerId = CryptoText.peerId(sender.public.encoded),
            receiverPeerId = receiverPeerId,
            type = SignalingCodec.Offer,
            payload = "fake-sdp"
        ) { bytes -> sign(sender, bytes) }

        val verified = SignalingCodec.verify(
            SignalingCodec.encode(message),
            CryptoText.base64Url(sender.public.encoded),
            receiverPeerId
        )

        assertEquals("fake-sdp", SignalingCodec.payloadText(verified))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedSignalIsRejected() {
        val sender = Ed25519Keys.generate()
        val receiver = Ed25519Keys.generate()
        val receiverPeerId = CryptoText.peerId(receiver.public.encoded)

        val message = SignalingCodec.create(
            sessionId = SignalingCodec.sessionId(),
            senderPeerId = CryptoText.peerId(sender.public.encoded),
            receiverPeerId = receiverPeerId,
            type = SignalingCodec.Offer,
            payload = "fake-sdp"
        ) { bytes -> sign(sender, bytes) }

        SignalingCodec.verify(
            SignalingCodec.encode(message).replace("OFFER", "ANSWER"),
            CryptoText.base64Url(sender.public.encoded),
            receiverPeerId
        )
    }

    private fun sign(pair: java.security.KeyPair, bytes: ByteArray): ByteArray {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(pair.private)
        signature.update(bytes)
        return signature.sign()
    }
}
