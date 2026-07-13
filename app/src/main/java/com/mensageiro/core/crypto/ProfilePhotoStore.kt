package com.mensageiro.core.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ProfilePhotoBackup(val sharing: Boolean, val bytes: ByteArray?, val sha256: String?)

class ProfilePhotoStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("profile_photos", Context.MODE_PRIVATE)
    private val directory = File(this.context.filesDir, "profiles").apply { mkdirs() }

    fun local(): StoredAttachment? = decode(prefs.getString(LocalPhoto, null))
        ?.takeIf { File(it.localPath).isFile }

    fun remote(peerId: String): StoredAttachment? = decode(prefs.getString("remote-$peerId", null))
        ?.takeIf { File(it.localPath).isFile }

    fun isSharing(): Boolean = prefs.getBoolean("sharing", true)

    fun setSharing(enabled: Boolean) {
        if (isSharing() == enabled) return
        prefs.edit().putBoolean("sharing", enabled).apply()
        AutomaticBackup.request(context)
    }

    fun import(uri: Uri): StoredAttachment {
        val source = decodeImage(uri)
        val result = Bitmap.createBitmap(PhotoSize, PhotoSize, Bitmap.Config.ARGB_8888)
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        Canvas(result).drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, PhotoSize, PhotoSize),
            null
        )
        if (source !== result) source.recycle()

        val target = File(directory, "me.webp")
        val temporary = File(directory, "me.tmp")
        try {
            temporary.outputStream().buffered().use { output ->
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                check(result.compress(format, 85, output)) { "Nao foi possivel salvar a foto." }
            }
            target.delete()
            check(temporary.renameTo(target)) { "Nao foi possivel concluir a foto." }
        } finally {
            result.recycle()
            temporary.delete()
        }

        val attachment = StoredAttachment(
            "perfil.webp",
            "image/webp",
            target.length(),
            sha256(target),
            target.absolutePath,
            true
        )
        prefs.edit().putString(LocalPhoto, encode(attachment)).apply()
        AutomaticBackup.request(context)
        return attachment
    }

    fun clearLocal() {
        if (local() == null) return
        local()?.localPath?.let { File(it).delete() }
        prefs.edit().remove(LocalPhoto).apply()
        AutomaticBackup.request(context)
    }

    fun backup(): JSONObject = JSONObject().put("sharing", isSharing()).apply {
        local()?.let { photo ->
            val bytes = File(photo.localPath).readBytes()
            require(bytes.size <= MaxBackupSize) { "Foto de perfil muito grande." }
            put("sha256", photo.sha256)
            put("data", CryptoText.base64Url(bytes))
        }
    }

    fun validateBackup(value: JSONObject?): ProfilePhotoBackup {
        if (value == null) return ProfilePhotoBackup(true, null, null)
        val encoded = value.optString("data")
        if (encoded.isBlank()) return ProfilePhotoBackup(value.optBoolean("sharing", true), null, null)
        val bytes = CryptoText.fromBase64Url(encoded)
        require(bytes.size in 1..MaxBackupSize) { "Foto de perfil invalida no backup." }
        val hash = value.getString("sha256")
        require(hash.matches(Regex("[0-9a-f]{64}")) && sha256(bytes) == hash) {
            "Foto de perfil invalida no backup."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..PhotoSize && bounds.outHeight in 1..PhotoSize) {
            "Foto de perfil invalida no backup."
        }
        return ProfilePhotoBackup(value.optBoolean("sharing", true), bytes, hash)
    }

    fun restoreBackup(backup: ProfilePhotoBackup) {
        val bytes = backup.bytes
        if (bytes == null) {
            local()?.localPath?.let { File(it).delete() }
            prefs.edit().remove(LocalPhoto).putBoolean("sharing", backup.sharing).apply()
            return
        }
        val target = File(directory, "me.webp")
        val temporary = File(directory, "me.tmp")
        try {
            temporary.writeBytes(bytes)
            target.delete()
            check(temporary.renameTo(target)) { "Nao foi possivel restaurar a foto." }
        } finally {
            temporary.delete()
        }
        val attachment = StoredAttachment(
            "perfil.webp", "image/webp", target.length(), requireNotNull(backup.sha256),
            target.absolutePath, true
        )
        prefs.edit()
            .putString(LocalPhoto, encode(attachment))
            .putBoolean("sharing", backup.sharing)
            .apply()
    }

    fun setRemote(peerId: String, attachment: StoredAttachment?) {
        val key = "remote-$peerId"
        if (attachment == null) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, encode(attachment)).apply()
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem invalida." }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PhotoSize) sample *= 2

        return if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            requireNotNull(context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }) { "Imagem invalida." }
        }
    }

    private fun encode(attachment: StoredAttachment): String = JSONObject()
        .put("name", attachment.name)
        .put("mimeType", attachment.mimeType)
        .put("size", attachment.size)
        .put("sha256", attachment.sha256)
        .put("localPath", attachment.localPath)
        .toString()

    private fun decode(value: String?): StoredAttachment? = value?.let {
        runCatching {
            val json = JSONObject(it)
            StoredAttachment(
                json.getString("name"),
                json.getString("mimeType"),
                json.getLong("size"),
                json.getString("sha256"),
                json.getString("localPath"),
                true
            )
        }.getOrNull()
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val LocalPhoto = "local"
        private const val PhotoSize = 512
        private const val MaxBackupSize = 2 * 1024 * 1024

        fun fileId(sha256: String) = "profile-$sha256"
    }
}
