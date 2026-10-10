package com.sergei.settape

import android.content.Context
import android.content.SharedPreferences

/** The handful of settings the app remembers. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settape", Context.MODE_PRIVATE)

    /** Which input to record from; [Inputs.AUTO] picks a USB device when one is plugged in. */
    var inputKey: String
        get() = sp.getString("input", Inputs.AUTO) ?: Inputs.AUTO
        set(v) = sp.edit().putString("input", v).apply()

    /** Stereo pair on a multichannel input: 0 = channels 1/2, 1 = channels 3/4, ... */
    var pair: Int
        get() = sp.getInt("pair", 0)
        set(v) = sp.edit().putInt("pair", v).apply()

    /** 0 = let the app choose from what the input offers. */
    var sampleRate: Int
        get() = sp.getInt("rate", 0)
        set(v) = sp.edit().putInt("rate", v).apply()

    var bits: Int
        get() = if (sp.getInt("bits", 24) == 16) 16 else 24
        set(v) = sp.edit().putInt("bits", v).apply()

    var auto: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var thresholdDb: Int
        get() = sp.getInt("threshold", -40).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** Input level in half-dB steps, so +3.5 dB is stored as 7. */
    var gainHalfDb: Int
        get() = sp.getInt("gain", 0).coerceIn(-GAIN_RANGE_DB * 2, GAIN_RANGE_DB * 2)
        set(v) = sp.edit().putInt("gain", v).apply()

    val gainDb: Float get() = gainHalfDb / 2f

    var holdSeconds: Int
        get() = sp.getInt("hold", 30)
        set(v) = sp.edit().putInt("hold", v).apply()

    companion object {
        const val THRESHOLD_MIN = -70
        const val THRESHOLD_MAX = -10
        /** The input level slider runs from minus to plus this many dB. */
        const val GAIN_RANGE_DB = 24
        val SAMPLE_RATES = intArrayOf(0, 44100, 48000, 96000)
        val HOLD_CHOICES = intArrayOf(5, 10, 30, 60, 120, 300)
    }
}
