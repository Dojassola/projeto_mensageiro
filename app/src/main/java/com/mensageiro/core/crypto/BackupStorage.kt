package com.mensageiro.core.crypto

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
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
        .setRequestedScopes(
            listOf(
                Scope(Scopes.DRIVE_APPFOLDER),
                Scope(Scopes.DRIVE_FILE)
            )
        )
        .build()

    fun accessToken(context: Context): String {
        val result = Tasks.await(
            Identity.getAuthorizationClient(context.applicationContext).authorize(authorizationRequest())
        )
        check(!result.hasResolution()) { "Reconecte o Google Drive pelo aplicativo." }
        return requireNotNull(result.accessToken) { "O Google Drive nao forneceu acesso." }
    }

    fun disconnect(context: Context, accessToken: String) {
        val body = "token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8.name())
        val connection = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Google recusou a desconexao (HTTP ${connection.responseCode}): $detail")
            }
        } finally {
            connection.disconnect()
        }
        Tasks.await(
            Identity.getAuthorizationClient(context.applicationContext).clearToken(
                ClearTokenRequest.builder().setToken(accessToken).build()
            )
        )
    }

    fun upload(accessToken: String, backup: String) {
        val bytes = backup.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MaxBackupBytes) { "Backup muito grande para o Google Drive." }
        val previousFiles = findFiles(accessToken)
        val newFileId = uploadNew(accessToken, bytes)
        previousFiles.filterNot { it == newFileId }.forEach { fileId ->
            runCatching {
                request("https://www.googleapis.com/drive/v3/files/$fileId", "DELETE", accessToken) {
                    if (responseCode != HttpURLConnection.HTTP_NOT_FOUND) requireSuccess(this)
                }
            }
        }
    }

    private fun uploadNew(accessToken: String, bytes: ByteArray): String {
        val endpoint = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        val boundary = "mensageiro-backup-boundary"
        val metadata = JSONObject().put("name", FileName).put("parents", listOf("appDataFolder"))
        val prefix = ("--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            metadata + "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: application/json\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return request(endpoint, "POST", accessToken) {
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.size + bytes.size + suffix.size)
            doOutput = true
            outputStream.use {
                it.write(prefix)
                it.write(bytes)
                it.write(suffix)
            }
            requireSuccess(this)
            JSONObject(inputStream.bufferedReader().use { it.readText() }).getString("id")
        }
    }

    fun download(accessToken: String): String {
        val fileId = findFiles(accessToken).firstOrNull()
            ?: error("Nenhum backup encontrado no Google Drive.")
        return request("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", "GET", accessToken) {
            requireSuccess(this)
            readLimited(inputStream, MaxBackupBytes).toString(StandardCharsets.UTF_8)
        }
    }

    private fun findFiles(accessToken: String): List<String> {
        val query = URLEncoder.encode(
            "name = '$FileName' and 'appDataFolder' in parents",
            StandardCharsets.UTF_8.name()
        )
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder&pageSize=100&orderBy=modifiedTime%20desc" +
            "&fields=files(id)&q=$query"
        return request(url, "GET", accessToken) {
            requireSuccess(this)
            val files = JSONObject(inputStream.bufferedReader().use { it.readText() }).getJSONArray("files")
            (0 until files.length()).map { files.getJSONObject(it).getString("id") }
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
        val operation = when {
            connection.url.query?.contains("uploadType=multipart") == true -> "enviar backup"
            connection.requestMethod == "POST" -> "criar backup"
            connection.requestMethod == "PUT" -> "enviar backup"
            connection.requestMethod == "DELETE" -> "remover backup antigo"
            connection.url.query?.contains("alt=media") == true -> "baixar backup"
            else -> "listar backups"
        }
        Log.e("DriveBackup", "$operation: HTTP $code $detail")
        throw IOException("Google Drive falhou ao $operation (HTTP $code)${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
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
