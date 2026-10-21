package com.sitecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Aiming plus on the live view only. Never drawn on the saved photo. Turns green when level. */
class CrosshairView(c: Context, a: AttributeSet?) : View(c, a) {
    var level = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val len = 48 * d
        val gap = 8 * d
        for (pass in 0..1) {
            if (pass == 0) { p.strokeWidth = 4.5f * d; p.color = Color.argb(140, 0, 0, 0) }
            else {
                p.strokeWidth = 2f * d
                p.color = if (level) Color.argb(240, 80, 255, 80) else Color.argb(230, 255, 255, 255)
            }
            canvas.drawLine(cx - len, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + len, cy, p)
            canvas.drawLine(cx, cy - len, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + len, p)
        }
    }
}
