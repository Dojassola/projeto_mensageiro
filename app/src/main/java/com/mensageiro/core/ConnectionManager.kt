package com.mensageiro.core

import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.core.webrtc.CallManager
import com.mensageiro.core.webrtc.P2pMessenger

internal class ManagedPeerSession(var contact: VerifiedContact) {
    var messenger: P2pMessenger? = null
    var callManager: CallManager? = null
    var status = "Procurando ${contact.displayName}..."
    var connected = false
    var remoteActive = false
    var syncedMessages = 0
    val newFiles = mutableSetOf<String>()

    fun close() {
        callManager?.disconnect()
        messenger?.close()
    }
}

internal class ConnectionManager {
    private val sessions = LinkedHashMap<String, ManagedPeerSession>()

    val keys: Set<String> get() = sessions.keys
    val values: Collection<ManagedPeerSession> get() = sessions.values
    val size: Int get() = sessions.size

    operator fun contains(peerId: String?): Boolean = peerId != null && peerId in sessions
    operator fun get(peerId: String?): ManagedPeerSession? = peerId?.let(sessions::get)

    operator fun set(peerId: String, session: ManagedPeerSession) {
        require(peerId == session.contact.peerId)
        sessions.put(peerId, session)?.close()
    }

    fun isEmpty(): Boolean = sessions.isEmpty()

    fun remove(peerId: String) {
        sessions.remove(peerId)?.close()
    }

    fun closeAll() {
        sessions.values.forEach(ManagedPeerSession::close)
        sessions.clear()
    }
}
