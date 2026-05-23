package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Synthwave palette
object Palette {
    const val BG_DEEP   = 0xFF0A0420.toInt()
    const val BG_MID    = 0xFF1A0F40.toInt()
    const val BG_TOP    = 0xFF2A1860.toInt()
    const val NEON_PINK   = 0xFFFF2D95.toInt()
    const val NEON_CYAN   = 0xFF00F0FF.toInt()
    const val NEON_PURPLE = 0xFFB026FF.toInt()
    const val NEON_GOLD   = 0xFFFFD700.toInt()
    const val NEON_GREEN  = 0xFF00FF88.toInt()
    const val NEON_RED    = 0xFFFF3366.toInt()
    const val TEXT_HI     = 0xFFEAF0FF.toInt()
    const val TEXT_LO     = 0xFF9F90D0.toInt()
    const val PANEL_DARK  = 0xFF160930.toInt()
    const val PANEL_GLOW  = 0xFF2A1860.toInt()
}

class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float,
    val maxLife: Float,
    val color: Int,
    val size: Float
) {
    fun update(dt: Float) {
        x += vx * dt; y += vy * dt
        vy += 200f * dt   // gravity
        life -= dt
    }
    fun draw(canvas: Canvas, paint: Paint) {
        val a = (life / maxLife).coerceIn(0f, 1f)
        paint.color = color
        paint.alpha = (a * 255).toInt()
        canvas.drawCircle(x, y, size * a, paint)
        paint.alpha = 255
    }
}

class FloatText(
    var x: Float, var y: Float,
    val text: String,
    val color: Int,
    var life: Float = 1.2f,
    val rise: Float = 60f
) {
    val maxLife = life
    fun update(dt: Float) {
        y -= rise * dt
        life -= dt
    }
    fun draw(canvas: Canvas, paint: Paint) {
        val a = (life / maxLife).coerceIn(0f, 1f)
        paint.color = color
        paint.alpha = (a * 255).toInt()
        canvas.drawText(text, x, y, paint)
        paint.alpha = 255
    }
}

class Star(var x: Float, var y: Float, var z: Float)

class GridFloor {
    fun draw(canvas: Canvas, w: Float, h: Float, t: Float, paint: Paint) {
        val horizon = h * 0.42f
        // sun
        paint.color = Palette.NEON_PINK
        paint.alpha = 200
        canvas.drawCircle(w/2f, horizon - 30f, 140f, paint)
        paint.alpha = 255
        // sun stripes (cutting through)
        paint.color = Palette.BG_DEEP
        for (i in 0..5) {
            val y = horizon - 90f + i * 22f
            canvas.drawRect(w/2f - 160f, y, w/2f + 160f, y + 6f + i, paint)
        }
        // distant mountains silhouette
        paint.color = Palette.BG_MID
        val path = android.graphics.Path()
        path.moveTo(0f, horizon)
        var x = 0f
        while (x < w) {
            val peak = 40f + (sin((x * 0.012f).toDouble()) * 30f).toFloat() +
                       (cos((x * 0.03f).toDouble()) * 18f).toFloat()
            path.lineTo(x, horizon - peak)
            x += 40f
        }
        path.lineTo(w, horizon); path.lineTo(w, h*0.45f); path.lineTo(0f, h*0.45f); path.close()
        canvas.drawPath(path, paint)
        // grid floor (perspective lines)
        paint.color = Palette.NEON_PINK
        paint.alpha = 200
        paint.strokeWidth = 2f
        // horizon line
        canvas.drawLine(0f, horizon, w, horizon, paint)
        // vertical perspective lines converging at center horizon
        val vCenter = w / 2f
        for (i in -10..10) {
            val baseX = vCenter + i * 60f
            canvas.drawLine(vCenter, horizon, baseX + i * 80f, h, paint)
        }
        // horizontal scrolling lines
        val scroll = (t * 60f) % 60f
        var y = horizon + scroll
        var step = 8f
        while (y < h) {
            paint.alpha = ((y - horizon) / (h - horizon) * 220f + 30f).toInt().coerceIn(40, 255)
            canvas.drawLine(0f, y, w, y, paint)
            y += step
            step += 4f
        }
        paint.alpha = 255
    }
}

// Floating world objects
class Coin(var x: Float, var y: Float, var vy: Float, var phase: Float, var value: Int = 5)
class CatBlob(var x: Float, var y: Float, var vx: Float, var bob: Float = 0f, var life: Float = 8f)
class Glitch(var x: Float, var y: Float, var vx: Float, var vy: Float, var hp: Int = 1, var life: Float = 6f)

class Shake(var amount: Float = 0f) {
    fun bump(a: Float) { if (a > amount) amount = a }
    fun decay(dt: Float) { amount *= (1f - 8f * dt).coerceAtLeast(0f) }
    fun apply(canvas: Canvas) {
        if (amount > 0.1f) {
            val dx = (Random.nextFloat() - 0.5f) * amount
            val dy = (Random.nextFloat() - 0.5f) * amount
            canvas.translate(dx, dy)
        }
    }
}
