package com.sergei.settape

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.sergei.settape.audio.Db
import com.sergei.settape.audio.Pipeline
import java.io.File

/** The whole app is this one screen: levels, the record button, a few settings and the recordings. */
class MainActivity : Activity(), Engine.Listener {

    private class Row(val line: LinearLayout, val value: TextView)

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val meters = Pipeline.Meters()
    private val pairMax = FloatArray(Pipeline.MAX_PAIRS)
    private var frame = 0
    private var askedForPermission = false
    private var resumed = false

    private lateinit var statusView: TextView
    private lateinit var timerView: TextView
    private lateinit var sourceView: TextView
    private lateinit var detailView: TextView
    private lateinit var meter: MeterView
    private lateinit var peakView: TextView
    private lateinit var clipView: TextView
    private lateinit var adviceView: TextView
    private lateinit var gainValue: TextView
    private lateinit var gainBar: SeekBar
    private lateinit var button: RecordButton
    private lateinit var hintView: TextView
    private lateinit var inputRow: Row
    private lateinit var pairRow: Row
    private lateinit var finderView: TextView
    private lateinit var rateRow: Row
    private lateinit var bitsRow: Row
    private lateinit var thresholdRow: Row
    private lateinit var holdRow: Row
    private lateinit var folderView: TextView
    private lateinit var recordingsBox: LinearLayout

    private val tick = object : Runnable {
        override fun run() {
            if (Engine.readMeters(meters)) {
                meter.push(meters.channels, meters.peak)
                for (p in 0 until meters.pairs) if (meters.pairPeak[p] > pairMax[p]) pairMax[p] = meters.pairPeak[p]
            } else {
                meter.clear()
            }
            if (++frame % 8 == 0) refreshLive()
            handler.postDelayed(this, 33)
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildScreen()
        Engine.addListener(this)
    }

    override fun onDestroy() {
        Engine.removeListener(this)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        Engine.uiVisible = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasMicPermission()) {
            openInput()
        } else if (!askedForPermission) {
            askForPermissions()
        }
        refreshState()
        refreshRecordings()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        Engine.uiVisible = false
        // Just showing levels is not worth holding the input for once the screen is gone.
        // Armed or recording carries on in the background.
        if (!isChangingConfigurations && Engine.state == Engine.State.MONITOR) Engine.close()
        super.onStop()
    }

    // ---- permissions ----

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askForPermissions() {
        askedForPermission = true
        val wanted = ArrayList<String>()
        wanted.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 29) wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMicPermission()) openInput()
        refreshState()
    }

    private fun openInput() {
        if (Engine.state == Engine.State.IDLE) Engine.open(this)
        if (Engine.state != Engine.State.IDLE) RecorderService.start(this)
    }

    private fun reopenInput() {
        Engine.reopen(this)
        if (Engine.state != Engine.State.IDLE) RecorderService.start(this)
        refreshState()
    }

    // ---- the record button ----

    private fun onButton() {
        if (!hasMicPermission()) {
            if (!askedForPermission || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                askForPermissions()
            } else {
                // Android has stopped showing the prompt, so the switch is in the app's settings page.
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
            return
        }
        Engine.toggle(this)
        if (Engine.state != Engine.State.IDLE) RecorderService.start(this)
        refreshState()
    }

    // ---- engine callbacks ----

    override fun onEngineChanged() {
        // The input can come back by itself (a mixer was plugged in); make sure the
        // background service is there for it.
        if (resumed && Engine.state != Engine.State.IDLE && !RecorderService.running) RecorderService.start(this)
        refreshState()
    }

    override fun onEngineMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshState()
    }

    override fun onRecordingsChanged() {
        refreshRecordings()
    }

    // ---- keeping the screen up to date ----

    /** Everything that only changes when the state, the input or a setting changes. */
    private fun refreshState() {
        val state = Engine.state
        val info = Engine.info
        val busy = Engine.busy

        when {
            !hasMicPermission() -> setStatus("MICROPHONE ACCESS NEEDED", Ui.AMBER)
            state == Engine.State.RECORDING -> setStatus(if (prefs.auto) "RECORDING · AUTO" else "RECORDING", Ui.RED)
            state == Engine.State.ARMED -> setStatus("ARMED · WAITING FOR SOUND", Ui.AMBER)
            state == Engine.State.MONITOR -> setStatus("READY", Ui.GREEN)
            else -> setStatus("NO INPUT", Ui.DIM)
        }

        button.mode = if (state == Engine.State.IDLE) Engine.State.MONITOR else state
        hintView.text = when {
            !hasMicPermission() -> "Tap to allow recording"
            state == Engine.State.RECORDING -> "Tap to stop"
            state == Engine.State.ARMED -> "Tap to disarm"
            prefs.auto -> "Tap to arm. Recording starts when sound arrives."
            else -> "Tap to record"
        }

        sourceView.text = if (info == null) {
            "No audio input open"
        } else {
            val rate = if (info.sampleRate % 1000 == 0) "${info.sampleRate / 1000} kHz" else "${info.sampleRate / 1000.0} kHz"
            val chans = when {
                info.deviceChannels > 2 -> "ch ${info.pair * 2 + 1}/${info.pair * 2 + 2}"
                info.channels == 1 -> "mono"
                else -> "stereo"
            }
            "${info.deviceLabel} · $chans · $rate · ${info.bits}-bit"
        }
        // Make it obvious when a mixer was expected but the phone's own microphone is in use.
        sourceView.setTextColor(if (info != null && !info.usb) Ui.AMBER else Ui.TEXT)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val chosen = prefs.inputKey
        inputRow.value.text = when {
            chosen == Inputs.AUTO -> "Automatic"
            else -> Inputs.list(am).firstOrNull { Inputs.key(it) == chosen }?.let { Inputs.label(it) } ?: "Automatic"
        }
        val multi = info != null && info.deviceChannels > 2
        pairRow.line.visibility = if (multi) View.VISIBLE else View.GONE
        finderView.visibility = if (multi) View.VISIBLE else View.GONE
        if (info != null) pairRow.value.text = "${info.pair * 2 + 1}/${info.pair * 2 + 2}"
        rateRow.value.text = rateLabel(prefs.sampleRate)
        bitsRow.value.text = "${prefs.bits}-bit WAV"
        thresholdRow.value.text = "${prefs.thresholdDb} dB"
        holdRow.value.text = holdLabel(prefs.holdSeconds)
        thresholdRow.line.visibility = if (prefs.auto) View.VISIBLE else View.GONE
        holdRow.line.visibility = if (prefs.auto) View.VISIBLE else View.GONE
        meter.thresholdDb = if (prefs.auto) prefs.thresholdDb.toFloat() else null

        // The input can't be swapped in the middle of a take.
        for (row in listOf(inputRow, pairRow, rateRow, bitsRow)) {
            row.line.isEnabled = !busy
            row.line.alpha = if (busy) 0.4f else 1f
        }
        refreshLive()
    }

    private fun setStatus(text: String, color: Int) {
        statusView.text = text
        statusView.setTextColor(color)
    }

    /** The numbers that move: called a few times a second. */
    private fun refreshLive() {
        val state = Engine.state
        timerView.text = Recordings.clock(if (state == Engine.State.RECORDING) Engine.takeSeconds else 0)

        val message = Engine.lastMessage
        val perSecond = Engine.bytesPerSecond
        val room = if (perSecond > 0) "room for " + Recordings.roughTime(Engine.freeBytes / perSecond) else ""
        when {
            state == Engine.State.RECORDING -> {
                detailView.text = Recordings.size(Engine.takeBytes) + " · " + room
                detailView.setTextColor(Ui.DIM)
            }
            message != null -> {
                detailView.text = message
                detailView.setTextColor(Ui.AMBER)
            }
            else -> {
                detailView.text = room
                detailView.setTextColor(Ui.DIM)
            }
        }

        if (state == Engine.State.IDLE) {
            peakView.text = "PEAK  --"
            clipView.text = ""
        } else {
            val l = Db.format(Db.fromLinear(meters.maxPeak[0]))
            peakView.text = if (meters.channels == 2) {
                "PEAK  L $l   R ${Db.format(Db.fromLinear(meters.maxPeak[1]))} dB"
            } else {
                "PEAK  $l dB"
            }
            when {
                meters.inputClips > 0 -> {
                    clipView.text = "SOURCE CLIPPED ×${meters.inputClips}"
                    clipView.setTextColor(Ui.RED)
                }
                meters.clips > 0 -> {
                    clipView.text = "CLIPPED ×${meters.clips}"
                    clipView.setTextColor(Ui.RED)
                }
                else -> {
                    clipView.text = "NO CLIPPING"
                    clipView.setTextColor(Ui.GREEN)
                }
            }
        }
        // Say which knob fixes it: the slider here, or the source.
        when {
            state != Engine.State.IDLE && meters.inputClips > 0 -> {
                adviceView.text = "The sound is already clipped when it reaches the phone. Turn it down at the source; " +
                    "the level slider can't repair that. Tap to reset."
                adviceView.setTextColor(Ui.AMBER)
            }
            state != Engine.State.IDLE && meters.clips > 0 -> {
                adviceView.text = "Too hot. Bring the input level down. Tap to reset."
                adviceView.setTextColor(Ui.AMBER)
            }
            else -> {
                adviceView.text = "Highest level since you last tapped here"
                adviceView.setTextColor(Ui.DIM)
            }
        }

        if (meters.pairs > 0 && finderView.visibility == View.VISIBLE) {
            val sb = StringBuilder("Level on each pair now:\n")
            for (p in 0 until meters.pairs) {
                if (p > 0) sb.append(if (p % 3 == 0) "\n" else "    ")
                val name = "${p * 2 + 1}/${p * 2 + 2}"
                sb.append(name.padStart(5)).append(' ').append(Db.format(Db.fromLinear(pairMax[p])).padStart(6))
                pairMax[p] = 0f
            }
            finderView.text = sb
        }
    }

    private fun refreshRecordings() {
        val dir = Recordings.dir(this)
        val shared = dir.absolutePath.contains("/Music/${Recordings.FOLDER}") && !dir.absolutePath.contains("/Android/data/")
        folderView.text = if (shared) "Saved on this device in Music/${Recordings.FOLDER}" else "Saved in ${dir.absolutePath}"

        val active = Engine.currentFile
        val items = Recordings.list(dir, active).filter { it.file != active }
        recordingsBox.removeAllViews()
        if (items.isEmpty()) {
            recordingsBox.addView(label("Nothing recorded yet.", 15f, Ui.DIM).apply { setPadding(0, dp(12), 0, dp(12)) })
            return
        }
        for (item in items) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                background = ripple()
                setOnClickListener { recordingMenu(item) }
            }
            line.addView(label(item.title, 16f, Ui.TEXT))
            line.addView(label(Recordings.clock(item.seconds.toLong()) + " · " + Recordings.size(item.bytes), 13f, Ui.DIM).apply {
                typeface = Typeface.MONOSPACE
            })
            recordingsBox.addView(line, matchWrap())
            recordingsBox.addView(divider())
        }
    }

    // ---- recordings: play, share, delete ----

    private fun recordingMenu(item: Recordings.Item) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("Play", "Share", "Delete")) { _, which ->
                when (which) {
                    0 -> withUri(item.file) { uri ->
                        launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, WAV).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                    1 -> withUri(item.file) { uri ->
                        val send = Intent(Intent.ACTION_SEND).setType(WAV).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        launch(Intent.createChooser(send, "Share recording"))
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("Delete ${item.title}? This can't be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            if (!item.file.delete()) toast("Android would not let the app delete this file.")
                            MediaScannerConnection.scanFile(applicationContext, arrayOf(item.file.absolutePath), null, null)
                            refreshRecordings()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    /** Other apps can only open the file through Android's media library, so look up its address there. */
    private fun withUri(file: File, then: (Uri) -> Unit) {
        MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), arrayOf(WAV)) { _, uri ->
            runOnUiThread {
                if (uri != null) then(uri) else toast("This file can only be reached from a file manager: ${file.absolutePath}")
            }
        }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No app on this device can open WAV files.")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- settings ----

    private fun pickInput() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = Inputs.list(am)
        val keys = listOf(Inputs.AUTO) + devices.map { Inputs.key(it) }
        val names = listOf("Automatic (USB when connected)") + devices.map { Inputs.label(it) }
        choose("Input", names, keys.indexOf(prefs.inputKey).coerceAtLeast(0)) { i ->
            prefs.inputKey = keys[i]
            prefs.pair = 0
            reopenInput()
        }
    }

    private fun pickPair() {
        val info = Engine.info ?: return
        val pairs = info.deviceChannels / 2
        val names = (0 until pairs).map { "Channels ${it * 2 + 1}/${it * 2 + 2}" }
        choose("Record which channels?", names, info.pair) { i ->
            prefs.pair = i
            reopenInput()
        }
    }

    private fun pickRate() {
        val rates = Prefs.SAMPLE_RATES
        choose("Sample rate", rates.map { rateLabel(it) }, rates.indexOf(prefs.sampleRate).coerceAtLeast(0)) { i ->
            prefs.sampleRate = rates[i]
            reopenInput()
        }
    }

    private fun pickBits() {
        val names = listOf("24-bit (full quality, about 1 GB per hour)", "16-bit (CD quality, about 650 MB per hour)")
        choose("File quality", names, if (prefs.bits == 16) 1 else 0) { i ->
            prefs.bits = if (i == 1) 16 else 24
            reopenInput()
        }
    }

    private fun pickHold() {
        val choices = Prefs.HOLD_CHOICES
        choose("Stop after this much silence", choices.map { holdLabel(it) }, choices.indexOf(prefs.holdSeconds).coerceAtLeast(0)) { i ->
            prefs.holdSeconds = choices[i]
            Engine.applyAutoSettings(this)
            refreshState()
        }
    }

    private fun pickThreshold() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val value = label("", 22f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE }
        val help = label(
            "Recording starts when the level rises above the white line on the meter, and stops once it has stayed below it. " +
                "Set it a little above the noise you see with nothing playing.",
            14f, Ui.DIM,
        )
        val bar = SeekBar(this).apply {
            max = Prefs.THRESHOLD_MAX - Prefs.THRESHOLD_MIN
            progress = prefs.thresholdDb - Prefs.THRESHOLD_MIN
        }
        fun show(db: Int) {
            value.text = "$db dB"
            meter.thresholdDb = db.toFloat()
        }
        show(prefs.thresholdDb)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = show(progress + Prefs.THRESHOLD_MIN)
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        box.addView(value)
        box.addView(bar, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(12) })
        box.addView(help)
        AlertDialog.Builder(this)
            .setTitle("Auto-record level")
            .setView(box)
            .setPositiveButton("Done") { _, _ ->
                prefs.thresholdDb = bar.progress + Prefs.THRESHOLD_MIN
                Engine.applyAutoSettings(this)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { refreshState() }
            .show()
    }

    private fun choose(title: String, names: List<String>, current: Int, picked: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) picked(which)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rateLabel(rate: Int): String = when (rate) {
        0 -> "Automatic"
        44100 -> "44.1 kHz"
        else -> "${rate / 1000} kHz"
    }

    private fun holdLabel(seconds: Int): String =
        if (seconds < 60) "$seconds s of silence" else "${seconds / 60} min of silence"

    // ---- building the screen ----

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        statusView = label("", 13f, Ui.DIM).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        timerView = label("00:00:00", 56f, Ui.TEXT).apply {
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
        }
        sourceView = label("", 15f, Ui.TEXT)
        detailView = label("", 13f, Ui.DIM)
        root.addView(statusView)
        root.addView(timerView, matchWrap().apply { topMargin = dp(6) })
        root.addView(sourceView, matchWrap().apply { topMargin = dp(6) })
        root.addView(detailView, matchWrap().apply { topMargin = dp(2) })

        // Levels. Tapping anywhere on the panel clears the peak and clip readouts.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Ui.PANEL)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                Engine.resetMeters()
                refreshLive()
            }
        }
        meter = MeterView(this)
        panel.addView(meter, matchWrap())
        val readout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        peakView = label("PEAK  --", 14f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE }
        clipView = label("", 14f, Ui.GREEN).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
        }
        readout.addView(peakView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        readout.addView(clipView)
        panel.addView(readout, matchWrap().apply { topMargin = dp(10) })
        adviceView = label("Highest level since you last tapped here", 12f, Ui.DIM)
        panel.addView(adviceView, matchWrap().apply { topMargin = dp(2) })

        // Input level, right under the meter so it can be set while watching it.
        val gainHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gainValue = label("", 14f, Ui.TEXT).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            setPadding(dp(12), dp(8), 0, dp(8))
            contentDescription = "Reset input level to 0 dB"
            setOnClickListener { setGain(0, moveBar = true) }
        }
        gainHead.addView(label("INPUT LEVEL", 12f, Ui.DIM).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gainHead.addView(gainValue)
        panel.addView(gainHead, matchWrap().apply { topMargin = dp(12) })

        gainBar = SeekBar(this).apply {
            max = Prefs.GAIN_RANGE_DB * 4 // half-dB steps
            progress = prefs.gainHalfDb + Prefs.GAIN_RANGE_DB * 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setGain(progress - Prefs.GAIN_RANGE_DB * 2, moveBar = false)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // The old peak and clip readings belong to the old level.
                    Engine.resetMeters()
                }
            })
        }
        panel.addView(gainBar, matchWrap())
        panel.addView(
            label("Tap the number to go back to 0 dB, which records exactly what comes in.", 12f, Ui.DIM),
            matchWrap().apply { topMargin = dp(2) },
        )
        showGain()
        root.addView(panel, matchWrap().apply { topMargin = dp(18) })

        // The button.
        button = RecordButton(this).apply { setOnClickListener { onButton() } }
        root.addView(button, LinearLayout.LayoutParams(dp(132), dp(132)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(26)
        })
        hintView = label("", 14f, Ui.DIM).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(hintView, matchWrap().apply { topMargin = dp(10) })

        // Settings.
        root.addView(heading("AUTO START / STOP"), matchWrap().apply { topMargin = dp(30) })
        val autoSwitch = Switch(this).apply {
            text = "Start on sound, stop on silence"
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minimumHeight = dp(52)
            isChecked = prefs.auto
            setOnCheckedChangeListener { _, checked ->
                prefs.auto = checked
                if (!checked && Engine.state == Engine.State.ARMED) Engine.stopTake()
                refreshState()
            }
        }
        root.addView(autoSwitch, matchWrap())
        thresholdRow = row(root, "Trigger level") { pickThreshold() }
        holdRow = row(root, "Stop after") { pickHold() }

        root.addView(heading("INPUT"), matchWrap().apply { topMargin = dp(22) })
        inputRow = row(root, "Record from") { pickInput() }
        pairRow = row(root, "Channels") { pickPair() }
        finderView = label("", 13f, Ui.DIM).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(finderView, matchWrap())
        rateRow = row(root, "Sample rate") { pickRate() }
        bitsRow = row(root, "Quality") { pickBits() }

        root.addView(heading("RECORDINGS"), matchWrap().apply { topMargin = dp(22) })
        folderView = label("", 13f, Ui.DIM)
        root.addView(folderView, matchWrap().apply { bottomMargin = dp(4) })
        recordingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recordingsBox, matchWrap())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun setGain(halfDb: Int, moveBar: Boolean) {
        prefs.gainHalfDb = halfDb
        if (moveBar) {
            gainBar.progress = halfDb + Prefs.GAIN_RANGE_DB * 2
            Engine.resetMeters()
        }
        Engine.applyGain(this)
        showGain()
    }

    private fun showGain() {
        val db = prefs.gainDb
        gainValue.text = (if (db > 0f) "+" else "") + String.format(java.util.Locale.US, "%.1f dB", db)
        gainValue.setTextColor(if (db == 0f) Ui.DIM else Ui.TEXT)
    }

    private fun row(parent: LinearLayout, title: String, onClick: () -> Unit): Row {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            background = ripple()
            setOnClickListener { onClick() }
        }
        val value = label("", 15f, Ui.DIM).apply { gravity = Gravity.END }
        line.addView(label(title, 16f, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
        })
        parent.addView(line, matchWrap())
        return Row(line, value)
    }

    private fun heading(text: String): TextView = label(text, 12f, Ui.DIM).apply {
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun ripple() = TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        getDrawable(tv.resourceId)
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = Ui.dp(this, value.toFloat())

    private companion object {
        const val WAV = "audio/x-wav"
    }
}
