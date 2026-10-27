package com.pixelated.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Full-screen, click-through view that paints the pixelated frame and its tag. */
class PixelOverlayView(context: Context) : View(context) {
    private var engine: PixelEngine? = null
    private var frame: Frame? = null
    private var bitmap: Bitmap? = null

    private val blockPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val tagPaint = Paint().apply { isAntiAlias = false }
    private val dst = Rect()
    private val location = IntArray(2)

    fun show(engine: PixelEngine, frame: Frame) {
        this.engine = engine
        this.frame = frame
        bitmap = frame.colors?.let { Bitmap.createBitmap(it, engine.cols, engine.rows, Bitmap.Config.ARGB_8888) }
        invalidate()
    }

    fun clear() {
        engine = null
        frame = null
        bitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val engine = engine ?: return
        val frame = frame ?: return
        // The engine works in screen coordinates; shift by wherever the window really landed.
        getLocationOnScreen(location)
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())

        bitmap?.let {
            val c = engine.content
            dst.set(c.left, c.top, c.left + engine.cols * engine.block, c.top + engine.rows * engine.block)
            canvas.save()
            canvas.clipRect(c.left, c.top, c.right, c.bottom)
            canvas.drawBitmap(it, null, dst, blockPaint)
            canvas.restore()
        }

        val t = engine.tag
        for (k in 0 until PixelEngine.TAG_CELLS) {
            tagPaint.color = if (engine.tagCell(frame.id, k)) Color.WHITE else Color.BLACK
            val left = (t.left + k * PixelEngine.TAG_CELL).toFloat()
            canvas.drawRect(left, t.top.toFloat(), left + PixelEngine.TAG_CELL, t.bottom.toFloat(), tagPaint)
        }
    }
}
