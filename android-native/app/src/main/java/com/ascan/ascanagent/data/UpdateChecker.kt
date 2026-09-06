package com.ascan.ascanagent.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
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

    /** true se remote > local (comparacao simples de numeros separados por ponto) */
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
}
