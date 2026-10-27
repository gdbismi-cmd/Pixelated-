package com.pixelated.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_BLOCK = "block_size"
    const val KEY_TWO_TONE = "two_tone"
    const val MIN_BLOCK = 8
    const val MAX_BLOCK = 80
    const val BLOCK_STEP = 4
    private const val DEFAULT_BLOCK = 24

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("pixelated", Context.MODE_PRIVATE)

    /** Block size in screen pixels; always even so it maps cleanly onto the half-size capture. */
    fun blockSize(context: Context) =
        get(context).getInt(KEY_BLOCK, DEFAULT_BLOCK).coerceIn(MIN_BLOCK, MAX_BLOCK) and 1.inv()

    fun setBlockSize(context: Context, size: Int) = get(context).edit().putInt(KEY_BLOCK, size).apply()

    /** Space Impact / Nokia 3310 style: two LCD tones instead of full colour. On by default. */
    fun twoTone(context: Context) = get(context).getBoolean(KEY_TWO_TONE, true)

    fun setTwoTone(context: Context, on: Boolean) = get(context).edit().putBoolean(KEY_TWO_TONE, on).apply()
}
