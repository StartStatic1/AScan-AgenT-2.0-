package com.ascan.ascanagent.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ascan.ascanagent.data.AppConfig
import com.ascan.ascanagent.data.AtkMode
import com.ascan.ascanagent.data.ComboMode
import com.ascan.ascanagent.data.ComboSlot
import com.ascan.ascanagent.data.Credential
import com.ascan.ascanagent.data.Hit
import com.ascan.ascanagent.data.HitStorage
import com.ascan.ascanagent.data.ProbeResult
import com.ascan.ascanagent.data.RemoteVersion
import com.ascan.ascanagent.data.ScanOrder
import com.ascan.ascanagent.data.ScanStats
import com.ascan.ascanagent.data.ScannerEngine
import com.ascan.ascanagent.data.ServerStatus
import com.ascan.ascanagent.data.UpdateChecker
import com.ascan.ascanagent.data.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = ScannerEngine()

    var server1 by mutableStateOf("")
    var server2 by mutableStateOf("")
    var server3 by mutableStateOf("")
    var server4 by mutableStateOf("")
    var server5 by mutableStateOf("")

    var threads by mutableStateOf("20")
    var mode by mutableStateOf(AtkMode.ADAPTATIVO)
    var scanOrder by mutableStateOf(ScanOrder.SEQUENCIAL)
    var comboMode by mutableStateOf(ComboMode.FILA)

    var loadedCombos by mutableStateOf<List<ComboSlot>>(emptyList())
    val comboCount: Int get() = loadedCombos.sumOf { it.items.size }
    val comboName: String
        get() = when {
            loadedCombos.isEmpty() -> ""
            loadedCombos.size == 1 -> loadedCombos[0].name
            else -> "${loadedCombos.size} combos"
        }

    var comboList by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedCombo by mutableStateOf("")
    var comboSource by mutableStateOf("")

    var proxyCount by mutableStateOf(0)
    var proxyLoading by mutableStateOf(false)
    private var proxies: List<String> = emptyList()

    var running by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var probing by mutableStateOf(false)
    var stats by mutableStateOf(ScanStats())
    var ranking by mutableStateOf<List<ServerStatus>>(emptyList())
    var probeResults by mutableStateOf<List<ProbeResult>>(emptyList())
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
                        if (path.isNotBlank()) log("Salvo: ${path.substringAfterLast('/')}")
                        else log("Hit OK (pasta app)")
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
                UpdateChecker.downloadAndInstall(getApplication(), info.apkUrl) { p ->
                    viewModelScope.launch(Dispatchers.Main.immediate) { downloadProgress = p }
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

    fun currentServers(): List<String> =
        listOf(server1, server2, server3, server4, server5)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { XtreamApi.normServer(it) }
            .distinct()

    fun testServers() {
        val servers = currentServers()
        if (servers.isEmpty()) {
            log("Informe ao menos 1 servidor")
            return
        }
        if (running || probing) return
        viewModelScope.launch {
            probing = true
            statusText = "Testando"
            log("Testando ${servers.size} servidor(es)...")
            val results = withContext(Dispatchers.IO) {
                servers.map { host -> async { XtreamApi.probeServer(host) } }.awaitAll()
            }
            probeResults = results
            ranking = results.map {
                ServerStatus(host = it.host, state = it.state, hits = 0, detail = it.detail)
            }
            results.forEach { r ->
                val icon = when (r.state) {
                    "ONLINE" -> "🟢"
                    "PROT" -> "🟠"
                    "TIMEOUT" -> "🟡"
                    else -> "🔴"
                }
                log("$icon ${r.host} → ${r.state} (${r.detail})")
            }
            probing = false
            statusText = "Pronto"
        }
    }

    fun refreshCombos() {
        viewModelScope.launch {
            loadingCombo = true
            val list = withContext(Dispatchers.IO) { XtreamApi.listGithubCombos() }
            comboList = list
            if (selectedCombo.isEmpty() && list.isNotEmpty()) selectedCombo = list.first().first
            loadingCombo = false
        }
    }

    fun loadSelectedCombo() {
        val item = comboList.find { it.first == selectedCombo } ?: return
        viewModelScope.launch {
            loadingCombo = true
            val text = withContext(Dispatchers.IO) { XtreamApi.fetchText(item.second) }
            if (text != null) applyComboParsed(XtreamApi.parseCombo(text), item.first, "online")
            else log("Falha ao baixar combo")
            loadingCombo = false
        }
    }

    fun loadComboFromUri(uri: Uri) {
        viewModelScope.launch {
            loadingCombo = true
            log("Lendo combo do celular...")
            val result = withContext(Dispatchers.IO) {
                try {
                    val cr = getApplication<Application>().contentResolver
                    var displayName = "combo_local.txt"
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) displayName = c.getString(idx) ?: displayName
                        }
                    }
                    val list = ArrayList<Credential>(4096)
                    cr.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
                            br.lineSequence().forEach { raw ->
                                val line = raw.trim()
                                if (line.isEmpty() || line.startsWith("#")) return@forEach
                                val i = line.indexOf(':')
                                if (i > 0) {
                                    val u = line.substring(0, i).trim()
                                    val p = line.substring(i + 1).trim()
                                    if (u.isNotEmpty() && p.isNotEmpty()) list += Credential(u, p)
                                }
                            }
                        }
                    }
                    list to displayName
                } catch (e: Exception) {
                    emptyList<Credential>() to ("erro: ${e.javaClass.simpleName}")
                }
            }
            val (items, name) = result
            if (items.isEmpty()) log("Combo local vazio ou inválido ($name)")
            else applyComboParsed(items, name, "local")
            loadingCombo = false
        }
    }

    private fun applyComboParsed(items: List<Credential>, name: String, source: String) {
        if (items.isEmpty()) return
        if (loadedCombos.size >= AppConfig.MAX_COMBOS) {
            log("Máximo ${AppConfig.MAX_COMBOS} combos. Remova um antes.")
            return
        }
        var finalName = name
        if (loadedCombos.any { it.name == finalName }) {
            finalName = "$name ($source)"
            if (loadedCombos.any { it.name == finalName }) {
                log("Combo \"$name\" já está na lista")
                return
            }
        }
        val slot = ComboSlot(name = finalName, items = items, source = source)
        loadedCombos = loadedCombos + slot
        comboSource = source
        selectedCombo = name
        val tag = when (source) {
            "local" -> "📱 Local"
            else -> "☁ Online"
        }
        log("$tag · $finalName — ${items.size}  (${loadedCombos.size}/${AppConfig.MAX_COMBOS})")
    }

    fun removeCombo(index: Int) {
        if (index !in loadedCombos.indices) return
        val removed = loadedCombos[index]
        loadedCombos = loadedCombos.toMutableList().also { it.removeAt(index) }
        log("Removido: ${removed.name}")
    }

    fun clearCombo() {
        loadedCombos = emptyList()
        comboSource = ""
        log("Combos limpos")
    }

    fun clearHits() {
        hits.clear()
        lastM3u = ""
        log("Hits da tela limpos (arquivos no Download ficam)")
    }

    fun hitsAsText(): String =
        hits.joinToString("\n\n") { it.text.ifBlank { "${it.user}:${it.pass}" } }

    fun hitsUserPass(): String =
        hits.joinToString("\n") { "${it.user}:${it.pass}" }

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
            val sources = listOf(
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
                    t.lineSequence().forEach { line ->
                        val px = parseProxyLine(line, scheme) ?: return@forEach
                        found += px
                    }
                    if (found.size >= 8000) break
                }
            }
            proxies = found.distinct().take(5000)
            proxyCount = proxies.size
            proxyLoading = false
            val httpN = proxies.count { it.startsWith("http", true) }
            val socksN = proxies.count { it.startsWith("socks", true) }
            log(if (proxyCount > 0) "OK Proxies: $proxyCount (HTTP $httpN · SOCKS $socksN)" else "Nenhum proxy")
        }
    }

    fun clearProxies() {
        proxies = emptyList()
        proxyCount = 0
        proxyLoading = false
        log("Proxies limpos (direto)")
    }

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
                } catch (_: Exception) {}
            }
            proxies = found.distinct().take(5000)
            proxyCount = proxies.size
            proxyLoading = false
            log(if (proxyCount > 0) "Repo · $proxyCount proxies" else "Nenhum .txt em /proxies")
        }
    }

    fun start() {
        val servers = currentServers()
        if (servers.isEmpty()) {
            log("Informe ao menos 1 servidor")
            return
        }
        if (loadedCombos.isEmpty()) {
            log("Carregue ao menos 1 combo (Online ou Celular)")
            return
        }
        val thr = threads.toIntOrNull()?.coerceIn(1, 64) ?: 20
        running = true
        paused = false
        statusText = "Rodando"
        hits.clear()
        stats = ScanStats(totalCombo = comboCount, proxies = proxyCount)
        engine.start(servers, loadedCombos, thr, mode, proxies, scanOrder, comboMode)
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
        return if (HitStorage.lastSavePath.isNotBlank()) HitStorage.lastSavePath else pub
    }
}
