package com.ascan.ascanagent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetch(): RemoteVersion? {
        return try {
            val req = Request.Builder()
                .url(AppConfig.VERSION_URL)
                .header("User-Agent", "AScanAgent/${AppConfig.VERSION}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val j = JSONObject(body)
                RemoteVersion(
                    version = j.optString("version", ""),
                    minVersion = j.optString("min_version", j.optString("version", "")),
                    force = j.optBoolean("force", false),
                    apkUrl = j.optString("apk_url", ""),
                    message = j.optString("message", "Nova versao disponivel")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isNewer(remote: String, local: String = AppConfig.VERSION): Boolean {
        fun parts(v: String): List<Int> =
            v.replace("-native", "", ignoreCase = true)
                .split(Regex("[^0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.toIntOrNull() ?: 0 }

        val a = parts(remote)
        val b = parts(local)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x > y) return true
            if (x < y) return false
        }
        return false
    }

    /**
     * Baixa o APK para cache e abre o instalador do sistema.
     * @return null se ok, ou mensagem de erro
     */
    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: ((Int) -> Unit)? = null
    ): String? {
        if (apkUrl.isBlank()) return "URL do APK vazia"
        return try {
            val req = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "AScanAgent/${AppConfig.VERSION}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "HTTP ${resp.code}"
                val body = resp.body ?: return "Resposta vazia"
                val total = body.contentLength()
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(dir, "AScan-Agent-update.apk")
                if (outFile.exists()) outFile.delete()

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0 && onProgress != null) {
                                onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                        output.flush()
                    }
                }

                if (outFile.length() < 50_000) {
                    outFile.delete()
                    return "APK invalido (muito pequeno)"
                }

                installApk(context, outFile)
                null
            }
        } catch (e: Exception) {
            e.message ?: "Falha no download"
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        context.startActivity(intent)
    }
}
