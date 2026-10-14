package com.douglas.ipodmux

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** La rueda tactil clasica: girar = mover la seleccion, tocar los bordes = menu / siguiente / anterior / play. */
class ClickWheelView(ctx: Context) : View(ctx) {
    var onTurn: ((Int) -> Unit)? = null
    var onCenter: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt() }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB5B5B5.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD2D2D2.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A6A6A.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private var lastAngle = 0.0
    private var acc = 0.0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var inRing = false

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx, cy, r, edgePaint)
        c.drawCircle(cx, cy, r * CENTER, centerPaint)
        c.drawCircle(cx, cy, r * CENTER, edgePaint)
        txt.textSize = r * 0.13f
        val mid = r * 0.69f
        val off = txt.textSize / 3f
        c.drawText("MENU", cx, cy - mid + off, txt)
        c.drawText(">>|", cx + mid, cy + off, txt)
        c.drawText("|<<", cx - mid, cy + off, txt)
        c.drawText(">||", cx, cy + mid + off, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val dist = hypot(e.x - cx, e.y - cy)
        val ang = atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                acc = 0.0
                lastAngle = ang
                inRing = dist > r * CENTER && dist < r
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (inRing && dist > r * 0.30f) {
                    var d = ang - lastAngle
                    if (d > PI) d -= 2 * PI
                    if (d < -PI) d += 2 * PI
                    lastAngle = ang
                    acc += d
                    if (hypot(e.x - downX, e.y - downY) > r * 0.12f) moved = true
                    val step = 0.40
                    while (acc > step) { acc -= step; onTurn?.invoke(1) }
                    while (acc < -step) { acc += step; onTurn?.invoke(-1) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    if (hypot(downX - cx, downY - cy) <= r * CENTER) {
                        onCenter?.invoke()
                    } else {
                        val a = atan2((downY - cy).toDouble(), (downX - cx).toDouble())
                        when {
                            abs(a) < PI / 4 -> onNext?.invoke()
                            a >= PI / 4 && a < 3 * PI / 4 -> onPlayPause?.invoke()
                            a <= -PI / 4 && a > -3 * PI / 4 -> onMenu?.invoke()
                            else -> onPrev?.invoke()
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    companion object {
        private const val CENTER = 0.38f
    }
}
