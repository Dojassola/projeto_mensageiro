package com.mensageiro.core

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mensageiro.core.crypto.ContactStore
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.MessageStore
import com.mensageiro.core.crypto.MessageStatus
import com.mensageiro.core.crypto.StoredMessage
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.AutomaticBackup
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.VerifiedContact
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
    val lastOnline: Long
)

data class ContactPreview(
    val lastMessage: StoredMessage?,
    val lastOnline: Long,
    val active: Boolean
)

object MessagingRuntime {
    fun interface Listener { fun onUpdate(snapshot: MessagingSnapshot) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var context: Context? = null
    private var messenger: P2pMessenger? = null
    private var identityStore: IdentityStore? = null
    private var messageStore: MessageStore? = null
    private var contact: VerifiedContact? = null
    private var status = "Sem contato"
    private var connected = false
    private var remoteActive = false
    @Volatile private var appVisible = false
    @Volatile private var conversationVisible = false

    @Synchronized
    fun start(source: Context) {
        val app = source.applicationContext
        context = app
        if (identityStore == null) identityStore = IdentityStore(app)
        if (messageStore == null) messageStore = MessageStore(app, identityStore!!)
        AutomaticBackup.resume(app)
        val contacts = ContactStore(app).all()
        val selected = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .getString("contact", null)
        val next = contacts.firstOrNull { it.peerId == selected } ?: contacts.firstOrNull()
        if (next?.peerId == contact?.peerId && messenger != null) return
        connect(next)
    }

    @Synchronized
    fun reload(source: Context) {
        messenger?.close()
        messenger = null
        contact = null
        identityStore = null
        messageStore = null
        connected = false
        start(source)
    }

    @Synchronized
    fun selectContact(source: Context, peerId: String) {
        source.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putString("contact", peerId).apply()
        if (contact?.peerId == peerId && messenger != null) return
        messenger?.close()
        messenger = null
        contact = null
        start(source)
    }

    @Synchronized
    fun reconnect() {
        messenger?.connect()
    }

    @Synchronized
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        messenger?.sendPresence(visible)
        if (visible) markRead()
    }

    @Synchronized
    fun setConversationVisible(visible: Boolean) {
        conversationVisible = visible
        if (visible) markRead()
    }

    @Synchronized
    fun send(text: String) {
        val current = contact ?: return
        val store = messageStore ?: return
        val message = StoredMessage(
            UUID.randomUUID().toString(), current.peerId, true, MessageStatus.PENDING, false,
            text.trim(), System.currentTimeMillis()
        )
        store.add(message)
        if (connected) messenger?.send(message)
        dispatch()
    }

    @Synchronized
    fun sendFile(messageId: String, attachment: StoredAttachment) {
        val current = contact ?: return
        val store = messageStore ?: return
        val message = StoredMessage(
            messageId,
            current.peerId,
            true,
            MessageStatus.PENDING,
            false,
            "",
            System.currentTimeMillis(),
            attachment
        )
        store.add(message)
        if (connected) messenger?.sendFileOffer(message)
        dispatch()
    }

    @Synchronized
    fun deleteMessage(id: String) {
        val store = messageStore ?: return
        val message = store.delete(id) ?: return
        context?.let { AttachmentStore(it).delete(id, message.attachment) }
        messenger?.cancelFile(id)
        dispatch()
    }

    @Synchronized
    fun refreshProfilePhoto() {
        val app = context ?: return
        val photos = ProfilePhotoStore(app)
        messenger?.sendProfilePhoto(photos.local().takeIf { photos.isSharing() })
        dispatch()
    }

    @Synchronized
    fun stop() {
        messenger?.close()
        messenger = null
        connected = false
        remoteActive = false
        status = "Servico parado"
        dispatch()
    }

    @Synchronized
    fun markRead() {
        if (!appVisible || !conversationVisible) return
        val current = contact ?: return
        val store = messageStore ?: return
        store.forContact(current.peerId)
            .filter {
                !it.mine && !it.system && it.status != MessageStatus.READ &&
                    (it.attachment == null || it.attachment.complete)
            }
            .forEach {
                store.markStatus(it.id, MessageStatus.READ)
                messenger?.sendReceipt(it.id, MessageStatus.READ)
                context?.getSystemService(NotificationManager::class.java)?.cancel(it.id.hashCode())
            }
        dispatch()
    }

    @Synchronized
    fun preview(peerId: String): ContactPreview {
        val store = messageStore
        return ContactPreview(
            store?.forContact(peerId)?.lastOrNull { !it.system },
            store?.lastOnline(peerId) ?: 0,
            contact?.peerId == peerId && connected && remoteActive
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
        val current = contact
        val store = messageStore
        return MessagingSnapshot(
            current,
            status,
            connected,
            connected && remoteActive,
            if (current != null && store != null) store.forContact(current.peerId) else emptyList(),
            if (current != null && store != null) store.lastOnline(current.peerId) else 0
        )
    }

    private fun connect(next: VerifiedContact?) {
        messenger?.close()
        messenger = null
        contact = next
        connected = false
        remoteActive = false
        if (next == null) {
            status = "Adicione um contato"
            dispatch()
            return
        }
        val app = context ?: return
        val identity = identityStore!!.getOrCreate()
        val profilePhotos = ProfilePhotoStore(app)
        var syncedMessages = 0
        val newFiles = mutableSetOf<String>()
        status = "Procurando ${next.displayName}..."
        messenger = P2pMessenger(
            app,
            identity,
            next,
            identityStore!!,
            onState = { newStatus, isConnected -> onState(newStatus, isConnected) },
            onMessage = { received ->
                val store = messageStore!!
                val added = store.add(
                    StoredMessage(
                        received.id, next.peerId, false, MessageStatus.DELIVERED, false,
                        received.text, received.timestamp
                    )
                )
                val savedStatus = store.forContact(next.peerId).firstOrNull { it.id == received.id }?.status
                messenger?.sendReceipt(
                    received.id,
                    if (savedStatus == MessageStatus.READ) MessageStatus.READ else MessageStatus.DELIVERED
                )
                if (added && !appVisible && canNotify(app)) {
                    app.getSystemService(NotificationManager::class.java)
                        .notify(received.id.hashCode(), Notifications.message(app, next.displayName, received.text))
                }
                dispatch()
            },
            onSent = { id ->
                messageStore!!.markStatus(id, MessageStatus.SENT)
                dispatch()
            },
            onReceipt = receipt@{ id, receiptStatus ->
                val store = messageStore!!
                val message = store.forContact(next.peerId).firstOrNull { it.id == id && it.mine }
                    ?: return@receipt
                store.markStatus(message.id, receiptStatus)
                dispatch()
            },
            onPresence = { active, _ ->
                remoteActive = active
                messageStore!!.markOnline(next.peerId)
                dispatch()
            },
            onSyncRequested = { afterTimestamp ->
                // ponytail: sync por cauda; usar manifesto de IDs se buracos intermediarios forem relevantes.
                messageStore!!.forContact(next.peerId)
                    .filter { !it.system && it.attachment == null && it.timestamp > afterTimestamp }
                    .forEach { messenger?.sendSync(it) }
                messenger?.finishSync()
            },
            onSyncedMessage = { message ->
                if (messageStore!!.add(message)) syncedMessages++
            },
            onSyncFinished = {
                if (syncedMessages > 0) {
                    val now = System.currentTimeMillis()
                    messageStore!!.addSystem(
                        next.peerId,
                        "Sincronizadas $syncedMessages mensagens as " +
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                        now
                    )
                    syncedMessages = 0
                    markRead()
                    dispatch()
                }
            },
            onFileOffer = { message ->
                if (messageStore!!.add(message)) newFiles += message.id
                dispatch()
            },
            onFileRequested = request@{ id, offset ->
                val message = messageStore!!.forContact(next.peerId)
                    .firstOrNull { it.id == id && it.mine && it.attachment?.complete == true }
                if (message != null) {
                    messenger?.sendFileChunk(message, offset)
                    return@request
                }
                val photo = profilePhotos.local().takeIf { profilePhotos.isSharing() } ?: return@request
                messenger?.sendProfileChunk(id, photo, offset)
            },
            onFileComplete = complete@{ id, attachment ->
                val store = messageStore!!
                store.forContact(next.peerId).firstOrNull { it.id == id } ?: return@complete
                store.updateAttachment(id, attachment)
                store.markStatus(id, MessageStatus.DELIVERED)
                messenger?.sendReceipt(id, MessageStatus.DELIVERED)
                if (newFiles.remove(id) && !appVisible && canNotify(app)) {
                    app.getSystemService(NotificationManager::class.java)
                        .notify(id.hashCode(), Notifications.message(app, next.displayName, "Arquivo: ${attachment.name}"))
                }
                markRead()
                dispatch()
            },
            onProfilePhoto = { photo ->
                profilePhotos.setRemote(next.peerId, photo)
                dispatch()
            },
            isAppVisible = { appVisible }
        )
        dispatch()
    }

    @Synchronized
    private fun onState(newStatus: String, isConnected: Boolean) {
        val current = contact ?: return
        val store = messageStore ?: return
        if (connected != isConnected) {
            val now = System.currentTimeMillis()
            store.addSystem(
                current.peerId,
                (if (isConnected) "Conectado as " else "Desconectado as ") +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                now
            )
            if (!isConnected && remoteActive) store.markOnline(current.peerId, now)
        }
        status = newStatus
        connected = isConnected
        if (!connected) remoteActive = false
        if (connected) {
            messenger?.sendPresence(appVisible)
            val profilePhotos = context?.let(::ProfilePhotoStore)
            messenger?.sendProfilePhoto(profilePhotos?.local().takeIf { profilePhotos?.isSharing() == true })
            val latestTimestamp = store.forContact(current.peerId)
                .filter { !it.system }
                .maxOfOrNull { it.timestamp } ?: 0
            messenger?.requestSync(latestTimestamp)
            store.forContact(current.peerId)
                .filter { it.mine && it.status.ordinal < MessageStatus.DELIVERED.ordinal }
                .forEach {
                    if (it.attachment == null) messenger?.send(it) else messenger?.sendFileOffer(it)
                }
            store.forContact(current.peerId)
                .filter { !it.mine && !it.system && it.status.ordinal >= MessageStatus.DELIVERED.ordinal }
                .forEach { messenger?.sendReceipt(it.id, it.status) }
        }
        dispatch()
    }

    private fun dispatch() {
        val value = snapshot()
        listeners.forEach { it.onUpdate(value) }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
