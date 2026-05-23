package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

// Cute pet-game palette: warm pastels + chunky navy outlines
object Style {
    // Sky / background gradient
    const val SKY_TOP    = 0xFFA7D8E8.toInt()   // soft sky blue
    const val SKY_MID    = 0xFFFFE6B8.toInt()   // peach cream
    const val SKY_BOT    = 0xFFFFD0A8.toInt()   // warm peach

    // Floor (pet platform / rug)
    const val FLOOR_A    = 0xFFFFB892.toInt()
    const val FLOOR_B    = 0xFFE69478.toInt()
    const val RUG        = 0xFF7AD3B0.toInt()

    // Surfaces / panels
    const val CREAM      = 0xFFFFF6E3.toInt()
    const val CREAM_DARK = 0xFFF0E4C6.toInt()
    const val PANEL      = 0xFFFFFFFF.toInt()
    const val PANEL_DK   = 0xFFFFEED0.toInt()

    // Accents
    const val CORAL      = 0xFFFF6B6B.toInt()
    const val CORAL_DK   = 0xFFE05050.toInt()
    const val SUN        = 0xFFFFCB3D.toInt()
    const val SUN_DK     = 0xFFE0A820.toInt()
    const val MINT       = 0xFF6BD4A8.toInt()
    const val MINT_DK    = 0xFF4DB088.toInt()
    const val SKY_BLUE   = 0xFF5DBBE8.toInt()
    const val SKY_BLUE_DK= 0xFF3B9BC8.toInt()
    const val LAVENDER   = 0xFFC8A8E9.toInt()
    const val LAVENDER_DK= 0xFFA888C8.toInt()
    const val PINK       = 0xFFFF9EC4.toInt()
    const val PINK_DK    = 0xFFE07AA8.toInt()

    // Text / outline
    const val NAVY       = 0xFF2D3047.toInt()
    const val NAVY_SOFT  = 0xFF454966.toInt()
    const val SHADOW     = 0x55000000

    // Sizes (logical; we scale by width / 1080f at runtime)
    const val TITLE_PX     = 100f
    const val HEADER_PX    = 64f
    const val BODY_PX      = 38f
    const val SMALL_PX     = 30f
    const val BUTTON_PX    = 46f
    const val BIG_NUM_PX   = 72f
    const val STAT_LABEL_PX= 32f
    const val STAT_VAL_PX  = 38f

    const val OUT_TEXT     = 7f
    const val OUT_UI       = 6f
    const val SHADOW_OFF   = 8f
    const val PANEL_R      = 36f
    const val BUTTON_R     = 32f
}

// Reusable paints
class StyleKit {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        color = Style.NAVY
    }
    val textOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Style.NAVY
    }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.SHADOW }
    var s = 1f   // scale factor

    fun setScale(viewWidth: Int) { s = viewWidth / 1080f }

    fun sz(v: Float) = v * s

    // Chunky text with outline. align=center recommended for game UI
    fun chunkyText(c: Canvas, t: String, x: Float, y: Float, sizePx: Float,
                   color: Int = Style.NAVY, outlineColor: Int = Style.NAVY,
                   outline: Float = Style.OUT_TEXT, align: Paint.Align = Paint.Align.LEFT) {
        // Only draw outline if outlineColor != color, otherwise just fill text bold
        val drawOutline = outlineColor != color
        val tsize = sizePx * s
        text.textSize = tsize
        text.textAlign = align
        textOut.textSize = tsize
        textOut.textAlign = align
        if (drawOutline) {
            textOut.color = outlineColor
            textOut.strokeWidth = outline * s
            c.drawText(t, x, y, textOut)
        }
        text.color = color
        c.drawText(t, x, y, text)
    }

    // Rounded chunky panel with drop shadow + outline
    fun panel(c: Canvas, r: RectF, fillColor: Int, outlineColor: Int = Style.NAVY,
              outline: Float = Style.OUT_UI, corner: Float = Style.PANEL_R,
              shadowOffset: Float = Style.SHADOW_OFF, shadow: Boolean = true) {
        val o = outline * s
        val co = corner * s
        val so = shadowOffset * s
        if (shadow) {
            this.shadow.color = Style.SHADOW
            c.drawRoundRect(r.left + so, r.top + so + 2f, r.right + so, r.bottom + so + 4f, co, co, this.shadow)
        }
        fill.color = fillColor
        c.drawRoundRect(r, co, co, fill)
        if (o > 0) {
            stroke.color = outlineColor
            stroke.strokeWidth = o
            c.drawRoundRect(r, co, co, stroke)
        }
    }

    // Pressable button: panel + centered icon + label
    fun button(c: Canvas, r: RectF, label: String, fillColor: Int,
               icon: String? = null, sizePx: Float = Style.BUTTON_PX,
               pressed: Boolean = false) {
        val pr = if (pressed) RectF(r.left, r.top + Style.SHADOW_OFF * s, r.right, r.bottom + Style.SHADOW_OFF * s) else r
        panel(c, pr, fillColor, Style.NAVY, Style.OUT_UI, Style.BUTTON_R,
              if (pressed) 2f else Style.SHADOW_OFF)

        val txtY = pr.centerY() + sz(sizePx) * 0.35f
        if (icon != null) {
            val iconSize = sizePx * 1.3f
            text.textSize = sz(iconSize)
            text.textAlign = Paint.Align.LEFT
            // measure
            val labelSize = sz(sizePx)
            text.textSize = labelSize
            val labW = text.measureText(label)
            text.textSize = sz(iconSize)
            val iconW = text.measureText(icon)
            val totalW = iconW + sz(16f) + labW
            val startX = pr.centerX() - totalW / 2f
            // icon (no outline — emojis don't outline well)
            text.color = Style.NAVY
            c.drawText(icon, startX, pr.centerY() + sz(iconSize) * 0.36f, text)
            // label with outline
            chunkyText(c, label, startX + iconW + sz(16f), txtY, sizePx,
                       Style.NAVY, Style.PANEL, Style.OUT_TEXT, Paint.Align.LEFT)
        } else {
            chunkyText(c, label, pr.centerX(), txtY, sizePx,
                       Style.NAVY, Style.PANEL, Style.OUT_TEXT, Paint.Align.CENTER)
        }
    }

    // Capsule pill (used for tags / small chips)
    fun pill(c: Canvas, r: RectF, label: String, fillColor: Int) {
        panel(c, r, fillColor, Style.NAVY, 4f, r.height() / 2f, 4f, true)
        chunkyText(c, label, r.centerX(), r.centerY() + sz(Style.SMALL_PX) * 0.36f,
                   Style.SMALL_PX, Style.NAVY, Style.NAVY, 4f, Paint.Align.CENTER)
    }

    // Chunky horizontal stat bar
    fun statBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: Float,
                fillColor: Int, label: String) {
        val o = 4f * s
        // shadow
        shadow.color = Style.SHADOW
        c.drawRoundRect(x + 4f * s, y + 4f * s + 2f, x + w + 4f * s, y + h + 4f * s, h/2f, h/2f, shadow)
        // bg
        fill.color = Style.CREAM_DARK
        c.drawRoundRect(x, y, x + w, y + h, h/2f, h/2f, fill)
        // fill
        val v = value.coerceIn(0f, 100f) / 100f
        if (v > 0) {
            fill.color = fillColor
            c.drawRoundRect(x, y, x + w * v, y + h, h/2f, h/2f, fill)
        }
        // outline
        stroke.color = Style.NAVY
        stroke.strokeWidth = o
        c.drawRoundRect(x, y, x + w, y + h, h/2f, h/2f, stroke)
        // label (outside left)
        chunkyText(c, label, x - sz(12f), y + h * 0.7f, Style.STAT_LABEL_PX,
                   Style.NAVY, Style.NAVY, 3f, Paint.Align.RIGHT)
        // value (centered)
        chunkyText(c, value.toInt().toString(), x + w / 2f, y + h * 0.72f,
                   Style.STAT_VAL_PX, Style.PANEL, Style.NAVY, 4f, Paint.Align.CENTER)
    }

    // Speech bubble
    fun speech(c: Canvas, cx: Float, bottomY: Float, t: String, maxW: Float) {
        text.textSize = sz(Style.BODY_PX)
        textOut.textSize = sz(Style.BODY_PX)
        val padding = sz(28f)
        val tw = (text.measureText(t) + padding * 2).coerceAtMost(maxW)
        val th = sz(Style.BODY_PX) * 1.7f
        val r = RectF(cx - tw / 2f, bottomY - th, cx + tw / 2f, bottomY)
        panel(c, r, Style.PANEL, Style.NAVY, Style.OUT_UI, sz(32f), 6f)
        // tail
        val p = Path()
        p.moveTo(cx - sz(14f), r.bottom)
        p.lineTo(cx, r.bottom + sz(28f))
        p.lineTo(cx + sz(14f), r.bottom)
        p.close()
        fill.color = Style.PANEL
        c.drawPath(p, fill)
        stroke.color = Style.NAVY
        stroke.strokeWidth = sz(Style.OUT_UI)
        c.drawPath(p, stroke)
        chunkyText(c, t, cx, r.centerY() + sz(Style.BODY_PX) * 0.36f,
                   Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
    }

    // Background gradient sky → cream → peach
    fun skyBg(c: Canvas, w: Float, h: Float) {
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Style.SKY_TOP, Style.SKY_MID, Style.SKY_BOT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
    }

    fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = Style.SUN
        c.drawCircle(cx, cy, r, fill)
        stroke.color = Style.NAVY
        stroke.strokeWidth = sz(Style.OUT_UI)
        c.drawCircle(cx, cy, r, stroke)
        // rays
        for (i in 0 until 8) {
            val a = i * Math.PI.toFloat() / 4f
            val x1 = cx + Math.cos(a.toDouble()).toFloat() * (r + sz(10f))
            val y1 = cy + Math.sin(a.toDouble()).toFloat() * (r + sz(10f))
            val x2 = cx + Math.cos(a.toDouble()).toFloat() * (r + sz(40f))
            val y2 = cy + Math.sin(a.toDouble()).toFloat() * (r + sz(40f))
            stroke.color = Style.NAVY
            stroke.strokeWidth = sz(8f)
            c.drawLine(x1, y1, x2, y2, stroke)
            stroke.color = Style.SUN
            stroke.strokeWidth = sz(5f)
            c.drawLine(x1, y1, x2, y2, stroke)
        }
    }

    fun cloud(c: Canvas, cx: Float, cy: Float, sz_: Float) {
        fill.color = Style.PANEL
        c.drawCircle(cx - sz_ * 0.6f, cy + sz_ * 0.2f, sz_ * 0.5f, fill)
        c.drawCircle(cx, cy, sz_ * 0.7f, fill)
        c.drawCircle(cx + sz_ * 0.6f, cy + sz_ * 0.2f, sz_ * 0.5f, fill)
        c.drawCircle(cx + sz_ * 0.2f, cy - sz_ * 0.2f, sz_ * 0.45f, fill)
        c.drawCircle(cx - sz_ * 0.3f, cy - sz_ * 0.15f, sz_ * 0.4f, fill)
        stroke.color = Style.NAVY
        stroke.strokeWidth = sz(Style.OUT_UI)
        // simple cloud outline via path
        val p = Path()
        p.addCircle(cx - sz_ * 0.6f, cy + sz_ * 0.2f, sz_ * 0.5f, Path.Direction.CW)
        p.addCircle(cx, cy, sz_ * 0.7f, Path.Direction.CW)
        p.addCircle(cx + sz_ * 0.6f, cy + sz_ * 0.2f, sz_ * 0.5f, Path.Direction.CW)
        p.addCircle(cx + sz_ * 0.2f, cy - sz_ * 0.2f, sz_ * 0.45f, Path.Direction.CW)
        p.addCircle(cx - sz_ * 0.3f, cy - sz_ * 0.15f, sz_ * 0.4f, Path.Direction.CW)
        c.drawPath(p, stroke)
    }

    fun floor(c: Canvas, w: Float, floorY: Float, h: Float) {
        // floor as a chunky horizontal panel with slight angle
        fill.shader = LinearGradient(0f, floorY, 0f, h,
            intArrayOf(Style.FLOOR_A, Style.FLOOR_B), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, floorY, w, h, fill)
        fill.shader = null
        // top stroke of floor
        stroke.color = Style.NAVY
        stroke.strokeWidth = sz(Style.OUT_UI)
        c.drawLine(0f, floorY, w, floorY, stroke)
        // rug
        val rugW = w * 0.7f
        val rugH = sz(60f)
        val rugX = w / 2f - rugW / 2f
        val rugY = floorY + sz(80f)
        fill.color = Style.RUG
        c.drawOval(rugX, rugY, rugX + rugW, rugY + rugH, fill)
        stroke.color = Style.NAVY
        stroke.strokeWidth = sz(Style.OUT_UI)
        c.drawOval(rugX, rugY, rugX + rugW, rugY + rugH, stroke)
    }
}
