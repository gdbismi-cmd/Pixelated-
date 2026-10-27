package com.pixelated.app

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle (right/bottom exclusive). Plain Kotlin so the engine can be unit tested on the JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A pixelated frame to put on screen: one ARGB colour per block plus the tag id that identifies it. */
class Frame(val id: Int, val colors: IntArray?) {
    val isRaw get() = colors == null
}

/**
 * Turns screen captures into pixelated frames.
 *
 * Android has no public way to keep an app's overlay out of its own screen capture, and since
 * Android 12 touches only pass through another app's overlay when it is at most 80% opaque. So
 * the overlay is drawn with window alpha [alpha], and every capture is a known blend:
 *
 *     captured = alpha * overlay + (1 - alpha) * screen
 *
 * Because the overlay is a flat colour inside each block, the block average of the real screen
 * can be solved for exactly: screen = (captured - alpha * overlay) / (1 - alpha).
 *
 * To know *which* overlay frame a capture contains, every frame carries a small barcode
 * ("tag") of black/white cells encoding its id. Raw frames (overlay fully transparent apart from
 * the tag) are used to start over after the overlay is enabled or the layout changes.
 *
 * All sizes are in real screen pixels except where noted "cap" (capture pixels).
 */
class PixelEngine(
    screenW: Int,
    screenH: Int,
    /** Area that gets pixelated (the screen minus status / navigation bars). */
    val content: Box,
    /** Block size in screen pixels. */
    val block: Int,
    /** Capture size in capture pixels. */
    val capW: Int,
    val capH: Int,
    private val alpha: Float,
    /** Space Impact / Nokia 3310 look: every block is either [DARK] or [LIGHT]. */
    val twoTone: Boolean = false,
) {
    val cols = (content.width + block - 1) / block
    val rows = (content.height + block - 1) / block

    /** Where the tag is drawn, in screen pixels. */
    val tag = Box(content.left, content.top, content.left + TAG_CELLS * TAG_CELL, content.top + TAG_CELL)

    private val sx = capW.toFloat() / screenW
    private val sy = capH.toFloat() / screenH

    // Block column / row of every capture pixel, -1 when outside the content area.
    private val colOf = IntArray(capW) { x -> blockIndex(x, sx, content.left, content.right) }
    private val rowOf = IntArray(capH) { y -> blockIndex(y, sy, content.top, content.bottom) }

    private val tagCap = toCapture(tag, margin = 1)

    /**
     * The blend strength actually measured on screen. Each tag bit is a white cell next to a
     * black one over (nearly) the same background, so their difference is alpha * 255.
     */
    private var measuredAlpha = alpha

    private val ring = arrayOfNulls<IntArray>(RING)
    private val ringValid = BooleanArray(RING)
    private var nextId = 0
    private var current: IntArray? = null

    private val sumR = LongArray(cols * rows)
    private val sumG = LongArray(cols * rows)
    private val sumB = LongArray(cols * rows)
    private val count = IntArray(cols * rows)

    private fun blockIndex(capPos: Int, scale: Float, start: Int, end: Int): Int {
        val screenPos = ((capPos + 0.5f) / scale).toInt()
        return if (screenPos in start until end) (screenPos - start) / block else -1
    }

    /** Converts a screen rectangle to capture pixels, grown by [margin] to cover filtering. */
    fun toCapture(box: Box, margin: Int = 2) = Box(
        max(0, (box.left * sx).toInt() - margin),
        max(0, (box.top * sy).toInt() - margin),
        min(capW, (box.right * sx).roundToInt() + margin),
        min(capH, (box.bottom * sy).roundToInt() + margin),
    )

    /** Starts over: returns a raw frame (transparent overlay + tag) that should be shown next. */
    fun reset(): Frame {
        ringValid.fill(false)
        current = null
        return allocate(null)
    }

    private fun allocate(colors: IntArray?): Frame {
        val id = nextId
        nextId = (nextId + 1) % RING
        ring[id] = colors
        ringValid[id] = true
        return Frame(id, colors)
    }

    /**
     * Processes one RGBA_8888 capture. Returns the next frame to display, or null when the
     * capture can't be used or nothing visibly changed.
     *
     * @param exclude a screen rectangle to ignore (the floating switch, drawn on top of everything).
     */
    fun process(buf: ByteBuffer, rowStride: Int, pixelStride: Int, exclude: Box?): Frame? {
        val id = decodeTag(buf, rowStride, pixelStride)
        if (id < 0 || !ringValid[id]) return null
        val shown = ring[id]
        calibrate(buf, rowStride, pixelStride)

        sumR.fill(0); sumG.fill(0); sumB.fill(0); count.fill(0)
        val ex = exclude?.let { toCapture(it) }
        for (y in 0 until capH) {
            val row = rowOf[y]
            if (row < 0) continue
            val skipTag = y >= tagCap.top && y < tagCap.bottom
            val skipEx = ex != null && y >= ex.top && y < ex.bottom
            val base = row * cols
            var p = y * rowStride
            for (x in 0 until capW) {
                val col = colOf[x]
                if (col >= 0 &&
                    !(skipTag && x >= tagCap.left && x < tagCap.right) &&
                    !(skipEx && x >= ex!!.left && x < ex.right)
                ) {
                    val i = base + col
                    sumR[i] += (buf.get(p).toInt() and 0xFF).toLong()
                    sumG[i] += (buf.get(p + 1).toInt() and 0xFF).toLong()
                    sumB[i] += (buf.get(p + 2).toInt() and 0xFF).toLong()
                    count[i]++
                }
                p += pixelStride
            }
        }

        // Estimated colour of the real screen in every block (null where nothing was visible).
        val estimate = arrayOfNulls<Int>(cols * rows)
        for (i in estimate.indices) {
            val n = count[i]
            if (n == 0) continue
            val r = sumR[i] / n.toFloat()
            val g = sumG[i] / n.toFloat()
            val b = sumB[i] / n.toFloat()
            estimate[i] = if (shown == null) rgb(r, g, b) else {
                val s = shown[i]
                rgb(unblend(r, s shr 16), unblend(g, s shr 8), unblend(b, s))
            }
        }
        val prev = current
        val out = if (twoTone) toTwoTone(estimate, prev) else toColor(estimate, prev, shown)
        if (prev != null && out.contentEquals(prev)) return null
        current = out
        return allocate(out)
    }

    private fun toColor(estimate: Array<Int?>, prev: IntArray?, shown: IntArray?) = IntArray(estimate.size) { i ->
        val c = estimate[i] ?: prev?.get(i) ?: shown?.get(i) ?: GRAY
        // Per-block hysteresis: tiny changes keep the old colour, so rounding noise
        // (amplified by the unblend) can't make blocks shimmer or keep the loop running.
        if (prev != null && colorDiff(c, prev[i]) <= CHANGE_THRESHOLD) prev[i] else c
    }

    /**
     * Two tones, like the Nokia 3310 LCD. A block goes dark when it is darker than the frame's
     * average brightness, so text and outlines stay readable instead of averaging away, and the
     * look adapts to light and dark apps. Hysteresis around the threshold stops edge blocks flickering.
     */
    private fun toTwoTone(estimate: Array<Int?>, prev: IntArray?): IntArray {
        var sum = 0f
        var n = 0
        for (c in estimate) if (c != null) { sum += luma(c); n++ }
        val threshold = if (n == 0) 128f else (sum / n).coerceIn(48f, 208f)
        // Hysteresis scales with the contrast on screen, so it never exceeds the gap
        // between the threshold and the background.
        var spread = 0f
        for (c in estimate) if (c != null) spread += abs(luma(c) - threshold)
        val band = if (n == 0) 0f else min(TONE_HYSTERESIS, 0.5f * spread / n)
        return IntArray(estimate.size) { i ->
            val was = prev?.get(i)
            val c = estimate[i] ?: return@IntArray was ?: LIGHT
            val l = luma(c)
            val dark = when (was) {
                DARK -> l <= threshold + band
                LIGHT -> l < threshold - band
                else -> l < threshold
            }
            if (dark) DARK else LIGHT
        }
    }

    private fun unblend(captured: Float, overlay: Int): Float =
        (captured - measuredAlpha * (overlay and 0xFF)) / (1f - measuredAlpha)

    private fun calibrate(buf: ByteBuffer, rowStride: Int, pixelStride: Int) {
        val contrasts = FloatArray(TAG_BITS) { bit ->
            val a = cellLuma(buf, rowStride, pixelStride, bit * 2)
            val b = cellLuma(buf, rowStride, pixelStride, bit * 2 + 1)
            abs(a - b) / 255f
        }
        contrasts.sort()
        val median = contrasts[TAG_BITS / 2]
        measuredAlpha = (measuredAlpha * 0.6f + median * 0.4f).coerceIn(alpha - 0.1f, min(0.97f, alpha + 0.1f))
    }

    /** Whether tag cell [k] is white for frame [id]. Each bit is a white/black or black/white pair. */
    fun tagCell(id: Int, k: Int): Boolean {
        val bit = (id shr (k / 2)) and 1 == 1
        return if (k % 2 == 0) bit else !bit
    }

    private fun decodeTag(buf: ByteBuffer, rowStride: Int, pixelStride: Int): Int {
        val hi = alpha * 255f - TAG_TOLERANCE
        val lo = (1f - alpha) * 255f + TAG_TOLERANCE
        var id = 0
        for (bit in 0 until TAG_BITS) {
            val a = cellLuma(buf, rowStride, pixelStride, bit * 2)
            val b = cellLuma(buf, rowStride, pixelStride, bit * 2 + 1)
            id = id or when {
                a >= hi && b <= lo -> 1 shl bit
                a <= lo && b >= hi -> 0
                else -> return -1
            }
        }
        return id
    }

    private fun cellLuma(buf: ByteBuffer, rowStride: Int, pixelStride: Int, k: Int): Float {
        val cx = ((tag.left + k * TAG_CELL + TAG_CELL / 2f) * sx).toInt()
        val cy = ((tag.top + TAG_CELL / 2f) * sy).toInt()
        var sum = 0
        var n = 0
        for (y in cy - 1..cy) for (x in cx - 1..cx) {
            if (x < 0 || y < 0 || x >= capW || y >= capH) continue
            val p = y * rowStride + x * pixelStride
            sum += (buf.get(p).toInt() and 0xFF) + (buf.get(p + 1).toInt() and 0xFF) + (buf.get(p + 2).toInt() and 0xFF)
            n += 3
        }
        return if (n == 0) -1f else sum / n.toFloat()
    }

    companion object {
        const val TAG_BITS = 5
        const val TAG_CELLS = TAG_BITS * 2
        /** Tag cell size in screen pixels. */
        const val TAG_CELL = 8
        private const val RING = 1 shl TAG_BITS
        private const val TAG_TOLERANCE = 12f
        /** Don't redraw for changes this small (also stops the capture/redraw loop on a still screen). */
        private const val CHANGE_THRESHOLD = 6
        private const val GRAY = 0xFF808080.toInt()
        private const val TONE_HYSTERESIS = 12f

        /** Nokia 3310 LCD palette. */
        const val DARK = 0xFF43523D.toInt()
        const val LIGHT = 0xFFC7F0D8.toInt()

        private fun luma(c: Int) = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)

        private fun channel(v: Float) = v.roundToInt().coerceIn(0, 255)
        private fun rgb(r: Float, g: Float, b: Float) =
            (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        private fun colorDiff(a: Int, b: Int) = maxOf(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )
    }
}
