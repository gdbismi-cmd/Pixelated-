package com.pixelated.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_BLOCK = "block_size"
    const val KEY_TWO_TONE = "two_tone"
    /** Pixel sizes offered on the slider, in screen pixels. */
    val SIZES = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64, 80)
    private const val DEFAULT_BLOCK = 1

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("pixelated", Context.MODE_PRIVATE)

    /** Block size in screen pixels. */
    fun blockSize(context: Context) =
        get(context).getInt(KEY_BLOCK, DEFAULT_BLOCK).coerceIn(SIZES.first(), SIZES.last())

    fun setBlockSize(context: Context, size: Int) = get(context).edit().putInt(KEY_BLOCK, size).apply()

    /** Space Impact / Nokia 3310 style: two LCD tones instead of full colour. On by default. */
    fun twoTone(context: Context) = get(context).getBoolean(KEY_TWO_TONE, true)

    fun setTwoTone(context: Context, on: Boolean) = get(context).edit().putBoolean(KEY_TWO_TONE, on).apply()
}
