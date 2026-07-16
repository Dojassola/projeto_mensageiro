package com.mensageiro.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mensageiro.core.MessagingRuntime
import com.mensageiro.core.MessagingSnapshot
import com.mensageiro.core.crypto.MessageCursor
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ChatUiState(
    val snapshot: MessagingSnapshot,
    val replyToId: String? = null,
    val editingId: String? = null,
    val message: String = "",
    val loadingOlder: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val navigateToMessageId: String? = null
)

internal class ChatViewModel(
    context: Context,
    private val peerId: String
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState(MessagingRuntime.snapshot(peerId, includeMessages = false)))
    private val listener = MessagingRuntime.Listener(::refresh)
    private var attached = false
    @Volatile private var initialLoaded = false
    @Volatile private var messageRevision = -1L
    private var refreshJob: Job? = null

    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    fun attach() {
        if (attached) return
        attached = true
        initialLoaded = false
        messageRevision = -1L
        MessagingRuntime.addListener(listener)
        MessagingRuntime.setConversationVisible(peerId, true)
        loadInitial()
    }

    fun detach() {
        if (!attached) return
        attached = false
        refreshJob?.cancel()
        MessagingRuntime.setConversationVisible(peerId, false)
        MessagingRuntime.removeListener(listener)
    }

    fun markRead() = MessagingRuntime.markRead(peerId)

    fun reconnect() = MessagingRuntime.reconnect(peerId)

    fun loadOlder() {
        val current = mutableState.value
        val oldest = current.snapshot.messages.firstOrNull() ?: return
        if (current.loadingOlder || !current.hasOlderMessages) return
        mutableState.update { it.copy(loadingOlder = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val page = MessagingRuntime.messagePage(
                peerId,
                MessageCursor(oldest.timestamp, oldest.id),
                PageSize + 1
            )
            val older = page.take(PageSize).asReversed()
            mutableState.update { state ->
                val messages = (older + state.snapshot.messages).distinctBy { it.id }
                state.copy(
                    snapshot = state.snapshot.copy(messages = messages),
                    loadingOlder = false,
                    hasOlderMessages = page.size > PageSize
                )
            }
        }
    }

    fun revealMessage(messageId: String) {
        if (!MessagingRuntime.messageExists(peerId, messageId)) {
            setMessage("Mensagem removida ou indisponivel.")
            return
        }
        viewModelScope.launch {
            while (attached && mutableState.value.snapshot.messages.none { it.id == messageId } &&
                mutableState.value.hasOlderMessages
            ) {
                loadOlder()
                do delay(16) while (attached && mutableState.value.loadingOlder)
            }
            mutableState.update { state ->
                if (state.snapshot.messages.any { it.id == messageId }) {
                    state.copy(navigateToMessageId = messageId)
                } else {
                    state.copy(message = "Mensagem removida ou indisponivel.")
                }
            }
        }
    }

    fun navigationHandled(messageId: String) {
        mutableState.update {
            if (it.navigateToMessageId == messageId) it.copy(navigateToMessageId = null) else it
        }
    }

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
        if (current.editingId == null) {
            mutableState.update { it.copy(replyToId = null, message = "") }
            viewModelScope.launch(Dispatchers.IO) {
                MessagingRuntime.send(peerId, text, current.replyToId)
            }
            return true
        }
        val error = MessagingRuntime.editMessage(peerId, current.editingId, text)
        mutableState.update {
            if (error.isBlank()) it.copy(replyToId = null, editingId = null, message = "")
            else it.copy(message = error)
        }
        return error.isBlank()
    }

    fun sendFile(messageId: String, attachment: StoredAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            MessagingRuntime.sendFile(peerId, messageId, attachment)
        }
    }

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

    private fun refresh(shell: MessagingSnapshot) {
        if (!attached) return
        if (!initialLoaded) {
            mutableState.update { state -> state.copy(snapshot = shell.copy(messages = state.snapshot.messages)) }
            return
        }
        val revision = MessagingRuntime.messageRevision(peerId)
        if (revision == messageRevision) {
            mutableState.update { state -> state.copy(snapshot = shell.copy(messages = state.snapshot.messages)) }
            return
        }
        messageRevision = revision
        val loaded = mutableState.value.snapshot.messages
        val oldest = loaded.firstOrNull()
        if (oldest == null) {
            loadInitial()
            return
        }
        val cursor = MessageCursor(oldest.timestamp, oldest.id)
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val updatedRange = MessagingRuntime.messagesFrom(peerId, cursor)
            if (!isActive) return@launch
            mutableState.update { state ->
                val olderLoadedDuringRefresh = state.snapshot.messages.filter {
                    it.timestamp < cursor.timestamp || (it.timestamp == cursor.timestamp && it.id < cursor.id)
                }
                state.copy(snapshot = shell.copy(messages = olderLoadedDuringRefresh + updatedRange))
            }
        }
    }

    private fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            val revision = MessagingRuntime.messageRevision(peerId)
            val page = MessagingRuntime.messagePage(peerId, null, PageSize + 1)
            val shell = MessagingRuntime.snapshot(peerId, includeMessages = false)
            messageRevision = revision
            initialLoaded = true
            mutableState.update {
                it.copy(
                    snapshot = shell.copy(messages = page.take(PageSize).asReversed()),
                    hasOlderMessages = page.size > PageSize
                )
            }
            refresh(shell)
        }
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

    private companion object {
        const val PageSize = 50
    }
}
