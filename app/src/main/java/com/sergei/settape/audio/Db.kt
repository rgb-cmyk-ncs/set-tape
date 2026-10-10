package com.sergei.settape.audio

import kotlin.math.log10
import kotlin.math.pow

/** Conversions between linear sample values (1.0 = digital full scale) and dBFS. */
object Db {
    /** Anything quieter than this is shown as silence. */
    const val FLOOR = -96f

    fun fromLinear(x: Float): Float {
        if (!(x > 1.6e-5f)) return FLOOR
        return (20.0 * log10(x.toDouble())).toFloat()
    }

    fun toLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    /** "-12.3", or "-inf" for silence. */
    fun format(db: Float): String =
        if (db <= FLOOR) "-∞" else String.format(java.util.Locale.US, "%.1f", db)
}
