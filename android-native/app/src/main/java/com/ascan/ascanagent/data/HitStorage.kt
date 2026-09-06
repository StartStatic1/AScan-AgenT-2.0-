package com.ascan.ascanagent.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva hits apenas na pasta do app (append no MESMO arquivo por servidor/dia).
 * Nunca cria arquivo novo por hit. Nunca lanca exception.
 */
object HitStorage {

    @Volatile
    private var resolvedDir: File? = null

    fun hitsDir(context: Context): File {
        resolvedDir?.let { if (it.exists() || it.mkdirs()) return it }

        val safe = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "AScan_App/HITS") },
            context.getExternalFilesDir(null)?.let { File(it, "AScan_App/HITS") },
            File(context.filesDir, "AScan_App/HITS")
        )

        for (d in safe) {
            try {
                if (!d.exists()) d.mkdirs()
                val probe = File(d, ".wtest")
                probe.writeText("ok")
                probe.delete()
                resolvedDir = d
                return d
            } catch (_: Exception) {
            }
        }

        val fallback = File(context.filesDir, "HITS")
        try { fallback.mkdirs() } catch (_: Exception) {}
        resolvedDir = fallback
        return fallback
    }

    fun save(context: Context, hit: Hit) {
        try {
            val dir = hitsDir(context)
            // host normalizado SEMPRE igual para o mesmo servidor
            val host = hit.server.substringBefore(":")
                .lowercase()
                .replace(".", "_")
                .replace(Regex("[^a-z0-9_]"), "_")
            val day = SimpleDateFormat("dd-MM", Locale.getDefault()).format(Date())
            val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val block = "[$stamp]\n${hit.text}\n\n"

            synchronized(this) {
                appendSafe(File(dir, "${day}_$host.txt"), block)
                appendSafe(File(dir, "HITS_GERAL.txt"), block)
                if (hit.unlimited) {
                    appendSafe(File(dir, "ILIMITADOS.txt"), block)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendSafe(file: File, block: String) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(block)
        } catch (_: Exception) {
        }
    }
}
