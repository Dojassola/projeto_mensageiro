package com.mensageiro.core

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

data class DownloadedUpdate(val update: AppUpdate, val file: File)

object AppUpdater {
    private const val LatestRelease =
        "https://api.github.com/repos/Dojassola/projeto_mensageiro/releases/latest"
    private const val ReleasePrefix =
        "https://github.com/Dojassola/projeto_mensageiro/releases/download/"
    private const val CheckInterval = 24 * 60 * 60 * 1_000L
    private const val ApkName = "Mensageiro-update.apk"

    fun shouldCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong("lastCheck", 0) >= CheckInterval

    fun check(context: Context): AppUpdate? {
        val connection = URL(LatestRelease).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Mensageiro/${versionName(context)}")
            check(connection.responseCode == 200) { "GitHub respondeu ${connection.responseCode}." }
            prefs(context).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.getString("tag_name")
            val releaseVersion = tag.removePrefix("v")
            if (compareVersions(releaseVersion, versionName(context)) <= 0) return null
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == "Mensageiro.apk" }
                ?: error("A release nao possui Mensageiro.apk.")
            val url = asset.getString("browser_download_url")
            val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
            require(url.startsWith(ReleasePrefix) && url.endsWith("/Mensageiro.apk")) {
                "Endereco de atualizacao invalido."
            }
            require(digest.matches(Regex("[0-9a-f]{64}"))) { "Release sem SHA-256." }
            AppUpdate(releaseVersion, url, digest, release.optString("body").take(500))
        } finally {
            connection.disconnect()
        }
    }

    fun enqueue(context: Context, update: AppUpdate): Long {
        val app = context.applicationContext
        val manager = app.getSystemService(DownloadManager::class.java)
        prefs(app).getLong("downloadId", 0).takeIf { it > 0 }?.let { manager.remove(it) }
        val target = updateFile(app).apply { delete() }
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Mensageiro ${update.versionName}")
            .setDescription("Baixando atualizacao")
            .setMimeType(ApkMime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, ApkName)
        val id = manager.enqueue(request)
        prefs(app).edit()
            .putLong("downloadId", id)
            .putString("update", encode(update))
            .apply()
        check(target.parentFile?.isDirectory == true) { "Pasta de atualizacao indisponivel." }
        return id
    }

    suspend fun await(context: Context, id: Long): DownloadedUpdate {
        val app = context.applicationContext
        val manager = app.getSystemService(DownloadManager::class.java)
        while (true) {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor.moveToFirst()) { "Download nao encontrado." }
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return requireNotNull(downloaded(app)) {
                        "O arquivo baixado e invalido."
                    }
                    DownloadManager.STATUS_FAILED -> error(
                        "Download falhou: " + cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                    )
                }
            }
            delay(1_000)
        }
    }

    fun downloaded(context: Context): DownloadedUpdate? {
        val update = decode(prefs(context).getString("update", null)) ?: return null
        if (compareVersions(update.versionName, versionName(context)) <= 0) {
            clear(context)
            return null
        }
        val file = updateFile(context)
        if (!file.isFile) return null
        if (sha256(file) != update.sha256) {
            clear(context)
            return null
        }
        return DownloadedUpdate(update, file)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    fun installIntent(context: Context, downloaded: DownloadedUpdate): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.files", downloaded.file),
                ApkMime
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun clear(context: Context) {
        updateFile(context).delete()
        prefs(context).edit().remove("downloadId").remove("update").apply()
    }

    private fun updateFile(context: Context): File {
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "Armazenamento indisponivel."
        }
        return File(directory, ApkName)
    }

    private fun encode(update: AppUpdate): String = JSONObject()
        .put("versionName", update.versionName)
        .put("apkUrl", update.apkUrl)
        .put("sha256", update.sha256)
        .put("notes", update.notes)
        .toString()

    private fun decode(value: String?): AppUpdate? = value?.let {
        runCatching {
            val json = JSONObject(it)
            AppUpdate(
                json.getString("versionName"), json.getString("apkUrl"),
                json.getString("sha256"), json.optString("notes")
            )
        }.getOrNull()
    }

    private fun compareVersions(first: String, second: String): Int {
        fun parts(value: String) = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
            .find(value)?.groupValues?.drop(1)?.map(String::toInt)
            ?: error("Versao invalida: $value")
        val a = parts(first)
        val b = parts(second)
        for (index in a.indices) if (a[index] != b[index]) return a[index].compareTo(b[index])
        return 0
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun versionName(context: Context): String =
        requireNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)

    private const val ApkMime = "application/vnd.android.package-archive"
}
