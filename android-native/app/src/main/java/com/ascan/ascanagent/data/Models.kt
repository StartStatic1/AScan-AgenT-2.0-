package com.ascan.ascanagent.data

data class Credential(val user: String, val pass: String)

data class Hit(
    val server: String,
    val user: String,
    val pass: String,
    val status: String,
    val plan: String,
    val connections: String,
    val created: String,
    val expires: String,
    val daysLeft: String,
    val m3u: String,
    val epg: String,
    val combo: String,
    val unlimited: Boolean,
    val text: String,
    val timeMs: Long = System.currentTimeMillis()
)

data class ServerStatus(
    val host: String,
    val state: String,
    val hits: Int = 0
)

data class ScanStats(
    val checks: Int = 0,
    val hits: Int = 0,
    val unlimited: Int = 0,
    val errors403: Int = 0,
    val errors429: Int = 0,
    val timeouts: Int = 0,
    val cpm: Int = 0,
    val progress: Float = 0f,
    val elapsedSec: Long = 0,
    val totalCombo: Int = 0,
    val proxies: Int = 0
)

enum class AtkMode(val label: String, val timeoutSec: Long, val delayMs: Long, val retries: Int) {
    PADRAO("Padrao", 5, 0, 2),
    ADAPTATIVO("Adaptativo", 4, 50, 2),
    FURTIVO("Furtivo", 7, 350, 3),
    CAMALEAO("Camaleao", 5, 150, 2),
    BYPASS("Bypass", 6, 200, 3)
}

object AppConfig {
    const val VERSION = "2.0.3-native"
    const val TELEGRAM = "https://t.me/+UfgoBcTQpwBlMDMx"
    const val REPO_OWNER = "StartStatic1"
    const val REPO_NAME = "AScan-AgenT-2.0-"
    const val COMBOS_API =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/combos"
    const val COMBOS_RAW =
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/combos/"
    const val VERSION_URL =
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/version.json"
}
