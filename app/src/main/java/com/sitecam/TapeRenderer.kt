package com.sitecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/** Compass strip with orange center pointer + vertical aiming line. Used on screen and on the photo. */
object TapeRenderer {
    private val names = mapOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE",
        180 to "S", 225 to "SW", 270 to "W", 315 to "NW")
    private val ORANGE = Color.rgb(255, 152, 0)

    /** u = size of one "dp" in pixels; lineU = total height of the aiming line in dp. */
    fun draw(c: Canvas, w: Float, az: Float, u: Float, lineU: Float = 84f) {
        val stripH = 40 * u
        c.drawRect(0f, 0f, w, stripH, Paint().apply { color = Color.argb(110, 0, 0, 0) })

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * u }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13 * u; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 10 * u; textAlign = Paint.Align.CENTER
        }
        val cx = w / 2f
        val ppd = w / 80f                       // 80 degrees visible across the strip
        var d = Math.floor((az - 45.0) / 5.0).toInt() * 5
        while (d <= az + 45) {
            val x = cx + (d - az) * ppd
            val dn = ((d % 360) + 360) % 360
            val major = dn % 15 == 0
            c.drawLine(x, stripH, x, stripH - (if (major) 14 * u else 7 * u), tick)
            if (dn % 45 == 0) c.drawText(names[dn] ?: "", x, stripH - 18 * u, big)
            else if (major) c.drawText(dn.toString(), x, stripH - 18 * u, small)
            d += 5
        }

        // orange pointer + vertical aiming line
        val org = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE; strokeWidth = 2.5f * u }
        c.drawLine(cx, 0f, cx, lineU * u, org)
        val tri = Path().apply {
            moveTo(cx - 7 * u, 0f); lineTo(cx + 7 * u, 0f); lineTo(cx, 12 * u); close()
        }
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE })

        // azimuth value next to the line
        val shown = (Math.round(az) % 360 + 360) % 360
        val label = String.format(Locale.US, "%d°", shown)
        val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * u; isFakeBoldText = true }
        val tw = vp.measureText(label)
        c.drawRect(cx + 6 * u, stripH + 3 * u, cx + 14 * u + tw, stripH + 24 * u,
            Paint().apply { color = Color.argb(120, 0, 0, 0) })
        c.drawText(label, cx + 10 * u, stripH + 19.5f * u, vp)
    }
}

class CompassTapeView(c: Context, a: AttributeSet?) : View(c, a) {
    var azimuth = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        TapeRenderer.draw(canvas, width.toFloat(), azimuth, resources.displayMetrics.density)
    }
}
