package com.ascan.ascanagent.ui

import android.app.Application
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
    var proxyLoading by mutableStateOf(false)
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
        engine.onStats = { s ->
            viewModelScope.launch(Dispatchers.Main.immediate) { stats = s }
        }
        engine.onHit = { h ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                try {
                    hits.add(0, h)
                    if (hits.size > 200) hits.removeAt(hits.lastIndex)
                    lastM3u = h.m3u
                    log("[HIT] (${h.server}) ${h.user}:${h.pass}")
                } catch (_: Exception) {
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val path = HitStorage.save(getApplication(), h)
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        if (path.isNotBlank()) {
                            log("Salvo: ${path.substringAfterLast('/')}")
                        } else {
                            log("Hit OK (pasta app)")
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        engine.onLog = { msg ->
            viewModelScope.launch(Dispatchers.Main.immediate) { log(msg) }
        }
        engine.onServerStatus = { list ->
            viewModelScope.launch(Dispatchers.Main.immediate) { ranking = list }
        }
        engine.onFinished = {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                running = false
                paused = false
                statusText = "Parado"
            }
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
        if (proxyLoading) return
        viewModelScope.launch {
            proxyLoading = true
            log("Baixando proxies...")
            val urls = listOf(
                "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all",
                "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
                "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
                "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
                "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt"
            )
            val found = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (u in urls) {
                    val t = XtreamApi.fetchText(u, 15) ?: continue
                    t.lineSequence().forEach { line ->
                        val p = line.trim()
                        if (p.isNotEmpty() && ':' in p && !p.startsWith("#") && ' ' !in p && p.length < 60) {
                            found += if ("://" in p) p else "http://$p"
                        }
                    }
                    if (found.size >= 1800) break
                }
            }
            proxies = found.distinct().take(2000)
            proxyCount = proxies.size
            proxyLoading = false
            log(if (proxyCount > 0) "OK Proxies prontos: $proxyCount" else "Nenhum proxy")
        }
    }

    fun clearProxies() {
        proxies = emptyList()
        proxyCount = 0
        proxyLoading = false
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

    fun hitsPath(): String {
        val pub = "/storage/emulated/0/Download/AScan_App/HITS"
        return if (HitStorage.lastSavePath.isNotBlank()) HitStorage.lastSavePath
        else pub
    }
}
