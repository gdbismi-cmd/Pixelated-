package com.pixelated.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Simulates the compositor: overlay drawn at window alpha 0.8 over the real screen, the floating
 * switch drawn opaque on top, and a half-resolution capture. Checks that the feedback loop
 * converges to the true pixelation of the screen and stops redrawing once it has.
 */
class PixelEngineTest {
    private val w = 360
    private val h = 640
    /** Capture downscale factor (the app uses 2, or 1 for 1 px blocks). */
    private var div = 2
    private val capW get() = w / div
    private val capH get() = h / div
    private val alpha = 0.8f
    private val content = Box(0, 24, w, h - 24)
    private var block = 16
    private val switch = Box(280, 300, 340, 332)

    /** The alpha the simulated compositor really blends with (may differ from what the engine assumes). */
    private var renderAlpha = alpha

    private fun scene(offset: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        when {
            x in (40 + offset)..(160 + offset) && y in 100..220 -> 0xFFE03040.toInt()
            (x / 3 + y / 5) % 7 == 0 -> 0xFF101010.toInt() // fine "text-like" detail
            else -> (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x80
        }
    }

    private fun render(engine: PixelEngine, frame: Frame?, screen: IntArray): IntArray {
        val out = screen.copyOf()
        if (frame != null) for (y in 0 until h) for (x in 0 until w) {
            var o: Int? = null
            val colors = frame.colors
            if (colors != null && x in content.left until content.right && y in content.top until content.bottom) {
                o = colors[((y - content.top) / block) * engine.cols + (x - content.left) / block]
            }
            val t = engine.tag
            if (x in t.left until t.right && y in t.top until t.bottom) {
                o = if (engine.tagCell(frame.id, (x - t.left) / PixelEngine.TAG_CELL)) -1 else 0xFF000000.toInt()
            }
            if (o != null) out[y * w + x] = blend(o, screen[y * w + x])
        }
        for (y in switch.top until switch.bottom) for (x in switch.left until switch.right) out[y * w + x] = 0xFF22AA55.toInt()
        return out
    }

    private fun blend(o: Int, s: Int): Int {
        fun ch(shift: Int) = Math.round(renderAlpha * (o shr shift and 0xFF) + (1 - renderAlpha) * (s shr shift and 0xFF))
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val stride get() = capW * 4 + 32 // row padding, like real ImageReader buffers

    private fun capture(display: IntArray): ByteBuffer {
        val buf = ByteBuffer.allocate(stride * capH)
        for (y in 0 until capH) for (x in 0 until capW) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until div) for (dx in 0 until div) {
                val c = display[(y * div + dy) * w + x * div + dx]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
            }
            val n = div * div
            val p = y * stride + x * 4
            buf.put(p, (r / n).toByte()); buf.put(p + 1, (g / n).toByte()); buf.put(p + 2, (b / n).toByte()); buf.put(p + 3, -1)
        }
        return buf
    }

    private fun expected(engine: PixelEngine, screen: IntArray, col: Int, row: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in content.top + row * block until minOf(content.bottom, content.top + (row + 1) * block))
            for (x in content.left + col * block until minOf(content.right, content.left + (col + 1) * block)) {
                val c = screen[y * w + x]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
            }
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Runs capture -> process -> display until the engine stops producing frames. */
    private fun settle(engine: PixelEngine, start: Frame, screen: IntArray): Pair<Frame, Int> {
        var shown = start
        var rounds = 0
        while (rounds < 20) {
            val next = engine.process(capture(render(engine, shown, screen)), stride, 4, switch) ?: break
            shown = next
            rounds++
        }
        return shown to rounds
    }

    private fun assertMatches(engine: PixelEngine, frame: Frame, screen: IntArray) {
        val colors = frame.colors
        assertNotNull(colors)
        colors!!
        var worst = 0
        for (row in 0 until engine.rows) for (col in 0 until engine.cols) {
            val bx = content.left + col * block
            val by = content.top + row * block
            val touchesSwitch = bx < switch.right + 4 && bx + block > switch.left - 4 && by < switch.bottom + 4 && by + block > switch.top - 4
            val touchesTag = by < engine.tag.bottom + 4 && bx < engine.tag.right + 4
            if (touchesSwitch || touchesTag) continue
            val e = expected(engine, screen, col, row)
            val a = colors[row * engine.cols + col]
            for (shift in intArrayOf(0, 8, 16)) worst = maxOf(worst, abs((e shr shift and 0xFF) - (a shr shift and 0xFF)))
        }
        assertTrue("worst channel error $worst", worst <= 4)
    }

    @Test
    fun convergesAndFollowsScreenChanges() {
        val engine = PixelEngine(w, h, content, block, capW, capH, alpha)
        val first = scene(0)
        val (frame, rounds) = settle(engine, engine.reset(), first)
        assertTrue("should stop redrawing on a still screen (rounds=$rounds)", rounds < 20)
        assertMatches(engine, frame, first)

        val moved = scene(60)
        val (frame2, rounds2) = settle(engine, frame, moved)
        assertTrue(rounds2 in 1 until 20)
        assertMatches(engine, frame2, moved)
    }

    /** Light "app" with a dark sprite and some text-like rows: what Space Impact mode is made for. */
    private fun lightScene(offset: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        when {
            x in (40 + offset)..(100 + offset) && y in 100..160 -> 0xFF202020.toInt()
            y in 400..440 && (x / 2) % 3 == 0 -> 0xFF303030.toInt()
            else -> 0xFFF2F2F2.toInt()
        }
    }

    private fun tone(engine: PixelEngine, frame: Frame, x: Int, y: Int) =
        frame.colors!![((y - content.top) / block) * engine.cols + (x - content.left) / block]

    @Test
    fun twoToneUsesOnlyTheNokiaPaletteAndSettles() {
        for (real in floatArrayOf(0.8f, 0.75f, 0.85f)) {
            renderAlpha = real
            val engine = PixelEngine(w, h, content, block, capW, capH, alpha, twoTone = true)
            val (frame, rounds) = settle(engine, engine.reset(), lightScene(0))
            assertTrue("alpha $real: no flicker loop (rounds=$rounds)", rounds < 20)
            assertTrue(frame.colors!!.all { it == PixelEngine.DARK || it == PixelEngine.LIGHT })
            assertEquals(PixelEngine.DARK, tone(engine, frame, 70, 130)) // sprite
            assertEquals(PixelEngine.DARK, tone(engine, frame, 100, 420)) // "text" stays visible
            assertEquals(PixelEngine.LIGHT, tone(engine, frame, 300, 250)) // background

            val (moved, rounds2) = settle(engine, frame, lightScene(160))
            assertTrue("alpha $real: rounds2=$rounds2", rounds2 in 1 until 20)
            assertEquals(PixelEngine.LIGHT, tone(engine, moved, 70, 130))
            assertEquals(PixelEngine.DARK, tone(engine, moved, 230, 130))
        }
    }

    @Test
    fun onePixelBlocksAtFullResolution() {
        block = 1
        div = 1
        for (twoTone in booleanArrayOf(true, false)) {
            val engine = PixelEngine(w, h, content, block, capW, capH, alpha, twoTone)
            assertEquals(w, engine.cols)
            val screen = lightScene(0)
            val (frame, rounds) = settle(engine, engine.reset(), screen)
            assertTrue("twoTone=$twoTone rounds=$rounds", rounds < 20)
            assertEquals(if (twoTone) PixelEngine.DARK else screen[130 * w + 70] and 0xFFFFFF or (0xFF shl 24), tone(engine, frame, 70, 130))
            if (!twoTone) assertMatches(engine, frame, screen)
        }
    }

    @Test
    fun ignoresCapturesWithoutTheOverlay() {
        val engine = PixelEngine(w, h, content, block, capW, capH, alpha)
        engine.reset()
        assertNull(engine.process(capture(render(engine, null, scene(0))), stride, 4, switch))
    }

    @Test
    fun tagEncodesEveryId() {
        val engine = PixelEngine(w, h, content, block, capW, capH, alpha)
        for (id in 0 until 32) {
            val cells = (0 until PixelEngine.TAG_CELLS).map { engine.tagCell(id, it) }
            for (bit in 0 until PixelEngine.TAG_BITS) assertEquals(!cells[bit * 2], cells[bit * 2 + 1])
        }
    }
}
