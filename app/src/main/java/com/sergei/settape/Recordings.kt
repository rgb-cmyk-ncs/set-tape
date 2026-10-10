package com.sergei.settape

import android.content.Context
import android.os.Environment
import com.sergei.settape.audio.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where recordings live and how they are named and listed. */
object Recordings {
    const val FOLDER = "SetTape"
    private const val PREFIX = "SetTape_"
    private const val PROBE = "SetTape_writetest_"

    class Item(val file: File, val title: String, val seconds: Double, val bytes: Long)

    @Volatile
    private var publicOk = false

    /**
     * Music/SetTape on shared storage, so recordings show up in file managers, over a USB cable
     * and survive uninstalling the app. Falls back to the app's own folder if that is refused.
     */
    fun dir(context: Context): File {
        @Suppress("DEPRECATION")
        val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (publicOk || probe(shared)) {
            publicOk = true
            return shared
        }
        val own = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, FOLDER)
        own.mkdirs()
        return own
    }

    private fun probe(d: File): Boolean = try {
        d.mkdirs()
        // Android only lets audio files into Music/, so the test file has to look like one.
        val t = File(d, "${PROBE}${System.nanoTime()}.wav")
        val ok = d.isDirectory && t.createNewFile()
        t.delete()
        ok
    } catch (e: Exception) {
        false
    }

    /** "SetTape_2026-10-10_22-15-03", with a number added if that name is already taken. */
    fun uniqueBase(dir: File, startMillis: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startMillis))
        var base = PREFIX + stamp
        var n = 1
        while (File(dir, "$base.wav").exists()) {
            n++
            base = "$PREFIX${stamp}_$n"
        }
        return base
    }

    /** Recordings newest first. [active] is skipped when repairing, since it is still being written. */
    fun list(dir: File, active: File?): List<Item> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", true) && !f.name.startsWith(".") && !f.name.startsWith(PROBE) }
            ?: return emptyList()
        val out = ArrayList<Item>()
        val now = System.currentTimeMillis()
        for (f in files.sortedByDescending { it.lastModified() }) {
            // Only touch files that have clearly stopped growing.
            if (f != active && now - f.lastModified() > 15_000) WavWriter.repair(f)
            val info = WavWriter.info(f)
            val title = f.name.removeSuffix(".wav").removePrefix(PREFIX).replace('_', ' ')
            out.add(Item(f, title, info?.seconds ?: 0.0, f.length()))
        }
        return out
    }

    fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    }

    /** "5 h 40 min" style, for how much recording time is left on the storage. */
    fun roughTime(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}
