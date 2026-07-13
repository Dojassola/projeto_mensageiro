package com.mensageiro.core.crypto

import android.content.Context
import org.json.JSONObject

data class StoredAttachment(
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
    val localPath: String,
    val complete: Boolean
)

data class StoredMessage(
    val id: String,
    val contactPeerId: String,
    val mine: Boolean,
    val status: MessageStatus,
    val system: Boolean,
    val text: String,
    val timestamp: Long,
    val attachment: StoredAttachment? = null,
    val replyToId: String? = null,
    val editedAt: Long = 0
)

data class MessageDeletion(val id: String, val contactPeerId: String, val deletedAt: Long)

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ
}

class MessageStore(context: Context, private val identityStore: IdentityStore) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("messages", Context.MODE_PRIVATE)
    private val presence = this.context.getSharedPreferences("presence", Context.MODE_PRIVATE)
    private val deletedPrefs = this.context.getSharedPreferences("deleted_messages", Context.MODE_PRIVATE)
    private val remoteDeletionPrefs = this.context.getSharedPreferences("remote_deletions", Context.MODE_PRIVATE)
    private val messages = LinkedHashMap<String, StoredMessage>().apply {
        prefs.all.forEach { (id, value) ->
            runCatching { decode(id, identityStore.unprotect(value as String)) }
                .onSuccess { put(id, it) }
        }
    }

    @Synchronized
    fun forContact(peerId: String): List<StoredMessage> =
        messages.values.filter { it.contactPeerId == peerId }.sortedBy { it.timestamp }

    @Synchronized
    fun all(): List<StoredMessage> = messages.values.sortedBy { it.timestamp }

    @Synchronized
    fun add(message: StoredMessage): Boolean {
        if (messages.containsKey(message.id) || deletedPrefs.contains(message.id)) return false
        save(message)
        return true
    }

    @Synchronized
    fun save(message: StoredMessage) {
        messages[message.id] = message
        prefs.edit().putString(message.id, identityStore.protect(encode(message))).apply()
        AutomaticBackup.request(context)
    }

    @Synchronized
    fun markStatus(id: String, status: MessageStatus) {
        val message = messages[id] ?: return
        if (status.ordinal > message.status.ordinal) save(message.copy(status = status))
    }

    @Synchronized
    fun updateAttachment(id: String, attachment: StoredAttachment) {
        val message = messages[id] ?: return
        save(message.copy(attachment = attachment))
    }

    @Synchronized
    fun edit(id: String, text: String, editedAt: Long): StoredMessage? {
        val message = messages[id] ?: return null
        val body = text.trim()
        require(!message.system && message.attachment == null && body.isNotEmpty() && body.length <= 4_000)
        require(editedAt > message.editedAt)
        return message.copy(text = body, editedAt = editedAt).also(::save)
    }

    @Synchronized
    fun mergeSynced(message: StoredMessage): Boolean {
        val current = messages[message.id] ?: return add(message)
        val status = if (message.status.ordinal > current.status.ordinal) message.status else current.status
        val newerEdit = message.editedAt > current.editedAt
        if (!newerEdit && status == current.status) return false
        save(
            current.copy(
                status = status,
                text = if (newerEdit) message.text else current.text,
                replyToId = if (newerEdit) message.replyToId else current.replyToId,
                editedAt = maxOf(current.editedAt, message.editedAt)
            )
        )
        return true
    }

    @Synchronized
    fun delete(
        id: String,
        forEveryone: Boolean = false,
        deletedAt: Long = System.currentTimeMillis()
    ): StoredMessage? {
        val message = messages.remove(id)
        if (message == null && deletedPrefs.getLong(id, 0) >= deletedAt) return null
        prefs.edit().remove(id).apply()
        deletedPrefs.edit().putLong(id, deletedAt).apply()
        if (forEveryone && message?.mine == true) {
            remoteDeletionPrefs.edit().putString(
                id,
                identityStore.protect("${message.contactPeerId}\n$deletedAt")
            ).apply()
        }
        AutomaticBackup.request(context)
        return message
    }

    fun deletedIds(): Set<String> = deletedPrefs.all.keys

    fun remoteDeletions(peerId: String): List<MessageDeletion> = remoteDeletionPrefs.all.mapNotNull { (id, value) ->
        runCatching {
            val parts = identityStore.unprotect(value as String).split('\n', limit = 2)
            MessageDeletion(id, parts[0], parts[1].toLong())
        }.getOrNull()?.takeIf { it.contactPeerId == peerId }
    }

    @Synchronized
    fun replaceDeleted(ids: Set<String>) {
        val editor = deletedPrefs.edit().clear()
        ids.forEach { editor.putLong(it, System.currentTimeMillis()) }
        editor.apply()
        val messageEditor = prefs.edit()
        ids.forEach {
            messages.remove(it)
            messageEditor.remove(it)
        }
        messageEditor.apply()
    }

    fun replaceRemoteDeletions(deletions: List<MessageDeletion>) {
        val editor = remoteDeletionPrefs.edit().clear()
        deletions.forEach {
            editor.putString(it.id, identityStore.protect("${it.contactPeerId}\n${it.deletedAt}"))
        }
        editor.apply()
    }

    fun addSystem(peerId: String, text: String, timestamp: Long = System.currentTimeMillis()) {
        add(StoredMessage("system-$timestamp-${text.hashCode()}", peerId, false, MessageStatus.READ, true, text, timestamp))
    }

    @Synchronized
    fun replaceAll(messages: List<StoredMessage>) {
        val editor = prefs.edit().clear()
        messages.forEach { editor.putString(it.id, identityStore.protect(encode(it))) }
        editor.apply()
        this.messages.clear()
        messages.associateByTo(this.messages) { it.id }
        AutomaticBackup.request(context)
    }

    fun lastOnline(peerId: String): Long = presence.getLong(peerId, 0)

    fun markOnline(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        presence.edit().putLong(peerId, timestamp).apply()
    }

    private fun encode(message: StoredMessage): String {
        if (message.replyToId != null || message.editedAt > 0) {
            return "v4\n" + JSONObject()
                .put("contactPeerId", message.contactPeerId)
                .put("mine", message.mine)
                .put("status", message.status.name)
                .put("system", message.system)
                .put("timestamp", message.timestamp)
                .put("text", message.text)
                .put("replyToId", message.replyToId)
                .put("editedAt", message.editedAt)
                .apply {
                    message.attachment?.let { attachment ->
                        put("attachment", JSONObject()
                            .put("name", attachment.name)
                            .put("mimeType", attachment.mimeType)
                            .put("size", attachment.size)
                            .put("sha256", attachment.sha256)
                            .put("localPath", attachment.localPath)
                            .put("complete", attachment.complete))
                    }
                }
                .toString()
        }
        val attachment = message.attachment ?: return (
            "v2\n${message.contactPeerId}\n${message.mine}\n${message.status.name}\n" +
                "${message.system}\n${message.timestamp}\n${message.text}"
        )
        return "v3\n" + JSONObject()
            .put("contactPeerId", message.contactPeerId)
            .put("mine", message.mine)
            .put("status", message.status.name)
            .put("system", message.system)
            .put("timestamp", message.timestamp)
            .put("text", message.text)
            .put("attachment", JSONObject()
                .put("name", attachment.name)
                .put("mimeType", attachment.mimeType)
                .put("size", attachment.size)
                .put("sha256", attachment.sha256)
                .put("localPath", attachment.localPath)
                .put("complete", attachment.complete))
            .toString()
    }

    private fun decode(id: String, text: String): StoredMessage {
        if (text.startsWith("v4\n")) {
            val value = JSONObject(text.substring(3))
            val attachment = value.optJSONObject("attachment")?.let {
                StoredAttachment(
                    it.getString("name"),
                    it.getString("mimeType"),
                    it.getLong("size"),
                    it.getString("sha256"),
                    it.getString("localPath"),
                    it.getBoolean("complete")
                )
            }
            return StoredMessage(
                id,
                value.getString("contactPeerId"),
                value.getBoolean("mine"),
                MessageStatus.valueOf(value.getString("status")),
                value.getBoolean("system"),
                value.getString("text"),
                value.getLong("timestamp"),
                attachment,
                value.optString("replyToId").takeIf { it.isNotBlank() && it != "null" },
                value.optLong("editedAt")
            )
        }
        if (text.startsWith("v3\n")) {
            val value = JSONObject(text.substring(3))
            val attachment = value.getJSONObject("attachment")
            return StoredMessage(
                id,
                value.getString("contactPeerId"),
                value.getBoolean("mine"),
                MessageStatus.valueOf(value.getString("status")),
                value.getBoolean("system"),
                value.getString("text"),
                value.getLong("timestamp"),
                StoredAttachment(
                    attachment.getString("name"),
                    attachment.getString("mimeType"),
                    attachment.getLong("size"),
                    attachment.getString("sha256"),
                    attachment.getString("localPath"),
                    attachment.getBoolean("complete")
                )
            )
        }
        if (text.startsWith("v2\n")) {
            val parts = text.split('\n', limit = 7)
            require(parts.size == 7)
            return StoredMessage(
                id,
                parts[1],
                parts[2].toBooleanStrict(),
                MessageStatus.valueOf(parts[3]),
                parts[4].toBooleanStrict(),
                parts[6],
                parts[5].toLong()
            )
        }

        val parts = text.split('\n', limit = 6)
        if (parts.size == 4) {
            val mine = parts[1].toBooleanStrict()
            return StoredMessage(
                id, parts[0], mine, if (mine) MessageStatus.SENT else MessageStatus.DELIVERED,
                false, parts[3], parts[2].toLong()
            )
        }
        if (parts.size == 5) {
            val mine = parts[1].toBooleanStrict()
            val pending = parts[2].toBooleanStrict()
            return StoredMessage(
                id, parts[0], mine, legacyStatus(mine, pending), false, parts[4], parts[3].toLong()
            )
        }
        require(parts.size == 6)
        val mine = parts[1].toBooleanStrict()
        val pending = parts[2].toBooleanStrict()
        val system = parts[3].toBooleanStrict()
        return StoredMessage(
            id,
            parts[0],
            mine,
            if (system) MessageStatus.READ else legacyStatus(mine, pending),
            system,
            parts[5],
            parts[4].toLong()
        )
    }

    private fun legacyStatus(mine: Boolean, pending: Boolean): MessageStatus = when {
        pending -> MessageStatus.PENDING
        mine -> MessageStatus.SENT
        else -> MessageStatus.DELIVERED
    }
}
