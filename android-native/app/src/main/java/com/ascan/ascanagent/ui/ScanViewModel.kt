package com.ascan.ascanagent.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ascan.ascanagent.data.AppConfig
import com.ascan.ascanagent.data.AtkMode
import com.ascan.ascanagent.data.Credential
import com.ascan.ascanagent.data.Hit
import com.ascan.ascanagent.data.HitStorage
import com.ascan.ascanagent.data.RemoteVersion
import com.ascan.ascanagent.data.ScanStats
import com.ascan.ascanagent.data.ScannerEngine
import com.ascan.ascanagent.data.ServerStatus
import com.ascan.ascanagent.data.UpdateChecker
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
    var updateInfo by mutableStateOf<RemoteVersion?>(null)
    var showUpdate by mutableStateOf(false)
    var downloadProgress by mutableStateOf(-1)
    var downloadError by mutableStateOf("")

    var showProxyPaste by mutableStateOf(false)
    var proxyPasteText by mutableStateOf("")

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
        checkUpdate()
    }

    fun checkUpdate() {
        viewModelScope.launch {
            val remote = withContext(Dispatchers.IO) { UpdateChecker.fetch() } ?: return@launch
            if (UpdateChecker.isNewer(remote.version)) {
                updateInfo = remote
                showUpdate = true
                log("Update: ${remote.version} — ${remote.message}")
            }
        }
    }

    fun dismissUpdate() {
        showUpdate = false
        downloadProgress = -1
        downloadError = ""
    }

    fun applyUpdate() {
        val info = updateInfo ?: return
        if (info.apkUrl.isBlank()) {
            downloadError = "URL vazia"
            return
        }
        viewModelScope.launch {
            downloadProgress = 0
            downloadError = ""
            val err = withContext(Dispatchers.IO) {
                UpdateChecker.downloadAndInstall(
                    getApplication(),
                    info.apkUrl
                ) { p ->
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        downloadProgress = p
                    }
                }
            }
            if (err != null) {
                downloadProgress = -2
                downloadError = err
                log("Update falhou: $err")
            } else {
                downloadProgress = 100
                log("APK baixado — confirme a instalação")
                showUpdate = false
            }
        }
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

    private fun parseProxyLine(line: String, defaultScheme: String = "http"): String? {
        val p = line.trim()
        if (p.isEmpty() || p.startsWith("#") || ':' !in p || ' ' in p || p.length > 90) return null
        return when {
            p.startsWith("socks5://", true) || p.startsWith("socks4://", true) ||
                p.startsWith("socks://", true) || p.startsWith("http://", true) ||
                p.startsWith("https://", true) -> p
            defaultScheme == "socks5" -> "socks5://$p"
            else -> "http://$p"
        }
    }

    fun loadProxiesOnline() {
        if (proxyLoading) return
        viewModelScope.launch {
            proxyLoading = true
            log("Baixando proxies (HTTP + SOCKS5)...")
            // HTTP first (melhor com OkHttp), depois SOCKS5 das mesmas fontes do print
            val sources = listOf(
                // HTTP / HTTPS
                "http" to "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=3000&country=all&ssl=all&anonymity=all",
                "http" to "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=BR,US,DE,NL,FR",
                "http" to "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt",
                "http" to "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
                "http" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
                "http" to "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
                "http" to "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
                "http" to "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt",
                "http" to "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt",
                "http" to "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
                // SOCKS5 (print do usuario + fontes extras)
                "socks5" to "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5",
                "socks5" to "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000",
                "socks5" to "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
                "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
                "socks5" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
                "socks5" to "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt",
                "socks5" to "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt",
                "socks5" to "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt",
                "socks5" to "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
                "socks5" to "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt",
                "socks5" to "https://gist.githubusercontent.com/Marlonwap/82199d4a0edb9ff8598e2bbfadde7faf/raw/"
            )
            val found = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for ((scheme, u) in sources) {
                    val t = XtreamApi.fetchText(u, 18) ?: continue
                    var n = 0
                    t.lineSequence().forEach { line ->
                        val px = parseProxyLine(line, scheme) ?: return@forEach
                        found += px
                        n++
                    }
                    if (n > 0) {
                        // log leve no main depois
                    }
                    if (found.size >= 8000) break
                }
            }
            proxies = found.distinct().take(5000)
            proxyCount = proxies.size
            proxyLoading = false
            val httpN = proxies.count { it.startsWith("http", true) }
            val socksN = proxies.count { it.startsWith("socks", true) }
            log(
                if (proxyCount > 0)
                    "OK Proxies: $proxyCount (HTTP $httpN · SOCKS $socksN)"
                else "Nenhum proxy"
            )
        }
    }

    fun clearProxies() {
        proxies = emptyList()
        proxyCount = 0
        proxyLoading = false
        log("Proxies limpos (direto)")
    }

    /** Offline: cola lista ip:porta (gerador / txt local) */
    fun applyProxyPaste(text: String) {
        val found = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            parseProxyLine(line, "http")?.let { found += it }
        }
        proxies = found.distinct().take(5000)
        proxyCount = proxies.size
        showProxyPaste = false
        proxyPasteText = ""
        log(if (proxyCount > 0) "Offline · $proxyCount proxies" else "Nenhum proxy válido no texto")
    }

    /** Offline: baixa .txt da pasta proxies/ no GitHub */
    fun loadProxiesFromRepo() {
        if (proxyLoading) return
        viewModelScope.launch {
            proxyLoading = true
            log("Carregando proxies do repositório...")
            val found = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val body = XtreamApi.fetchText(AppConfig.PROXIES_API, 20) ?: return@withContext
                try {
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name")
                        val dl = o.optString("download_url")
                        if (!name.endsWith(".txt", true)) continue
                        val t = XtreamApi.fetchText(dl, 20) ?: continue
                        t.lineSequence().forEach { line ->
                            parseProxyLine(line, "http")?.let { found += it }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            proxies = found.distinct().take(5000)
            proxyCount = proxies.size
            proxyLoading = false
            log(if (proxyCount > 0) "Repo · $proxyCount proxies" else "Nenhum .txt em /proxies")
        }
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
