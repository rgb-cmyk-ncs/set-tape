package com.sergei.settape

import android.content.Context
import android.graphics.Color
import android.util.TypedValue

/** Colours and small layout helpers shared by the screen and its custom views. */
object Ui {
    val BG = Color.parseColor("#0E0F12")
    val PANEL = Color.parseColor("#181A20")
    val LINE = Color.parseColor("#262931")
    val TEXT = Color.parseColor("#ECEEF2")
    val DIM = Color.parseColor("#8B909A")
    val GREEN = Color.parseColor("#35D07F")
    val AMBER = Color.parseColor("#FFB224")
    val RED = Color.parseColor("#FF453A")

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun sp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
}
