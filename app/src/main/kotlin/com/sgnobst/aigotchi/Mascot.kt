package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

// Smooth vector mascot — glossy, gradient, expressive.
// Replaces the old pixel sprites with a commercial-game look.
object Mascot {

    enum class Mood { NEUTRAL, HAPPY, SLEEPY, ANGRY, SAD, THINKING }

    fun draw(c: Canvas, fill: Paint, stroke: Paint, kit: StyleKit,
             cx: Float, cy: Float, sizeBase: Float,
             stage: Int, mood: Mood,
             blink: Boolean, t: Float,
             gpuCount: Int = 0, robotArm: Boolean = false) {

        val s = kit.s
        val sz = sizeBase * s  // overall radius
        val bodyW = sz * 1.10f
        val bodyH = sz * 1.20f

        // Body palette by stage
        val (bodyTop, bodyBot, bodyAccent) = when {
            stage >= 9 -> Triple(0xFFFFE38A.toInt(), 0xFFFF9F45.toInt(), Style.WARN)
            stage >= 7 -> Triple(0xFFD1A4FF.toInt(), 0xFF8E4DD8.toInt(), Style.SECONDARY)
            stage >= 5 -> Triple(0xFF74D6FF.toInt(), 0xFF2E7FC8.toInt(), 0xFF4FB7F0.toInt())
            stage >= 3 -> Triple(0xFF8DE6A8.toInt(), 0xFF2BA168.toInt(), Style.SUCCESS)
            else        -> Triple(0xFFE8E2C8.toInt(), 0xFFA89E78.toInt(), 0xFFD8D2B0.toInt())
        }

        // ── Halo (stage 9+) ──
        if (stage >= 9) {
            val haloT = (sin(t * 1.4) * 0.1f + 1f).toFloat()
            kit.glowOrb(c, cx, cy - bodyH * 0.95f, sz * 0.55f * haloT, Style.WARN)
            // Halo ring
            stroke.color = Style.WARN
            stroke.strokeWidth = sz * 0.06f
            c.drawOval(cx - sz * 0.55f, cy - bodyH * 1.05f, cx + sz * 0.55f, cy - bodyH * 0.85f, stroke)
        }

        // ── Soft glow behind body ──
        kit.glowOrb(c, cx, cy, sz * 1.4f, bodyAccent)

        // ── Drop shadow on floor ──
        fill.shader = RadialGradient(cx, cy + bodyH * 1.05f, sz * 1.0f,
            intArrayOf(0x80000000.toInt(), 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - sz * 1.0f, cy + bodyH * 0.95f,
                   cx + sz * 1.0f, cy + bodyH * 1.20f, fill)
        fill.shader = null

        // ── Antenna ──
        stroke.color = 0xFF1A1830.toInt()
        stroke.strokeWidth = sz * 0.08f
        c.drawLine(cx, cy - bodyH * 0.72f, cx, cy - bodyH * 0.95f, stroke)
        // Antenna tip (glowing dot)
        val tipPulse = (sin(t * 4f) * 0.15f + 1f)
        kit.glowOrb(c, cx, cy - bodyH * 0.98f, sz * 0.18f * tipPulse, Style.PRIMARY)
        fill.color = Style.PRIMARY
        c.drawCircle(cx, cy - bodyH * 0.98f, sz * 0.10f * tipPulse, fill)
        fill.color = Style.TEXT_HI
        c.drawCircle(cx - sz * 0.025f, cy - bodyH * 1.00f, sz * 0.035f, fill)

        // ── Body (rounded squircle) ──
        val bodyRect = RectF(cx - bodyW, cy - bodyH * 0.70f, cx + bodyW, cy + bodyH * 0.85f)
        // Drop body shadow
        fill.color = 0x66000000
        fill.shader = null
        c.drawRoundRect(bodyRect.left, bodyRect.top + sz * 0.10f,
                        bodyRect.right, bodyRect.bottom + sz * 0.10f,
                        sz * 0.45f, sz * 0.45f, fill)
        // Body gradient
        fill.shader = LinearGradient(0f, bodyRect.top, 0f, bodyRect.bottom,
            bodyTop, bodyBot, Shader.TileMode.CLAMP)
        c.drawRoundRect(bodyRect, sz * 0.45f, sz * 0.45f, fill)
        fill.shader = null
        // Inner glossy highlight (upper half)
        fill.shader = LinearGradient(0f, bodyRect.top, 0f, bodyRect.centerY(),
            0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(bodyRect.left + sz * 0.12f, bodyRect.top + sz * 0.06f,
                        bodyRect.right - sz * 0.12f, bodyRect.centerY() + sz * 0.10f,
                        sz * 0.35f, sz * 0.35f, fill)
        fill.shader = null
        // Top sparkle highlight oval (small bright spot)
        fill.color = 0x80FFFFFF.toInt()
        c.drawOval(cx - bodyW * 0.55f, cy - bodyH * 0.45f,
                   cx - bodyW * 0.15f, cy - bodyH * 0.20f, fill)

        // ── Eyes ──
        drawEyes(c, fill, stroke, cx, cy, sz, mood, blink, t)

        // ── Cheeks ──
        if (mood == Mood.HAPPY || mood == Mood.NEUTRAL) {
            val cheekY = cy + sz * 0.12f
            fill.shader = RadialGradient(cx - sz * 0.55f, cheekY, sz * 0.18f,
                intArrayOf(0xCCFF98B0.toInt(), 0x00FF98B0), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx - sz * 0.55f, cheekY, sz * 0.18f, fill)
            fill.shader = RadialGradient(cx + sz * 0.55f, cheekY, sz * 0.18f,
                intArrayOf(0xCCFF98B0.toInt(), 0x00FF98B0), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx + sz * 0.55f, cheekY, sz * 0.18f, fill)
            fill.shader = null
        }

        // ── Mouth ──
        drawMouth(c, fill, stroke, cx, cy, sz, mood)

        // ── Glasses (stage 5+) ──
        if (stage >= 5) {
            stroke.color = 0xFF1A1830.toInt()
            stroke.strokeWidth = sz * 0.07f
            val eyeOff = sz * 0.42f; val eyeY = cy - sz * 0.18f
            c.drawCircle(cx - eyeOff, eyeY, sz * 0.32f, stroke)
            c.drawCircle(cx + eyeOff, eyeY, sz * 0.32f, stroke)
            stroke.strokeWidth = sz * 0.05f
            c.drawLine(cx - eyeOff + sz * 0.30f, eyeY,
                       cx + eyeOff - sz * 0.30f, eyeY, stroke)
            // Lens shine
            fill.color = 0x55FFFFFF
            fill.shader = null
            c.drawCircle(cx - eyeOff + sz * 0.08f, eyeY - sz * 0.10f, sz * 0.06f, fill)
            c.drawCircle(cx + eyeOff + sz * 0.08f, eyeY - sz * 0.10f, sz * 0.06f, fill)
        }

        // ── Bowtie (stage 7+) ──
        if (stage >= 7) {
            val by = cy + bodyH * 0.55f
            fill.color = Style.PRIMARY
            val p = Path()
            p.moveTo(cx, by)
            p.lineTo(cx - sz * 0.32f, by - sz * 0.20f)
            p.lineTo(cx - sz * 0.32f, by + sz * 0.20f); p.close()
            c.drawPath(p, fill)
            p.reset()
            p.moveTo(cx, by)
            p.lineTo(cx + sz * 0.32f, by - sz * 0.20f)
            p.lineTo(cx + sz * 0.32f, by + sz * 0.20f); p.close()
            c.drawPath(p, fill)
            fill.color = Style.PRIMARY_DK
            c.drawCircle(cx, by, sz * 0.10f, fill)
        }

        // ── GPU LEDs (small lights along bottom edge) ──
        if (gpuCount >= 1) {
            val n = minOf(4, gpuCount)
            val colors = intArrayOf(Style.PRIMARY, Style.ACCENT, Style.WARN, Style.SECONDARY)
            for (i in 0 until n) {
                val ph = t * 3f + i
                val lx = cx - bodyW * 0.6f + i * (bodyW * 1.2f / n.toFloat()) + bodyW * 0.15f
                val ly = bodyRect.bottom - sz * 0.10f
                val pulse = (sin(ph) * 0.4f + 0.6f)
                kit.glowOrb(c, lx, ly, sz * 0.18f * pulse, colors[i])
                fill.color = colors[i]
                fill.shader = null
                c.drawCircle(lx, ly, sz * 0.06f * pulse, fill)
            }
        }

        // ── Robot arm (after TOOL_ROBOTARM) ──
        if (robotArm) {
            val ax = cx + bodyW + sz * 0.10f
            val ay = cy
            // upper arm
            fill.color = 0xFFC8CFE0.toInt()
            fill.shader = null
            c.drawRoundRect(ax, ay - sz * 0.10f, ax + sz * 0.6f, ay + sz * 0.10f,
                            sz * 0.10f, sz * 0.10f, fill)
            // joint
            fill.color = 0xFF6F7693.toInt()
            c.drawCircle(ax + sz * 0.6f, ay, sz * 0.14f, fill)
            // hand (claw)
            fill.color = Style.PRIMARY
            c.drawCircle(ax + sz * 0.85f, ay, sz * 0.14f, fill)
        }
    }

    private fun drawEyes(c: Canvas, fill: Paint, stroke: Paint,
                         cx: Float, cy: Float, sz: Float,
                         mood: Mood, blink: Boolean, t: Float) {
        val eyeOff = sz * 0.42f
        val eyeY = cy - sz * 0.18f
        val eyeR = sz * 0.26f

        // Eye whites
        fill.color = 0xFFFFFFFF.toInt()
        fill.shader = null

        if (blink || mood == Mood.SLEEPY) {
            // Closed eyes: arcs
            stroke.color = 0xFF1A1830.toInt()
            stroke.strokeWidth = sz * 0.08f
            stroke.style = Paint.Style.STROKE
            c.drawArc(cx - eyeOff - eyeR, eyeY - eyeR * 0.4f,
                      cx - eyeOff + eyeR, eyeY + eyeR * 0.6f,
                      0f, 180f, false, stroke)
            c.drawArc(cx + eyeOff - eyeR, eyeY - eyeR * 0.4f,
                      cx + eyeOff + eyeR, eyeY + eyeR * 0.6f,
                      0f, 180f, false, stroke)
            return
        }

        // Eye background
        c.drawCircle(cx - eyeOff, eyeY, eyeR, fill)
        c.drawCircle(cx + eyeOff, eyeY, eyeR, fill)

        // Pupils
        val pupilColor = when (mood) {
            Mood.ANGRY -> Style.DANGER
            Mood.SAD -> 0xFF5A6FA0.toInt()
            Mood.THINKING -> 0xFF1A1830.toInt()
            else -> 0xFF1A1830.toInt()
        }
        fill.color = pupilColor

        // For THINKING, pupils shift to upper-right looking
        val px = if (mood == Mood.THINKING) sz * 0.05f else sz * 0.03f
        val py = if (mood == Mood.THINKING) -sz * 0.05f else sz * 0.03f
        c.drawCircle(cx - eyeOff + px, eyeY + py, eyeR * 0.55f, fill)
        c.drawCircle(cx + eyeOff + px, eyeY + py, eyeR * 0.55f, fill)

        // Highlights
        fill.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx - eyeOff + sz * 0.10f, eyeY - sz * 0.06f, sz * 0.06f, fill)
        c.drawCircle(cx + eyeOff + sz * 0.10f, eyeY - sz * 0.06f, sz * 0.06f, fill)
        c.drawCircle(cx - eyeOff - sz * 0.04f, eyeY + sz * 0.07f, sz * 0.025f, fill)
        c.drawCircle(cx + eyeOff - sz * 0.04f, eyeY + sz * 0.07f, sz * 0.025f, fill)

        // Angry brows
        if (mood == Mood.ANGRY) {
            stroke.color = Style.DANGER_DK
            stroke.strokeWidth = sz * 0.10f
            c.drawLine(cx - eyeOff - eyeR * 0.6f, eyeY - eyeR * 1.1f,
                       cx - eyeOff + eyeR * 0.6f, eyeY - eyeR * 0.5f, stroke)
            c.drawLine(cx + eyeOff + eyeR * 0.6f, eyeY - eyeR * 1.1f,
                       cx + eyeOff - eyeR * 0.6f, eyeY - eyeR * 0.5f, stroke)
        }
    }

    private fun drawMouth(c: Canvas, fill: Paint, stroke: Paint,
                          cx: Float, cy: Float, sz: Float, mood: Mood) {
        val mY = cy + sz * 0.38f
        stroke.color = 0xFF1A1830.toInt()
        stroke.strokeWidth = sz * 0.08f
        stroke.style = Paint.Style.STROKE
        when (mood) {
            Mood.SAD -> c.drawArc(cx - sz * 0.22f, mY - sz * 0.08f,
                                  cx + sz * 0.22f, mY + sz * 0.14f, 180f, 180f, false, stroke)
            Mood.ANGRY -> {
                fill.color = 0xFF1A1830.toInt()
                fill.shader = null
                val p = Path()
                p.moveTo(cx - sz * 0.18f, mY)
                p.lineTo(cx + sz * 0.18f, mY)
                p.lineTo(cx, mY + sz * 0.15f); p.close()
                c.drawPath(p, fill)
            }
            Mood.SLEEPY -> {
                stroke.strokeCap = Paint.Cap.ROUND
                c.drawLine(cx - sz * 0.12f, mY + sz * 0.04f,
                           cx + sz * 0.12f, mY + sz * 0.04f, stroke)
            }
            Mood.THINKING -> {
                c.drawLine(cx - sz * 0.10f, mY + sz * 0.06f,
                           cx + sz * 0.10f, mY + sz * 0.02f, stroke)
            }
            else -> {
                // Happy smile (default)
                c.drawArc(cx - sz * 0.24f, mY - sz * 0.18f,
                          cx + sz * 0.24f, mY + sz * 0.12f, 0f, 180f, false, stroke)
                // Tongue / blush hint
                fill.color = 0xFFFF6890.toInt()
                fill.shader = null
                c.drawArc(cx - sz * 0.14f, mY - sz * 0.05f,
                          cx + sz * 0.14f, mY + sz * 0.08f, 20f, 140f, true, fill)
            }
        }
    }

    fun moodFor(g: GameState, blink: Boolean): Mood {
        if (g.stats[STAT_HELPFUL] < 15) return Mood.SLEEPY
        if (g.stats[STAT_HARMLESS] < 15) return Mood.ANGRY
        if (g.stats[STAT_HONEST] < 15) return Mood.NEUTRAL
        if (g.stats[STAT_HELPFUL] > 60) return Mood.HAPPY
        return Mood.NEUTRAL
    }
}
