package com.mensageiro.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import com.mensageiro.core.MessagingRuntime
import com.mensageiro.core.MessagingSnapshot
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.StoredMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ChatUiState(
    val snapshot: MessagingSnapshot,
    val replyToId: String? = null,
    val editingId: String? = null,
    val message: String = ""
)

internal class ChatViewModel(
    context: Context,
    private val peerId: String
) : ViewModel() {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(ChatUiState(MessagingRuntime.snapshot(peerId)))
    private val listener = MessagingRuntime.Listener { refresh() }
    private var attached = false

    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    fun attach() {
        if (attached) return
        attached = true
        MessagingRuntime.selectContact(app, peerId)
        MessagingRuntime.addListener(listener)
        MessagingRuntime.setConversationVisible(peerId, true)
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        MessagingRuntime.setConversationVisible(peerId, false)
        MessagingRuntime.removeListener(listener)
    }

    fun markRead() = MessagingRuntime.markRead(peerId)

    fun reconnect() = MessagingRuntime.reconnect(peerId)

    fun startReply(message: StoredMessage) {
        if (message.contactPeerId != peerId || message.system) return
        mutableState.update { it.copy(replyToId = message.id, editingId = null) }
    }

    fun startEditing(message: StoredMessage): String? {
        if (message.contactPeerId != peerId || !message.mine || message.system || message.attachment != null) return null
        mutableState.update { it.copy(replyToId = null, editingId = message.id) }
        return message.text
    }

    fun cancelComposer() {
        mutableState.update { it.copy(replyToId = null, editingId = null) }
    }

    fun submit(text: String): Boolean {
        if (text.isBlank()) return false
        val current = mutableState.value
        val error = current.editingId?.let { MessagingRuntime.editMessage(peerId, it, text) }.orEmpty()
        if (current.editingId == null) MessagingRuntime.send(peerId, text, current.replyToId)
        mutableState.update {
            if (error.isBlank()) it.copy(replyToId = null, editingId = null, message = "")
            else it.copy(message = error)
        }
        return error.isBlank()
    }

    fun sendFile(messageId: String, attachment: StoredAttachment) =
        MessagingRuntime.sendFile(peerId, messageId, attachment)

    fun deleteLocal(messageId: String) {
        MessagingRuntime.deleteMessage(peerId, messageId)
        clearTarget(messageId)
    }

    fun deleteForEveryone(messageId: String): Boolean {
        val error = MessagingRuntime.deleteMessageForEveryone(peerId, messageId)
        setMessage(error)
        if (error.isBlank()) clearTarget(messageId)
        return error.isBlank()
    }

    fun startCall() = setMessage(MessagingRuntime.startCall(peerId))

    fun acceptCall() = setMessage(MessagingRuntime.acceptCall(peerId))

    fun rejectCall() = MessagingRuntime.rejectCall(peerId)

    fun endCall() = MessagingRuntime.endCall(peerId)

    fun setMuted(muted: Boolean) = MessagingRuntime.setCallMuted(peerId, muted)

    fun setSpeaker(enabled: Boolean) = MessagingRuntime.setSpeakerOn(peerId, enabled)

    fun setMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    private fun refresh() {
        val snapshot = MessagingRuntime.snapshot(peerId)
        mutableState.update { it.copy(snapshot = snapshot) }
    }

    private fun clearTarget(messageId: String) {
        mutableState.update {
            it.copy(
                replyToId = it.replyToId?.takeUnless { id -> id == messageId },
                editingId = it.editingId?.takeUnless { id -> id == messageId }
            )
        }
    }

    override fun onCleared() {
        detach()
    }
}
