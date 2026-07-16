package com.mensageiro.core.crypto

import android.content.Context
import com.mensageiro.data.local.MessageDatabase
import com.mensageiro.data.local.MessageRow
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

data class MessageCursor(val timestamp: Long, val id: String)

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
    private val database = MessageDatabase(this.context).also { database ->
        database.importLegacy {
            prefs.all.mapNotNull { (id, value) ->
                runCatching {
                    val payload = value as String
                    val message = decode(id, identityStore.unprotect(payload))
                    MessageRow(id, message.contactPeerId, message.timestamp, payload)
                }.getOrNull()
            }
        }
    }
    private val messages = LinkedHashMap<String, StoredMessage>().apply {
        database.loadAll().forEach { row ->
            runCatching { decode(row.id, identityStore.unprotect(row.payload)) }
                .onSuccess { put(row.id, it) }
        }
    }
    private val messageOrder = compareBy<StoredMessage>({ it.timestamp }, { it.id })
    private val conversations = messages.values.groupBy { it.contactPeerId }
        .mapValuesTo(HashMap()) { (_, values) -> values.sortedWith(messageOrder) }
    private val revisions = conversations.keys.associateWithTo(HashMap()) { 1L }

    @Synchronized
    fun forContact(peerId: String): List<StoredMessage> =
        conversations[peerId].orEmpty()

    @Synchronized
    fun lastMessage(peerId: String): StoredMessage? =
        conversations[peerId]?.lastOrNull { !it.system }

    @Synchronized
    fun revision(peerId: String): Long = revisions[peerId] ?: 0

    fun page(peerId: String, before: MessageCursor? = null, limit: Int = 50): List<StoredMessage> =
        database.page(peerId, before?.timestamp, before?.id, limit).mapNotNull { row ->
            runCatching { decode(row.id, identityStore.unprotect(row.payload)) }.getOrNull()
        }

    fun from(peerId: String, cursor: MessageCursor): List<StoredMessage> =
        database.from(peerId, cursor.timestamp, cursor.id).mapNotNull { row ->
            runCatching { decode(row.id, identityStore.unprotect(row.payload)) }.getOrNull()
        }

    @Synchronized
    fun all(): List<StoredMessage> = messages.values.sortedBy { it.timestamp }

    @Synchronized
    fun get(id: String): StoredMessage? = messages[id]

    @Synchronized
    fun add(message: StoredMessage): Boolean {
        if (messages.containsKey(message.id) || deletedPrefs.contains(message.id)) return false
        save(message)
        return true
    }

    @Synchronized
    fun save(message: StoredMessage) {
        database.upsert(row(message))
        messages.put(message.id, message)?.takeIf { it.contactPeerId != message.contactPeerId }
            ?.let(::removeFromConversation)
        upsertConversation(message)
        AutomaticBackup.request(context)
    }

    @Synchronized
    fun markStatus(id: String, status: MessageStatus) {
        val message = get(id) ?: return
        if (status.ordinal > message.status.ordinal) save(message.copy(status = status))
    }

    @Synchronized
    fun markConversationRead(peerId: String): List<StoredMessage> {
        val current = conversations[peerId].orEmpty()
        val changed = current.filter {
            !it.mine && !it.system && it.status != MessageStatus.READ &&
                (it.attachment == null || it.attachment.complete)
        }
        if (changed.isEmpty()) return emptyList()
        val changedIds = changed.mapTo(HashSet()) { it.id }
        val updated = current.map { message ->
            if (message.id !in changedIds) message else message.copy(status = MessageStatus.READ).also {
                messages[it.id] = it
            }
        }
        database.upsert(updated.filter { it.id in changedIds }.map(::row))
        conversations[peerId] = updated
        touch(peerId)
        AutomaticBackup.request(context)
        return updated.filter { it.id in changedIds }
    }

    @Synchronized
    fun updateAttachment(id: String, attachment: StoredAttachment) {
        val message = get(id) ?: return
        save(message.copy(attachment = attachment))
    }

    @Synchronized
    fun edit(id: String, text: String, editedAt: Long): StoredMessage? {
        val message = get(id) ?: return null
        val body = text.trim()
        require(!message.system && message.attachment == null && body.isNotEmpty() && body.length <= 4_000)
        require(editedAt > message.editedAt)
        return message.copy(text = body, editedAt = editedAt).also(::save)
    }

    @Synchronized
    fun mergeSynced(message: StoredMessage): Boolean {
        val current = get(message.id) ?: return add(message)
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
        message?.let(::removeFromConversation)
        if (message == null && deletedPrefs.getLong(id, 0) >= deletedAt) return null
        database.delete(id)
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

    @Synchronized
    fun deleteConversation(peerId: String): List<StoredMessage> {
        val removed = messages.values.filter { it.contactPeerId == peerId }
        if (removed.isEmpty()) return emptyList()
        val deletedEditor = deletedPrefs.edit()
        val deletedAt = System.currentTimeMillis()
        removed.forEach {
            messages.remove(it.id)
            deletedEditor.putLong(it.id, deletedAt)
        }
        database.deleteConversation(peerId)
        deletedEditor.apply()
        conversations.remove(peerId)
        touch(peerId)
        presence.edit().remove(peerId).apply()
        remoteDeletions(peerId).takeIf { it.isNotEmpty() }?.let { deletions ->
            val editor = remoteDeletionPrefs.edit()
            deletions.forEach { editor.remove(it.id) }
            editor.apply()
        }
        AutomaticBackup.request(context)
        return removed
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
        database.delete(ids)
        ids.forEach {
            messages.remove(it)?.let(::removeFromConversation)
        }
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
        database.replaceAll(messages.map(::row))
        this.messages.clear()
        messages.associateByTo(this.messages) { it.id }
        conversations.clear()
        messages.groupBy { it.contactPeerId }
            .mapValuesTo(conversations) { (_, values) -> values.sortedWith(messageOrder) }
        revisions.clear()
        messages.mapTo(HashSet()) { it.contactPeerId }.forEach(::touch)
        AutomaticBackup.request(context)
    }

    fun lastOnline(peerId: String): Long = presence.getLong(peerId, 0)

    fun markOnline(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        presence.edit().putLong(peerId, timestamp).apply()
    }

    private fun upsertConversation(message: StoredMessage) {
        val current = conversations[message.contactPeerId].orEmpty()
        val updated = current.toMutableList()
        val existing = updated.indexOfFirst { it.id == message.id }
        if (existing >= 0) updated.removeAt(existing)
        val index = updated.binarySearch(message, messageOrder).let { if (it >= 0) it else -it - 1 }
        updated.add(index, message)
        conversations[message.contactPeerId] = updated
        touch(message.contactPeerId)
    }

    private fun removeFromConversation(message: StoredMessage) {
        val current = conversations[message.contactPeerId] ?: return
        val updated = current.filterNot { it.id == message.id }
        if (updated.isEmpty()) conversations.remove(message.contactPeerId)
        else conversations[message.contactPeerId] = updated
        touch(message.contactPeerId)
    }

    private fun touch(peerId: String) {
        revisions[peerId] = (revisions[peerId] ?: 0) + 1
    }

    private fun row(message: StoredMessage): MessageRow = MessageRow(
        message.id,
        message.contactPeerId,
        message.timestamp,
        identityStore.protect(encode(message))
    )

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
