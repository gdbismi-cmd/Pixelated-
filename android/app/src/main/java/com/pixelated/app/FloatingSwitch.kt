package com.pixelated.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * A small draggable on/off toggle that floats above every app.
 * Tap to switch pixelation on or off, drag to move it out of the way.
 */
@SuppressLint("ViewConstructor")
class FloatingSwitch(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onToggle: (Boolean) -> Unit,
    private val onMoved: () -> Unit,
) : View(context) {

    var isOn = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val trackOn = Color.parseColor("#06D6A0")
    private val trackOff = Color.parseColor("#9E9E9E")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    init {
        contentDescription = context.getString(R.string.switch_description)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((64 * density).toInt(), (36 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 2 * density
        val h = height.toFloat()
        val w = width.toFloat()

        // Shadow-ish outline, track, thumb.
        paint.color = Color.argb(70, 0, 0, 0)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.color = if (isOn) trackOn else trackOff
        rect.set(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        val r = h / 2 - pad * 2.5f
        val cx = if (isOn) w - h / 2 else h / 2
        paint.color = Color.WHITE
        canvas.drawCircle(cx, h / 2, r, paint)

        // A tiny 2x2 "pixel" glyph inside the thumb.
        val s = r * 0.45f
        paint.color = if (isOn) trackOn else trackOff
        canvas.drawRect(cx - s, h / 2 - s, cx, h / 2, paint)
        canvas.drawRect(cx, h / 2, cx + s, h / 2 + s, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(this, params)
                    onMoved()
                }
            }
            MotionEvent.ACTION_UP -> if (!dragging) performClick() else onMoved()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        isOn = !isOn
        onToggle(isOn)
        return true
    }
}
