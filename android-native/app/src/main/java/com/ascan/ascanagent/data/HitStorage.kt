package com.ascan.ascanagent.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva hits:
 * 1) Pasta do app (sempre funciona)
 * 2) Download publico /AScan_App/HITS (mesmo arquivo, append)
 */
object HitStorage {

    @Volatile
    private var resolvedDir: File? = null

    @Volatile
    var lastSavePath: String = ""
        private set

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

    fun save(context: Context, hit: Hit): String {
        return try {
            val dir = hitsDir(context)
            val host = hit.server.substringBefore(":")
                .lowercase()
                .replace(".", "_")
                .replace(Regex("[^a-z0-9_]"), "_")
            val day = SimpleDateFormat("dd-MM", Locale.getDefault()).format(Date())
            val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val block = "[$stamp]\n${hit.text}\n\n"
            val serverName = "${day}_$host.txt"

            var pathShown = ""
            synchronized(this) {
                val f1 = File(dir, serverName)
                appendSafe(f1, block)
                appendSafe(File(dir, "HITS_GERAL.txt"), block)
                if (hit.unlimited) appendSafe(File(dir, "ILIMITADOS.txt"), block)
                pathShown = f1.absolutePath

                // Publico: Download/AScan_App/HITS (mesmo nome, append)
                val pub = mirrorPublic(context, serverName, block)
                if (pub != null) pathShown = pub
                mirrorPublic(context, "HITS_GERAL.txt", block)
                if (hit.unlimited) mirrorPublic(context, "ILIMITADOS.txt", block)
            }
            lastSavePath = pathShown
            pathShown
        } catch (_: Exception) {
            ""
        }
    }

    private fun appendSafe(file: File, block: String) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(block)
        } catch (_: Exception) {
        }
    }

    /** Retorna caminho amigavel se gravou no Download publico */
    private fun mirrorPublic(context: Context, fileName: String, block: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val rel = Environment.DIRECTORY_DOWNLOADS + "/AScan_App/HITS"
                val uri = findOrCreateDownload(resolver, collection, fileName, rel) ?: return null
                resolver.openOutputStream(uri, "wa")?.use { out ->
                    out.write(block.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                "/storage/emulated/0/Download/AScan_App/HITS/$fileName"
            } else {
                val pub = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AScan_App/HITS"
                )
                if (!pub.exists()) pub.mkdirs()
                val f = File(pub, fileName)
                appendSafe(f, block)
                f.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findOrCreateDownload(
        resolver: android.content.ContentResolver,
        collection: Uri,
        fileName: String,
        relativePath: String
    ): Uri? {
        // Procura arquivo existente com mesmo nome (para append)
        try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection =
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                    MediaStore.Downloads.RELATIVE_PATH + " LIKE ?"
            val args = arrayOf(fileName, "%AScan_App/HITS%")
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (_: Exception) {
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        return try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        }
    }
}
