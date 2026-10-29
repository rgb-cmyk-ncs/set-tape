package com.sergei.settape.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

class GainTests {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("gain").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun tone(name: String, bits: Int, amp: Float, frames: Int = 200_000): File {
        val f = File(dir, name)
        WavWriter(f, 48000, 2, bits).use { w ->
            val buf = FloatArray(frames * 2) { i -> amp * sin(2 * PI * 440 * (i / 2) / 48000.0).toFloat() }
            w.write(buf, 0, frames)
        }
        return f
    }

    @Test fun normalizes24BitToMinusOneDb() {
        val f = tone("a.wav", 24, 0.1f)
        val db = Gain.normalize(listOf(f))
        assertEquals(19.0f, db, 0.1f)
        assertEquals(-1f, Gain.toDb(Gain.peak(listOf(f))), 0.05f)
        assertEquals(200_000L, WavWriter.info(f)!!.frames)
    }

    @Test fun normalizes16BitAndKeepsPartsMatched() {
        val a = tone("a.wav", 16, 0.25f, 300_000) // > one chunk
        val b = tone("b.wav", 16, 0.05f)
        Gain.normalize(listOf(a, b))
        assertEquals(-1f, Gain.toDb(Gain.peak(listOf(a))), 0.05f)
        // b gets the same gain, so it stays 14 dB under a.
        assertEquals(-1f - 13.98f, Gain.toDb(Gain.peak(listOf(b))), 0.1f)
    }

    @Test fun boostStopsBeforeClipping() {
        val f = tone("a.wav", 24, 0.7f)
        val db = Gain.change(f, 6f)
        assertTrue(db < 6f)
        assertEquals(-1f, Gain.toDb(Gain.peak(listOf(f))), 0.05f)
        assertEquals(0f, Gain.change(f, 3f), 0f)
    }

    @Test fun softerWorks() {
        val f = tone("a.wav", 24, 0.5f)
        assertEquals(-6f, Gain.change(f, -6f), 0f)
        assertEquals(Gain.toDb(0.5f) - 6f, Gain.toDb(Gain.peak(listOf(f))), 0.05f)
    }

    @Test fun silenceIsLeftAlone() {
        val f = tone("a.wav", 24, 0f)
        assertEquals(0f, Gain.normalize(listOf(f)), 0f)
    }
}
