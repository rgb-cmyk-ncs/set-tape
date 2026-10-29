package com.sergei.settape.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

/**
 * Changes the volume of finished recordings, in place. Works on the 16- and 24-bit
 * WAV files [WavWriter] makes; the file is read and rewritten in chunks so even a
 * 4 GB part needs little memory.
 */
object Gain {
    /** One job at a time, so two changes to the same file can never overlap. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor()

    const val NORMALIZE_TARGET_DB = -1f
    /** Normalizing never boosts more than this, so a near-silent take doesn't become loud hiss. */
    const val MAX_BOOST_DB = 30f
    private const val CHUNK_FRAMES = 65536

    fun toDb(linear: Float): Float = if (linear <= 0f) -200f else 20f * log10(linear)
    fun fromDb(db: Float): Float = 10f.pow(db / 20f)

    /** Loudest sample over all [files], 0..1 (1 = full scale). */
    fun peak(files: List<File>): Float {
        var peak = 0
        var full = 1
        for (f in files) {
            val info = WavWriter.info(f) ?: continue
            if (info.bits != 16 && info.bits != 24) continue
            full = if (info.bits == 24) 8388608 else 32768
            val bps = info.bits / 8
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(WavWriter.HEADER_BYTES.toLong())
                val buf = ByteArray(CHUNK_FRAMES * info.channels * bps)
                var left = info.frames * info.channels * bps
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    var i = 0
                    while (i + bps <= n) {
                        val v = sample(buf, i, bps)
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        i += bps
                    }
                    left -= n
                }
            }
        }
        return (peak.toFloat() / full).coerceAtMost(1f)
    }

    /** Multiplies every sample in [file] by [gain]. Samples that would go past full scale are held there. */
    fun apply(file: File, gain: Float) {
        val info = WavWriter.info(file) ?: throw IllegalArgumentException("Not a SetTape WAV file")
        require(info.bits == 16 || info.bits == 24) { "Only 16- and 24-bit WAV is supported" }
        val bps = info.bits / 8
        val max = if (bps == 3) 8388607 else 32767
        val min = -max - 1
        var r = 0x2545F491
        RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteArray(CHUNK_FRAMES * info.channels * bps)
            var pos = WavWriter.HEADER_BYTES.toLong()
            var left = info.frames * info.channels * bps
            while (left > 0) {
                raf.seek(pos)
                val want = minOf(buf.size.toLong(), left).toInt()
                raf.readFully(buf, 0, want)
                var i = 0
                while (i + bps <= want) {
                    var x = sample(buf, i, bps) * gain
                    if (bps == 2) {
                        // Same one-LSB triangular dither as when recording.
                        r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
                        x += ((r and 0xFFFF) - ((r ushr 16) and 0xFFFF)) / 65536f
                    }
                    var v = Math.round(x)
                    if (v > max) v = max else if (v < min) v = min
                    buf[i] = v.toByte()
                    buf[i + 1] = (v shr 8).toByte()
                    if (bps == 3) buf[i + 2] = (v shr 16).toByte()
                    i += bps
                }
                raf.seek(pos)
                raf.write(buf, 0, want)
                pos += want
                left -= want
            }
        }
    }

    /**
     * Brings the loudest moment of a take (all its parts together, so they stay matched) up to
     * [NORMALIZE_TARGET_DB]. Returns the change in dB, or 0 when nothing needed doing.
     */
    fun normalize(files: List<File>): Float {
        val p = peak(files)
        if (p <= 0f) return 0f
        val db = (NORMALIZE_TARGET_DB - toDb(p)).coerceAtMost(MAX_BOOST_DB)
        if (db < 0.1f) return 0f
        val g = fromDb(db)
        for (f in files) apply(f, g)
        return db
    }

    /**
     * Changes [file] by [db], but never so far that it would clip: a boost is cut short at
     * [NORMALIZE_TARGET_DB]. Returns the change actually made.
     */
    fun change(file: File, db: Float): Float {
        var d = db
        if (d > 0f) {
            val p = peak(listOf(file))
            if (p > 0f) d = minOf(d, NORMALIZE_TARGET_DB - toDb(p))
            if (d < 0.1f) return 0f
        }
        apply(file, fromDb(d))
        return d
    }

    private fun sample(b: ByteArray, i: Int, bps: Int): Int =
        if (bps == 3) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() shl 16)
        else (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
}
