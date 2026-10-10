package com.sergei.settape.audio

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTests {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("settape").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- helpers ----

    private fun read24(file: File): IntArray {
        val bytes = file.readBytes()
        val n = (bytes.size - 44) / 3
        return IntArray(n) { i ->
            val o = 44 + i * 3
            (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() shl 16)
        }
    }

    private fun read16(file: File): IntArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = (b.capacity() - 44) / 2
        return IntArray(n) { i -> b.getShort(44 + i * 2).toInt() }
    }

    private fun headerInt(file: File, at: Int): Long =
        ByteBuffer.wrap(file.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xFFFFFFFFL

    private class Events : Pipeline.Listener {
        val states = ArrayList<Pipeline.State>()
        val takes = ArrayList<Pair<List<File>, Long>>()
        val errors = ArrayList<String>()
        override fun onStateChanged(state: Pipeline.State) { states.add(state) }
        override fun onTakeFinished(files: List<File>, frames: Long) { takes.add(files to frames) }
        override fun onError(message: String) { errors.add(message) }
    }

    private fun pipeline(
        events: Events,
        rate: Int = 48000,
        deviceChannels: Int = 2,
        selected: IntArray = intArrayOf(0, 1),
        bits: Int = 24,
        maxDataBytes: Long = WavWriter.DEFAULT_MAX_DATA_BYTES,
    ) = Pipeline(
        rate, deviceChannels, selected, bits,
        Pipeline.TakeFiles { start, part -> File(dir, "take_${start}_$part.wav") },
        events,
        maxDataBytes = maxDataBytes,
        clock = { 1000L + events.takes.size },
    )

    /** Feeds [seconds] of a stereo signal in 20 ms blocks. */
    private fun feed(p: Pipeline, rate: Int, seconds: Double, amplitude: Float, phase: LongArray = longArrayOf(0)) {
        val block = rate / 50
        val buf = FloatArray(block * p.deviceChannels)
        var left = (seconds * rate).toLong()
        while (left > 0) {
            val n = minOf(block.toLong(), left).toInt()
            for (f in 0 until n) {
                val v = if (amplitude == 0f) 0f else (amplitude * sin(2 * PI * 440.0 * phase[0] / rate)).toFloat()
                for (c in 0 until p.deviceChannels) buf[f * p.deviceChannels + c] = v
                phase[0]++
            }
            p.process(buf, n)
            left -= n
        }
    }

    // ---- WAV writer ----

    @Test
    fun wav24IsBitExactAndClamps() {
        val f = File(dir, "a.wav")
        val ints = intArrayOf(0, 1, -1, 8388607, -8388608, 123456, -654321, 42)
        val src = FloatArray(ints.size + 2)
        for (i in ints.indices) src[i] = ints[i] / 8388608f
        src[ints.size] = 1.7f
        src[ints.size + 1] = -3f
        val w = WavWriter(f, 48000, 2, 24)
        w.write(src, 0, src.size / 2)
        w.close()

        assertArrayEquals(ints + intArrayOf(8388607, -8388608), read24(f))
        assertEquals(44L + src.size * 3, f.length())
        assertEquals(src.size * 3L, headerInt(f, 40))
        assertEquals(36L + src.size * 3, headerInt(f, 4))
        val info = WavWriter.info(f)!!
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bits)
        assertEquals(5L, info.frames)
    }

    @Test
    fun wav16StaysWithinOneStepOfTheSource() {
        val f = File(dir, "b.wav")
        val src = FloatArray(2000) { (0.5 * sin(it * 0.01)).toFloat() }
        val w = WavWriter(f, 44100, 1, 16)
        w.write(src, 0, src.size)
        w.close()
        val out = read16(f)
        assertEquals(src.size, out.size)
        for (i in src.indices) assertTrue(abs(out[i] - src[i] * 32768f) <= 1.5f)
    }

    @Test
    fun headerIsKeptCurrentWhileRecording() {
        val f = File(dir, "c.wav")
        val rate = 8000
        val w = WavWriter(f, rate, 1, 16)
        val block = FloatArray(rate)
        repeat(7) { w.write(block, 0, rate) } // 7 seconds, never closed
        val stored = headerInt(f, 40) / 2 / rate
        assertTrue("header should cover at least 5 s, was $stored", stored >= 5)
        assertEquals(7L * rate, WavWriter.info(f)!!.frames)
        w.close()
    }

    @Test
    fun closeCanCutTheEndOff() {
        val f = File(dir, "d.wav")
        val w = WavWriter(f, 1000, 2, 24)
        w.write(FloatArray(2000), 0, 1000)
        w.close(400)
        assertEquals(44L + 400 * 6, f.length())
        assertEquals(400L * 6, headerInt(f, 40))
    }

    @Test
    fun repairFixesAnInterruptedRecording() {
        val f = File(dir, "e.wav")
        val w = WavWriter(f, 48000, 2, 24)
        w.write(FloatArray(96000), 0, 48000) // one second: header still says zero
        assertEquals(0L, headerInt(f, 40))
        // ...the app dies here, plus a torn half-frame at the end
        f.appendBytes(byteArrayOf(1, 2))
        assertTrue(WavWriter.repair(f))
        assertEquals(48000L * 6, headerInt(f, 40))
        assertEquals(44L + 48000 * 6, f.length())
        assertFalse(WavWriter.repair(f))
        w.close()
    }

    // ---- pre-roll ----

    @Test
    fun prerollKeepsTheMostRecentAudioInOrder() {
        val pr = PreRoll(4, 2) // four stereo frames
        val got = ArrayList<Float>()
        val collect = { a: FloatArray, off: Int, frames: Int -> for (i in off until off + frames * 2) got.add(a[i]) }
        pr.write(floatArrayOf(1f, 2f, 3f, 4f), 4)
        pr.drain(collect)
        assertEquals(listOf(1f, 2f, 3f, 4f), got)

        got.clear()
        pr.write(floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f), 6)
        pr.drain(collect)
        assertEquals(listOf(3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), got)

        got.clear()
        pr.write(FloatArray(20) { it.toFloat() }, 20)
        pr.drain(collect)
        assertEquals((12..19).map { it.toFloat() }, got)

        pr.clear()
        got.clear()
        pr.drain(collect)
        assertTrue(got.isEmpty())
    }

    // ---- pipeline ----

    @Test
    fun recordsTheChosenPairFromAMultichannelInput() {
        val ev = Events()
        val p = pipeline(ev, deviceChannels = 6, selected = intArrayOf(2, 3))
        assertTrue(p.startManual())
        val frames = 960
        val buf = FloatArray(frames * 6) { i -> ((i % 6) * 1000 + i / 6) / 8388608f }
        p.process(buf, frames)
        p.stop()

        assertEquals(listOf(Pipeline.State.RECORDING, Pipeline.State.MONITOR), ev.states)
        val out = read24(ev.takes.single().first.single())
        assertEquals(frames * 2, out.size)
        for (f in 0 until frames) {
            assertEquals(2000 + f, out[f * 2])
            assertEquals(3000 + f, out[f * 2 + 1])
        }
    }

    @Test
    fun metersReportPeaksPerChannelAndPerPair() {
        val p = pipeline(Events(), deviceChannels = 4, selected = intArrayOf(2, 3))
        val buf = FloatArray(4 * 100)
        buf[4 * 10 + 0] = 0.9f   // pair 1/2
        buf[4 * 20 + 2] = -0.5f  // recorded left
        buf[4 * 30 + 3] = 0.25f  // recorded right
        p.process(buf, 100)
        val m = Pipeline.Meters()
        p.readMeters(m)
        assertEquals(2, m.channels)
        assertEquals(0.5f, m.peak[0], 0f)
        assertEquals(0.25f, m.peak[1], 0f)
        assertEquals(2, m.pairs)
        assertEquals(0.9f, m.pairPeak[0], 0f)
        assertEquals(0.5f, m.pairPeak[1], 0f)

        // The per-read peak resets, the running maximum does not.
        p.process(FloatArray(400), 100)
        p.readMeters(m)
        assertEquals(0f, m.peak[0], 0f)
        assertEquals(0.5f, m.maxPeak[0], 0f)
        p.resetMeters()
        p.readMeters(m)
        assertEquals(0f, m.maxPeak[0], 0f)
    }

    @Test
    fun clippingNeedsSeveralFullScaleSamplesInARow() {
        val p = pipeline(Events())
        val m = Pipeline.Meters()
        val buf = FloatArray(2 * 200)
        // Two in a row on the left: a hot peak, not a clip.
        buf[2 * 10] = 1f; buf[2 * 11] = 1f
        p.process(buf, 200)
        p.readMeters(m)
        assertEquals(0, m.clips)
        // A flat top on the right counts once however long it is...
        for (f in 50 until 90) buf[2 * f + 1] = -1f
        p.process(buf, 200)
        p.readMeters(m)
        assertEquals(1, m.clips)
        // ...including when it straddles two blocks.
        val a = FloatArray(2 * 10); a[2 * 8] = 1f; a[2 * 9] = 1f
        val b = FloatArray(2 * 10); b[0] = 1f
        p.process(a, 10)
        p.process(b, 10)
        p.readMeters(m)
        assertEquals(2, m.clips)
        // -0.5 dB is loud but clean.
        p.resetMeters()
        p.process(FloatArray(2 * 200) { Db.toLinear(-0.5f) }, 200)
        p.readMeters(m)
        assertEquals(0, m.clips)
    }

    @Test
    fun inputLevelScalesWhatIsMeteredAndRecorded() {
        val ev = Events()
        val p = pipeline(ev)
        val m = Pipeline.Meters()
        p.setGainDb(-6.0206f, immediate = true) // half
        p.startManual()
        val src = FloatArray(2 * 480) { i -> if (i % 2 == 0) 0.8f else -0.4f }
        p.process(src, 480)
        p.readMeters(m)
        assertEquals(0.4f, m.maxPeak[0], 1e-4f)
        assertEquals(0.2f, m.maxPeak[1], 1e-4f)
        p.stop()
        val out = read24(ev.takes.single().first.single())
        assertEquals(0.4f, out[0] / 8388608f, 1e-4f)
        assertEquals(-0.2f, out[1] / 8388608f, 1e-4f)
    }

    @Test
    fun zeroDbLeavesTheAudioUntouched() {
        val ev = Events()
        val p = pipeline(ev)
        p.setGainDb(3f, immediate = true)
        p.setGainDb(0f, immediate = true)
        p.startManual()
        val ints = IntArray(2 * 500) { (it * 7919) % 8388607 - 4000000 }
        p.process(FloatArray(ints.size) { ints[it] / 8388608f }, 500)
        p.stop()
        assertArrayEquals(ints, read24(ev.takes.single().first.single()))
    }

    @Test
    fun clippingIsBlamedOnTheRightPlace() {
        val m = Pipeline.Meters()

        // A flat top arriving from the source is still a clip after turning the level down.
        val a = pipeline(Events())
        a.setGainDb(-12f, immediate = true)
        val flat = FloatArray(2 * 200)
        for (f in 50 until 90) flat[2 * f] = 1f
        a.process(flat, 200)
        a.readMeters(m)
        assertEquals(1, m.inputClips)
        assertEquals(0, m.clips)
        assertEquals(0.2512f, m.maxPeak[0], 1e-3f)

        // A clean source pushed too far by the level control is the slider's fault.
        val b = pipeline(Events())
        b.setGainDb(12f, immediate = true)
        b.process(FloatArray(2 * 200) { 0.5f }, 200)
        b.readMeters(m)
        assertEquals(0, m.inputClips)
        assertTrue(m.clips >= 1)

        b.resetMeters()
        b.readMeters(m)
        assertEquals(0, m.clips)
        assertEquals(0, m.inputClips)
    }

    @Test
    fun movingTheLevelDuringATakeGlidesInsteadOfJumping() {
        val ev = Events()
        val p = pipeline(ev)
        p.startManual()
        val block = FloatArray(2 * 480) { 0.5f }
        p.process(block, 480)
        p.setGainDb(-6.0206f)
        p.process(block, 480) // glides here
        p.process(block, 480) // settled
        p.stop()
        val out = read24(ev.takes.single().first.single()).map { it / 8388608f }
        val left = out.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(0.5f, left[479], 1e-4f)
        assertEquals(0.25f, left[1439], 1e-4f)
        var biggestStep = 0f
        for (i in 1 until left.size) biggestStep = maxOf(biggestStep, abs(left[i] - left[i - 1]))
        assertTrue("step $biggestStep", biggestStep < 0.001f)
        for (i in 480 until 960) assertTrue(left[i] <= left[i - 1])
    }

    @Test
    fun autoModeStartsOnSignalAndStopsAfterSilence() {
        val rate = 48000
        val ev = Events()
        val p = pipeline(ev, rate = rate)
        p.arm(-40f, 10f)
        val phase = longArrayOf(0)
        feed(p, rate, 5.0, 0f, phase)
        assertEquals(Pipeline.State.ARMED, p.state)
        feed(p, rate, 3.0, 0.5f, phase)
        assertEquals(Pipeline.State.RECORDING, p.state)
        feed(p, rate, 9.0, 0f, phase)
        assertEquals("still inside the hold time", Pipeline.State.RECORDING, p.state)
        feed(p, rate, 2.0, 0f, phase)
        assertEquals(Pipeline.State.ARMED, p.state)
        assertTrue(ev.errors.isEmpty())

        val (files, frames) = ev.takes.single()
        val out = read24(files.single())
        assertEquals(frames * 2, out.size.toLong())

        // The take opens with pre-roll silence, and the music starts un-clipped about 1.75 s in.
        val onset = out.indexOfFirst { it != 0 } / 2
        assertTrue("onset at $onset", onset in (1.70 * rate).toInt()..(1.80 * rate).toInt())
        // 1.75 s lead-in + 3 s of signal + 2 s tail; the other 8+ s of silence was trimmed.
        assertTrue("length $frames", frames in (6.70 * rate).toLong()..(6.80 * rate).toLong())

        // It goes round again for the next burst of signal.
        feed(p, rate, 1.0, 0.5f, phase)
        assertEquals(Pipeline.State.RECORDING, p.state)
        p.stop()
        assertEquals(2, ev.takes.size)
        assertEquals(Pipeline.State.MONITOR, p.state)
    }

    @Test
    fun aShortPopOrQuietNoiseDoesNotStartATake() {
        val rate = 48000
        val ev = Events()
        val p = pipeline(ev, rate = rate)
        p.arm(-40f, 10f)
        feed(p, rate, 1.0, 0f)
        feed(p, rate, 0.1, 0.9f)                 // cable pop
        feed(p, rate, 1.0, 0f)
        feed(p, rate, 3.0, Db.toLinear(-50f))    // hiss under the threshold
        assertEquals(Pipeline.State.ARMED, p.state)
        assertTrue(ev.takes.isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun longTakesRollIntoNewPartsWithoutLosingAudio() {
        val rate = 8000
        val ev = Events()
        val p = pipeline(ev, rate = rate, maxDataBytes = 8000L * 6) // one second per part
        assertTrue(p.startManual())
        val block = 160
        var counter = 1
        repeat((2.5 * rate / block).toInt()) {
            val buf = FloatArray(block * 2)
            for (f in 0 until block) { buf[f * 2] = counter / 8388608f; buf[f * 2 + 1] = -counter / 8388608f; counter++ }
            p.process(buf, block)
        }
        p.stop()
        val (files, frames) = ev.takes.single()
        assertEquals(3, files.size)
        assertEquals(20000L, frames)
        val all = files.flatMap { read24(it).toList() }
        assertEquals(40000, all.size)
        for (f in 0 until 20000) {
            assertEquals(f + 1, all[f * 2])
            assertEquals(-(f + 1), all[f * 2 + 1])
        }
        assertEquals(44L + 8000 * 6, files[0].length())
    }

    @Test
    fun aFileThatCannotBeCreatedIsReportedNotThrown() {
        val ev = Events()
        val blocker = File(dir, "not-a-folder").apply { writeText("x") }
        val p = Pipeline(
            48000, 2, intArrayOf(0, 1), 24,
            Pipeline.TakeFiles { _, _ -> File(blocker, "take.wav") }, ev,
        )
        assertFalse(p.startManual())
        assertEquals(Pipeline.State.MONITOR, p.state)
        assertEquals(1, ev.errors.size)
        p.process(FloatArray(200), 100) // carries on metering
        assertNotNull(p.state)
    }

    @Test
    fun monoInputsRecordMonoFiles() {
        val ev = Events()
        val p = pipeline(ev, deviceChannels = 1, selected = intArrayOf(0), bits = 16)
        p.startManual()
        p.process(FloatArray(480) { 0.25f }, 480)
        p.stop()
        val f = ev.takes.single().first.single()
        val info = WavWriter.info(f)!!
        assertEquals(1, info.channels)
        assertEquals(16, info.bits)
        assertEquals(480L, info.frames)
    }

    @Test
    fun dbConversionsRoundTrip() {
        assertEquals(0f, Db.fromLinear(1f), 1e-4f)
        assertEquals(-6.0206f, Db.fromLinear(0.5f), 1e-3f)
        assertEquals(Db.FLOOR, Db.fromLinear(0f), 0f)
        assertEquals(0.1f, Db.toLinear(-20f), 1e-6f)
    }
}
