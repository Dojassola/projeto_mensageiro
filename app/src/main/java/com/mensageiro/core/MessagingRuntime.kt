package com.mensageiro.core

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.AutomaticBackup
import com.mensageiro.core.crypto.ContactStore
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.MessageAction
import com.mensageiro.core.crypto.MessageActionType
import com.mensageiro.core.crypto.MessageStatus
import com.mensageiro.core.crypto.MessageStore
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.StoredMessage
import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.core.signaling.SignalingHub
import com.mensageiro.core.webrtc.P2pMessenger
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

data class MessagingSnapshot(
    val contact: VerifiedContact?,
    val status: String,
    val connected: Boolean,
    val active: Boolean,
    val messages: List<StoredMessage>,
    val lastOnline: Long,
    val serviceStatus: String
)

data class ContactPreview(
    val lastMessage: StoredMessage?,
    val lastOnline: Long,
    val active: Boolean
)

object MessagingRuntime {
    fun interface Listener { fun onUpdate(snapshot: MessagingSnapshot) }

    private class Session(var contact: VerifiedContact) {
        var messenger: P2pMessenger? = null
        var status = "Procurando ${contact.displayName}..."
        var connected = false
        var remoteActive = false
        var syncedMessages = 0
        val newFiles = mutableSetOf<String>()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val sessions = LinkedHashMap<String, Session>()
    private var context: Context? = null
    private var identityStore: IdentityStore? = null
    private var messageStore: MessageStore? = null
    private var signalingHub: SignalingHub? = null
    private var selectedPeerId: String? = null
    private var conversationPeerId: String? = null
    @Volatile private var appVisible = false

    @Synchronized
    fun start(source: Context) {
        val app = source.applicationContext
        context = app
        if (identityStore == null) identityStore = IdentityStore(app)
        if (messageStore == null) messageStore = MessageStore(app, identityStore!!)
        AutomaticBackup.resume(app)

        val identity = identityStore!!.getOrCreate()
        if (signalingHub == null) signalingHub = SignalingHub(identity.peerId)
        syncSessions(ContactStore(app).all(), identity)

        val saved = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .getString("contact", null)
        if (selectedPeerId !in sessions) {
            selectedPeerId = saved?.takeIf { it in sessions } ?: sessions.keys.firstOrNull()
        }
        dispatch()
    }

    @Synchronized
    fun reload(source: Context) {
        closeSessions()
        selectedPeerId = null
        conversationPeerId = null
        identityStore = null
        messageStore = null
        start(source)
    }

    @Synchronized
    fun selectContact(source: Context, peerId: String) {
        start(source)
        if (peerId !in sessions) return
        selectedPeerId = peerId
        source.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .edit().putString("contact", peerId).apply()
        dispatch()
    }

    @Synchronized
    fun reconnect() {
        sessions[selectedPeerId]?.messenger?.connect()
    }

    @Synchronized
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        sessions.values.forEach { it.messenger?.sendPresence(visible) }
        if (visible) markRead()
    }

    @Synchronized
    fun setConversationVisible(visible: Boolean) {
        setConversationVisible(selectedPeerId, visible)
    }

    @Synchronized
    fun setConversationVisible(peerId: String?, visible: Boolean) {
        if (visible) conversationPeerId = peerId
        else if (conversationPeerId == peerId) conversationPeerId = null
        if (visible) markRead(peerId)
    }

    @Synchronized
    fun send(text: String, replyToId: String? = null) {
        val session = sessions[selectedPeerId] ?: return
        val store = messageStore ?: return
        val reply = replyToId?.takeIf { id ->
            store.forContact(session.contact.peerId).any { it.id == id && !it.system }
        }
        val message = StoredMessage(
            UUID.randomUUID().toString(), session.contact.peerId, true, MessageStatus.PENDING, false,
            text.trim(), System.currentTimeMillis(), replyToId = reply
        )
        store.add(message)
        if (session.connected) session.messenger?.send(message)
        dispatch()
    }

    @Synchronized
    fun sendFile(messageId: String, attachment: StoredAttachment) {
        val session = sessions[selectedPeerId] ?: return
        val store = messageStore ?: return
        val message = StoredMessage(
            messageId,
            session.contact.peerId,
            true,
            MessageStatus.PENDING,
            false,
            "",
            System.currentTimeMillis(),
            attachment
        )
        store.add(message)
        if (session.connected) session.messenger?.sendFileOffer(message)
        dispatch()
    }

    @Synchronized
    fun deleteMessage(id: String) {
        val store = messageStore ?: return
        val message = store.delete(id) ?: return
        context?.let { AttachmentStore(it).delete(id, message.attachment) }
        sessions[message.contactPeerId]?.messenger?.cancelFile(id)
        dispatch()
    }

    @Synchronized
    fun editMessage(id: String, text: String): String {
        val session = sessions[selectedPeerId] ?: return "Contato indisponivel."
        val store = messageStore ?: return "Mensagens indisponiveis."
        val message = store.forContact(session.contact.peerId)
            .firstOrNull { it.id == id && it.mine && !it.system && it.attachment == null }
            ?: return "Esta mensagem nao pode ser editada."
        val edited = runCatching { store.edit(message.id, text, System.currentTimeMillis()) }
            .getOrElse { return "Texto invalido." }
            ?: return "Mensagem nao encontrada."
        session.messenger?.sendEdit(edited)
        dispatch()
        return ""
    }

    @Synchronized
    fun deleteMessageForEveryone(id: String): String {
        val session = sessions[selectedPeerId] ?: return "Contato indisponivel."
        val store = messageStore ?: return "Mensagens indisponiveis."
        val message = store.forContact(session.contact.peerId).firstOrNull { it.id == id && it.mine }
            ?: return "Somente mensagens enviadas por voce podem ser excluidas para todos."
        val deletedAt = System.currentTimeMillis()
        session.messenger?.sendDelete(id, deletedAt)
        store.delete(id, forEveryone = true, deletedAt = deletedAt)
        context?.let { AttachmentStore(it).delete(id, message.attachment) }
        session.messenger?.cancelFile(id)
        dispatch()
        return ""
    }

    @Synchronized
    fun refreshProfilePhoto() {
        val app = context ?: return
        val photos = ProfilePhotoStore(app)
        val photo = photos.local().takeIf { photos.isSharing() }
        sessions.values.forEach { it.messenger?.sendProfilePhoto(photo) }
        dispatch()
    }

    @Synchronized
    fun stop() {
        closeSessions()
        selectedPeerId = null
        conversationPeerId = null
        dispatch()
    }

    @Synchronized
    fun markRead(peerId: String? = conversationPeerId) {
        if (!appVisible || conversationPeerId != peerId) return
        val session = sessions[peerId] ?: return
        val store = messageStore ?: return
        store.forContact(session.contact.peerId)
            .filter {
                !it.mine && !it.system && it.status != MessageStatus.READ &&
                    (it.attachment == null || it.attachment.complete)
            }
            .forEach {
                store.markStatus(it.id, MessageStatus.READ)
                session.messenger?.sendReceipt(it.id, MessageStatus.READ)
                context?.getSystemService(NotificationManager::class.java)?.cancel(it.id.hashCode())
            }
        dispatch()
    }

    @Synchronized
    fun preview(peerId: String): ContactPreview {
        val store = messageStore
        val session = sessions[peerId]
        return ContactPreview(
            store?.forContact(peerId)?.lastOrNull { !it.system },
            store?.lastOnline(peerId) ?: 0,
            session?.connected == true && session.remoteActive
        )
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onUpdate(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    fun snapshot(): MessagingSnapshot {
        val session = sessions[selectedPeerId]
        val store = messageStore
        val connectedCount = sessions.values.count { it.connected }
        return MessagingSnapshot(
            session?.contact,
            session?.status ?: if (sessions.isEmpty()) "Adicione um contato" else "Sem contato selecionado",
            session?.connected == true,
            session?.connected == true && session.remoteActive,
            if (session != null && store != null) store.forContact(session.contact.peerId) else emptyList(),
            if (session != null && store != null) store.lastOnline(session.contact.peerId) else 0,
            if (sessions.isEmpty()) "Nenhum contato" else "$connectedCount de ${sessions.size} conectados"
        )
    }

    private fun syncSessions(contacts: List<VerifiedContact>, identity: com.mensageiro.core.crypto.LocalIdentity) {
        val peerIds = contacts.mapTo(HashSet()) { it.peerId }
        sessions.keys.filter { it !in peerIds }.toList().forEach { peerId ->
            sessions.remove(peerId)?.messenger?.close()
        }
        contacts.forEach { contact ->
            val current = sessions[contact.peerId]
            if (current == null) {
                createSession(contact, identity)
            } else if (current.contact.publicKey != contact.publicKey ||
                current.contact.encryptionPublicKey != contact.encryptionPublicKey
            ) {
                current.messenger?.close()
                sessions.remove(contact.peerId)
                createSession(contact, identity)
            } else {
                current.contact = contact
            }
        }
    }

    private fun createSession(
        contact: VerifiedContact,
        identity: com.mensageiro.core.crypto.LocalIdentity
    ) {
        val app = context ?: return
        val identities = identityStore ?: return
        val hub = signalingHub ?: return
        val session = Session(contact)
        sessions[contact.peerId] = session
        session.messenger = P2pMessenger(
            app,
            identity,
            contact,
            identities,
            hub,
            onState = { status, connected -> onState(session, status, connected) },
            onMessage = { onMessage(session, it) },
            onMessageAction = { onMessageAction(session, it) },
            onSent = { onSent(session, it) },
            onReceipt = { id, status -> onReceipt(session, id, status) },
            onPresence = { active, _ -> onPresence(session, active) },
            onSyncRequested = { onSyncRequested(session, it) },
            onSyncedMessage = { onSyncedMessage(session, it) },
            onSyncFinished = { onSyncFinished(session) },
            onFileOffer = { onFileOffer(session, it) },
            onFileRequested = { id, offset -> onFileRequested(session, id, offset) },
            onFileComplete = { id, attachment -> onFileComplete(session, id, attachment) },
            onProfilePhoto = { onProfilePhoto(session, it) },
            onContactPayload = { onContactPayload(session, it) },
            isAppVisible = { appVisible }
        )
    }

    @Synchronized
    private fun onMessage(session: Session, received: com.mensageiro.core.crypto.ReceivedMessage) {
        if (!isCurrent(session)) return
        val app = context ?: return
        val store = messageStore ?: return
        val added = store.add(
            StoredMessage(
                received.id,
                session.contact.peerId,
                false,
                MessageStatus.DELIVERED,
                false,
                received.text,
                received.timestamp,
                replyToId = received.replyToId
            )
        )
        val savedStatus = store.forContact(session.contact.peerId)
            .firstOrNull { it.id == received.id }?.status
        session.messenger?.sendReceipt(
            received.id,
            if (savedStatus == MessageStatus.READ) MessageStatus.READ else MessageStatus.DELIVERED
        )
        if (added && (!appVisible || conversationPeerId != session.contact.peerId) && canNotify(app)) {
            app.getSystemService(NotificationManager::class.java).notify(
                received.id.hashCode(),
                Notifications.message(app, session.contact.displayName, received.text)
            )
        }
        if (conversationPeerId == session.contact.peerId) markRead(session.contact.peerId)
        dispatch()
    }

    @Synchronized
    private fun onMessageAction(session: Session, action: MessageAction) {
        if (!isCurrent(session)) return
        val store = messageStore ?: return
        val message = store.forContact(session.contact.peerId)
            .firstOrNull { it.id == action.targetId }
        when (action.type) {
            MessageActionType.EDIT -> {
                if (message != null && !message.mine && !message.system && message.attachment == null &&
                    action.timestamp > message.editedAt
                ) {
                    runCatching { store.edit(message.id, action.text, action.timestamp) }
                }
            }
            MessageActionType.DELETE -> {
                if (message?.mine == true || message?.system == true) return
                store.delete(action.targetId, deletedAt = action.timestamp)
                context?.let { app ->
                    if (message != null) AttachmentStore(app).delete(message.id, message.attachment)
                    app.getSystemService(NotificationManager::class.java)?.cancel(action.targetId.hashCode())
                }
                session.messenger?.cancelFile(action.targetId)
            }
        }
        dispatch()
    }

    @Synchronized
    private fun onSent(session: Session, id: String) {
        if (!isCurrent(session)) return
        messageStore?.markStatus(id, MessageStatus.SENT)
        dispatch()
    }

    @Synchronized
    private fun onReceipt(session: Session, id: String, status: MessageStatus) {
        if (!isCurrent(session)) return
        val store = messageStore ?: return
        val message = store.forContact(session.contact.peerId).firstOrNull { it.id == id && it.mine } ?: return
        store.markStatus(message.id, status)
        dispatch()
    }

    @Synchronized
    private fun onPresence(session: Session, active: Boolean) {
        if (!isCurrent(session)) return
        session.remoteActive = active
        messageStore?.markOnline(session.contact.peerId)
        dispatch()
    }

    @Synchronized
    private fun onSyncRequested(session: Session, afterTimestamp: Long) {
        if (!isCurrent(session)) return
        // ponytail: sync por cauda; usar manifesto de IDs se buracos intermediarios forem relevantes.
        messageStore?.forContact(session.contact.peerId)
            ?.filter {
                !it.system && it.attachment == null &&
                    (it.timestamp > afterTimestamp || it.editedAt > afterTimestamp)
            }
            ?.forEach { session.messenger?.sendSync(it) }
        session.messenger?.finishSync()
    }

    @Synchronized
    private fun onSyncedMessage(session: Session, message: StoredMessage) {
        if (isCurrent(session) && messageStore?.mergeSynced(message) == true) session.syncedMessages++
    }

    @Synchronized
    private fun onSyncFinished(session: Session) {
        if (!isCurrent(session) || session.syncedMessages == 0) return
        val now = System.currentTimeMillis()
        messageStore?.addSystem(
            session.contact.peerId,
            "Sincronizadas ${session.syncedMessages} mensagens as " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
            now
        )
        session.syncedMessages = 0
        markRead(session.contact.peerId)
        dispatch()
    }

    @Synchronized
    private fun onFileOffer(session: Session, message: StoredMessage) {
        if (!isCurrent(session)) return
        if (messageStore?.add(message) == true) session.newFiles += message.id
        dispatch()
    }

    @Synchronized
    private fun onFileRequested(session: Session, id: String, offset: Long) {
        if (!isCurrent(session)) return
        val message = messageStore?.forContact(session.contact.peerId)
            ?.firstOrNull { it.id == id && it.mine && it.attachment?.complete == true }
        if (message != null) {
            session.messenger?.sendFileChunk(message, offset)
            return
        }
        val app = context ?: return
        val photos = ProfilePhotoStore(app)
        val photo = photos.local().takeIf { photos.isSharing() } ?: return
        session.messenger?.sendProfileChunk(id, photo, offset)
    }

    @Synchronized
    private fun onFileComplete(session: Session, id: String, attachment: StoredAttachment) {
        if (!isCurrent(session)) return
        val app = context ?: return
        val store = messageStore ?: return
        store.forContact(session.contact.peerId).firstOrNull { it.id == id } ?: return
        store.updateAttachment(id, attachment)
        store.markStatus(id, MessageStatus.DELIVERED)
        session.messenger?.sendReceipt(id, MessageStatus.DELIVERED)
        if (session.newFiles.remove(id) && (!appVisible || conversationPeerId != session.contact.peerId) && canNotify(app)) {
            app.getSystemService(NotificationManager::class.java).notify(
                id.hashCode(),
                Notifications.message(app, session.contact.displayName, "Arquivo: ${attachment.name}")
            )
        }
        markRead(session.contact.peerId)
        dispatch()
    }

    @Synchronized
    private fun onProfilePhoto(session: Session, photo: StoredAttachment?) {
        if (!isCurrent(session)) return
        context?.let { ProfilePhotoStore(it).setRemote(session.contact.peerId, photo) }
        dispatch()
    }

    @Synchronized
    private fun onContactPayload(session: Session, payload: String) {
        if (!isCurrent(session)) return
        val app = context ?: return
        runCatching {
            ContactStore(app).updateSharePayload(session.contact.peerId, payload, identityStore!!.getOrCreate().publicKey)
        }.onSuccess { session.contact = it }
        dispatch()
    }

    @Synchronized
    private fun onState(session: Session, newStatus: String, connected: Boolean) {
        if (!isCurrent(session)) return
        val store = messageStore ?: return
        if (session.connected != connected) {
            val now = System.currentTimeMillis()
            store.addSystem(
                session.contact.peerId,
                (if (connected) "Conectado as " else "Desconectado as ") +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                now
            )
            if (!connected && session.remoteActive) store.markOnline(session.contact.peerId, now)
        }
        session.status = newStatus
        session.connected = connected
        if (!connected) session.remoteActive = false
        if (connected) prepareConnectedSession(session, store)
        dispatch()
    }

    private fun prepareConnectedSession(session: Session, store: MessageStore) {
        session.messenger?.sendPresence(appVisible)
        val photos = context?.let(::ProfilePhotoStore)
        session.messenger?.sendProfilePhoto(photos?.local().takeIf { photos?.isSharing() == true })
        val latestTimestamp = store.forContact(session.contact.peerId)
            .filter { !it.system }
            .maxOfOrNull { maxOf(it.timestamp, it.editedAt) } ?: 0
        session.messenger?.requestSync(latestTimestamp)
        store.forContact(session.contact.peerId)
            .filter { it.mine && it.status.ordinal < MessageStatus.DELIVERED.ordinal }
            .forEach {
                if (it.attachment == null) session.messenger?.send(it)
                else session.messenger?.sendFileOffer(it)
            }
        store.forContact(session.contact.peerId)
            .filter { it.mine && it.editedAt > 0 }
            .forEach { session.messenger?.sendEdit(it) }
        store.remoteDeletions(session.contact.peerId)
            .forEach { session.messenger?.sendDelete(it.id, it.deletedAt) }
        store.forContact(session.contact.peerId)
            .filter { !it.mine && !it.system && it.status.ordinal >= MessageStatus.DELIVERED.ordinal }
            .forEach { session.messenger?.sendReceipt(it.id, it.status) }
    }

    private fun isCurrent(session: Session): Boolean = sessions[session.contact.peerId] === session

    private fun closeSessions() {
        sessions.values.forEach { it.messenger?.close() }
        sessions.clear()
        signalingHub?.close()
        signalingHub = null
    }

    private fun dispatch() {
        val value = snapshot()
        listeners.forEach { it.onUpdate(value) }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
