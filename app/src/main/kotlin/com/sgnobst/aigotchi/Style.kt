package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

// v4.0: 8-bit pixel-RPG palette. 16-color NES-ish.
object Style {
    // Background / sky / depth
    const val BG_NIGHT    = 0xFF0B0B2A.toInt()
    const val BG_DUSK     = 0xFF1B1340.toInt()
    const val BG_DAWN     = 0xFF3A2A70.toInt()
    const val BG_DAY_TOP  = 0xFF4A6BD8.toInt()
    const val BG_DAY_MID  = 0xFF6B92E8.toInt()
    const val STAR        = 0xFFEDE7B0.toInt()
    const val MOON        = 0xFFF4E8A0.toInt()

    // Ground / tiles
    const val FLOOR_A     = 0xFF3A3050.toInt()
    const val FLOOR_B     = 0xFF2A2240.toInt()
    const val FLOOR_LINE  = 0xFF6A5DA0.toInt()
    const val WALL_DARK   = 0xFF1C1638.toInt()
    const val WALL_LIGHT  = 0xFF332858.toInt()

    // Bright sprite / accent (NES-y)
    const val PX_WHITE    = 0xFFF6F7FB.toInt()
    const val PX_LIGHT    = 0xFFC8D0E8.toInt()
    const val PX_GRAY     = 0xFF606480.toInt()
    const val PX_DARK     = 0xFF1A1830.toInt()
    const val PX_BLACK    = 0xFF000000.toInt()

    const val NEON_RED    = 0xFFF24A4A.toInt()
    const val NEON_RED_DK = 0xFF8B1F1F.toInt()
    const val NEON_PINK   = 0xFFFF6FA8.toInt()
    const val NEON_ORANGE = 0xFFFFA838.toInt()
    const val NEON_YELLOW = 0xFFFFE34A.toInt()
    const val NEON_GREEN  = 0xFF52E064.toInt()
    const val NEON_GRN_DK = 0xFF1F8A2A.toInt()
    const val NEON_CYAN   = 0xFF50C8F4.toInt()
    const val NEON_CYAN_DK= 0xFF1F6FAA.toInt()
    const val NEON_BLUE   = 0xFF4A6FE0.toInt()
    const val NEON_PURPLE = 0xFFB670F0.toInt()
    const val NEON_BROWN  = 0xFFA06A40.toInt()

    // UI accents
    const val UI_PANEL    = 0xFF12102C.toInt()
    const val UI_PANEL_LT = 0xFF1F1C40.toInt()
    const val UI_BORDER   = 0xFFF6F7FB.toInt()  // white pixel border
    const val UI_BORDER_DK= 0xFF5A5A80.toInt()  // bevel

    // Text
    const val TEXT_HI     = 0xFFF6F7FB.toInt()
    const val TEXT_LO     = 0xFF9FA0C8.toInt()
    const val TEXT_GREEN  = 0xFF80FF80.toInt()  // dot-matrix LED feel

    // Logical text sizes (scaled by width/1080)
    const val TITLE_PX     = 110f
    const val HEADER_PX    = 64f
    const val BODY_PX      = 38f
    const val SMALL_PX     = 30f
    const val TINY_PX      = 24f
    const val BUTTON_PX    = 44f
    const val BIG_NUM_PX   = 72f
    const val STAT_LABEL_PX= 28f
    const val STAT_VAL_PX  = 28f

    // Pixel-grid sizing helpers
    const val PIXEL_UNIT_BASE = 10f   // base "logical pixel" before scaling
}

// Reusable paint kit, with pixel-art primitives.
class StyleKit {
    val fill = Paint()  // INTENTIONALLY no anti-alias → crisp pixels
    val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        color = Style.TEXT_HI
    }
    val textOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
        color = Style.PX_BLACK
    }
    var s = 1f   // scale factor
    var pxU = 6f // size of one "logical pixel" cell after scale

    fun setScale(viewWidth: Int) {
        s = viewWidth / 1080f
        pxU = (Style.PIXEL_UNIT_BASE * s).coerceAtLeast(2f)
    }

    fun sz(v: Float) = v * s

    // ─── Chunky bold text with pixel-style hard outline ───
    fun pxText(c: Canvas, t: String, x: Float, y: Float, sizePx: Float,
               color: Int = Style.TEXT_HI, outlineColor: Int = Style.PX_BLACK,
               outline: Float = 6f, align: Paint.Align = Paint.Align.LEFT) {
        val tsize = sizePx * s
        text.textSize = tsize
        text.textAlign = align
        if (outline > 0f && outlineColor != color) {
            textOut.textSize = tsize
            textOut.textAlign = align
            textOut.color = outlineColor
            textOut.strokeWidth = outline * s
            c.drawText(t, x, y, textOut)
        }
        text.color = color
        c.drawText(t, x, y, text)
    }

    // ─── Pixel panel: hard 2px border + bevel ───
    fun pxPanel(c: Canvas, r: RectF, fillColor: Int = Style.UI_PANEL,
                borderColor: Int = Style.UI_BORDER,
                bevelDark: Int = Style.UI_BORDER_DK,
                drawShadow: Boolean = true) {
        if (drawShadow) {
            fill.color = 0x80000000.toInt()
            c.drawRect(r.left + pxU, r.top + pxU * 1.6f, r.right + pxU, r.bottom + pxU * 1.6f, fill)
        }
        // fill
        fill.color = fillColor
        c.drawRect(r, fill)
        // outer hard border (2px thick)
        val o = pxU * 0.6f
        fill.color = borderColor
        c.drawRect(r.left, r.top, r.right, r.top + o, fill)            // top
        c.drawRect(r.left, r.bottom - o, r.right, r.bottom, fill)      // bottom
        c.drawRect(r.left, r.top, r.left + o, r.bottom, fill)          // left
        c.drawRect(r.right - o, r.top, r.right, r.bottom, fill)        // right
        // inner bevel: 1 pixel inside, dark on bottom-right
        val bi = o
        fill.color = bevelDark
        c.drawRect(r.left + bi, r.bottom - 2 * o, r.right - bi, r.bottom - o, fill)
        c.drawRect(r.right - 2 * o, r.top + bi, r.right - o, r.bottom - bi, fill)
        // corner notches: dark pixel at all 4 corners (chunky NES feel)
        fill.color = bevelDark
        c.drawRect(r.left, r.top, r.left + o, r.top + o, fill)
        c.drawRect(r.right - o, r.top, r.right, r.top + o, fill)
        c.drawRect(r.left, r.bottom - o, r.left + o, r.bottom, fill)
        c.drawRect(r.right - o, r.bottom - o, r.right, r.bottom, fill)
    }

    // ─── Pixel button: pressed state pushes down + dims ───
    fun pxButton(c: Canvas, r: RectF, label: String,
                 fillColor: Int = Style.NEON_RED,
                 icon: String? = null,
                 sizePx: Float = Style.BUTTON_PX,
                 pressed: Boolean = false) {
        val o = pxU * 0.6f
        val pr = if (pressed) RectF(r.left, r.top + o * 1.5f, r.right, r.bottom + o * 1.5f) else r
        // shadow
        if (!pressed) {
            fill.color = 0x90000000.toInt()
            c.drawRect(pr.left + pxU, pr.top + pxU * 1.4f, pr.right + pxU, pr.bottom + pxU * 1.4f, fill)
        }
        // fill
        fill.color = fillColor
        c.drawRect(pr, fill)
        // top highlight strip
        fill.color = blend(fillColor, Style.PX_WHITE, 0.5f)
        c.drawRect(pr.left + o, pr.top + o, pr.right - o, pr.top + 2 * o, fill)
        // bottom shadow strip
        fill.color = blend(fillColor, Style.PX_BLACK, 0.45f)
        c.drawRect(pr.left + o, pr.bottom - 2 * o, pr.right - o, pr.bottom - o, fill)
        // hard border
        fill.color = Style.PX_BLACK
        c.drawRect(pr.left, pr.top, pr.right, pr.top + o, fill)
        c.drawRect(pr.left, pr.bottom - o, pr.right, pr.bottom, fill)
        c.drawRect(pr.left, pr.top, pr.left + o, pr.bottom, fill)
        c.drawRect(pr.right - o, pr.top, pr.right, pr.bottom, fill)
        // text
        val txtY = pr.centerY() + sz(sizePx) * 0.35f
        if (icon != null) {
            text.textSize = sz(sizePx * 1.15f)
            text.textAlign = Paint.Align.LEFT
            text.color = Style.PX_BLACK
            val iconW = text.measureText(icon)
            text.textSize = sz(sizePx)
            val labW = text.measureText(label)
            val totalW = iconW + sz(16f) + labW
            val startX = pr.centerX() - totalW / 2f
            text.textSize = sz(sizePx * 1.15f)
            text.color = Style.PX_BLACK
            c.drawText(icon, startX, pr.centerY() + sz(sizePx) * 0.38f, text)
            pxText(c, label, startX + iconW + sz(16f), txtY, sizePx,
                   Style.PX_WHITE, Style.PX_BLACK, 6f, Paint.Align.LEFT)
        } else {
            pxText(c, label, pr.centerX(), txtY, sizePx,
                   Style.PX_WHITE, Style.PX_BLACK, 6f, Paint.Align.CENTER)
        }
    }

    // ─── Pixel pill/tag chip ───
    fun pxChip(c: Canvas, r: RectF, label: String, fillColor: Int = Style.NEON_PURPLE) {
        val o = pxU * 0.5f
        fill.color = Style.PX_BLACK
        c.drawRect(r.left - o, r.top - o, r.right + o, r.bottom + o, fill)
        fill.color = fillColor
        c.drawRect(r, fill)
        fill.color = blend(fillColor, Style.PX_WHITE, 0.4f)
        c.drawRect(r.left, r.top, r.right, r.top + o, fill)
        fill.color = blend(fillColor, Style.PX_BLACK, 0.4f)
        c.drawRect(r.left, r.bottom - o, r.right, r.bottom, fill)
        pxText(c, label, r.centerX(), r.centerY() + sz(Style.SMALL_PX) * 0.36f,
               Style.SMALL_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
    }

    // ─── Segmented pixel stat bar (10 blocks) ───
    fun pxStatBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, value: Float,
                  fillColor: Int, label: String) {
        val o = pxU * 0.5f
        // label
        pxText(c, label, x - sz(12f), y + h * 0.72f, Style.STAT_LABEL_PX,
               Style.TEXT_HI, Style.PX_BLACK, 4f, Paint.Align.RIGHT)
        // outer black border
        fill.color = Style.PX_BLACK
        c.drawRect(x - o, y - o, x + w + o, y + h + o, fill)
        // bg
        fill.color = Style.UI_PANEL_LT
        c.drawRect(x, y, x + w, y + h, fill)
        // 10 segments
        val v = (value / 10f).coerceIn(0f, 10f)
        val segW = w / 10f
        val gap = pxU * 0.3f
        for (i in 0 until 10) {
            if (i >= v.toInt() && i + 1 > v) break
            val filled = (i + 1) <= v
            val segX = x + i * segW + gap
            val segR = x + (i + 1) * segW - gap
            if (filled) {
                fill.color = fillColor
                c.drawRect(segX, y + gap, segR, y + h - gap, fill)
                fill.color = blend(fillColor, Style.PX_WHITE, 0.45f)
                c.drawRect(segX, y + gap, segR, y + gap + pxU * 0.4f, fill)
            }
        }
    }

    // ─── Generic pixel sprite drawer: char-grid bitmap ───
    // grid is array of equal-length strings; palette maps char → color (0 = transparent skip)
    fun pxSprite(c: Canvas, x: Float, y: Float, grid: Array<String>,
                 palette: Map<Char, Int>, px: Float) {
        for ((row, line) in grid.withIndex()) {
            for ((col, ch) in line.withIndex()) {
                if (ch == '.' || ch == ' ') continue
                val color = palette[ch] ?: continue
                fill.color = color
                c.drawRect(x + col * px, y + row * px,
                           x + col * px + px + 0.5f, y + row * px + px + 0.5f, fill)
            }
        }
    }

    // ─── Speech bubble (pixel style) ───
    fun pxSpeech(c: Canvas, cx: Float, bottomY: Float, t: String, maxW: Float) {
        text.textSize = sz(Style.BODY_PX)
        val padding = sz(24f)
        val tw = (text.measureText(t) + padding * 2).coerceAtMost(maxW)
        val th = sz(Style.BODY_PX) * 1.7f
        val r = RectF(cx - tw / 2f, bottomY - th, cx + tw / 2f, bottomY)
        pxPanel(c, r, Style.PX_WHITE, Style.PX_BLACK, Style.PX_GRAY, true)
        // tail (pixel-stepped triangle)
        val o = pxU
        fill.color = Style.PX_WHITE
        c.drawRect(cx - o * 1.5f, r.bottom, cx + o * 1.5f, r.bottom + o, fill)
        c.drawRect(cx - o, r.bottom + o, cx + o, r.bottom + o * 2, fill)
        c.drawRect(cx - o * 0.5f, r.bottom + o * 2, cx + o * 0.5f, r.bottom + o * 3, fill)
        // tail border
        fill.color = Style.PX_BLACK
        c.drawRect(cx - o * 1.5f - o * 0.5f, r.bottom, cx - o * 1.5f, r.bottom + o * 0.6f, fill)
        c.drawRect(cx + o * 1.5f, r.bottom, cx + o * 1.5f + o * 0.5f, r.bottom + o * 0.6f, fill)
        c.drawRect(cx - o, r.bottom + o, cx - o * 0.5f, r.bottom + o * 1.6f, fill)
        c.drawRect(cx + o * 0.5f, r.bottom + o, cx + o, r.bottom + o * 1.6f, fill)
        // text
        pxText(c, t, cx, r.centerY() + sz(Style.BODY_PX) * 0.36f,
               Style.BODY_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
    }

    // ─── Pixelated checkerboard floor ───
    fun pxFloor(c: Canvas, w: Float, floorY: Float, h: Float,
                colorA: Int = Style.FLOOR_A, colorB: Int = Style.FLOOR_B,
                lineColor: Int = Style.FLOOR_LINE) {
        val tile = pxU * 6  // 6 logical pixel cells per tile
        val rows = ((h - floorY) / tile).toInt() + 2
        val cols = (w / tile).toInt() + 2
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val even = (row + col) % 2 == 0
                fill.color = if (even) colorA else colorB
                c.drawRect(col * tile, floorY + row * tile,
                           col * tile + tile + 0.5f, floorY + row * tile + tile + 0.5f, fill)
            }
        }
        // floor top stroke
        fill.color = lineColor
        c.drawRect(0f, floorY, w, floorY + pxU * 0.6f, fill)
        fill.color = Style.PX_BLACK
        c.drawRect(0f, floorY - pxU * 0.4f, w, floorY, fill)
    }

    // ─── Background sky/star/parallax ───
    fun pxSky(c: Canvas, w: Float, h: Float, dayProgress: Float, stars: List<FloatArray>) {
        // dayProgress 0→1 over a day. Map to night → dawn → day → dusk
        val phase = dayProgress * 4f  // 0..4
        val topCol: Int; val midCol: Int
        when {
            phase < 1f -> {
                topCol = blend(Style.BG_NIGHT, Style.BG_DUSK, phase)
                midCol = blend(Style.BG_DUSK, Style.BG_DAWN, phase)
            }
            phase < 2f -> {
                val t = phase - 1f
                topCol = blend(Style.BG_DUSK, Style.BG_DAWN, t)
                midCol = blend(Style.BG_DAWN, Style.BG_DAY_TOP, t)
            }
            phase < 3f -> {
                val t = phase - 2f
                topCol = blend(Style.BG_DAWN, Style.BG_DAY_TOP, t)
                midCol = blend(Style.BG_DAY_TOP, Style.BG_DAY_MID, t)
            }
            else -> {
                val t = phase - 3f
                topCol = blend(Style.BG_DAY_TOP, Style.BG_NIGHT, t)
                midCol = blend(Style.BG_DAY_MID, Style.BG_DUSK, t)
            }
        }
        // sky: top half topCol, bottom half midCol (banded for retro feel)
        val bandH = h / 12f
        for (i in 0 until 12) {
            val t = i / 11f
            fill.color = blend(topCol, midCol, t)
            c.drawRect(0f, i * bandH, w, (i + 1) * bandH + 0.5f, fill)
        }
        // stars (only visible at night/dusk)
        val starAlpha = when {
            phase < 1f -> 1f - phase
            phase < 2f -> 0f
            phase < 3f -> 0f
            else -> phase - 3f
        }
        if (starAlpha > 0.05f) {
            fill.color = Style.STAR
            fill.alpha = (255 * starAlpha).toInt().coerceIn(0, 255)
            for (s in stars) {
                val sz = s[2]
                c.drawRect(s[0], s[1], s[0] + sz, s[1] + sz, fill)
            }
            fill.alpha = 255
        }
        // moon/sun
        val isDay = phase in 1.5f..3.5f
        val sunPhase = ((dayProgress + 0.5f) % 1f)  // arc across sky
        val sx = w * (sunPhase)
        val sy = h * 0.18f + (Math.sin(sunPhase * Math.PI).toFloat() - 1f) * h * 0.10f
        if (isDay) {
            // pixel sun
            val r = sz(50f)
            fill.color = Style.NEON_YELLOW
            c.drawRect(sx - r, sy - r, sx + r, sy + r, fill)
            fill.color = Style.NEON_ORANGE
            c.drawRect(sx - r * 0.5f, sy + r, sx + r * 0.5f, sy + r * 1.2f, fill)
            c.drawRect(sx - r, sy - r * 0.5f, sx - r * 1.2f, sy + r * 0.5f, fill)
            c.drawRect(sx + r, sy - r * 0.5f, sx + r * 1.2f, sy + r * 0.5f, fill)
            c.drawRect(sx - r * 0.5f, sy - r * 1.2f, sx + r * 0.5f, sy - r, fill)
            fill.color = Style.PX_BLACK
            c.drawRect(sx - r - pxU * 0.4f, sy - r, sx - r, sy + r, fill)
            c.drawRect(sx + r, sy - r, sx + r + pxU * 0.4f, sy + r, fill)
            c.drawRect(sx - r, sy - r - pxU * 0.4f, sx + r, sy - r, fill)
            c.drawRect(sx - r, sy + r, sx + r, sy + r + pxU * 0.4f, fill)
        } else {
            val r = sz(46f)
            fill.color = Style.MOON
            c.drawRect(sx - r, sy - r, sx + r, sy + r, fill)
            // crescent shadow
            fill.color = blend(Style.MOON, Style.PX_BLACK, 0.6f)
            c.drawRect(sx + r * 0.2f, sy - r * 0.8f, sx + r * 0.7f, sy + r * 0.6f, fill)
            fill.color = Style.PX_BLACK
            c.drawRect(sx - r - pxU * 0.4f, sy - r, sx - r, sy + r, fill)
            c.drawRect(sx + r, sy - r, sx + r + pxU * 0.4f, sy + r, fill)
            c.drawRect(sx - r, sy - r - pxU * 0.4f, sx + r, sy - r, fill)
            c.drawRect(sx - r, sy + r, sx + r, sy + r + pxU * 0.4f, fill)
        }
    }

    // mountain silhouette band (parallax)
    fun pxMountains(c: Canvas, w: Float, baseY: Float, color: Int, peak: Float, seed: Float) {
        val step = pxU * 4
        val pts = (w / step).toInt() + 2
        val p = Path()
        p.moveTo(0f, baseY)
        for (i in 0 until pts) {
            val x = i * step
            val h = (Math.sin((x * 0.013f + seed).toDouble()) * peak * 0.4f +
                     Math.cos((x * 0.027f + seed * 1.7f).toDouble()) * peak * 0.3f).toFloat() + peak * 0.5f
            p.lineTo(x, baseY - h)
        }
        p.lineTo(w, baseY)
        p.lineTo(w, baseY + sz(40f))
        p.lineTo(0f, baseY + sz(40f))
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            val ti = t.coerceIn(0f, 1f)
            val ai = ((a ushr 24) and 0xFF); val ar = ((a ushr 16) and 0xFF); val ag = ((a ushr 8) and 0xFF); val ab = a and 0xFF
            val bi = ((b ushr 24) and 0xFF); val br = ((b ushr 16) and 0xFF); val bg = ((b ushr 8) and 0xFF); val bb = b and 0xFF
            val nA = (ai + (bi - ai) * ti).toInt()
            val nR = (ar + (br - ar) * ti).toInt()
            val nG = (ag + (bg - ag) * ti).toInt()
            val nB = (ab + (bb - ab) * ti).toInt()
            return (nA shl 24) or (nR shl 16) or (nG shl 8) or nB
        }
    }
}

// Expose blend at top level too for convenience
fun blend(a: Int, b: Int, t: Float): Int = StyleKit.blend(a, b, t)
