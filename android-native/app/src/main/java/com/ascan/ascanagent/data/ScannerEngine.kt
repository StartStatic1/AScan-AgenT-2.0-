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
        val serverHits = mutableMapOf<String, AtomicInteger>()
        val serverState = mutableMapOf<String, String>()
        val stateMutex = Mutex()

        servers.forEach {
            val h = XtreamApi.normServer(it)
            serverHits[h] = AtomicInteger(0)
            serverState[h] = "..."
        }

        onLog?.invoke("Start ${servers.size} srv | ${combo.size} combo | thr $threads | ${mode.label} | px ${proxies.size}")

        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 64))
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        job = scope.launch {
            val channel = Channel<Pair<String, Credential>>(capacity = Channel.UNLIMITED)
            launch {
                for (srv in servers) {
                    val host = XtreamApi.normServer(srv)
                    for (cred in combo) {
                        if (stopped.get()) break
                        channel.send(host to cred)
                    }
                }
                channel.close()
            }

            val workers = List(threads.coerceIn(1, 64)) {
                launch {
                    for ((server, cred) in channel) {
                        if (stopped.get()) break
                        while (paused.get() && !stopped.get()) {
                            delay(200)
                        }
                        if (stopped.get()) break

                        if (mode.delayMs > 0) delay(mode.delayMs)

                        val proxy = if (proxies.isNotEmpty()) {
                            val i = proxyIdx.getAndIncrement()
                            proxies[i % proxies.size]
                        } else null

                        var result = XtreamApi.check(
                            server, cred.user, cred.pass,
                            timeoutSec = mode.timeoutSec,
                            proxyUrl = proxy
                        )
                        if (proxy != null && result.err in listOf(
                                "Timeout", "ConnectTimeout", "ProxyError",
                                "ConnectionError", "UnknownHostException"
                            )
                        ) {
                            result = XtreamApi.check(
                                server, cred.user, cred.pass,
                                timeoutSec = mode.timeoutSec,
                                proxyUrl = null
                            )
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
                                onHit?.invoke(hit)
                            }
                            result.code == 403 || result.err.contains("403") -> {
                                e403.incrementAndGet()
                                stateMutex.withLock { serverState[server] = "PROT" }
                            }
                            result.code == 429 || result.err.contains("429") -> {
                                e429.incrementAndGet()
                                stateMutex.withLock { serverState[server] = "PROT" }
                            }
                            result.err.contains("Timeout", true) || result.code == 0 -> {
                                timeouts.incrementAndGet()
                            }
                            result.code == 200 -> {
                                stateMutex.withLock {
                                    if (serverState[server] != "PROT") serverState[server] = "ON"
                                }
                            }
                        }

                        if (n % 5 == 0 || result.hit) {
                            val elapsed = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(1)
                            val cpm = ((checks.get() * 60.0) / elapsed).toInt()
                            onStats?.invoke(
                                ScanStats(
                                    checks = checks.get(),
                                    hits = hits.get(),
                                    unlimited = unlimited.get(),
                                    errors403 = e403.get(),
                                    errors429 = e429.get(),
                                    timeouts = timeouts.get(),
                                    cpm = cpm,
                                    progress = checks.get().toFloat() / total,
                                    elapsedSec = elapsed,
                                    totalCombo = combo.size,
                                    proxies = proxies.size
                                )
                            )
                            val ranking = serverHits.keys.map { h ->
                                ServerStatus(
                                    host = h,
                                    state = serverState[h] ?: "...",
                                    hits = serverHits[h]?.get() ?: 0
                                )
                            }.sortedByDescending { it.hits }
                            onServerStatus?.invoke(ranking)
                        }
                    }
                }
            }
            workers.forEach { it.join() }
            stopped.set(true)
            val elapsed = ((System.currentTimeMillis() - startMs) / 1000).coerceAtLeast(1)
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
            onLog?.invoke("Fim | Hits ${hits.get()} | Checks ${checks.get()}")
            onFinished?.invoke()
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
