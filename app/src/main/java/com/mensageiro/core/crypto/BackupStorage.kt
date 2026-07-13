package com.mensageiro.core.crypto

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DriveBackupStorage {
    const val DestinationName = "Google Drive"
    private const val FileName = "mensageiro-automatico.json"
    private const val MaxBackupBytes = 20_000_000

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER)))
        .build()

    fun accessToken(context: Context): String {
        val result = Tasks.await(
            Identity.getAuthorizationClient(context.applicationContext).authorize(authorizationRequest())
        )
        check(!result.hasResolution()) { "Reconecte o Google Drive pelo aplicativo." }
        return requireNotNull(result.accessToken) { "O Google Drive nao forneceu acesso." }
    }

    fun upload(accessToken: String, backup: String) {
        val bytes = backup.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MaxBackupBytes) { "Backup muito grande para o Google Drive." }
        val fileId = findFile(accessToken)
        val endpoint = if (fileId == null) {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=resumable"
        }
        val metadata = if (fileId == null) {
            JSONObject().put("name", FileName).put("parents", listOf("appDataFolder"))
        } else JSONObject()
        val location = request(endpoint, "POST", accessToken) {
            if (fileId != null) setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "application/json")
            setRequestProperty("X-Upload-Content-Length", bytes.size.toString())
            doOutput = true
            outputStream.bufferedWriter().use { it.write(metadata.toString()) }
            requireSuccess(this)
            requireNotNull(getHeaderField("Location")) { "O Google Drive nao iniciou o envio." }
        }
        request(location, "PUT", accessToken) {
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
            doOutput = true
            outputStream.use { it.write(bytes) }
            requireSuccess(this)
        }
    }

    fun download(accessToken: String): String {
        val fileId = findFile(accessToken) ?: error("Nenhum backup encontrado no Google Drive.")
        return request("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", "GET", accessToken) {
            requireSuccess(this)
            readLimited(inputStream, MaxBackupBytes).toString(StandardCharsets.UTF_8)
        }
    }

    private fun findFile(accessToken: String): String? {
        val query = URLEncoder.encode(
            "name = '$FileName' and 'appDataFolder' in parents",
            StandardCharsets.UTF_8.name()
        )
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder&pageSize=1&orderBy=modifiedTime%20desc&fields=files(id)&q=$query"
        return request(url, "GET", accessToken) {
            requireSuccess(this)
            val files = JSONObject(inputStream.bufferedReader().use { it.readText() }).getJSONArray("files")
            if (files.length() == 0) null else files.getJSONObject(0).getString("id")
        }
    }

    private fun <T> request(
        url: String,
        method: String,
        accessToken: String,
        block: HttpURLConnection.() -> T
    ): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Connection", "close")
            connection.block()
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
        throw IOException("Google Drive respondeu HTTP $code${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "Backup do Google Drive muito grande." }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}

object LocalBackupStorage {
    const val DestinationName = "Downloads/Mensageiro"
    private const val FileName = "mensageiro-automatico.json"

    fun write(context: Context, backup: String) {
        if (Build.VERSION.SDK_INT >= 29) writeMediaStore(context, backup)
        else writeLegacy(backup)
    }

    private fun writeMediaStore(context: Context, backup: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Mensageiro/"
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FileName, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else null
        }
        val uri = existing ?: requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        })) { "Nao foi possivel criar o backup local." }
        requireNotNull(resolver.openOutputStream(uri, "wt")) {
            "Nao foi possivel abrir o backup local."
        }.bufferedWriter().use { it.write(backup) }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(backup: String) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Mensageiro"
        ).apply { check(exists() || mkdirs()) { "Nao foi possivel criar a pasta de backup." } }
        val target = File(directory, FileName)
        val temporary = File(directory, "$FileName.tmp")
        temporary.bufferedWriter().use { it.write(backup) }
        if (target.exists()) check(target.delete()) { "Nao foi possivel substituir o backup local." }
        check(temporary.renameTo(target)) { "Nao foi possivel concluir o backup local." }
    }
}
