package com.sgnobst.aigotchi

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.random.Random

// Square pixel particle (no anti-alias, no gravity falloff to keep retro look)
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
        vy += 220f * dt
        // step velocity for chunky motion
        vx *= 0.985f
        life -= dt
    }
    fun draw(canvas: Canvas, paint: Paint) {
        val a = (life / maxLife).coerceIn(0f, 1f)
        // snap to integer px for crisp look
        val ix = x.toInt().toFloat()
        val iy = y.toInt().toFloat()
        val s = (size * (0.4f + a * 0.8f)).toInt().coerceAtLeast(2).toFloat()
        paint.color = color
        paint.alpha = (a * 255).toInt()
        canvas.drawRect(ix, iy, ix + s, iy + s, paint)
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

class Shake(var amount: Float = 0f) {
    fun bump(a: Float) { if (a > amount) amount = a }
    fun decay(dt: Float) { amount *= (1f - 8f * dt).coerceAtLeast(0f) }
}

// ───── Pixel sprite data (char-grids) ─────
// Common palette chars used across sprites:
//   '.' = transparent
//   'K' = pure black outline
//   'W' = white
//   'C' = body color (varies)
//   'D' = body shadow color
//   'L' = body light highlight
//   'E' = eye dark
//   'M' = mouth/teeth
//   'R' = cheek/red
//   'Y' = yellow accent
//   'P' = secondary panel (gray)
object PixelArt {
    // 16x16 AI sprite — stage 1 (basic blob bot)
    val AI_STAGE1 = arrayOf(
        "................",
        ".......K........",
        "......KYK.......",
        "......KKK.......",
        "....KKKKKKKK....",
        "...KCLLLLLCCK...",
        "...KLCKCCKCLK...",
        "..KLCWEWWEWCLK..",
        "..KLCWEWWEWCLK..",
        "..KLCCCMMCCCCK..",
        "..KLCCCCCCCCCK..",
        "..KLCRCCCCRCCK..",
        "...KCCDDDDCCK...",
        "....KKKKKKKK....",
        "......K..K......",
        "......K..K......"
    )
    // 16x18 — stage 3, slightly taller with glasses
    val AI_STAGE3 = arrayOf(
        "................",
        ".......K........",
        "......KYK.......",
        "......KKK.......",
        "....KKKKKKKK....",
        "...KCLLLLLCCK...",
        "..KKLCCCCCCLKK..",
        "..KLKEEKKEEKLK..",
        "..KLCWEKKEWCLK..",
        "..KLCCKKKKCCCK..",
        "..KLCCCMMCCCCK..",
        "..KLCCCCCCCCCK..",
        "..KLCRCCCCRCCK..",
        "...KCCDDDDCCK...",
        "....KKKKKKKK....",
        "......K..K......",
        "......K..K......",
        ".....KKK.KKK...."
    )
    // 18x18 — stage 5, glasses + tie
    val AI_STAGE5 = arrayOf(
        "..................",
        "........K.........",
        ".......KYK........",
        ".......KKK........",
        ".....KKKKKKKK.....",
        "....KCLLLLLLCCK...",
        "...KCLKKLLKKCLK...",
        "..KKLCWEKKEWCLKK..",
        "..KLCCWEKKEWCCLK..",
        "..KLCKKKKKKKKCLK..",
        "..KLCCCCMMCCCCCK..",
        "..KLCCCCCCCCCCCK..",
        "..KLCRRCCCCCRRCK..",
        "..KLCCRRCCCCRCCK..",
        "...KCCDDDDDDCCK...",
        "....KKKKKKKKKK....",
        "......K..K..K.....",
        "......K..K..K....."
    )
    // 20x20 — stage 7, agentic, antennae + larger
    val AI_STAGE7 = arrayOf(
        "....................",
        ".........K..........",
        "........KYK.........",
        ".......KKKKK........",
        ".....KKKKKKKKK......",
        "....KCLLLLLLLCCK....",
        "...KCLKKLLLLKKCLK...",
        "..KKLCWEKKKKEWCLKK..",
        "..KLCCWEKKKKEWCCLK..",
        "..KLCKKKKKKKKKKCLK..",
        "..KLCCCCCMMCCCCCCK..",
        "..KLCCCCCCCCCCCCCK..",
        "..KLCRRCCCYYCCRRCK..",
        "..KLCCRRCCYYCCRCCK..",
        "...KCCDDDDDDDDCCK...",
        "....KKKKKKKKKKK.....",
        ".....KKKK..KKKK.....",
        ".....KKK....KKK.....",
        ".....KK......KK.....",
        ".....KK......KK....."
    )
    // 22x22 — stage 9+, halo, divine
    val AI_STAGE9 = arrayOf(
        "......................",
        ".....YYYYYYYYYY.......",
        "....Y...........Y.....",
        ".....YYYYYYYYYY.......",
        "..........K...........",
        ".........KYK..........",
        "........KKKKK.........",
        ".......KKKKKKK........",
        ".....KKKKKKKKKKK......",
        "....KCLLLLLLLLLCCK....",
        "...KCLKKKKLLLKKKCLK...",
        "..KKLCWEEKKKEEWCLKK..",
        "..KLCCWEEKKKEEWCCLK..",
        "..KLCKKKKKKKKKKKCLK..",
        "..KLCCCCCCMMMCCCCCK..",
        "..KLCCCCCCCCCCCCCCCK.",
        "..KLCRRCCCCYYYCCRRCK.",
        "..KLCCRRCCCYYYCCRCCK.",
        "...KCCDDDDDDDDDDDCCK.",
        "....KKKKKKKKKKKKK....",
        ".....KKKK..KKKK......",
        ".....KK......KK......"
    )

    // 16x12 pixel cat (purple/dark)
    val CAT_SPRITE = arrayOf(
        "................",
        "..K..........K..",
        ".KCK........KCK.",
        ".KCCK......KCCK.",
        ".KCCCKKKKKKCCCK.",
        ".KCCWWWWWWWWCCK.",
        ".KCWGCWWWWCGWCK.",
        ".KCCCCCKKCCCCCK.",
        ".KCCCCCCCCCCCCK.",
        "..KCCCCCCCCCCK..",
        "...KKKKKKKKKK...",
        "....K..K..K.K..."
    )

    // 8x8 coin
    val COIN_SPRITE = arrayOf(
        "..KKKK..",
        ".KYYYYK.",
        "KYWYYYYK",
        "KYYKYYYK",
        "KYYKYYYK",
        "KYYKYYYK",
        ".KYYYYK.",
        "..KKKK.."
    )

    // 10x10 glitch triangle
    val GLITCH_SPRITE = arrayOf(
        "....KK....",
        "...KRRK...",
        "...KRRK...",
        "..KRRRRK..",
        "..KRRRRK..",
        ".KRRWWRRK.",
        ".KRRKKRRK.",
        "KRRRRRRRRK",
        "KKKKKKKKKK"
    )

    // 24x16 building (server) — used as decor
    val SERVER_RACK = arrayOf(
        "........................",
        ".KKKKKKKKKKKKKKKKKKKKKK.",
        ".KCCCCCCCCCCCCCCCCCCCCK.",
        ".KCKGKGKGKGKGKGKGKGKCK.K",
        ".KCKGKGKGKGKGKGKGKGKCK..",
        ".KCKGKGKGKGKGKGKGKGKCK.K",
        ".KCCCCCCCCCCCCCCCCCCCCK.",
        ".KCKGKGKGKGKGKGKGKGKCK.K",
        ".KCKGKGKGKGKGKGKGKGKCK..",
        ".KCKGKGKGKGKGKGKGKGKCK.K",
        ".KCCCCCCCCCCCCCCCCCCCCK.",
        ".KCKKKGKKKGKKKGKKKGKCK..",
        ".KCKKKGKKKGKKKGKKKGKCK..",
        ".KCCCCCCCCCCCCCCCCCCCCK.",
        ".KKKKKKKKKKKKKKKKKKKKKK.",
        "........................"
    )

    // 12x14 desk lamp
    val LAMP = arrayOf(
        "............",
        "...KKKKKKK..",
        "..KYYYYYYK..",
        "...KKKKKK...",
        ".....KK.....",
        ".....KK.....",
        "....KKK.....",
        "...KK.K.....",
        "...K..K.....",
        "...KKKK.....",
        "...KKKK.....",
        "..KKKKKK....",
        "..KKKKKK....",
        "............"
    )

    // 16x10 a tiny pixel cloud
    val CLOUD = arrayOf(
        "....KKKKKK......",
        "...KWWWWWWK.....",
        "..KWWWWWWWWK....",
        ".KWWWWWWWWWWKKK.",
        ".KWWWWWWWWWWWWK.",
        "KWWWWWWWWWWWWWK.",
        "KKKKKKKKKKKKKKK.",
        "................",
        "................",
        "................"
    )

    // ─── Palette helper builders ───
    // Body color varies by stage (matches drawAi color)
    fun aiPalette(bodyC: Int, bodyD: Int, bodyL: Int): Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'W' to Style.PX_WHITE,
        'E' to Style.PX_DARK,
        'C' to bodyC,
        'D' to bodyD,
        'L' to bodyL,
        'M' to Style.NEON_RED_DK,
        'R' to Style.NEON_PINK,
        'Y' to Style.NEON_YELLOW,
        'P' to Style.PX_GRAY
    )

    val CAT_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'C' to Style.PX_DARK,
        'W' to Style.NEON_PURPLE,
        'G' to Style.NEON_GREEN
    )

    val COIN_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'Y' to Style.NEON_YELLOW,
        'W' to Style.PX_WHITE
    )

    val GLITCH_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'R' to Style.NEON_RED,
        'W' to Style.PX_WHITE
    )

    val SERVER_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'C' to Style.PX_GRAY,
        'G' to Style.NEON_GREEN
    )

    val LAMP_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'Y' to Style.NEON_YELLOW
    )

    val CLOUD_PAL: Map<Char, Int> = mapOf(
        'K' to Style.PX_BLACK,
        'W' to Style.PX_WHITE
    )
}

// Falling-star/twinkle background helper
object Stars {
    fun gen(n: Int, w: Int, h: Int, base: Float): MutableList<FloatArray> {
        val out = mutableListOf<FloatArray>()
        repeat(n) {
            out.add(floatArrayOf(
                Random.nextFloat() * w,
                Random.nextFloat() * h * 0.55f,
                base + Random.nextFloat() * base
            ))
        }
        return out
    }
}
