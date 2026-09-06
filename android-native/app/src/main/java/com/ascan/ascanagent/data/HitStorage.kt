package com.ascan.ascanagent.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva hits de forma segura.
 * 1) Sempre grava na pasta do app (sempre tem permissao).
 * 2) Tenta espelhar no Download publico (pode falhar no Android 10+ — ignoramos).
 * Nunca lanca exception para nao derrubar o scan.
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
            val host = hit.server.substringBefore(":")
                .replace(".", "_")
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
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

            tryMirrorPublic(context, "${day}_$host.txt", block)
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

    private fun tryMirrorPublic(context: Context, fileName: String, block: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AScan_App/HITS")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(block.toByteArray(Charsets.UTF_8))
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val pub = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AScan_App/HITS"
                )
                if (!pub.exists()) pub.mkdirs()
                appendSafe(File(pub, fileName), block)
            }
        } catch (_: Exception) {
        }
    }
}
