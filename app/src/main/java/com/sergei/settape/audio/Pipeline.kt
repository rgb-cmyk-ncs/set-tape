package com.sergei.settape.audio

import java.io.File

/**
 * Everything that happens to the audio between the input and the file: picking the channels
 * to record, metering, clip detection, the auto start/stop trigger and writing takes.
 *
 * It has no Android dependencies, so it can be tested on a normal JVM. [process] is called
 * from the audio thread; the control methods may be called from any thread.
 */
class Pipeline(
    val sampleRate: Int,
    val deviceChannels: Int,
    selected: IntArray,
    val bits: Int,
    private val files: TakeFiles,
    private val listener: Listener,
    prerollSeconds: Float = PREROLL_SECONDS,
    private val maxDataBytes: Long = WavWriter.DEFAULT_MAX_DATA_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { MONITOR, ARMED, RECORDING }

    fun interface TakeFiles {
        /** The file for part [part] (1-based) of the take that started at [startMillis]. */
        fun fileFor(startMillis: Long, part: Int): File
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onTakeFinished(files: List<File>, frames: Long)
        fun onError(message: String)
    }

    /** A snapshot of the meters, filled in by [readMeters]. Values are linear, 1.0 = full scale. */
    class Meters {
        var channels = 0
        /** Highest sample per recorded channel since the previous read. */
        val peak = FloatArray(2)
        /** Highest sample per recorded channel since the last reset. */
        val maxPeak = FloatArray(2)
        /** Times the recorded signal (after the input level control) hit full scale since the last reset. */
        var clips = 0
        /** Times the signal was already at full scale as it arrived, before the level control. */
        var inputClips = 0
        var pairs = 0
        /** Highest sample per input pair (1/2, 3/4, ...) since the previous read. */
        val pairPeak = FloatArray(MAX_PAIRS)
    }

    private val lock = Any()
    private val meterLock = Any()

    private val sel = selected.copyOf()
    val channels = sel.size
    private val bytesPerFrame = channels * bits / 8

    private var work = FloatArray(0)
    private val preroll = PreRoll((prerollSeconds * sampleRate).toInt(), channels)
    private val startFrames = (START_SECONDS * sampleRate).toInt()
    private val tailFrames = (TAIL_SECONDS * sampleRate).toLong()

    @Volatile
    var state = State.MONITOR
        private set

    /** Frames written to the current take (all parts together). */
    @Volatile
    var takeFrames = 0L
        private set

    @Volatile
    var currentFile: File? = null
        private set

    val takeBytes: Long get() = takeFrames * bytesPerFrame
    val bytesPerSecond: Long get() = sampleRate.toLong() * bytesPerFrame

    // Input level. The audio thread glides from [gain] to [targetGain] over one block,
    // so moving the slider during a take doesn't click.
    @Volatile private var targetGain = 1f
    private var gain = 1f

    private var auto = false
    private var thresholdLin = Db.toLinear(-40f)
    private var holdFrames = 30L * sampleRate
    private var aboveFrames = 0
    private var lastLoudFrame = 0L

    private var writer: WavWriter? = null
    private var takeFiles = ArrayList<File>()
    private var takeStartMillis = 0L
    private var part = 1
    private var partStartFrame = 0L

    // Meter state, guarded by meterLock.
    private val clipRun = IntArray(channels)
    private val inputClipRun = IntArray(channels)
    private var inputClips = 0
    private val blockPeak = FloatArray(channels)
    private val heldPeak = FloatArray(channels)
    private val maxPeak = FloatArray(channels)
    private var clips = 0
    private val pairCount = minOf(MAX_PAIRS, (deviceChannels + 1) / 2)
    private val pairBlock = FloatArray(pairCount)
    private val pairHeld = FloatArray(pairCount)

    init {
        require(channels in 1..2) { "Record one or two channels" }
        require(sel.all { it in 0 until deviceChannels }) { "Channel outside the input" }
    }

    /** Feeds one block of interleaved input ([frames] frames of [deviceChannels] channels). */
    fun process(buf: FloatArray, frames: Int) {
        if (frames <= 0) return
        val n = frames * channels
        if (work.size < n) work = FloatArray(n)
        val w = work

        // Pull out the channels being recorded, measuring them on the way.
        var loudest = 0f
        var newClips = 0
        var newInputClips = 0
        val g0 = gain
        val g1 = targetGain
        val gStep = (g1 - g0) / frames
        for (c in 0 until channels) {
            var peak = 0f
            var run = clipRun[c]
            var inRun = inputClipRun[c]
            var g = g0
            var i = sel[c]
            var o = c
            for (f in 0 until frames) {
                val x = buf[i]
                // Clipping that happened before the audio got here: no level setting can undo it.
                if (x >= CLIP_LEVEL || x <= -CLIP_LEVEL) {
                    inRun++
                    if (inRun == CLIP_RUN) newInputClips++
                } else {
                    inRun = 0
                }
                val y = x * g
                w[o] = y
                val a = if (y < 0f) -y else y
                if (a > peak) peak = a
                if (a >= CLIP_LEVEL) {
                    run++
                    if (run == CLIP_RUN) newClips++
                } else {
                    run = 0
                }
                g += gStep
                i += deviceChannels
                o += channels
            }
            clipRun[c] = run
            inputClipRun[c] = inRun
            blockPeak[c] = peak
            if (peak > loudest) loudest = peak
        }
        gain = g1

        // A rough level for every input pair, so the right pair can be found on a multichannel device.
        if (deviceChannels > 2) {
            for (p in 0 until pairCount) pairBlock[p] = 0f
            val total = frames * deviceChannels
            var ch = 0
            for (i in 0 until total) {
                val x = buf[i]
                val a = if (x < 0f) -x else x
                val p = ch shr 1
                if (p < pairCount && a > pairBlock[p]) pairBlock[p] = a
                ch++
                if (ch == deviceChannels) ch = 0
            }
        }

        synchronized(meterLock) {
            for (c in 0 until channels) {
                if (blockPeak[c] > heldPeak[c]) heldPeak[c] = blockPeak[c]
                if (blockPeak[c] > maxPeak[c]) maxPeak[c] = blockPeak[c]
            }
            clips += newClips
            inputClips += newInputClips
            if (deviceChannels > 2) {
                for (p in 0 until pairCount) if (pairBlock[p] > pairHeld[p]) pairHeld[p] = pairBlock[p]
            }
        }

        synchronized(lock) {
            when (state) {
                State.MONITOR -> Unit

                State.ARMED -> {
                    preroll.write(w, n)
                    if (loudest >= thresholdLin) aboveFrames += frames else aboveFrames = 0
                    if (aboveFrames >= startFrames) {
                        aboveFrames = 0
                        if (beginTake()) {
                            preroll.drain { a, offset, count -> writeToTake(a, offset, count) }
                            preroll.clear()
                            lastLoudFrame = takeFrames
                        }
                    }
                }

                State.RECORDING -> {
                    writeToTake(w, 0, frames)
                    if (auto && state == State.RECORDING) {
                        if (loudest >= thresholdLin) {
                            lastLoudFrame = takeFrames
                        } else if (takeFrames - lastLoudFrame >= holdFrames) {
                            // Quiet for long enough: close the take, keeping a short tail, and wait again.
                            endTake(lastLoudFrame + tailFrames)
                            preroll.clear()
                            setState(State.ARMED)
                        }
                    }
                }
            }
        }
    }

    /** Starts recording immediately. Returns false if the file could not be created. */
    fun startManual(): Boolean {
        synchronized(lock) {
            if (state == State.RECORDING) return true
            auto = false
            return beginTake()
        }
    }

    /** Waits for the signal to rise above [thresholdDb], and stops after [holdSeconds] of quiet. */
    fun arm(thresholdDb: Float, holdSeconds: Float) {
        synchronized(lock) {
            setAuto(thresholdDb, holdSeconds)
            if (state != State.RECORDING) {
                auto = true
                aboveFrames = 0
                preroll.clear()
                setState(State.ARMED)
            }
        }
    }

    /** Changes the trigger settings; safe while armed or recording. */
    fun setAuto(thresholdDb: Float, holdSeconds: Float) {
        synchronized(lock) {
            thresholdLin = Db.toLinear(thresholdDb)
            holdFrames = (holdSeconds * sampleRate).toLong()
        }
    }

    /**
     * Sets the input level in dB (0 = record exactly what arrives). The meters, the auto
     * trigger and the file all see the adjusted signal. With [immediate] there is no glide.
     */
    fun setGainDb(db: Float, immediate: Boolean = false) {
        val lin = if (db == 0f) 1f else Db.toLinear(db)
        targetGain = lin
        if (immediate) {
            synchronized(lock) { gain = lin }
        }
    }

    /** Stops recording (or disarms) and goes back to just showing levels. */
    fun stop() {
        synchronized(lock) {
            if (state == State.RECORDING) endTake(-1)
            auto = false
            setState(State.MONITOR)
        }
    }

    fun resetMeters() {
        synchronized(meterLock) {
            for (c in 0 until channels) maxPeak[c] = 0f
            clips = 0
            inputClips = 0
        }
    }

    fun readMeters(out: Meters) {
        synchronized(meterLock) {
            out.channels = channels
            for (c in 0 until channels) {
                out.peak[c] = heldPeak[c]
                out.maxPeak[c] = maxPeak[c]
                heldPeak[c] = 0f
            }
            out.clips = clips
            out.inputClips = inputClips
            out.pairs = if (deviceChannels > 2) pairCount else 0
            for (p in 0 until out.pairs) {
                out.pairPeak[p] = pairHeld[p]
                pairHeld[p] = 0f
            }
        }
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        listener.onStateChanged(s)
    }

    private fun beginTake(): Boolean {
        takeStartMillis = clock()
        part = 1
        partStartFrame = 0
        takeFrames = 0
        takeFiles = ArrayList()
        return try {
            openPart()
            setState(State.RECORDING)
            true
        } catch (e: Exception) {
            writer = null
            currentFile = null
            auto = false
            setState(State.MONITOR)
            listener.onError("Could not create the recording file (${e.message ?: e.javaClass.simpleName})")
            false
        }
    }

    private fun openPart() {
        val f = files.fileFor(takeStartMillis, part)
        f.parentFile?.mkdirs()
        writer = WavWriter(f, sampleRate, channels, bits, maxDataBytes)
        takeFiles.add(f)
        currentFile = f
    }

    private fun writeToTake(a: FloatArray, offset: Int, frames: Int) {
        var o = offset
        var left = frames
        try {
            while (left > 0) {
                var wr = writer ?: return
                if (wr.remainingFrames() <= 0L) {
                    // The file is at the WAV size limit: carry straight on in a new part.
                    wr.close()
                    part++
                    partStartFrame = takeFrames
                    openPart()
                    wr = writer ?: return
                }
                val k = minOf(left.toLong(), wr.remainingFrames()).toInt()
                wr.write(a, o, k)
                o += k * channels
                left -= k
                takeFrames += k
            }
        } catch (e: Exception) {
            // Usually a full disk. Keep what was written and stop cleanly.
            endTake(-1)
            auto = false
            setState(State.MONITOR)
            listener.onError("Recording stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Closes the take. A [trimTo] inside the take cuts it there (used to drop trailing silence). */
    private fun endTake(trimTo: Long) {
        val wr = writer ?: return
        writer = null
        var frames = takeFrames
        try {
            val keepInPart = trimTo - partStartFrame
            if (trimTo in 1 until takeFrames && keepInPart > 0) {
                wr.close(keepInPart)
                frames = trimTo
            } else {
                wr.close()
            }
        } catch (e: Exception) {
            listener.onError("The recording may be incomplete: ${e.message ?: e.javaClass.simpleName}")
        }
        currentFile = null
        listener.onTakeFinished(ArrayList(takeFiles), frames)
    }

    companion object {
        const val MAX_PAIRS = 16

        /** Audio kept from before the trigger fired. */
        const val PREROLL_SECONDS = 2f

        /** The signal has to stay above the threshold this long to start a take, so a cable pop won't. */
        const val START_SECONDS = 0.25f

        /** Quiet audio kept after the last loud moment when an auto take is closed. */
        const val TAIL_SECONDS = 2f

        /** Samples this close to full scale, several in a row, count as clipping. */
        const val CLIP_LEVEL = 0.999f
        const val CLIP_RUN = 3
    }
}
