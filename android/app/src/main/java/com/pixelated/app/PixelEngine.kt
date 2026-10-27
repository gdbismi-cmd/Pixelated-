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
    private val screenW: Int,
    private val screenH: Int,
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

    // Int sums are enough: at most 255 * (pixels per block) and blocks are at most 80 px.
    // These are full-screen sized at 1 px blocks, so they are allocated once and reused.
    private val sumR = IntArray(cols * rows)
    private val sumG = IntArray(cols * rows)
    private val sumB = IntArray(cols * rows)
    private val count = IntArray(cols * rows)
    private val estimate = IntArray(cols * rows)
    private val lumas = IntArray(cols * rows)
    private var bytes = ByteArray(0)
    /** Output buffers of frames that left the ring, reused to avoid big per-frame allocations. */
    private val spare = ArrayDeque<IntArray>()

    private fun newOutput() = spare.removeFirstOrNull() ?: IntArray(cols * rows)

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
        // Only the last few frames can still be on screen; drop older ones (they are full-screen
        // sized at small block sizes).
        val stale = (id - KEEP + RING) % RING
        ring[stale]?.let { if (it !== current) spare.addLast(it) }
        ring[stale] = null
        ringValid[stale] = false
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

        // One bulk copy: indexing a ByteArray is much faster than ByteBuffer.get per channel.
        val size = min(buf.capacity(), (capH - 1) * rowStride + capW * pixelStride)
        if (bytes.size < size) bytes = ByteArray(size)
        buf.duplicate().apply { position(0) }.get(bytes, 0, size)
        val px = bytes

        val ex = exclude?.let { toCapture(it) }
        if (onePixelPerBlock) {
            unblendPixels(px, rowStride, pixelStride, shown, ex)
        } else {
            sumBlocks(px, rowStride, pixelStride, ex)
            unblendBlocks(shown)
        }
        val prev = current
        val out = if (twoTone) toTwoTone(prev) else toColor(prev, shown)
        if (prev != null && out.contentEquals(prev)) {
            spare.addLast(out)
            return null
        }
        current = out
        return allocate(out)
    }

    /** Each block covers exactly one capture pixel (1 px blocks): skip the sums entirely. */
    private val onePixelPerBlock = block * capW == screenW && block * capH == screenH

    private fun unblendPixels(px: ByteArray, rowStride: Int, pixelStride: Int, shown: IntArray?, ex: Box?) {
        count.fill(0)
        // Fixed point (16.16): screen = captured * k - overlay * m.
        val k = (65536f / (1f - measuredAlpha)).toInt()
        val m = (65536f * measuredAlpha / (1f - measuredAlpha)).toInt()
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
                    val r = px[p].toInt() and 0xFF
                    val g = px[p + 1].toInt() and 0xFF
                    val b = px[p + 2].toInt() and 0xFF
                    estimate[i] = if (shown == null) {
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        val o = shown[i]
                        (0xFF shl 24) or
                            (clamp(r * k - (o shr 16 and 0xFF) * m) shl 16) or
                            (clamp(g * k - (o shr 8 and 0xFF) * m) shl 8) or
                            clamp(b * k - (o and 0xFF) * m)
                    }
                    count[i] = 1
                }
                p += pixelStride
            }
        }
    }

    private fun sumBlocks(px: ByteArray, rowStride: Int, pixelStride: Int, ex: Box?) {
        sumR.fill(0); sumG.fill(0); sumB.fill(0); count.fill(0)
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
                    sumR[i] += px[p].toInt() and 0xFF
                    sumG[i] += px[p + 1].toInt() and 0xFF
                    sumB[i] += px[p + 2].toInt() and 0xFF
                    count[i]++
                }
                p += pixelStride
            }
        }
    }

    /** Estimated colour of the real screen in every block (only where count > 0). */
    private fun unblendBlocks(shown: IntArray?) {
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
    }

    private fun toColor(prev: IntArray?, shown: IntArray?): IntArray {
        val out = newOutput()
        for (i in out.indices) {
            val c = when {
                count[i] > 0 -> estimate[i]
                prev != null -> prev[i]
                shown != null -> shown[i]
                else -> GRAY
            }
            // Per-block hysteresis: tiny changes keep the old colour, so rounding noise
            // (amplified by the unblend) can't make blocks shimmer or keep the loop running.
            out[i] = if (prev != null && colorDiff(c, prev[i]) <= CHANGE_THRESHOLD) prev[i] else c
        }
        return out
    }

    /**
     * Two tones, like the Nokia 3310 LCD. A block goes dark when it is darker than the frame's
     * average brightness, so text and outlines stay readable instead of averaging away, and the
     * look adapts to light and dark apps. Hysteresis around the threshold stops edge blocks flickering.
     * Luma is in 1/256 steps of an 8-bit level (integer maths: this runs per pixel at 1 px blocks).
     */
    private fun toTwoTone(prev: IntArray?): IntArray {
        var sum = 0L
        var n = 0
        for (i in estimate.indices) if (count[i] > 0) {
            val l = luma(estimate[i])
            lumas[i] = l
            sum += l
            n++
        }
        val threshold = if (n == 0) 128 * 256 else (sum / n).toInt().coerceIn(48 * 256, 208 * 256)
        // Hysteresis scales with the contrast on screen, so it never exceeds the gap
        // between the threshold and the background.
        var spread = 0L
        for (i in estimate.indices) if (count[i] > 0) spread += abs(lumas[i] - threshold)
        val band = if (n == 0) 0 else min(TONE_HYSTERESIS * 256, (spread / (2L * n)).toInt())
        val out = newOutput()
        for (i in out.indices) {
            val was = if (prev != null) prev[i] else 0
            if (count[i] == 0) {
                out[i] = if (prev != null) was else LIGHT
                continue
            }
            val l = lumas[i]
            val dark = when (was) {
                DARK -> l <= threshold + band
                LIGHT -> l < threshold - band
                else -> l < threshold
            }
            out[i] = if (dark) DARK else LIGHT
        }
        return out
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
        /** Frames kept for decoding; captures lag the screen by only a frame or two. */
        private const val KEEP = 6
        private const val TAG_TOLERANCE = 12f
        /** Don't redraw for changes this small (also stops the capture/redraw loop on a still screen). */
        private const val CHANGE_THRESHOLD = 6
        private const val GRAY = 0xFF808080.toInt()
        private const val TONE_HYSTERESIS = 12

        /** Nokia 3310 LCD palette. */
        const val DARK = 0xFF43523D.toInt()
        const val LIGHT = 0xFFC7F0D8.toInt()

        private fun luma(c: Int) = 77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)

        private fun channel(v: Float) = v.roundToInt().coerceIn(0, 255)
        /** Rounds a 16.16 fixed-point value to a 0..255 channel. */
        private fun clamp(v: Int): Int {
            val c = (v + 32768) shr 16
            return if (c < 0) 0 else if (c > 255) 255 else c
        }
        private fun rgb(r: Float, g: Float, b: Float) =
            (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        private fun colorDiff(a: Int, b: Int) = maxOf(
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )
    }
}
