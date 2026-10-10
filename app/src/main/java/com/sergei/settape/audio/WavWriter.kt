package com.sergei.settape.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes interleaved float audio to a 16- or 24-bit PCM WAV file.
 *
 * The header is refreshed every few seconds while recording, so if the app is killed or the
 * battery dies the file on disk is still a valid WAV that is at most a few seconds short.
 */
class WavWriter(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    maxDataBytes: Long = DEFAULT_MAX_DATA_BYTES,
) : Closeable {

    private val bytesPerSample = bits / 8
    private val bytesPerFrame = channels * bytesPerSample
    private val maxFrames = maxDataBytes / bytesPerFrame
    private val raf: RandomAccessFile
    private var scratch = ByteArray(0)
    private var headerFrames = 0L
    private var rng = 0x2545F491
    private var closed = false

    var frames = 0L
        private set

    init {
        require(bits == 16 || bits == 24) { "Only 16- and 24-bit WAV is supported" }
        require(channels in 1..2) { "Only mono and stereo are supported" }
        raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(header(sampleRate, channels, bits, 0))
    }

    /** How many more frames fit before the file reaches the WAV size limit. */
    fun remainingFrames(): Long = maxFrames - frames

    /** Writes [frameCount] frames starting at sample index [offset] of [src]. */
    fun write(src: FloatArray, offset: Int, frameCount: Int) {
        val samples = frameCount * channels
        val bytes = samples * bytesPerSample
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        val b = scratch
        var j = 0
        if (bits == 24) {
            for (i in offset until offset + samples) {
                var v = Math.round(src[i] * 8388608f)
                if (v > 8388607) v = 8388607 else if (v < -8388608) v = -8388608
                b[j] = v.toByte()
                b[j + 1] = (v shr 8).toByte()
                b[j + 2] = (v shr 16).toByte()
                j += 3
            }
        } else {
            // Triangular dither, one LSB wide, so quiet passages fade into hiss instead of grit.
            var r = rng
            for (i in offset until offset + samples) {
                r = r xor (r shl 13)
                r = r xor (r ushr 17)
                r = r xor (r shl 5)
                val dither = ((r and 0xFFFF) - ((r ushr 16) and 0xFFFF)) / 65536f
                var v = Math.round(src[i] * 32768f + dither)
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                b[j] = v.toByte()
                b[j + 1] = (v shr 8).toByte()
                j += 2
            }
            rng = r
        }
        raf.write(b, 0, bytes)
        frames += frameCount
        if (frames - headerFrames >= sampleRate.toLong() * HEADER_SECONDS) updateHeader()
    }

    private fun updateHeader() {
        val end = raf.filePointer
        raf.seek(0)
        raf.write(header(sampleRate, channels, bits, frames * bytesPerFrame))
        raf.seek(end)
        headerFrames = frames
    }

    override fun close() = close(-1)

    /** Finishes the file. If [keepFrames] is shorter than what was written, the end is cut off. */
    fun close(keepFrames: Long) {
        if (closed) return
        closed = true
        try {
            if (keepFrames in 0 until frames) {
                frames = keepFrames
                raf.setLength(HEADER_BYTES + frames * bytesPerFrame)
                raf.seek(raf.length())
            }
            updateHeader()
        } finally {
            raf.close()
        }
    }

    class Info(val sampleRate: Int, val channels: Int, val bits: Int, val frames: Long) {
        val seconds: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
    }

    companion object {
        const val HEADER_BYTES = 44
        const val HEADER_SECONDS = 5

        /** WAV sizes are 32-bit, so a file tops out just under 4 GB; longer takes roll into a new part. */
        const val DEFAULT_MAX_DATA_BYTES = 4_000_000_000L

        fun header(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
            val blockAlign = channels * bits / 8
            val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(channels.toShort())
            b.putInt(sampleRate)
            b.putInt(sampleRate * blockAlign)
            b.putShort(blockAlign.toShort())
            b.putShort(bits.toShort())
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
            return b.array()
        }

        /**
         * Reads the format of a file written by this class. The length comes from the file size
         * rather than the header, so it is right even for a recording that was cut off.
         */
        fun info(file: File): Info? {
            try {
                if (file.length() < HEADER_BYTES) return null
                val head = ByteArray(HEADER_BYTES)
                RandomAccessFile(file, "r").use { it.readFully(head) }
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return null
                if (String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                if (String(head, 36, 4, Charsets.US_ASCII) != "data") return null
                val channels = b.getShort(22).toInt()
                val rate = b.getInt(24)
                val blockAlign = b.getShort(32).toInt()
                val bits = b.getShort(34).toInt()
                if (channels <= 0 || rate <= 0 || blockAlign <= 0) return null
                return Info(rate, channels, bits, (file.length() - HEADER_BYTES) / blockAlign)
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * Fixes the size fields of a recording that was interrupted before it could be closed.
         * Returns true if the file needed fixing.
         */
        fun repair(file: File): Boolean {
            val info = info(file) ?: return false
            val blockAlign = info.channels * info.bits / 8
            val dataBytes = info.frames * blockAlign
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    val head = ByteArray(HEADER_BYTES)
                    raf.readFully(head)
                    val stored = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
                    val whole = HEADER_BYTES + dataBytes
                    if (stored == dataBytes.toInt() && raf.length() == whole) return false
                    raf.setLength(whole)
                    raf.seek(0)
                    raf.write(header(info.sampleRate, info.channels, info.bits, dataBytes))
                }
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }
}
