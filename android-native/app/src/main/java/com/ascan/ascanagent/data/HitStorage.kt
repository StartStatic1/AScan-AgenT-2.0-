package com.ascan.ascanagent.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HitStorage {

    fun hitsDir(context: Context): File {
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AScan_App/HITS"),
            File(context.getExternalFilesDir(null), "AScan_App/HITS"),
            File(context.filesDir, "AScan_App/HITS")
        )
        for (d in candidates) {
            try {
                if (!d.exists()) d.mkdirs()
                if (d.canWrite()) return d
            } catch (_: Exception) {
            }
        }
        val fallback = File(context.filesDir, "HITS")
        fallback.mkdirs()
        return fallback
    }

    fun save(context: Context, hit: Hit) {
        val dir = hitsDir(context)
        val host = hit.server.substringBefore(":").replace(".", "_")
        val day = SimpleDateFormat("dd-MM", Locale.getDefault()).format(Date())
        val serverFile = File(dir, "${day}_$host.txt")
        val geral = File(dir, "HITS_GERAL.txt")
        val block = "[${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}]\n${hit.text}\n\n"
        synchronized(this) {
            serverFile.appendText(block)
            geral.appendText(block)
            if (hit.unlimited) {
                File(dir, "ILIMITADOS.txt").appendText(block)
            }
        }
    }
}
