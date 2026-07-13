package com.mensageiro.core.crypto

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PreparedAttachment(val messageId: String, val attachment: StoredAttachment)

class AttachmentStore(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "attachments").apply { mkdirs() }

    fun import(uri: Uri): PreparedAttachment {
        val id = UUID.randomUUID().toString()
        val file = finalFile(id)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Arquivo indisponivel." }
            input.use { source ->
                file.outputStream().buffered().use { target ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= MaxSize) { "O arquivo excede 25 MB." }
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        val attachment = StoredAttachment(
            displayName(uri),
            context.contentResolver.getType(uri) ?: "application/octet-stream",
            size,
            digest.digest().toHex(),
            file.absolutePath,
            true
        )
        return PreparedAttachment(id, attachment)
    }

    fun incomingOffset(id: String): Long = when {
        finalFile(id).exists() -> finalFile(id).length()
        else -> partialFile(id).takeIf(File::exists)?.length() ?: 0
    }

    fun append(id: String, offset: Long, bytes: ByteArray, expectedSize: Long): Long {
        require(expectedSize in 0..MaxSize && offset >= 0 && offset + bytes.size <= expectedSize) {
            "Bloco de arquivo invalido."
        }
        return RandomAccessFile(partialFile(id), "rw").use { file ->
            require(file.length() == offset) { "Offset de arquivo inesperado." }
            file.seek(offset)
            file.write(bytes)
            file.length()
        }
    }

    fun finish(id: String, attachment: StoredAttachment): StoredAttachment {
        val partial = partialFile(id)
        if (attachment.size == 0L && !partial.exists()) partial.createNewFile()
        require(partial.length() == attachment.size) { "Arquivo incompleto." }
        require(sha256(partial) == attachment.sha256) { "Hash do arquivo invalido." }
        val final = finalFile(id)
        final.delete()
        require(partial.renameTo(final)) { "Nao foi possivel concluir o arquivo." }
        return attachment.copy(localPath = final.absolutePath, complete = true)
    }

    fun completed(id: String, attachment: StoredAttachment): StoredAttachment? =
        finalFile(id).takeIf { it.exists() && it.length() == attachment.size }
            ?.let { attachment.copy(localPath = it.absolutePath, complete = true) }

    fun reset(id: String) {
        partialFile(id).delete()
    }

    fun delete(id: String, attachment: StoredAttachment?) {
        partialFile(id).delete()
        finalFile(id).delete()
        attachment?.localPath?.takeIf(String::isNotBlank)?.let { File(it).delete() }
    }

    fun uri(attachment: StoredAttachment): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        File(attachment.localPath)
    )

    private fun displayName(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            .orEmpty()
        return name.ifBlank { "arquivo" }.take(160)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun partialFile(id: String) = File(directory, "$id.part")
    private fun finalFile(id: String) = File(directory, "$id.bin")
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val MaxSize = 25L * 1024 * 1024
        const val ChunkSize = 24 * 1024
    }
}

data class EncryptedFileChunk(val nonce: String, val ciphertext: String)

object FileChunkCodec {
    fun encrypt(key: ByteArray, fileId: String, offset: Long, bytes: ByteArray): EncryptedFileChunk {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$fileId|$offset".toByteArray())
        return EncryptedFileChunk(
            CryptoText.base64Url(nonce),
            CryptoText.base64Url(cipher.doFinal(bytes))
        )
    }

    fun decrypt(
        key: ByteArray,
        fileId: String,
        offset: Long,
        encrypted: EncryptedFileChunk
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, CryptoText.fromBase64Url(encrypted.nonce))
        )
        cipher.updateAAD("$fileId|$offset".toByteArray())
        return cipher.doFinal(CryptoText.fromBase64Url(encrypted.ciphertext))
    }
}
