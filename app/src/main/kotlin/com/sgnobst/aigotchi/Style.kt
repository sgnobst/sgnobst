package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

// v6.0 — modern commercial mobile-game palette + smooth primitives.
object Style {
    // ─ Backdrop ─
    const val BG_BASE    = 0xFF0A0A1E.toInt()
    const val BG_PANEL   = 0xCC1A1830.toInt()   // semi-transparent dark surface
    const val BG_PANEL_2 = 0xF21F1D38.toInt()
    const val BG_FROST   = 0x33FFFFFF              // bright frosted tint

    // ─ Brand accents ─
    const val PRIMARY     = 0xFFFF5C7C.toInt()    // coral-pink CTA
    const val PRIMARY_DK  = 0xFFC93A5C.toInt()
    const val PRIMARY_LT  = 0xFFFF8AA4.toInt()

    const val ACCENT      = 0xFF3AE0D0.toInt()    // teal highlight (money / ASK)
    const val ACCENT_DK   = 0xFF1AA899.toInt()
    const val ACCENT_LT   = 0xFF74EEDF.toInt()

    const val SECONDARY   = 0xFFB670F0.toInt()    // purple
    const val SECONDARY_DK= 0xFF7E3CB8.toInt()

    const val WARN        = 0xFFFFA838.toInt()
    const val WARN_DK     = 0xFFB87A20.toInt()

    const val DANGER      = 0xFFFF4F4F.toInt()
    const val DANGER_DK   = 0xFFB02828.toInt()

    const val SUCCESS     = 0xFF44D67A.toInt()
    const val SUCCESS_DK  = 0xFF1F9E50.toInt()

    // ─ Radial glow colors (very low alpha) ─
    const val GLOW_PURPLE = 0x66B048FF
    const val GLOW_TEAL   = 0x553AE0D0
    const val GLOW_PINK   = 0x44E73C7E

    // ─ Text ─
    const val TEXT_HI     = 0xFFFFFFFF.toInt()
    const val TEXT_MD     = 0xFFCFD2E6.toInt()
    const val TEXT_LO     = 0xFF8E92AE.toInt()
    const val TEXT_DARK   = 0xFF0A0A1E.toInt()

    // ─ Shadow ─
    const val SHADOW      = 0x66000000

    // ─ Type scale (logical px, scaled by width/1080) ─
    const val DISPLAY_PX = 130f
    const val HERO_PX    = 88f
    const val H1_PX      = 66f
    const val H2_PX      = 54f
    const val H3_PX      = 44f
    const val BODY_PX    = 38f
    const val LABEL_PX   = 32f
    const val CAPTION_PX = 26f
    const val TINY_PX    = 22f
    const val CTA_PX     = 48f
    const val TAB_PX     = 26f

    // ─ Geometry ─
    const val PANEL_R    = 44f
    const val CARD_R     = 36f
    const val CHIP_R_PX  = 999f
}

class StyleKit {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Style.TEXT_HI
    }
    val textRegular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        color = Style.TEXT_MD
    }
    var s = 1f

    fun setScale(viewWidth: Int) { s = viewWidth / 1080f }
    fun sz(v: Float) = v * s

    // ─── Backdrop: deep base + animated radial glows ───
    fun drawBg(c: Canvas, w: Float, h: Float, t: Float) {
        fill.color = Style.BG_BASE
        fill.shader = null
        c.drawRect(0f, 0f, w, h, fill)

        // Glow A: purple top-left
        val gAcx = w * 0.22f + (Math.sin(t.toDouble() * 0.25) * w * 0.05f).toFloat()
        val gAcy = h * 0.18f
        fill.shader = RadialGradient(gAcx, gAcy, w * 0.65f,
            intArrayOf(Style.GLOW_PURPLE, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)

        // Glow B: teal top-right
        val gBcx = w * 0.85f - (Math.cos(t.toDouble() * 0.32) * w * 0.06f).toFloat()
        val gBcy = h * 0.22f
        fill.shader = RadialGradient(gBcx, gBcy, w * 0.55f,
            intArrayOf(Style.GLOW_TEAL, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)

        // Glow C: pink bottom-mid
        val gCcx = w * 0.4f + (Math.sin(t.toDouble() * 0.2) * w * 0.08f).toFloat()
        val gCcy = h * 0.78f
        fill.shader = RadialGradient(gCcx, gCcy, w * 0.7f,
            intArrayOf(Style.GLOW_PINK, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)

        fill.shader = null
    }

    // ─── Rounded text with soft shadow ───
    fun heading(c: Canvas, t: String, x: Float, y: Float, sizePx: Float,
                color: Int = Style.TEXT_HI,
                align: Paint.Align = Paint.Align.LEFT,
                shadow: Boolean = true) {
        val tsize = sizePx * s
        text.textSize = tsize
        text.textAlign = align
        if (shadow) {
            text.color = Style.SHADOW
            c.drawText(t, x, y + sz(4f), text)
        }
        text.color = color
        c.drawText(t, x, y, text)
    }

    fun body(c: Canvas, t: String, x: Float, y: Float, sizePx: Float = Style.BODY_PX,
             color: Int = Style.TEXT_MD,
             align: Paint.Align = Paint.Align.LEFT,
             bold: Boolean = false) {
        val p = if (bold) text else textRegular
        p.textSize = sizePx * s
        p.textAlign = align
        p.color = color
        c.drawText(t, x, y, p)
    }

    fun measure(t: String, sizePx: Float, bold: Boolean = true): Float {
        val p = if (bold) text else textRegular
        p.textSize = sizePx * s
        return p.measureText(t)
    }

    // ─── Soft drop-shadow rounded panel ───
    fun panel(c: Canvas, r: RectF, fillColor: Int = Style.BG_PANEL,
              radius: Float = Style.PANEL_R,
              elevation: Float = 6f,
              borderColor: Int = 0,
              borderW: Float = 0f) {
        val cr = radius * s
        // shadow
        if (elevation > 0) {
            val sh = elevation * s
            fill.color = Style.SHADOW
            fill.shader = null
            c.drawRoundRect(r.left, r.top + sh, r.right, r.bottom + sh, cr, cr, fill)
        }
        fill.color = fillColor
        fill.shader = null
        c.drawRoundRect(r, cr, cr, fill)
        if (borderColor != 0 && borderW > 0f) {
            stroke.color = borderColor
            stroke.strokeWidth = borderW * s
            c.drawRoundRect(r, cr, cr, stroke)
        }
    }

    // ─── Glass card (translucent) ───
    fun card(c: Canvas, r: RectF, radius: Float = Style.CARD_R) {
        val cr = radius * s
        fill.color = Style.SHADOW
        fill.shader = null
        c.drawRoundRect(r.left, r.top + sz(6f), r.right, r.bottom + sz(6f), cr, cr, fill)
        fill.color = Style.BG_PANEL
        c.drawRoundRect(r, cr, cr, fill)
        // subtle top highlight stroke
        fill.color = 0x14FFFFFF
        c.drawRoundRect(r.left, r.top, r.right, r.top + cr, cr, cr, fill)
    }

    // ─── Primary CTA pill button (gradient fill + glow) ───
    fun ctaButton(c: Canvas, r: RectF, label: String, fillColor: Int = Style.PRIMARY,
                  icon: String? = null, pressed: Boolean = false,
                  sizePx: Float = Style.CTA_PX) {
        val cr = r.height() / 2f
        val pr = if (pressed) RectF(r.left, r.top + sz(4f), r.right, r.bottom + sz(4f)) else r
        // outer glow
        fill.color = fillColor and 0x00FFFFFF or 0x55000000.inv() and fillColor or 0x55000000  // glow alpha
        fill.shader = RadialGradient(pr.centerX(), pr.centerY(),
            pr.width() * 0.7f,
            intArrayOf((fillColor and 0xFFFFFF) or 0x55000000, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(pr.left - sz(20f), pr.top - sz(10f),
                        pr.right + sz(20f), pr.bottom + sz(20f), cr, cr, fill)
        fill.shader = null
        // shadow
        if (!pressed) {
            fill.color = 0x80000000.toInt()
            c.drawRoundRect(pr.left, pr.top + sz(10f), pr.right, pr.bottom + sz(10f), cr, cr, fill)
        }
        // fill — vertical gradient (light top → darker bottom)
        val lighter = blend(fillColor, 0xFFFFFFFF.toInt(), 0.18f)
        val darker = blend(fillColor, 0xFF000000.toInt(), 0.18f)
        fill.shader = LinearGradient(0f, pr.top, 0f, pr.bottom,
            lighter, darker, Shader.TileMode.CLAMP)
        c.drawRoundRect(pr, cr, cr, fill)
        fill.shader = null
        // glossy top half oval
        fill.color = 0x33FFFFFF
        c.drawRoundRect(pr.left + sz(12f), pr.top + sz(6f),
                        pr.right - sz(12f), pr.top + pr.height() * 0.45f, cr, cr, fill)

        // label
        val tx = pr.centerX()
        val ty = pr.centerY() + sz(sizePx) * 0.35f
        if (icon != null) {
            text.textSize = sz(sizePx * 1.15f); text.textAlign = Paint.Align.LEFT
            val iconW = text.measureText(icon)
            text.textSize = sz(sizePx); text.textAlign = Paint.Align.LEFT
            val labW = text.measureText(label)
            val totalW = iconW + sz(20f) + labW
            val startX = tx - totalW / 2f
            text.textSize = sz(sizePx * 1.15f); text.color = Style.TEXT_HI
            c.drawText(icon, startX, pr.centerY() + sz(sizePx) * 0.38f, text)
            heading(c, label, startX + iconW + sz(20f), ty, sizePx, Style.TEXT_HI, Paint.Align.LEFT, shadow = false)
        } else {
            heading(c, label, tx, ty, sizePx, Style.TEXT_HI, Paint.Align.CENTER, shadow = false)
        }
    }

    // ─── Ghost button (transparent w/ border) ───
    fun ghostButton(c: Canvas, r: RectF, label: String, icon: String? = null,
                    color: Int = Style.TEXT_HI, sizePx: Float = Style.BODY_PX, pressed: Boolean = false) {
        val cr = r.height() / 2f
        val pr = if (pressed) RectF(r.left, r.top + sz(2f), r.right, r.bottom + sz(2f)) else r
        fill.color = 0x33FFFFFF
        fill.shader = null
        c.drawRoundRect(pr, cr, cr, fill)
        stroke.color = color
        stroke.strokeWidth = sz(3f)
        c.drawRoundRect(pr, cr, cr, stroke)
        val tx = pr.centerX()
        val ty = pr.centerY() + sz(sizePx) * 0.35f
        if (icon != null) {
            text.textSize = sz(sizePx); text.textAlign = Paint.Align.LEFT
            val labW = text.measureText(label)
            text.textSize = sz(sizePx * 1.1f); text.textAlign = Paint.Align.LEFT
            val iconW = text.measureText(icon)
            val total = iconW + sz(14f) + labW
            val sx = tx - total / 2f
            text.color = color
            c.drawText(icon, sx, pr.centerY() + sz(sizePx) * 0.38f, text)
            heading(c, label, sx + iconW + sz(14f), ty, sizePx, color, Paint.Align.LEFT, shadow = false)
        } else {
            heading(c, label, tx, ty, sizePx, color, Paint.Align.CENTER, shadow = false)
        }
    }

    // ─── Small icon button (round) ───
    fun iconButton(c: Canvas, cx: Float, cy: Float, radius: Float, icon: String,
                   bg: Int = Style.BG_PANEL, fg: Int = Style.TEXT_HI, pressed: Boolean = false) {
        val r = radius * s
        val ox = if (pressed) sz(2f) else 0f
        fill.color = Style.SHADOW
        c.drawCircle(cx, cy + sz(4f) + ox, r, fill)
        fill.color = bg
        c.drawCircle(cx, cy + ox, r, fill)
        text.textSize = r * 1.0f; text.textAlign = Paint.Align.CENTER; text.color = fg
        c.drawText(icon, cx, cy + ox + r * 0.35f, text)
    }

    // ─── Chip / tag pill ───
    fun chip(c: Canvas, r: RectF, label: String, fillColor: Int = Style.BG_PANEL_2,
             textColor: Int = Style.TEXT_HI, sizePx: Float = Style.CAPTION_PX,
             bordered: Boolean = false) {
        val cr = r.height() / 2f
        fill.color = fillColor
        fill.shader = null
        c.drawRoundRect(r, cr, cr, fill)
        if (bordered) {
            stroke.color = textColor
            stroke.strokeWidth = sz(2f)
            c.drawRoundRect(r, cr, cr, stroke)
        }
        heading(c, label, r.centerX(), r.centerY() + sz(sizePx) * 0.35f,
                sizePx, textColor, Paint.Align.CENTER, shadow = false)
    }

    // ─── Smooth filled progress bar ───
    fun progressBar(c: Canvas, x: Float, y: Float, w: Float, h: Float,
                    value: Float, color: Int) {
        val cr = h / 2f
        // track
        fill.color = 0x33FFFFFF
        fill.shader = null
        c.drawRoundRect(x, y, x + w, y + h, cr, cr, fill)
        val v = (value / 100f).coerceIn(0f, 1f)
        if (v > 0f) {
            val fw = w * v
            // gradient fill
            val lighter = blend(color, 0xFFFFFFFF.toInt(), 0.3f)
            fill.shader = LinearGradient(x, y, x, y + h, lighter, color, Shader.TileMode.CLAMP)
            c.drawRoundRect(x, y, x + fw.coerceAtLeast(h), y + h, cr, cr, fill)
            fill.shader = null
        }
    }

    // ─── Circular progress ring (for day, etc) ───
    fun progressRing(c: Canvas, cx: Float, cy: Float, radius: Float, value: Float,
                     color: Int, trackColor: Int = 0x33FFFFFF, thickness: Float = 12f) {
        val r = radius * s
        val tk = thickness * s
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = trackColor
        stroke.strokeWidth = tk
        c.drawCircle(cx, cy, r, stroke)
        if (value > 0f) {
            stroke.color = color
            val sweep = 360f * value.coerceIn(0f, 1f)
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, stroke)
        }
    }

    // ─── Smooth speech bubble ───
    fun speech(c: Canvas, cx: Float, bottomY: Float, t: String, maxW: Float) {
        text.textSize = sz(Style.BODY_PX)
        val padX = sz(36f); val padY = sz(20f)
        val tw = (text.measureText(t) + padX * 2).coerceAtMost(maxW)
        val th = sz(Style.BODY_PX) * 1.7f + padY
        val r = RectF(cx - tw / 2f, bottomY - th, cx + tw / 2f, bottomY)
        fill.color = Style.SHADOW
        fill.shader = null
        c.drawRoundRect(r.left, r.top + sz(6f), r.right, r.bottom + sz(6f), sz(28f), sz(28f), fill)
        fill.color = Style.TEXT_HI
        c.drawRoundRect(r, sz(28f), sz(28f), fill)
        // tail
        val p = Path()
        p.moveTo(cx - sz(16f), r.bottom - sz(2f))
        p.lineTo(cx, r.bottom + sz(24f))
        p.lineTo(cx + sz(16f), r.bottom - sz(2f))
        p.close()
        c.drawPath(p, fill)
        heading(c, t, cx, r.centerY() + sz(Style.BODY_PX) * 0.35f,
                Style.BODY_PX, Style.TEXT_DARK, Paint.Align.CENTER, shadow = false)
    }

    // ─── Soft radial glow at a point (mascot halo) ───
    fun glowOrb(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val r = radius * s
        fill.shader = RadialGradient(cx, cy, r,
            intArrayOf((color and 0xFFFFFF) or 0x66000000, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    // ─── Confetti sparkle (4-point star) ───
    fun sparkle(c: Canvas, cx: Float, cy: Float, sizePx: Float, color: Int) {
        val r = sizePx * s
        val p = Path()
        p.moveTo(cx, cy - r)
        p.lineTo(cx + r * 0.25f, cy - r * 0.25f)
        p.lineTo(cx + r, cy)
        p.lineTo(cx + r * 0.25f, cy + r * 0.25f)
        p.lineTo(cx, cy + r)
        p.lineTo(cx - r * 0.25f, cy + r * 0.25f)
        p.lineTo(cx - r, cy)
        p.lineTo(cx - r * 0.25f, cy - r * 0.25f)
        p.close()
        fill.color = color
        fill.shader = null
        c.drawPath(p, fill)
    }

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            val ti = t.coerceIn(0f, 1f)
            val aa = ((a ushr 24) and 0xFF); val ar = ((a ushr 16) and 0xFF); val ag = ((a ushr 8) and 0xFF); val ab = a and 0xFF
            val ba = ((b ushr 24) and 0xFF); val br = ((b ushr 16) and 0xFF); val bg = ((b ushr 8) and 0xFF); val bb = b and 0xFF
            val nA = (aa + (ba - aa) * ti).toInt()
            val nR = (ar + (br - ar) * ti).toInt()
            val nG = (ag + (bg - ag) * ti).toInt()
            val nB = (ab + (bb - ab) * ti).toInt()
            return (nA shl 24) or (nR shl 16) or (nG shl 8) or nB
        }
    }
}

fun blend(a: Int, b: Int, t: Float): Int = StyleKit.blend(a, b, t)
