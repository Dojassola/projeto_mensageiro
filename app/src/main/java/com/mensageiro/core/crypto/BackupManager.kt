package com.mensageiro.core.crypto

import android.content.Context
import android.net.Uri
import com.mensageiro.app.MensageiroApplication
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class RestoreResult(val contacts: Int, val messages: Int)

class BackupManager(
    context: Context,
    private val identityStore: IdentityStore = IdentityStore(context),
    private val contactStore: ContactStore = ContactStore(context),
    private val messageStore: MessageStore = MessageStore(context, identityStore),
    private val profilePhotoStore: ProfilePhotoStore = ProfilePhotoStore(context)
) {

    fun export(password: String): String {
        validatePassword(password)
        val identity = identityStore.exportBackup()
        val data = JSONObject()
            .put("version", DataVersion)
            .put("identity", JSONObject()
                .put("peerId", identity.peerId)
                .put("publicKey", identity.publicKey)
                .put("privateKey", identity.privateKey)
                .put("encryptionPublicKey", identity.encryptionPublicKey)
                .put("encryptionPrivateKey", identity.encryptionPrivateKey)
                .put("displayName", identity.displayName))
            .put("profile", profilePhotoStore.backup())
            .put("contacts", JSONArray().apply {
                contactStore.all().forEach { contact ->
                    put(JSONObject()
                        .put("peerId", contact.peerId)
                        .put("publicKey", contact.publicKey)
                        .put("encryptionPublicKey", contact.encryptionPublicKey)
                        .put("displayName", contact.displayName)
                        .put("sharePayload", contact.sharePayload))
                }
            })
            .put("messages", JSONArray().apply {
                messageStore.all().forEach { message ->
                    val value = JSONObject()
                        .put("id", message.id)
                        .put("contactPeerId", message.contactPeerId)
                        .put("mine", message.mine)
                        .put("status", message.status.name)
                        .put("system", message.system)
                        .put("text", message.text)
                        .put("timestamp", message.timestamp)
                        .put("replyToId", message.replyToId)
                        .put("editedAt", message.editedAt)
                    message.attachment?.let { attachment ->
                        value.put("attachment", JSONObject()
                            .put("name", attachment.name)
                            .put("mimeType", attachment.mimeType)
                            .put("size", attachment.size)
                            .put("sha256", attachment.sha256))
                    }
                    put(value)
                }
            })
            .put("deletedMessages", JSONArray(messageStore.deletedIds().toList()))
            .put("remoteDeletions", JSONArray().apply {
                contactStore.all().forEach { contact ->
                    messageStore.remoteDeletions(contact.peerId).forEach { deletion ->
                        put(JSONObject()
                            .put("id", deletion.id)
                            .put("contactPeerId", deletion.contactPeerId)
                            .put("deletedAt", deletion.deletedAt))
                    }
                }
            })

        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, Iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(FileVersion.toByteArray())
        val encrypted = cipher.doFinal(data.toString().toByteArray())
        return JSONObject()
            .put("format", FileVersion)
            .put("iterations", Iterations)
            .put("salt", CryptoText.base64Url(salt))
            .put("iv", CryptoText.base64Url(iv))
            .put("ciphertext", CryptoText.base64Url(encrypted))
            .toString()
    }

    fun restore(file: String, password: String): RestoreResult {
        validatePassword(password)
        require(file.length <= MaxFileSize) { "Arquivo de backup muito grande." }
        val wrapper = JSONObject(file)
        require(wrapper.getString("format") == FileVersion) { "Formato de backup invalido." }
        val iterations = wrapper.getInt("iterations")
        require(iterations in 100_000..1_000_000) { "Parametros do backup invalidos." }
        val salt = CryptoText.fromBase64Url(wrapper.getString("salt"))
        val iv = CryptoText.fromBase64Url(wrapper.getString("iv"))
        require(salt.size == 16 && iv.size == 12) { "Parametros do backup invalidos." }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(FileVersion.toByteArray())
        val plaintext = cipher.doFinal(CryptoText.fromBase64Url(wrapper.getString("ciphertext"))).decodeToString()
        val data = JSONObject(plaintext)
        require(data.getInt("version") == DataVersion) { "Versao dos dados nao suportada." }

        val identityJson = data.getJSONObject("identity")
        val identityBackup = IdentityBackup(
            identityJson.getString("peerId"),
            identityJson.getString("publicKey"),
            identityJson.getString("privateKey"),
            identityJson.getString("encryptionPublicKey"),
            identityJson.getString("encryptionPrivateKey"),
            identityJson.getString("displayName")
        )
        val identity = identityStore.validateBackup(identityBackup)
        val contacts = data.getJSONArray("contacts").objects { value ->
            VerifiedContact(
                value.getString("peerId"),
                value.getString("publicKey"),
                value.getString("encryptionPublicKey"),
                value.getString("displayName"),
                "",
                value.optString("sharePayload")
            )
        }
        val validContacts = contactStore.validateForRestore(contacts, identity.publicKey)
        require(validContacts.none { it.peerId == identity.peerId }) { "O backup contem o proprio perfil como contato." }
        val contactIds = validContacts.mapTo(mutableSetOf()) { it.peerId }
        val messages = data.getJSONArray("messages").objects { value ->
            val attachment = value.optJSONObject("attachment")
            StoredMessage(
                value.getString("id"),
                value.getString("contactPeerId"),
                value.getBoolean("mine"),
                MessageStatus.valueOf(value.getString("status")),
                value.getBoolean("system"),
                value.getString("text"),
                value.getLong("timestamp"),
                attachment?.let {
                    StoredAttachment(
                        it.getString("name"),
                        it.getString("mimeType"),
                        it.getLong("size"),
                        it.getString("sha256"),
                        "",
                        false
                    )
                },
                value.optString("replyToId").takeIf { it.isNotBlank() && it != "null" },
                value.optLong("editedAt")
            )
        }
        validateMessages(messages, contactIds)
        val deletedIds = data.optJSONArray("deletedMessages")?.let { values ->
            (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
        } ?: emptySet()
        require(deletedIds.all { it.isNotBlank() && it.length <= 200 }) { "Exclusoes invalidas no backup." }
        val remoteDeletions = data.optJSONArray("remoteDeletions")?.objects { value ->
            MessageDeletion(
                value.getString("id"),
                value.getString("contactPeerId"),
                value.getLong("deletedAt")
            )
        } ?: emptyList()
        require(remoteDeletions.all {
            it.id.isNotBlank() && it.id.length <= 200 && it.contactPeerId in contactIds && it.deletedAt > 0
        }) { "Exclusoes remotas invalidas no backup." }
        val profile = profilePhotoStore.validateBackup(data.optJSONObject("profile"))

        identityStore.restoreBackup(identityBackup)
        contactStore.replaceAll(validContacts, identity.publicKey)
        messageStore.replaceAll(messages)
        messageStore.replaceDeleted(deletedIds)
        messageStore.replaceRemoteDeletions(remoteDeletions)
        profilePhotoStore.restoreBackup(profile)
        return RestoreResult(validContacts.size, messages.size)
    }

    private fun validateMessages(messages: List<StoredMessage>, contactIds: Set<String>) {
        require(messages.map { it.id }.distinct().size == messages.size) { "Mensagens duplicadas no backup." }
        messages.forEach {
            require(it.id.isNotBlank() && it.id.length <= 200) { "ID de mensagem invalido." }
            require(it.contactPeerId in contactIds) { "Mensagem vinculada a contato ausente." }
            require(it.timestamp > 0 && it.text.length <= 64_000) { "Mensagem invalida no backup." }
            require(it.editedAt >= 0 && (it.replyToId == null || it.replyToId.length <= 200)) {
                "Referencia de mensagem invalida no backup."
            }
            it.attachment?.let { attachment ->
                require(
                    attachment.name.isNotBlank() && attachment.name.length <= 160 &&
                        attachment.size in 0..AttachmentStore.MaxSize &&
                        attachment.sha256.matches(Regex("[0-9a-f]{64}"))
                ) { "Anexo invalido no backup." }
            }
        }
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun validatePassword(password: String) {
        require(password.length >= 6) { "Use uma senha com pelo menos 6 caracteres." }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }

    private companion object {
        const val FileVersion = "mensageiro-backup-v1"
        const val DataVersion = 1
        const val Iterations = 210_000
        const val MaxFileSize = 20_000_000
    }
}

data class AutomaticBackupStatus(
    val enabled: Boolean,
    val lastSuccess: Long,
    val nextBackup: Long,
    val error: String,
    val destination: String
)

object AutomaticBackup {
    const val BackupInterval = 6 * 60 * 60 * 1_000L
    private const val Drive = "drive"
    private const val Local = "local"
    private const val InitialDelay = 10_000L
    private const val RetryDelay = 30 * 60 * 1_000L
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private var writing = false
    private var runAgainNow = false

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun status(context: Context): AutomaticBackupStatus = prefs(context).let {
        val destination = when (it.getString("type", null)) {
            Drive -> DriveBackupStorage.DestinationName
            Local -> LocalBackupStorage.DestinationName
            else -> it.getString("destination", null)
                ?: it.getString("uri", null)?.let { value -> destinationName(context, Uri.parse(value)) }.orEmpty()
        }
        AutomaticBackupStatus(
            it.getBoolean("enabled", false),
            it.getLong("lastSuccess", 0),
            it.getLong("nextBackup", 0),
            it.getString("error", "").orEmpty(),
            destination
        )
    }

    @Synchronized
    fun enableDrive(context: Context, password: String) = enable(context, Drive, password)

    @Synchronized
    fun enableLocal(context: Context, password: String) = enable(context, Local, password)

    private fun enable(context: Context, type: String, password: String) {
        require(password.length >= 6) { "Use uma senha com pelo menos 6 caracteres." }
        val app = context.applicationContext
        val protectedPassword = IdentityStore(app).protect(password)
        pending?.cancel(false)
        pending = null
        prefs(app).edit()
            .putBoolean("enabled", true)
            .putString("type", type)
            .putString("password", protectedPassword)
            .remove("uri")
            .remove("destination")
            .remove("lastSuccess")
            .remove("nextBackup")
            .remove("dirtyAt")
            .remove("error")
            .apply()
        markDirty(app)
        if (writing) runAgainNow = true
        else scheduleAt(app, System.currentTimeMillis() + InitialDelay)
    }

    @Synchronized
    fun disable(context: Context) {
        pending?.cancel(false)
        pending = null
        prefs(context).edit().clear().apply()
    }

    @Synchronized
    fun request(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!writing && pending?.isDone == false) return
        markDirty(app)
        if (!writing) schedule(app)
    }

    @Synchronized
    fun resume(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || prefs(app).getLong("dirtyAt", 0) == 0L) return
        schedule(app)
    }

    @Synchronized
    fun runNow(context: Context) {
        val app = context.applicationContext
        check(isEnabled(app)) { "Ative o backup automatico primeiro." }
        markDirty(app)
        if (writing) {
            runAgainNow = true
            return
        }
        pending?.cancel(false)
        pending = null
        scheduleAt(app, System.currentTimeMillis())
    }

    private fun schedule(context: Context) {
        if (pending?.isDone == false) return
        val preferences = prefs(context)
        val now = System.currentTimeMillis()
        val persisted = preferences.getLong("nextBackup", 0)
        val lastSuccess = preferences.getLong("lastSuccess", 0)
        val due = when {
            persisted > 0 -> maxOf(now, persisted)
            lastSuccess == 0L -> now + InitialDelay
            else -> maxOf(now + InitialDelay, lastSuccess + BackupInterval)
        }
        scheduleAt(context, due)
    }

    private fun scheduleAt(context: Context, due: Long) {
        prefs(context).edit().putLong("nextBackup", due).apply()
        pending = executor.schedule(
            { write(context.applicationContext) },
            (due - System.currentTimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS
        )
    }

    private fun write(context: Context) {
        synchronized(this) { writing = true }
        val preferences = prefs(context)
        val dirtyAt = preferences.getLong("dirtyAt", 0)
        val result = runCatching {
            val password = IdentityStore(context).unprotect(requireNotNull(preferences.getString("password", null)))
            val manager = (context.applicationContext as? MensageiroApplication)
                ?.container?.backupManager ?: BackupManager(context)
            val backup = manager.export(password)
            when (preferences.getString("type", null)) {
                Drive -> DriveBackupStorage.upload(DriveBackupStorage.accessToken(context), backup)
                Local -> LocalBackupStorage.write(context, backup)
                else -> {
                    val uri = Uri.parse(requireNotNull(preferences.getString("uri", null)))
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    stream.bufferedWriter().use { it.write(backup) }
                }
            }
        }
        finishWrite(context, dirtyAt, result.exceptionOrNull())
    }

    @Synchronized
    private fun finishWrite(context: Context, dirtyAt: Long, error: Throwable?) {
        writing = false
        pending = null
        val preferences = prefs(context)
        if (error != null) {
            runAgainNow = false
            preferences.edit()
                .putString("error", error.message ?: "Falha no backup automatico.")
                .apply()
            scheduleAt(context, System.currentTimeMillis() + RetryDelay)
            return
        }

        val now = System.currentTimeMillis()
        val currentDirtyAt = preferences.getLong("dirtyAt", 0)
        val editor = preferences.edit().putLong("lastSuccess", now).remove("error")
        if (currentDirtyAt <= dirtyAt) {
            editor.remove("dirtyAt").remove("nextBackup").apply()
        } else {
            editor.remove("nextBackup").apply()
            if (runAgainNow) scheduleAt(context, now) else schedule(context)
        }
        runAgainNow = false
    }

    private fun markDirty(context: Context) {
        val preferences = prefs(context)
        val value = maxOf(System.currentTimeMillis(), preferences.getLong("dirtyAt", 0) + 1)
        preferences.edit().putLong("dirtyAt", value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun destinationName(context: Context, uri: Uri): String {
        val authority = uri.authority.orEmpty()
        if (authority.contains("google.android.apps.docs.storage")) return "Google Drive"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        return provider?.loadLabel(context.packageManager)?.toString().orEmpty()
            .ifBlank { "Arquivo selecionado" }
    }
}
