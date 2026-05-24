package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.Paint

// Smooth circular particle (anti-aliased).
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
        vy += 240f * dt
        vx *= 0.985f
        life -= dt
    }
    fun draw(canvas: Canvas, paint: Paint) {
        val a = (life / maxLife).coerceIn(0f, 1f)
        val s = size * (0.4f + a * 0.8f)
        paint.color = color
        paint.alpha = (a * 255).toInt()
        paint.isAntiAlias = true
        canvas.drawCircle(x, y, s, paint)
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
}

class Coin(var x: Float, var y: Float, var vy: Float, var phase: Float, var value: Int = 5)
class CatBlob(var x: Float, var y: Float, var vx: Float, var bob: Float = 0f, var life: Float = 8f)
class Glitch(var x: Float, var y: Float, var vx: Float, var vy: Float, var hp: Int = 1, var life: Float = 6f)

class Shake(var amount: Float = 0f)
