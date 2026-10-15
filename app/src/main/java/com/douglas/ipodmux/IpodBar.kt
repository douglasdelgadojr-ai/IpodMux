package com.douglas.ipodmux

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** La barra de progreso azul de la pantalla "Reproduciendo" del iPod clasico. */
class IpodBarView(ctx: Context) : View(ctx) {
    var fraction: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF8A8A8A.toInt()
    }
    private val rect = RectF()
    private val clip = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return
        val r = h / 2f
        rect.set(1f, 1f, w - 1f, h - 1f)
        trackPaint.shader = LinearGradient(0f, 0f, 0f, h, 0xFFB4B4B4.toInt(), 0xFFF0F0F0.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, trackPaint)
        val fw = (w - 2f) * fraction
        if (fw > 0f) {
            fillPaint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF7CC4FF.toInt(), 0xFF0A6FD6.toInt(), Shader.TileMode.CLAMP)
            val save = c.save()
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.clipPath(clip)
            c.drawRect(1f, 1f, 1f + fw, h - 1f, fillPaint)
            c.restoreToCount(save)
        }
        c.drawRoundRect(rect, r, r, edgePaint)
    }
}
