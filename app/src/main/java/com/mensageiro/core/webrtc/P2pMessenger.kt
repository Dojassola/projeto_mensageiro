package com.mensageiro.core.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.ChatPayloadCodec
import com.mensageiro.core.crypto.CryptoText
import com.mensageiro.core.crypto.EncryptedFileChunk
import com.mensageiro.core.crypto.FileChunkCodec
import com.mensageiro.core.crypto.LocalIdentity
import com.mensageiro.core.crypto.MessageCodec
import com.mensageiro.core.crypto.MessageAction
import com.mensageiro.core.crypto.MessageActionCodec
import com.mensageiro.core.crypto.MessageActionType
import com.mensageiro.core.crypto.MessageStatus
import com.mensageiro.core.crypto.ReceivedMessage
import com.mensageiro.core.crypto.ReceiptCodec
import com.mensageiro.core.crypto.PresenceCodec
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.StoredMessage
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.core.signaling.SignalingCodec
import com.mensageiro.core.signaling.SignalingHub
import com.mensageiro.core.signaling.SignalingMessage
import com.mensageiro.core.signaling.SupabaseSignaling
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.RandomAccessFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class P2pMessenger(
    context: Context,
    private val identity: LocalIdentity,
    private val contact: VerifiedContact,
    private val identityStore: IdentityStore,
    private val signalingHub: SignalingHub,
    private val onState: (String, Boolean) -> Unit,
    private val onMessage: (ReceivedMessage) -> Unit,
    private val onMessageAction: (MessageAction) -> Unit,
    private val onSent: (String) -> Unit,
    private val onReceipt: (String, MessageStatus) -> Unit,
    private val onPresence: (Boolean, Long) -> Unit,
    private val onSyncRequested: (Long) -> Unit,
    private val onSyncedMessage: (StoredMessage) -> Unit,
    private val onSyncFinished: () -> Unit,
    private val onFileOffer: (StoredMessage) -> Unit,
    private val onFileRequested: (String, Long) -> Unit,
    private val onFileComplete: (String, StoredAttachment) -> Unit,
    private val onProfilePhoto: (StoredAttachment?) -> Unit,
    private val onContactPayload: (String) -> Unit,
    private val isAppVisible: () -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val signaling = SupabaseSignaling()
    private val attachmentStore = AttachmentStore(context)
    private val incomingFiles = ConcurrentHashMap<String, IncomingFile>()
    private val factory: PeerConnectionFactory
    private val pendingIce = mutableListOf<IceCandidate>()
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var sessionId: String? = null
    private var initiator = false
    private var generation = 0
    private var retryDelay = InitialRetryDelay
    @Volatile private var closed = false

    init {
        factory = peerConnectionFactory(context.applicationContext)
        signalingHub.subscribe(contact.peerId, ::receiveSignal) {
            state("Sinalizacao: $it", false)
        }
        if (identity.peerId < contact.peerId) connect()
        else {
            signalingHub.setWaiting(contact.peerId, true, fast = true)
            state("Aguardando ${contact.displayName}...", false)
        }
        executor.scheduleWithFixedDelay({ sendPresence(isAppVisible()) }, 30, 30, TimeUnit.SECONDS)
    }

    fun connect() {
        if (closed) return
        executor.execute {
            retryDelay = InitialRetryDelay
            signalingHub.setWaiting(contact.peerId, true, fast = true)
            beginConnection()
        }
    }

    private fun beginConnection() {
        if (closed) return
        if (identity.peerId >= contact.peerId) {
            resetPeer()
            state("Aguardando ${contact.displayName}...", false)
            return
        }
        resetPeer()
        state("Conectando...", false)
        initiator = true
        sessionId = SignalingCodec.sessionId()
        createPeer()
        attachChannel(peer!!.createDataChannel("messages-v1", DataChannel.Init()))
        peer!!.createOffer(SdpCallbacks(created = { offer ->
            peer?.setLocalDescription(SdpCallbacks(set = {
                publish(SignalingCodec.Offer, offer.description)
            }), offer)
        }), MediaConstraints.empty())
        val attempt = generation
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(MaxRetryDelay)
        executor.schedule({
            if (!closed && attempt == generation && channel?.state() != DataChannel.State.OPEN) {
                signalingHub.setWaiting(contact.peerId, true, fast = true)
                beginConnection()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun send(message: StoredMessage) {
        if (closed) return
        executor.execute {
        val activeChannel = channel
        if (activeChannel?.state() != DataChannel.State.OPEN) {
            state("Contato desconectado.", false)
            return@execute
        }
        runCatching {
            val payload = MessageCodec.create(
                identity,
                contact,
                ChatPayloadCodec.encode(message.text, message.replyToId),
                message.id,
                message.timestamp,
                identityStore::sign
            )
            check(sendPacket(activeChannel, payload)) {
                "Falha ao entregar ao canal."
            }
            main.post { onSent(message.id) }
        }.onFailure { state("Falha: ${it.message}", true) }
        }
    }

    fun sendEdit(message: StoredMessage) {
        if (!message.mine || message.editedAt <= 0) return
        sendAction(MessageAction(MessageActionType.EDIT, message.id, message.text, message.editedAt))
    }

    fun sendDelete(messageId: String, deletedAt: Long) {
        sendAction(MessageAction(MessageActionType.DELETE, messageId, "", deletedAt))
    }

    private fun sendAction(action: MessageAction) {
        if (closed) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val payload = MessageCodec.create(
                    identity,
                    contact,
                    MessageActionCodec.encode(action),
                    UUID.randomUUID().toString(),
                    action.timestamp,
                    identityStore::sign,
                    maxLength = 5_000
                )
                check(sendPacket(activeChannel, "A|$payload")) { "Falha ao enviar operacao." }
            }.onFailure { state("Falha na operacao: ${it.message}", true) }
        }
    }

    fun sendReceipt(messageId: String, status: MessageStatus) {
        if (closed) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val receipt = ReceiptCodec.create(identity, contact, messageId, status, identityStore::sign)
                check(sendPacket(activeChannel, "R|$receipt")) { "Falha ao confirmar mensagem." }
            }.onFailure { state("Falha na confirmacao: ${it.message}", true) }
        }
    }

    fun sendPresence(active: Boolean) {
        if (closed) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val presence = PresenceCodec.create(identity, contact, active, identityStore::sign)
                check(sendPacket(activeChannel, "P|$presence")) { "Falha ao enviar presenca." }
            }.onFailure { state("Falha na presenca: ${it.message}", true) }
        }
    }

    fun requestSync(afterTimestamp: Long) =
        sendControl("Q|mensageiro-sync-v2|${afterTimestamp.coerceAtLeast(0)}")

    fun finishSync() = sendControl("E|mensageiro-sync-v1")

    fun sendSync(message: StoredMessage) {
        if (closed || message.system || message.attachment != null) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val record = JSONObject()
                    .put("version", 1)
                    .put("id", message.id)
                    .put("origin", if (message.mine) identity.peerId else contact.peerId)
                    .put("status", message.status.name)
                    .put("timestamp", message.timestamp)
                    .put("text", message.text)
                    .put("replyToId", message.replyToId)
                    .put("editedAt", message.editedAt)
                    .toString()
                val payload = MessageCodec.create(
                    identity,
                    contact,
                    record,
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    identityStore::sign,
                    maxLength = 12_000
                )
                check(sendPacket(activeChannel, "S|$payload")) { "Falha ao sincronizar mensagem." }
            }.onFailure { state("Falha na sincronizacao: ${it.message}", true) }
        }
    }

    fun sendFileOffer(message: StoredMessage) {
        val attachment = message.attachment ?: return
        if (closed || !attachment.complete || !File(attachment.localPath).isFile) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val offer = JSONObject()
                    .put("version", 1)
                    .put("name", attachment.name)
                    .put("mimeType", attachment.mimeType)
                    .put("size", attachment.size)
                    .put("sha256", attachment.sha256)
                    .put("text", message.text)
                    .toString()
                val payload = MessageCodec.create(
                    identity, contact, offer, message.id, message.timestamp, identityStore::sign
                )
                check(sendPacket(activeChannel, "F|$payload")) { "Falha ao oferecer arquivo." }
                main.post { onSent(message.id) }
            }.onFailure { state("Falha no arquivo: ${it.message}", true) }
        }
    }

    fun sendFileChunk(message: StoredMessage, offset: Long) {
        val attachment = message.attachment ?: return
        if (closed || !message.mine || !attachment.complete) return
        sendAttachmentChunk(message.id, attachment, offset)
    }

    fun sendProfilePhoto(attachment: StoredAttachment?) {
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                val value = JSONObject().put("version", 1).put("clear", attachment == null)
                val id = if (attachment == null) "profile-clear" else {
                    require(attachment.complete && File(attachment.localPath).isFile)
                    value.put("mimeType", attachment.mimeType)
                        .put("size", attachment.size)
                        .put("sha256", attachment.sha256)
                    ProfilePhotoStore.fileId(attachment.sha256)
                }
                val payload = MessageCodec.create(
                    identity,
                    contact,
                    value.toString(),
                    id,
                    System.currentTimeMillis(),
                    identityStore::sign
                )
                check(sendPacket(activeChannel, "V|$payload")) { "Falha ao anunciar foto." }
            }.onFailure { state("Falha na foto: ${it.message}", true) }
        }
    }

    fun sendProfileChunk(id: String, attachment: StoredAttachment, offset: Long) {
        if (id != ProfilePhotoStore.fileId(attachment.sha256)) return
        sendAttachmentChunk(id, attachment, offset)
    }

    private fun sendAttachmentChunk(id: String, attachment: StoredAttachment, offset: Long) {
        if (closed || !attachment.complete) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() != DataChannel.State.OPEN) return@execute
            runCatching {
                require(offset in 0 until attachment.size) { "Offset solicitado invalido." }
                val bytes = RandomAccessFile(attachment.localPath, "r").use { file ->
                    file.seek(offset)
                    ByteArray(minOf(AttachmentStore.ChunkSize.toLong(), attachment.size - offset).toInt())
                        .also { file.readFully(it) }
                }
                val key = identityStore.messageKey(contact.encryptionPublicKey, "file|$id")
                val encrypted = FileChunkCodec.encrypt(key, id, offset, bytes)
                check(sendPacket(activeChannel, "C|$id|$offset|${encrypted.nonce}|${encrypted.ciphertext}")) {
                    "Falha ao enviar bloco."
                }
            }.onFailure { state("Falha no bloco: ${it.message}", true) }
        }
    }

    fun cancelFile(id: String) {
        incomingFiles.remove(id)
        attachmentStore.delete(id, null)
    }

    fun close() {
        closed = true
        signalingHub.setWaiting(contact.peerId, false)
        signalingHub.unsubscribe(contact.peerId)
        executor.shutdownNow()
        resetPeer()
    }

    private fun receiveSignal(payload: String) {
        if (closed) return
        executor.execute {
            if (closed) return@execute
            runCatching { SignalingCodec.verify(payload, contact.publicKey, identity.peerId) }
                .onSuccess(::handleSignal)
                .onFailure { state("Sinalizacao rejeitada: ${it.message}", false) }
        }
    }

    private fun handleSignal(message: SignalingMessage) {
        if (message.senderPeerId != contact.peerId) return
        when (message.type) {
            SignalingCodec.Offer -> if (!initiator && message.sessionId != sessionId) {
                resetPeer()
                acceptOffer(message)
            }
            SignalingCodec.Answer -> if (initiator && message.sessionId == sessionId) {
                peer?.setRemoteDescription(SdpCallbacks(set = ::flushIce), description(message))
            }
            SignalingCodec.IceCandidate -> if (message.sessionId == sessionId) {
                val json = JSONObject(SignalingCodec.payloadText(message))
                val candidate = IceCandidate(json.getString("mid"), json.getInt("line"), json.getString("sdp"))
                if (peer?.remoteDescription == null) pendingIce += candidate else peer?.addIceCandidate(candidate)
            }
        }
    }

    private fun acceptOffer(message: SignalingMessage) {
        initiator = false
        sessionId = message.sessionId
        state("Convite recebido...", false)
        createPeer()
        peer!!.setRemoteDescription(SdpCallbacks(set = {
            flushIce()
            peer?.createAnswer(SdpCallbacks(created = { answer ->
                peer?.setLocalDescription(SdpCallbacks(set = {
                    publish(SignalingCodec.Answer, answer.description)
                }), answer)
            }), MediaConstraints.empty())
        }), description(message))
    }

    private fun createPeer() {
        // ponytail: public prototype relay; replace with rotating credentials before release.
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            turnServer("turn:openrelay.metered.ca:80"),
            turnServer("turn:openrelay.metered.ca:443"),
            turnServer("turn:openrelay.metered.ca:443?transport=tcp")
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = checkNotNull(factory.createPeerConnection(config, PeerObserver(generation)))
    }

    private fun resetPeer() {
        generation++
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        peer?.close()
        peer?.dispose()
        channel = null
        peer = null
        sessionId = null
        pendingIce.clear()
    }

    private fun turnServer(url: String): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(url)
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()

    private fun publish(type: String, payload: String) {
        if (closed) return
        executor.execute {
            val currentSession = sessionId ?: return@execute
            runCatching {
                signaling.publish(
                    SignalingCodec.create(
                        currentSession,
                        identity.peerId,
                        contact.peerId,
                        type,
                        payload,
                        identityStore::sign
                    )
                )
            }.onFailure { state("Sinalizacao: ${it.message}", false) }
        }
    }

    private fun publish(candidate: IceCandidate) {
        if (closed) return
        val payload = JSONObject()
            .put("mid", candidate.sdpMid)
            .put("line", candidate.sdpMLineIndex)
            .put("sdp", candidate.sdp)
            .toString()
        publish(SignalingCodec.IceCandidate, payload)
    }

    private fun description(message: SignalingMessage): SessionDescription = SessionDescription(
        if (message.type == SignalingCodec.Offer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
        SignalingCodec.payloadText(message)
    )

    private fun flushIce() {
        pendingIce.forEach { peer?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun attachChannel(dataChannel: DataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (channel !== dataChannel || closed) return
                val open = dataChannel.state() == DataChannel.State.OPEN
                signalingHub.setWaiting(contact.peerId, !open)
                if (open) {
                    retryDelay = InitialRetryDelay
                    sendControl("I|${identity.publicPayload}")
                }
                state(if (open) "Conectado" else "Canal: ${dataChannel.state()}", open)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val packet = bytes.decodeToString()
                when {
                    packet.startsWith("I|") -> runCatching {
                        val payload = packet.substring(2)
                        val shared = CryptoText.verifyContact(payload, identity.publicKey)
                        require(
                            shared.peerId == contact.peerId && shared.publicKey == contact.publicKey &&
                                shared.encryptionPublicKey == contact.encryptionPublicKey
                        ) { "Identidade nao corresponde ao contato." }
                        payload
                    }.onSuccess { payload -> main.post { onContactPayload(payload) } }
                        .onFailure { state("Identidade de contato rejeitada: ${it.message}", true) }
                    packet.startsWith("R|") -> runCatching {
                        ReceiptCodec.verify(packet.substring(2), identity, contact)
                    }.onSuccess { receipt -> main.post { onReceipt(receipt.messageId, receipt.status) } }
                        .onFailure { state("Confirmacao rejeitada: ${it.message}", true) }
                    packet.startsWith("P|") -> runCatching {
                        PresenceCodec.verify(packet.substring(2), identity, contact)
                    }.onSuccess { presence -> main.post { onPresence(presence.active, presence.timestamp) } }
                        .onFailure { state("Presenca rejeitada: ${it.message}", true) }
                    packet.startsWith("A|") -> runCatching {
                        val envelope = MessageCodec.open(
                            packet.substring(2), identity, contact, identityStore::messageKey
                        )
                        MessageActionCodec.decode(envelope.text)
                    }.onSuccess { action -> main.post { onMessageAction(action) } }
                        .onFailure { state("Operacao de mensagem rejeitada: ${it.message}", true) }
                    packet == "Q|mensageiro-sync-v1" -> main.post { onSyncRequested(0) }
                    packet.startsWith("Q|mensageiro-sync-v2|") -> runCatching {
                        packet.substringAfterLast('|').toLong().also { require(it >= 0) }
                    }.onSuccess { timestamp -> main.post { onSyncRequested(timestamp) } }
                        .onFailure { state("Pedido de sincronizacao invalido.", true) }
                    packet == "E|mensageiro-sync-v1" -> main.post(onSyncFinished)
                    packet.startsWith("S|") -> runCatching {
                        val envelope = MessageCodec.open(
                            packet.substring(2), identity, contact, identityStore::messageKey
                        )
                        val record = JSONObject(envelope.text)
                        require(record.getInt("version") == 1) { "Versao de sincronizacao invalida." }
                        val origin = record.getString("origin")
                        require(origin == identity.peerId || origin == contact.peerId) { "Origem sincronizada invalida." }
                        val sourceStatus = MessageStatus.valueOf(record.getString("status"))
                        val id = record.getString("id")
                        val text = record.getString("text")
                        val timestamp = record.getLong("timestamp")
                        require(id.isNotBlank() && id.length <= 200 && text.length <= 4_000 && timestamp > 0) {
                            "Mensagem sincronizada invalida."
                        }
                        StoredMessage(
                            id,
                            contact.peerId,
                            origin == identity.peerId,
                            if (sourceStatus == MessageStatus.READ) MessageStatus.READ else MessageStatus.DELIVERED,
                            false,
                            text,
                            timestamp,
                            replyToId = record.optString("replyToId")
                                .takeIf { it.isNotBlank() && it != "null" },
                            editedAt = record.optLong("editedAt")
                        )
                    }.onSuccess { message -> main.post { onSyncedMessage(message) } }
                        .onFailure { state("Sincronizacao rejeitada: ${it.message}", true) }
                    packet.startsWith("F|") -> receiveFileOffer(packet.substring(2))
                    packet.startsWith("V|") -> receiveProfilePhoto(packet.substring(2))
                    packet.startsWith("G|") -> runCatching {
                        val fields = packet.split('|', limit = 3)
                        require(fields.size == 3 && fields[1].length <= 200)
                        fields[1] to fields[2].toLong().also { require(it >= 0) }
                    }.onSuccess { (id, offset) -> main.post { onFileRequested(id, offset) } }
                        .onFailure { state("Pedido de arquivo rejeitado.", true) }
                    packet.startsWith("C|") -> receiveFileChunk(packet)
                    else -> runCatching {
                        val envelope = MessageCodec.open(packet, identity, contact, identityStore::messageKey)
                        val content = ChatPayloadCodec.decode(envelope.text)
                        envelope.copy(text = content.text, replyToId = content.replyToId)
                    }.onSuccess { message -> main.post { onMessage(message) } }
                        .onFailure { state("Mensagem rejeitada: ${it.message}", true) }
                }
            }
        })
    }

    private fun sendPacket(dataChannel: DataChannel, text: String): Boolean =
        dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))

    private fun sendControl(packet: String) {
        if (closed) return
        executor.execute {
            val activeChannel = channel
            if (activeChannel?.state() == DataChannel.State.OPEN) sendPacket(activeChannel, packet)
        }
    }

    private fun receiveFileOffer(wire: String) {
        runCatching {
            val envelope = MessageCodec.open(wire, identity, contact, identityStore::messageKey)
            val value = JSONObject(envelope.text)
            require(value.getInt("version") == 1)
            val attachment = StoredAttachment(
                value.getString("name").also { require(it.isNotBlank() && it.length <= 160) },
                value.getString("mimeType").also { require(it.length <= 160) },
                value.getLong("size").also { require(it in 0..AttachmentStore.MaxSize) },
                value.getString("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) },
                "",
                false
            )
            val message = StoredMessage(
                envelope.id,
                contact.peerId,
                false,
                MessageStatus.SENT,
                false,
                value.getString("text"),
                envelope.timestamp,
                attachment
            )
            incomingFiles[envelope.id] = IncomingFile(attachment, false)
            main.post { onFileOffer(message) }
            val completed = attachmentStore.completed(envelope.id, attachment)
                ?: if (attachment.size == 0L) attachmentStore.finish(envelope.id, attachment) else null
            if (completed != null) main.post { onFileComplete(envelope.id, completed) }
            else requestFile(envelope.id, attachmentStore.incomingOffset(envelope.id))
        }.onFailure { state("Oferta de arquivo rejeitada: ${it.message}", true) }
    }

    private fun receiveProfilePhoto(wire: String) {
        runCatching {
            val envelope = MessageCodec.open(wire, identity, contact, identityStore::messageKey)
            val value = JSONObject(envelope.text)
            require(value.getInt("version") == 1)
            if (value.getBoolean("clear")) {
                require(envelope.id == "profile-clear")
                clearIncomingProfiles()
                main.post { onProfilePhoto(null) }
                return
            }

            val attachment = StoredAttachment(
                "perfil.webp",
                value.getString("mimeType").also { require(it == "image/webp") },
                value.getLong("size").also { require(it in 1..ProfileMaxSize) },
                value.getString("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) },
                "",
                false
            )
            require(envelope.id == ProfilePhotoStore.fileId(attachment.sha256))
            clearIncomingProfiles(envelope.id)
            incomingFiles[envelope.id] = IncomingFile(attachment, true)
            val completed = attachmentStore.completed(envelope.id, attachment)
            if (completed != null) {
                incomingFiles.remove(envelope.id)
                main.post { onProfilePhoto(completed) }
            } else requestFile(envelope.id, attachmentStore.incomingOffset(envelope.id))
        }.onFailure { state("Foto de perfil rejeitada: ${it.message}", true) }
    }

    private fun clearIncomingProfiles(exceptId: String? = null) {
        incomingFiles.entries
            .filter { it.value.profile && it.key != exceptId }
            .forEach { (id, _) ->
                incomingFiles.remove(id)
                attachmentStore.delete(id, null)
            }
    }

    private fun receiveFileChunk(packet: String) {
        runCatching {
            val fields = packet.split('|', limit = 5)
            require(fields.size == 5)
            val id = fields[1]
            val offset = fields[2].toLong()
            val incoming = requireNotNull(incomingFiles[id]) { "Arquivo nao oferecido." }
            val attachment = incoming.attachment
            val key = identityStore.messageKey(contact.encryptionPublicKey, "file|$id")
            val bytes = FileChunkCodec.decrypt(key, id, offset, EncryptedFileChunk(fields[3], fields[4]))
            val nextOffset = attachmentStore.append(id, offset, bytes, attachment.size)
            if (nextOffset == attachment.size) {
                runCatching { attachmentStore.finish(id, attachment) }
                    .onSuccess { completed ->
                        incomingFiles.remove(id)
                        if (incoming.profile) main.post { onProfilePhoto(completed) }
                        else main.post { onFileComplete(id, completed) }
                    }.onFailure {
                        attachmentStore.reset(id)
                        requestFile(id, 0)
                    }
            } else requestFile(id, nextOffset)
        }.onFailure { state("Bloco de arquivo rejeitado: ${it.message}", true) }
    }

    private fun requestFile(id: String, offset: Long) = sendControl("G|$id|$offset")

    private fun state(text: String, connected: Boolean) {
        main.post { onState(text, connected) }
    }

    private inner class PeerObserver(private val peerGeneration: Int) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (peerGeneration != generation || closed) return
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                state("Desconectado. Tentando novamente...", false)
                connect()
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            if (peerGeneration == generation && !closed) publish(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = attachChannel(dataChannel)
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private inner class SdpCallbacks(
        private val created: (SessionDescription) -> Unit = {},
        private val set: () -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = created(description)
        override fun onSetSuccess() = set()
        override fun onCreateFailure(error: String) = state("WebRTC: $error", false)
        override fun onSetFailure(error: String) = state("WebRTC: $error", false)
    }

    private object MediaConstraints {
        fun empty() = org.webrtc.MediaConstraints()
    }

    private companion object {
        const val ProfileMaxSize = 2L * 1024 * 1024
        const val InitialRetryDelay = 20_000L
        const val MaxRetryDelay = 5 * 60_000L
        @Volatile private var sharedFactory: PeerConnectionFactory? = null

        @Synchronized
        fun peerConnectionFactory(context: Context): PeerConnectionFactory {
            sharedFactory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            return PeerConnectionFactory.builder().createPeerConnectionFactory().also { sharedFactory = it }
        }
    }

    private data class IncomingFile(val attachment: StoredAttachment, val profile: Boolean)
}
