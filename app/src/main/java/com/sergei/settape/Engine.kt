package com.sergei.settape

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.sergei.settape.audio.Pipeline
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the audio input for the whole app process: opens the device, runs the read thread and
 * feeds the [Pipeline]. The screen and the background service both just watch this object,
 * so recording carries on whichever of them is alive.
 *
 * Call everything here from the main thread.
 */
object Engine {
    enum class State { IDLE, MONITOR, ARMED, RECORDING }

    /** What is actually open right now, as Android reports it. */
    class Info(
        val deviceLabel: String,
        val usb: Boolean,
        val sampleRate: Int,
        val deviceChannels: Int,
        val pair: Int,
        val channels: Int,
        val bits: Int,
    )

    interface Listener {
        /** State, input or take changed. */
        fun onEngineChanged()

        /** Something the user should be told. */
        fun onEngineMessage(message: String)

        /** A file was finished. */
        fun onRecordingsChanged()
    }

    private const val MIN_FREE_BYTES = 100L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var app: Context? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var deviceCallbackRegistered = false
    private var routedDeviceId = -1 // only set once Android has confirmed the route
    private var wantedKey: String? = null
    private var takeBase = ""
    private var takeBaseFor = -1L

    @Volatile private var running = false
    @Volatile private var pipeline: Pipeline? = null

    @Volatile var info: Info? = null
        private set

    @Volatile var freeBytes = 0L
        private set

    /** Set by the screen, so a newly plugged-in device is only picked up while someone is looking. */
    var uiVisible = false

    /** The last thing reported through [Listener.onEngineMessage]; shown until the next change. */
    var lastMessage: String? = null
        private set

    val state: State
        get() = when (pipeline?.state) {
            null -> State.IDLE
            Pipeline.State.MONITOR -> State.MONITOR
            Pipeline.State.ARMED -> State.ARMED
            Pipeline.State.RECORDING -> State.RECORDING
        }

    val busy: Boolean get() = state == State.ARMED || state == State.RECORDING

    val takeSeconds: Long get() = pipeline?.let { it.takeFrames / it.sampleRate } ?: 0
    val takeBytes: Long get() = pipeline?.takeBytes ?: 0
    val bytesPerSecond: Long get() = pipeline?.bytesPerSecond ?: 0
    val currentFile: File? get() = pipeline?.currentFile

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun readMeters(out: Pipeline.Meters): Boolean {
        val p = pipeline ?: return false
        p.readMeters(out)
        return true
    }

    fun resetMeters() { pipeline?.resetMeters() }

    /** Opens the input and starts showing levels. Returns false (with a message) if it can't. */
    fun open(context: Context): Boolean {
        if (pipeline != null) return true
        val ok = openInternal(context.applicationContext)
        changed()
        return ok
    }

    /** Closes the input. Any take in progress is finished first. */
    fun close() {
        if (pipeline == null) return
        closeInternal()
        changed()
    }

    /** Re-opens the input with the current settings (after a setting or device change). */
    fun reopen(context: Context): Boolean {
        if (busy) return true
        closeInternal()
        val ok = openInternal(context.applicationContext)
        changed()
        return ok
    }

    /** The big button: start (or arm), or stop. */
    fun toggle(context: Context) {
        when (state) {
            State.IDLE -> if (open(context)) begin(context)
            State.MONITOR -> begin(context)
            State.ARMED, State.RECORDING -> stopTake()
        }
    }

    fun stopTake() {
        pipeline?.stop()
    }

    /** Pushes changed trigger settings to a running pipeline. */
    fun applyAutoSettings(context: Context) {
        val prefs = Prefs(context)
        pipeline?.setAuto(prefs.thresholdDb.toFloat(), prefs.holdSeconds.toFloat())
    }

    /** Pushes the input level setting to a running pipeline. */
    fun applyGain(context: Context) {
        pipeline?.setGainDb(Prefs(context).gainDb)
    }

    private fun begin(context: Context) {
        val p = pipeline ?: return
        val prefs = Prefs(context)
        lastMessage = null
        p.resetMeters()
        if (prefs.auto) p.arm(prefs.thresholdDb.toFloat(), prefs.holdSeconds.toFloat()) else p.startManual()
    }

    // ---- opening and closing the input ----

    private fun openInternal(ctx: Context): Boolean {
        app = ctx
        val prefs = Prefs(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerDeviceCallback(am)

        val device = Inputs.resolve(am, prefs.inputKey)
        val rec = createRecord(device, prefs.sampleRate)
        if (rec == null) {
            message("Could not open the audio input. Check the microphone permission and the cable.")
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            message("Android refused to start the audio input (${e.message}).")
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            message("The audio input is busy. Close other recording apps and try again.")
            return false
        }

        val ch = rec.channelCount
        val rate = rec.sampleRate
        val pairs = maxOf(1, ch / 2)
        val pair = prefs.pair.coerceIn(0, pairs - 1)
        val selected = if (ch == 1) intArrayOf(0) else intArrayOf(pair * 2, pair * 2 + 1)
        val dir = Recordings.dir(ctx)
        freeBytes = dir.usableSpace

        val p = Pipeline(
            rate, ch, selected, prefs.bits,
            Pipeline.TakeFiles { start, part ->
                if (part == 1 || takeBaseFor != start) {
                    takeBase = Recordings.uniqueBase(dir, start)
                    takeBaseFor = start
                }
                File(dir, if (part == 1) "$takeBase.wav" else "${takeBase}_part$part.wav")
            },
            pipelineListener,
        )
        p.setGainDb(prefs.gainDb, immediate = true)

        // Show what Android says it actually routed, which can differ from what was asked for.
        val confirmed = try { rec.routedDevice } catch (e: Exception) { null }
        val routed = confirmed ?: device
        routedDeviceId = confirmed?.id ?: -1
        wantedKey = device?.let { Inputs.key(it) }
        info = Info(
            deviceLabel = routed?.let { Inputs.label(it) } ?: "Default input",
            usb = routed?.let { Inputs.isUsb(it) } ?: false,
            sampleRate = rate,
            deviceChannels = ch,
            pair = pair,
            channels = selected.size,
            bits = prefs.bits,
        )

        record = rec
        pipeline = p
        running = true
        try {
            rec.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { routeChanged(rec, p) }, main)
        } catch (e: Exception) { }
        thread = Thread({ readLoop(rec, p, dir) }, "SetTape-audio").also { it.start() }
        return true
    }

    private fun closeInternal() {
        val p = pipeline
        val rec = record
        running = false
        p?.stop() // finishes the file before the input goes away
        try { rec?.stop() } catch (e: Exception) { }
        try { thread?.join(1500) } catch (e: InterruptedException) { }
        try { rec?.release() } catch (e: Exception) { }
        thread = null
        record = null
        pipeline = null
        info = null
        routedDeviceId = -1
        wantedKey = null
    }

    /**
     * Tries the richest format first and steps down until Android accepts one: all of the
     * device's channels (so any stereo pair can be picked), then 8, then plain stereo, then mono.
     */
    @SuppressLint("MissingPermission")
    private fun createRecord(device: AudioDeviceInfo?, wantedRate: Int): AudioRecord? {
        val max = minOf(Inputs.maxChannels(device), Pipeline.MAX_PAIRS * 2)
        val channelTries = listOf(max, 8, 2, 1).filter { it <= max }.distinct()
        val rateTries = listOf(Inputs.preferredRate(device, wantedRate), 48000, 44100).distinct()
        // UNPROCESSED means no automatic gain or noise suppression; VOICE_RECOGNITION is the
        // closest thing on phones that don't offer it.
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val raw = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sourceTries = listOfNotNull(
            if (raw) MediaRecorder.AudioSource.UNPROCESSED else null,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (rate in rateTries) for (ch in channelTries) for (source in sourceTries) {
            val rec = tryCreate(source, rate, ch) ?: continue
            if (device != null) {
                try { rec.preferredDevice = device } catch (e: Exception) { }
            }
            return rec
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryCreate(source: Int, rate: Int, ch: Int): AudioRecord? {
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .apply {
                    when (ch) {
                        1 -> setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        2 -> setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        else -> setChannelIndexMask((1 shl ch) - 1)
                    }
                }
                .build()
            // About a second of buffer, so a slow moment on the storage can't cause a dropout.
            val oneSecond = rate * ch * 4
            val min = AudioRecord.getMinBufferSize(
                rate,
                if (ch == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(oneSecond, min * 2))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED || rec.channelCount != ch) {
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- the audio thread ----

    private fun readLoop(rec: AudioRecord, p: Pipeline, dir: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val ch = p.deviceChannels
        val buf = FloatArray(maxOf(p.sampleRate / 50, 64) * ch) // 20 ms blocks
        var blocks = 0
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                -1
            }
            if (!running) break
            if (n < 0) {
                main.post { inputLost(p) }
                break
            }
            if (n >= ch) p.process(buf, n / ch)

            if (++blocks % 100 == 0) { // every two seconds
                val free = dir.usableSpace
                freeBytes = free
                if (free < MIN_FREE_BYTES && p.state == Pipeline.State.RECORDING) {
                    p.stop()
                    main.post { message("Storage is almost full, so the recording was stopped and saved.") }
                }
            }
        }
    }

    private fun inputLost(p: Pipeline) {
        if (pipeline !== p) return
        val wasBusy = busy
        closeInternal()
        message(if (wasBusy) "The input went away. What was recorded so far is saved." else "The input went away.")
        val ctx = app
        if (uiVisible && ctx != null) openInternal(ctx)
        changed()
    }

    /** Android moved the recording to a different device (or has only now said which one it is). */
    private fun routeChanged(rec: AudioRecord, p: Pipeline) {
        if (pipeline !== p) return
        val d = try { rec.routedDevice } catch (e: Exception) { null } ?: return
        if (d.id == routedDeviceId) return
        val old = info ?: return
        if (routedDeviceId >= 0 && old.usb && !Inputs.isUsb(d)) {
            // It was on the USB device and now is not: treat it as unplugged.
            inputLost(p)
            return
        }
        routedDeviceId = d.id
        info = Info(Inputs.label(d), Inputs.isUsb(d), old.sampleRate, old.deviceChannels, old.pair, old.channels, old.bits)
        changed()
    }

    private val pipelineListener = object : Pipeline.Listener {
        override fun onStateChanged(state: Pipeline.State) {
            main.post { changed() }
        }

        override fun onTakeFinished(files: List<File>, frames: Long) {
            main.post {
                val ctx = app
                if (ctx != null) {
                    // Tell Android's media library, so the files appear in other apps straight away.
                    try {
                        MediaScannerConnection.scanFile(ctx, files.map { it.absolutePath }.toTypedArray(), null, null)
                    } catch (e: Exception) { }
                }
                for (l in listeners) l.onRecordingsChanged()
            }
        }

        override fun onError(message: String) {
            main.post { message(message) }
        }
    }

    // ---- plugging and unplugging ----

    private fun registerDeviceCallback(am: AudioManager) {
        if (deviceCallbackRegistered) return
        deviceCallbackRegistered = true
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            private var first = true

            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (first) { // Android reports everything already connected once, straight away
                    first = false
                    return
                }
                if (added.none { it.isSource }) return
                val ctx = app ?: return
                // Pick up a newly plugged-in mixer, but never switch inputs under a running take.
                if (uiVisible && !busy) reopen(ctx) else changed()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                val p = pipeline
                if (p == null) {
                    changed()
                    return
                }
                val usb = info?.usb == true
                if (removed.any { it.id == routedDeviceId || (usb && Inputs.key(it) == wantedKey) }) {
                    // Stop here rather than silently carrying on through the phone's microphone.
                    inputLost(p)
                } else {
                    changed()
                }
            }
        }, main)
    }

    private fun message(text: String) {
        lastMessage = text
        for (l in listeners) l.onEngineMessage(text)
    }

    private fun changed() {
        for (l in listeners) l.onEngineChanged()
    }
}
