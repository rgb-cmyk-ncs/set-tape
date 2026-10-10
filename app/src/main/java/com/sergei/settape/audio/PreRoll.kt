package com.sergei.settape.audio

/**
 * Ring buffer holding the most recent audio while the recorder is armed, so a take that
 * starts on a level trigger still contains the moments just before the trigger fired.
 *
 * Capacity and every write are whole frames, so interleaved channels stay aligned.
 */
class PreRoll(frames: Int, private val channels: Int) {
    private val buf = FloatArray(maxOf(frames, 1) * channels)
    private var pos = 0
    private var filled = 0

    fun clear() {
        pos = 0
        filled = 0
    }

    /** Appends [samples] interleaved samples from the start of [src]. */
    fun write(src: FloatArray, samples: Int) {
        var from = 0
        var left = samples
        if (left > buf.size) {
            from = left - buf.size
            left = buf.size
        }
        while (left > 0) {
            val n = minOf(left, buf.size - pos)
            System.arraycopy(src, from, buf, pos, n)
            pos = (pos + n) % buf.size
            from += n
            left -= n
        }
        filled = minOf(buf.size, filled + minOf(samples, buf.size))
    }

    /** Hands the buffered audio to [sink] oldest first, as (array, sampleOffset, frameCount). */
    fun drain(sink: (FloatArray, Int, Int) -> Unit) {
        if (filled == 0) return
        val start = (pos - filled + buf.size) % buf.size
        val first = minOf(filled, buf.size - start)
        sink(buf, start, first / channels)
        val rest = filled - first
        if (rest > 0) sink(buf, 0, rest / channels)
    }
}
