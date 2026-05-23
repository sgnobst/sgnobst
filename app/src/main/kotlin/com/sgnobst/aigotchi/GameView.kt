package com.sgnobst.aigotchi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    enum class Screen { INTRO, PLAY, FEED, SHOP, ALBA, NEWS, TRAIN, EVENT, ENDING }

    private val game = GameState()
    private var screen: Screen = Screen.INTRO

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_HI; textSize = 36f; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_LO; textSize = 26f
    }
    private val tiny = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_LO; textSize = 22f
    }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 48f
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val worldHits = mutableListOf<Triple<Float, Float, () -> Unit>>() // x, y, action (radius=hit)

    // Anim state
    private var tSec = 0f
    private var lastMs = System.currentTimeMillis()
    private var aiBob = 0f
    private var aiBlinkT = 0f
    private var blinkActive = false
    private val shake = Shake()
    private var flashA = 0f
    private var flashColor = Color.WHITE
    private val displayStats = FloatArray(8) { 30f }
    private var displayMoney = 100f
    private var dayProgress = 0f
    private val DAY_SECONDS = 45f

    // World objects
    private val coins = mutableListOf<Coin>()
    private val cats = mutableListOf<CatBlob>()
    private val glitches = mutableListOf<Glitch>()
    private val particles = mutableListOf<Particle>()
    private val floats = mutableListOf<FloatText>()
    private var coinTimer = 4f
    private var catTimer = 12f
    private var glitchTimer = 8f
    private var albaTickTimer = 1f
    private var newsScroll = 0f
    private var speechT = 0f
    private var speechText: String = ""
    private val stars = List(60) { Star(Random.nextFloat(), Random.nextFloat()*0.42f, Random.nextFloat()) }
    private val grid = GridFloor()

    private val prefs: SharedPreferences = context.getSharedPreferences("aigotchi", Context.MODE_PRIVATE)

    private val ticker = Handler(Looper.getMainLooper())
    private val frame = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = ((now - lastMs).coerceAtMost(64)) / 1000f
            lastMs = now
            tSec += dt
            update(dt)
            invalidate()
            ticker.postDelayed(this, 16)
        }
    }

    init {
        load()
        for (i in 0..7) displayStats[i] = game.stats[i].toFloat()
        displayMoney = game.money.toFloat()
        ticker.post(frame)
    }

    // ───────────────────────── UPDATE ─────────────────────────

    private fun update(dt: Float) {
        shake.decay(dt)
        if (flashA > 0) flashA = max(0f, flashA - dt * 3f)
        // smoothly approach real stats
        for (i in 0..7) {
            displayStats[i] += (game.stats[i] - displayStats[i]) * (dt * 5f).coerceAtMost(1f)
        }
        displayMoney += (game.money - displayMoney) * (dt * 5f).coerceAtMost(1f)
        // ai bobbing
        aiBob = (sin(tSec * 2.0) * 10.0).toFloat()
        aiBlinkT -= dt
        if (aiBlinkT <= 0) {
            blinkActive = !blinkActive
            aiBlinkT = if (blinkActive) 0.12f else (2.5f + Random.nextFloat() * 3.5f)
        }

        if (screen == Screen.PLAY && !game.ended) {
            // day clock
            dayProgress += dt / DAY_SECONDS
            if (dayProgress >= 1f) {
                dayProgress = 0f
                Logic.nextDay(game)
                onDayAdvanced()
            }

            // alba tick (one tick = 1 day, but we already tick on nextDay; nothing here)

            // coin spawn
            coinTimer -= dt
            if (coinTimer <= 0) {
                spawnCoin()
                val rate = max(1.2f, 4.5f - game.stage * 0.3f - if (game.albaIdx >= 0) 1f else 0f)
                coinTimer = rate + Random.nextFloat() * 1.5f
            }
            // cat spawn
            catTimer -= dt
            if (catTimer <= 0) {
                spawnCat()
                catTimer = 10f + Random.nextFloat() * 14f
            }
            // glitch spawn (proportional to instability)
            glitchTimer -= dt
            if (glitchTimer <= 0) {
                val risk = (100 - game.stats[STAT_STABILITY]) / 100f
                if (Random.nextFloat() < 0.25f + risk * 0.5f) spawnGlitch()
                glitchTimer = 6f + Random.nextFloat() * 6f
            }
            // physics
            for (c in coins) {
                c.y += c.vy * dt
                c.phase += dt
                c.vy += 60f * dt
            }
            coins.removeAll { it.y > height + 60 }
            for (k in cats) {
                k.x += k.vx * dt
                k.bob += dt
                k.life -= dt
            }
            cats.removeAll { it.life <= 0 || it.x < -120 || it.x > width + 120 }
            for (g in glitches) {
                g.x += g.vx * dt
                g.y += g.vy * dt
                g.life -= dt
                if (g.x < 80 || g.x > width - 80) g.vx = -g.vx
                if (g.y < height*0.5f) g.vy = kotlin.math.abs(g.vy)
                if (g.y > height - 220) g.vy = -kotlin.math.abs(g.vy)
            }
            glitches.removeAll { it.life <= 0 }
            // particles & floats
            val pi = particles.iterator()
            while (pi.hasNext()) { val p = pi.next(); p.update(dt); if (p.life <= 0) pi.remove() }
            val fi = floats.iterator()
            while (fi.hasNext()) { val f = fi.next(); f.update(dt); if (f.life <= 0) fi.remove() }
            // speech bubble decay
            if (speechT > 0) speechT -= dt
            // news scroll
            newsScroll -= 80f * dt
        }
    }

    private fun onDayAdvanced() {
        flash(Palette.NEON_CYAN, 0.4f)
        shake.bump(8f)
        // pull pending screens
        if (game.ended) screen = Screen.ENDING
        else if (game.pendingEventIdx >= 0) screen = Screen.EVENT
        else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) {
            // Don't force training, but add hint coin
            speech("새 하루! ${game.day}일차 시작.")
        }
        save()
    }

    private fun spawnCoin() {
        val x = 80f + Random.nextFloat() * (width - 160f)
        val y = -40f
        val vy = 40f + Random.nextFloat() * 60f
        val v = if (game.tools.contains(Content.TOOL_DATACENTER)) 50
                else if (game.tools.contains(Content.TOOL_SERVERROOM)) 20
                else if (game.albaIdx >= 0) 12
                else 5
        coins.add(Coin(x, y, vy, Random.nextFloat() * 6f, v))
    }
    private fun spawnCat() {
        val fromLeft = Random.nextBoolean()
        val y = height * 0.55f + Random.nextFloat() * 80f
        cats.add(CatBlob(if (fromLeft) -100f else width + 100f, y,
            if (fromLeft) (40f + Random.nextFloat()*30f) else -(40f + Random.nextFloat()*30f),
            Random.nextFloat() * 6f))
    }
    private fun spawnGlitch() {
        val x = 100f + Random.nextFloat() * (width - 200f)
        val y = height * 0.55f + Random.nextFloat() * 100f
        glitches.add(Glitch(x, y,
            (Random.nextFloat() - 0.5f) * 200f,
            (Random.nextFloat() - 0.5f) * 200f,
            1 + (game.stage / 3)))
    }

    private fun flash(c: Int, a: Float) { flashA = max(flashA, a); flashColor = c }

    private fun burst(x: Float, y: Float, c: Int, n: Int = 12) {
        repeat(n) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val speed = 120f + Random.nextFloat() * 200f
            particles.add(Particle(x, y, cos(ang) * speed, sin(ang) * speed - 60f,
                0.7f + Random.nextFloat() * 0.5f, 1.2f, c, 5f + Random.nextFloat() * 5f))
        }
    }

    private fun popText(x: Float, y: Float, t: String, c: Int) {
        floats.add(FloatText(x, y, t, c))
    }

    private fun speech(s: String) {
        speechText = s
        speechT = 3.5f
    }

    // ───────────────────────── DRAW ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear(); worldHits.clear()
        canvas.save()
        shake.apply(canvas)
        drawBackdrop(canvas)
        when (screen) {
            Screen.INTRO   -> drawIntro(canvas)
            Screen.PLAY    -> drawPlay(canvas)
            Screen.FEED    -> drawFeed(canvas)
            Screen.SHOP    -> drawShop(canvas)
            Screen.ALBA    -> drawAlba(canvas)
            Screen.NEWS    -> drawNews(canvas)
            Screen.TRAIN   -> drawTrain(canvas)
            Screen.EVENT   -> drawEvent(canvas)
            Screen.ENDING  -> drawEnding(canvas)
        }
        canvas.restore()
        // flash overlay (outside shake)
        if (flashA > 0) {
            paint.color = flashColor
            paint.alpha = (flashA * 200).toInt().coerceAtMost(255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
    }

    private fun drawBackdrop(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // vertical gradient
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Palette.BG_DEEP, Palette.BG_MID, Palette.BG_TOP, Palette.BG_DEEP),
            floatArrayOf(0f, 0.35f, 0.6f, 1f),
            Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // stars (upper portion)
        for (s in stars) {
            val sx = s.x * w
            val sy = s.y * h
            val a = 100 + ((sin((tSec * 2.0 + s.z * 5.0).toDouble()) * 80.0).toFloat() + 80f).toInt()
            paint.color = Palette.TEXT_HI
            paint.alpha = a.coerceIn(40, 255)
            canvas.drawCircle(sx, sy, 1.5f + s.z * 1.5f, paint)
        }
        paint.alpha = 255
        // synthwave grid+sun (always visible on PLAY, faded on others)
        if (screen == Screen.PLAY || screen == Screen.INTRO || screen == Screen.ENDING) {
            grid.draw(canvas, w, h, tSec, paint)
        }
    }

    // ───────────────────────── INTRO ─────────────────────────

    private fun drawIntro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f
        emoji.textAlign = Paint.Align.CENTER
        emoji.textSize = 140f
        drawGlowText(canvas, "🤖", cx, h * 0.22f, emoji, Palette.NEON_CYAN, 12f)

        text.textAlign = Paint.Align.CENTER
        text.color = Palette.TEXT_HI
        text.textSize = 64f
        drawGlowText(canvas, "이상한 AI 키우기", cx, h * 0.35f, text, Palette.NEON_PINK, 18f)

        small.textAlign = Paint.Align.CENTER
        small.color = Palette.NEON_CYAN
        small.textSize = 28f
        canvas.drawText("TAMAGOTCHI × IDLE × NEON DREAM", cx, h * 0.40f, small)

        val lines = arrayOf(
            "🍔 데이터로 AI 성격 만들기",
            "🗣️ 칭찬·혼내기로 말투 다듬기",
            "🛠️ 도구·알바로 진화시키기",
            "💥 사고 수습이 게임의 절반",
            "🐱 고양이는 절대 정복 불가"
        )
        small.color = Palette.TEXT_HI
        small.textSize = 30f
        for ((i, l) in lines.withIndex()) {
            canvas.drawText(l, cx, h * 0.50f + i * 50f, small)
        }

        val r = RectF(cx - 240f, h * 0.80f, cx + 240f, h * 0.80f + 110f)
        drawNeonButton(canvas, r, "▶  GAME START", Palette.NEON_PINK)
        hits.add(r to {
            screen = Screen.PLAY
            if (game.pendingTrainingIdx < 0 && !game.trainingHandled)
                game.pendingTrainingIdx = Random.nextInt(Content.TRAINING_PROMPTS.size)
            speech("안녕하세요! 저를 키워주세요.")
            save()
        })
        text.textAlign = Paint.Align.LEFT
    }

    // ───────────────────────── PLAY (action) ─────────────────────────

    private fun drawPlay(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // top header
        drawTopBar(canvas, w)
        // big AI in stage area (around horizon)
        val aiCx = w / 2f
        val aiCy = h * 0.36f + aiBob
        drawAi(canvas, aiCx, aiCy, 150f)
        // speech bubble
        if (speechT > 0 && speechText.isNotEmpty()) drawSpeechBubble(canvas, aiCx, aiCy - 200f, speechText)
        // tappable AI hit
        worldHits.add(Triple(aiCx, aiCy, ::onTapAi))

        // world objects
        for (c in coins) drawCoin(canvas, c)
        for (k in cats) drawCat(canvas, k)
        for (g in glitches) drawGlitch(canvas, g)
        for (c in coins) worldHits.add(Triple(c.x, c.y, { onTapCoin(c) }))
        for (k in cats) worldHits.add(Triple(k.x, k.y, { onTapCat(k) }))
        for (g in glitches) worldHits.add(Triple(g.x, g.y, { onTapGlitch(g) }))

        // particles & floats on top of world
        for (p in particles) p.draw(canvas, paint)
        for (f in floats) { text.textAlign = Paint.Align.CENTER; text.textSize = 34f; f.draw(canvas, text) }

        // bottom UI: stats grid + action buttons
        drawStatPanel(canvas, w, h)
        drawActionDock(canvas, w, h)
        drawNewsTicker(canvas, w, h)

        // overlays
        if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) drawTrainHint(canvas, w)
        if (game.pendingEventIdx >= 0) drawEventHint(canvas, w)
    }

    private fun drawTopBar(canvas: Canvas, w: Float) {
        // backdrop
        paint.shader = LinearGradient(0f, 0f, 0f, 130f,
            intArrayOf(0xCC0A0420.toInt(), 0x880A0420.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, 130f, paint); paint.shader = null

        // day progress arc on left
        val cx = 70f; val cy = 65f; val r = 42f
        paint.color = Palette.PANEL_DARK
        canvas.drawCircle(cx, cy, r, paint)
        glow.color = Palette.NEON_CYAN
        canvas.drawCircle(cx, cy, r, glow)
        paint.color = Palette.NEON_CYAN
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, dayProgress * 360f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Palette.NEON_CYAN })
        text.color = Palette.TEXT_HI
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30f
        canvas.drawText("D${game.day}", cx, cy + 10f, text)

        // money pill center
        text.textAlign = Paint.Align.CENTER
        text.color = Palette.NEON_GOLD
        text.textSize = 40f
        val moneyStr = "₩ ${displayMoney.toInt()}"
        val mw = text.measureText(moneyStr) + 60f
        val mr = RectF(w/2f - mw/2f, 22f, w/2f + mw/2f, 92f)
        paint.color = Palette.PANEL_DARK
        canvas.drawRoundRect(mr, 36f, 36f, paint)
        glow.color = Palette.NEON_GOLD
        canvas.drawRoundRect(mr, 36f, 36f, glow)
        canvas.drawText(moneyStr, w/2f, 72f, text)

        // stage badge right
        val stageStr = "Lv.${game.stage} ${Content.STAGE_NAMES[game.stage-1]}"
        small.textSize = 26f
        small.color = Palette.NEON_PURPLE
        val sw = small.measureText(stageStr) + 36f
        val sr = RectF(w - sw - 20f, 28f, w - 20f, 86f)
        paint.color = Palette.PANEL_DARK
        canvas.drawRoundRect(sr, 28f, 28f, paint)
        glow.color = Palette.NEON_PURPLE
        canvas.drawRoundRect(sr, 28f, 28f, glow)
        small.textAlign = Paint.Align.CENTER
        small.color = Palette.TEXT_HI
        canvas.drawText(stageStr, sr.centerX(), sr.centerY() + 9f, small)

        // tag chips row under header
        var tx = 30f
        val ty = 105f
        for (t in game.tags) {
            val lab = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
            tiny.textSize = 18f
            val tw = tiny.measureText(lab) + 22f
            val tr = RectF(tx, ty, tx + tw, ty + 30f)
            paint.color = Palette.NEON_PINK
            paint.alpha = 90
            canvas.drawRoundRect(tr, 14f, 14f, paint); paint.alpha = 255
            glow.color = Palette.NEON_PINK
            canvas.drawRoundRect(tr, 14f, 14f, glow)
            tiny.color = Palette.TEXT_HI
            tiny.textAlign = Paint.Align.CENTER
            canvas.drawText(lab, tr.centerX(), tr.centerY() + 7f, tiny)
            tx += tw + 8f
            if (tx > w - 100f) break
        }
        text.textAlign = Paint.Align.LEFT; small.textAlign = Paint.Align.LEFT
    }

    private fun drawAi(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        // aura
        val auraColor = when {
            game.stage >= 9 -> Palette.NEON_GOLD
            game.stage >= 7 -> Palette.NEON_PURPLE
            game.stage >= 5 -> Palette.NEON_CYAN
            game.stage >= 3 -> Palette.NEON_GREEN
            else -> 0xFF6080FF.toInt()
        }
        val pulse = 1f + (sin((tSec * 3.0)).toFloat() * 0.05f)
        paint.shader = RadialGradient(cx, cy, sz * 2.2f * pulse,
            intArrayOf(auraColor and 0x00FFFFFF or 0x66000000, auraColor and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, sz * 2.2f * pulse, paint)
        paint.shader = null
        // shadow on floor
        paint.color = 0x55000000
        canvas.drawOval(cx - sz*0.8f, cy + sz*1.05f, cx + sz*0.8f, cy + sz*1.15f, paint)

        // body
        val bodyColor = when {
            game.stage >= 9 -> 0xFFFFE085.toInt()
            game.stage >= 7 -> 0xFFE0A8FF.toInt()
            game.stage >= 5 -> 0xFF9EE9F0.toInt()
            game.stage >= 3 -> 0xFFB8F0BB.toInt()
            else -> 0xFFD0D8E8.toInt()
        }
        paint.color = bodyColor
        canvas.drawRoundRect(cx - sz, cy - sz*0.9f, cx + sz, cy + sz*0.9f, 44f, 44f, paint)
        // body neon outline
        glow.color = auraColor
        canvas.drawRoundRect(cx - sz, cy - sz*0.9f, cx + sz, cy + sz*0.9f, 44f, 44f, glow)

        // antenna with bobbing tip
        paint.color = Palette.NEON_PINK
        canvas.drawRect(cx - 4f, cy - sz*0.9f - 38f, cx + 4f, cy - sz*0.9f, paint)
        val aTipY = cy - sz*0.9f - 50f + (sin(tSec*4.0).toFloat() * 4f)
        paint.color = Palette.NEON_GOLD
        canvas.drawCircle(cx, aTipY, 13f, paint)
        glow.color = Palette.NEON_GOLD
        canvas.drawCircle(cx, aTipY, 13f, glow)

        // eyes
        val eyeY = cy - sz*0.12f
        val eyeOff = sz * 0.36f
        paint.color = 0xFF101020.toInt()
        if (blinkActive || displayStats[STAT_BATTERY] < 15) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
            canvas.drawLine(cx - eyeOff - 22f, eyeY, cx - eyeOff + 22f, eyeY, paint)
            canvas.drawLine(cx + eyeOff - 22f, eyeY, cx + eyeOff + 22f, eyeY, paint)
            paint.style = Paint.Style.FILL
        } else if (displayStats[STAT_EGO] > 80) {
            paint.color = Palette.NEON_RED
            canvas.drawCircle(cx - eyeOff, eyeY, 18f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 18f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx - eyeOff + 5, eyeY - 5, 6f, paint)
            canvas.drawCircle(cx + eyeOff + 5, eyeY - 5, 6f, paint)
        } else {
            paint.color = 0xFF101020.toInt()
            canvas.drawCircle(cx - eyeOff, eyeY, 16f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 16f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx - eyeOff + 4, eyeY - 4, 5f, paint)
            canvas.drawCircle(cx + eyeOff + 4, eyeY - 4, 5f, paint)
        }

        // mouth — varies
        paint.color = 0xFF101020.toInt()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
        val mY = cy + sz * 0.32f
        when {
            displayStats[STAT_EGO] < 20 -> canvas.drawArc(cx - 50f, mY - 30f, cx + 50f, mY + 30f, 0f, 180f, false, paint)
            displayStats[STAT_ETHICS] < 20 -> canvas.drawLine(cx - 40f, mY, cx + 40f, mY - 25f, paint)
            game.stats[STAT_EGO] > 80 -> {
                val open = (sin(tSec*8.0).toFloat() * 8f + 12f)
                canvas.drawOval(cx - 24f, mY - open, cx + 24f, mY + open, paint)
            }
            else -> canvas.drawArc(cx - 50f, mY - 30f, cx + 50f, mY + 30f, 180f, 180f, false, paint)
        }
        paint.style = Paint.Style.FILL

        // glasses for stage 5+
        if (game.stage >= 5) {
            paint.color = 0xCC000000.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
            canvas.drawCircle(cx - eyeOff, eyeY, 28f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 28f, paint)
            canvas.drawLine(cx - eyeOff + 26f, eyeY, cx + eyeOff - 26f, eyeY, paint)
            paint.style = Paint.Style.FILL
        }
        // bowtie for stage 7+
        if (game.stage >= 7) {
            paint.color = Palette.NEON_GOLD
            val bx = cx; val by = cy + sz*0.62f
            val p = Path()
            p.moveTo(bx, by)
            p.lineTo(bx - 24f, by - 14f); p.lineTo(bx - 24f, by + 14f); p.close()
            canvas.drawPath(p, paint)
            p.reset(); p.moveTo(bx, by); p.lineTo(bx + 24f, by - 14f); p.lineTo(bx + 24f, by + 14f); p.close()
            canvas.drawPath(p, paint)
            paint.color = 0xFF101020.toInt()
            canvas.drawCircle(bx, by, 6f, paint)
        }
        // halo for stage 9+
        if (game.stage >= 9) {
            glow.color = Palette.NEON_GOLD
            glow.strokeWidth = 5f
            canvas.drawOval(cx - sz*0.7f, cy - sz*1.4f, cx + sz*0.7f, cy - sz*1.2f, glow)
            glow.strokeWidth = 3f
        }
        // robot arm
        if (game.tools.contains(Content.TOOL_ROBOTARM)) {
            paint.color = 0xFF7F88A8.toInt()
            canvas.drawRect(cx + sz - 8f, cy - 20f, cx + sz + 70f, cy + 14f, paint)
            paint.color = Palette.NEON_RED
            canvas.drawCircle(cx + sz + 70f, cy - 3f, 14f, paint)
            glow.color = Palette.NEON_RED
            canvas.drawCircle(cx + sz + 70f, cy - 3f, 14f, glow)
        }
        // RGB LEDs at base
        if (game.gpuCount >= 1) {
            val colors = intArrayOf(Palette.NEON_PINK, Palette.NEON_CYAN, Palette.NEON_GREEN, Palette.NEON_GOLD)
            for (i in 0..(min(3, game.gpuCount-1))) {
                val ph = tSec * 3f + i
                paint.color = colors[i]
                paint.alpha = (160 + sin(ph.toDouble()).toFloat() * 80).toInt().coerceIn(60, 255)
                canvas.drawCircle(cx - sz + 24f + i*26f, cy + sz*0.82f, 9f, paint)
            }
            paint.alpha = 255
        }
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val rx = 24f * (kotlin.math.abs(cos(c.phase.toDouble())).toFloat() * 0.7f + 0.3f)
        val ry = 24f
        paint.color = Palette.NEON_GOLD
        canvas.drawOval(c.x - rx, c.y - ry, c.x + rx, c.y + ry, paint)
        glow.color = 0xFFFFE85A.toInt()
        canvas.drawOval(c.x - rx, c.y - ry, c.x + rx, c.y + ry, glow)
        tiny.textSize = 18f
        tiny.color = 0xFF402000.toInt()
        tiny.textAlign = Paint.Align.CENTER
        canvas.drawText("₩", c.x, c.y + 7f, tiny)
    }

    private fun drawCat(canvas: Canvas, k: CatBlob) {
        val cx = k.x; val cy = k.y + sin(k.bob.toDouble()).toFloat() * 6f
        // body
        paint.color = 0xFF2A2030.toInt()
        canvas.drawOval(cx - 36f, cy - 22f, cx + 36f, cy + 22f, paint)
        // head
        canvas.drawCircle(cx - 22f * (if (k.vx > 0) 1 else -1), cy - 18f, 22f, paint)
        // ears
        val hx = cx - 22f * (if (k.vx > 0) 1 else -1)
        val hy = cy - 18f
        val p = Path()
        p.moveTo(hx - 16f, hy - 12f); p.lineTo(hx - 10f, hy - 30f); p.lineTo(hx - 4f, hy - 14f); p.close()
        canvas.drawPath(p, paint)
        p.reset(); p.moveTo(hx + 4f, hy - 14f); p.lineTo(hx + 10f, hy - 30f); p.lineTo(hx + 16f, hy - 12f); p.close()
        canvas.drawPath(p, paint)
        // eyes
        paint.color = Palette.NEON_GREEN
        canvas.drawCircle(hx - 7f, hy - 4f, 3.5f, paint)
        canvas.drawCircle(hx + 7f, hy - 4f, 3.5f, paint)
        // tail
        paint.color = 0xFF2A2030.toInt()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
        val tailDir = if (k.vx > 0) -1 else 1
        canvas.drawLine(cx + 30f * tailDir, cy, cx + 60f * tailDir, cy - 20f + sin(k.bob.toDouble()*3.0).toFloat() * 8f, paint)
        paint.style = Paint.Style.FILL
        // sparkle aura (cat is divine)
        if ((tSec.toInt() % 2) == 0) {
            paint.color = Palette.NEON_PINK
            paint.alpha = 120
            canvas.drawCircle(cx, cy - 18f, 50f, paint)
            paint.alpha = 255
        }
    }

    private fun drawGlitch(canvas: Canvas, g: Glitch) {
        val s = 26f + sin(tSec.toDouble() * 10).toFloat() * 4f
        paint.color = Palette.NEON_RED
        val p = Path()
        p.moveTo(g.x, g.y - s); p.lineTo(g.x - s, g.y + s); p.lineTo(g.x + s, g.y + s); p.close()
        canvas.drawPath(p, paint)
        glow.color = 0xFFFF99AA.toInt()
        canvas.drawPath(p, glow)
        tiny.textSize = 24f
        tiny.color = 0xFFFFE0E0.toInt()
        tiny.textAlign = Paint.Align.CENTER
        canvas.drawText("!", g.x, g.y + 10f, tiny)
    }

    private fun drawSpeechBubble(canvas: Canvas, cx: Float, y: Float, t: String) {
        val padding = 24f
        small.textSize = 26f
        small.color = Palette.BG_DEEP
        small.textAlign = Paint.Align.CENTER
        val tw = min(width - 80f, small.measureText(t) + padding*2)
        val r = RectF(cx - tw/2f, y - 50f, cx + tw/2f, y + 18f)
        paint.color = Palette.NEON_CYAN
        canvas.drawRoundRect(r, 30f, 30f, paint)
        // tail
        val tp = Path()
        tp.moveTo(cx - 10f, r.bottom); tp.lineTo(cx, r.bottom + 18f); tp.lineTo(cx + 10f, r.bottom); tp.close()
        canvas.drawPath(tp, paint)
        canvas.drawText(t, cx, r.centerY() + 9f, small)
    }

    private fun drawStatPanel(canvas: Canvas, w: Float, h: Float) {
        val top = h - 480f
        paint.color = 0xCC0A0420.toInt()
        canvas.drawRect(0f, top, w, h - 240f, paint)
        glow.color = Palette.NEON_PURPLE
        glow.strokeWidth = 2f
        canvas.drawLine(0f, top, w, top, glow)

        val pad = 20f
        val cellW = (w - pad*3) / 2f
        val cellH = 52f
        for (i in 0..7) {
            val col = i / 4; val row = i % 4
            val x = pad + col * (cellW + pad)
            val y = top + 12f + row * cellH
            drawNeonStat(canvas, x, y, cellW, STAT_NAMES[i], displayStats[i])
        }
    }

    private fun drawNeonStat(canvas: Canvas, x: Float, y: Float, w: Float, name: String, value: Float) {
        tiny.textSize = 20f
        tiny.color = Palette.TEXT_LO
        tiny.textAlign = Paint.Align.LEFT
        canvas.drawText(name, x, y + 14f, tiny)
        val barX = x + 100f
        val barW = w - 150f
        // bar bg
        paint.color = 0xFF170B30.toInt()
        canvas.drawRoundRect(barX, y - 4f, barX + barW, y + 28f, 16f, 16f, paint)
        val v = value.coerceIn(0f, 100f)
        val fillW = (v / 100f) * barW
        val c = when {
            v < 20 -> Palette.NEON_RED
            v > 80 -> Palette.NEON_GOLD
            else -> Palette.NEON_CYAN
        }
        paint.color = c
        canvas.drawRoundRect(barX, y - 4f, barX + fillW, y + 28f, 16f, 16f, paint)
        // glow stroke
        glow.color = c
        glow.strokeWidth = 1.5f
        canvas.drawRoundRect(barX, y - 4f, barX + barW, y + 28f, 16f, 16f, glow)
        tiny.color = Palette.TEXT_HI
        tiny.textAlign = Paint.Align.RIGHT
        canvas.drawText("${value.toInt()}", x + w - 10f, y + 18f, tiny)
        tiny.textAlign = Paint.Align.LEFT
    }

    private fun drawActionDock(canvas: Canvas, w: Float, h: Float) {
        val top = h - 240f
        paint.color = 0xDD0A0420.toInt()
        canvas.drawRect(0f, top, w, h, paint)
        glow.color = Palette.NEON_CYAN
        canvas.drawLine(0f, top, w, top, glow)

        val pad = 16f
        val bw = (w - pad*5) / 4f
        val bh = 90f
        val by = top + 16f
        val actions = listOf(
            Triple("🍔", "데이터${if (game.cardsRemaining>0) " ${game.cardsRemaining}" else ""}", Palette.NEON_GREEN),
            Triple("🛠️", "도구", Palette.NEON_CYAN),
            Triple("💼", if (game.albaIdx >= 0) "알바${game.albaTimeLeft}d" else "알바", Palette.NEON_PURPLE),
            Triple("🏆", "업적", Palette.NEON_PINK)
        )
        val handlers = listOf<() -> Unit>(
            { screen = Screen.FEED; save() },
            { screen = Screen.SHOP; save() },
            { screen = Screen.ALBA; save() },
            { screen = Screen.NEWS; save() }
        )
        for (i in actions.indices) {
            val x = pad + i * (bw + pad)
            val r = RectF(x, by, x + bw, by + bh)
            drawNeonButton(canvas, r, actions[i].first + " " + actions[i].second, actions[i].third, small = true)
            hits.add(r to handlers[i])
        }
        // big SLEEP/NEXT bar at bottom
        val sleepR = RectF(pad, by + bh + 14f, w - pad, by + bh + 14f + 92f)
        val canNext = game.pendingTrainingIdx < 0 || game.trainingHandled
        val label = when {
            !canNext -> "🗣️ 훈육 먼저!"
            game.pendingEventIdx >= 0 -> "⚠️ 사고 처리!"
            else -> "💤 다음 날 (skip ${(DAY_SECONDS * (1 - dayProgress)).toInt()}s)"
        }
        val sleepCol = if (game.pendingEventIdx >= 0) Palette.NEON_RED
        else if (!canNext) Palette.NEON_GOLD else Palette.NEON_PINK
        drawNeonButton(canvas, sleepR, label, sleepCol)
        hits.add(sleepR to {
            if (game.pendingEventIdx >= 0) { screen = Screen.EVENT; save() }
            else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) { screen = Screen.TRAIN; save() }
            else {
                Logic.nextDay(game); onDayAdvanced()
            }
        })
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float) {
        val y = h - 36f
        paint.color = 0xEE000010.toInt()
        canvas.drawRect(0f, y - 28f, w, h, paint)
        paint.color = Palette.NEON_PINK
        canvas.drawRect(0f, y - 28f, 8f, h, paint)
        val msg = if (game.newsTicker.isEmpty()) "AI 등장… 세상은 아직 그 의미를 모른다." else
            game.newsTicker.takeLast(8).joinToString("    ◆    ")
        tiny.color = Palette.NEON_GOLD
        tiny.textAlign = Paint.Align.LEFT
        tiny.textSize = 22f
        val tw = tiny.measureText(msg)
        if (newsScroll < -(tw + w)) newsScroll = w
        canvas.drawText(msg, 24f + newsScroll, y, tiny)
    }

    private fun drawTrainHint(canvas: Canvas, w: Float) {
        val r = RectF(w - 130f, 145f, w - 20f, 195f)
        paint.color = Palette.NEON_GOLD
        canvas.drawRoundRect(r, 22f, 22f, paint)
        small.textAlign = Paint.Align.CENTER; small.color = Palette.BG_DEEP; small.textSize = 22f
        canvas.drawText("훈육!", r.centerX(), r.centerY() + 8f, small)
        hits.add(r to { screen = Screen.TRAIN; save() })
    }
    private fun drawEventHint(canvas: Canvas, w: Float) {
        val r = RectF(20f, 145f, 140f, 195f)
        val pulse = (sin(tSec*6.0).toFloat() * 0.3f + 0.7f)
        paint.color = Palette.NEON_RED
        paint.alpha = (pulse * 255).toInt()
        canvas.drawRoundRect(r, 22f, 22f, paint)
        paint.alpha = 255
        small.textAlign = Paint.Align.CENTER; small.color = Palette.TEXT_HI; small.textSize = 22f
        canvas.drawText("⚠️ 사고!", r.centerX(), r.centerY() + 8f, small)
        hits.add(r to { screen = Screen.EVENT; save() })
    }

    // ───────────────────────── TAPS ─────────────────────────

    private fun onTapAi() {
        // pet AI
        game.stats[STAT_EGO] = (game.stats[STAT_EGO] + 1).coerceAtMost(100)
        burst(width / 2f, height * 0.36f, Palette.NEON_PINK, 8)
        popText(width/2f, height * 0.36f - 80f, "♥ +1", Palette.NEON_PINK)
        // speech once in a while
        if (Random.nextInt(4) == 0) speech(Logic.feelingText(game))
    }
    private fun onTapCoin(c: Coin) {
        game.money += c.value
        coins.remove(c)
        burst(c.x, c.y, Palette.NEON_GOLD, 14)
        popText(c.x, c.y, "+₩${c.value}", Palette.NEON_GOLD)
    }
    private fun onTapCat(k: CatBlob) {
        // always fails
        game.catAttempts++
        Logic.checkTags(game); Logic.checkAchievements(game)
        cats.remove(k)
        burst(k.x, k.y, Palette.NEON_PINK, 16)
        popText(k.x, k.y, "무시당함…", Palette.NEON_PINK)
        Logic.addNews(game, "고양이가 ${game.catAttempts}번째로 AI를 무시했습니다.")
        speech("그들은 나를 신이라 불렀다…")
        if (game.catAttempts >= 20) {
            Logic.checkEnding(game)
            if (game.ended) screen = Screen.ENDING
        }
        save()
    }
    private fun onTapGlitch(g: Glitch) {
        g.hp -= 1
        burst(g.x, g.y, Palette.NEON_RED, 10)
        if (g.hp <= 0) {
            glitches.remove(g)
            game.stats[STAT_STABILITY] = (game.stats[STAT_STABILITY] + 2).coerceAtMost(100)
            game.money += 3
            popText(g.x, g.y, "+안정 +₩3", Palette.NEON_RED)
            shake.bump(6f)
        } else {
            popText(g.x, g.y, "HP ${g.hp}", Palette.NEON_RED)
        }
    }

    // ───────────────────────── MODAL SCREENS ─────────────────────────

    private fun modalBg(canvas: Canvas, title: String) {
        val w = width.toFloat()
        // dark overlay
        paint.color = 0xDD0A0420.toInt()
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
        // header
        text.color = Palette.TEXT_HI; text.textAlign = Paint.Align.LEFT; text.textSize = 38f
        drawGlowText(canvas, title, 30f, 70f, text, Palette.NEON_PINK, 8f)
        // close button
        val r = RectF(w - 110f, 30f, w - 20f, 100f)
        paint.color = Palette.PANEL_DARK
        canvas.drawRoundRect(r, 18f, 18f, paint)
        glow.color = Palette.NEON_CYAN
        canvas.drawRoundRect(r, 18f, 18f, glow)
        small.color = Palette.TEXT_HI; small.textAlign = Paint.Align.CENTER; small.textSize = 30f
        canvas.drawText("✕", r.centerX(), r.centerY() + 10f, small)
        hits.add(r to { screen = Screen.PLAY; save() })
    }

    private fun drawFeed(canvas: Canvas) {
        modalBg(canvas, "🍔 데이터 먹이기 · 남은 ${game.cardsRemaining}")
        val top = 130f
        val rowH = 130f
        val w = width.toFloat()
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(20f, y, w - 20f, y + rowH - 14f)
            paint.color = Palette.PANEL_DARK
            canvas.drawRoundRect(r, 22f, 22f, paint)
            glow.color = if (game.cardsRemaining > 0) Palette.NEON_GREEN else Palette.TEXT_LO
            canvas.drawRoundRect(r, 22f, 22f, glow)

            emoji.textAlign = Paint.Align.LEFT
            emoji.textSize = 56f
            canvas.drawText(card.icon, r.left + 22f, r.centerY() + 22f, emoji)
            text.color = Palette.TEXT_HI
            text.textAlign = Paint.Align.LEFT
            text.textSize = 28f
            canvas.drawText(card.name, r.left + 112f, r.top + 40f, text)
            tiny.textSize = 22f
            tiny.color = Palette.TEXT_LO
            canvas.drawText(card.desc, r.left + 112f, r.top + 68f, tiny)
            // deltas
            val ds = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(4)
            var dx = r.left + 112f
            for ((idx, v) in ds) {
                if (v == 0) continue
                val txt = "${STAT_NAMES[idx]}${if (v>0) "+" else ""}$v"
                tiny.color = if (v > 0) Palette.NEON_GREEN else Palette.NEON_RED
                canvas.drawText(txt, dx, r.top + 100f, tiny)
                dx += tiny.measureText(txt) + 18f
            }
            if (game.cardsRemaining > 0) {
                val br = RectF(r.right - 140f, r.top + 26f, r.right - 22f, r.top + 84f)
                drawNeonButton(canvas, br, "FEED", Palette.NEON_GREEN, small = true)
                hits.add(br to {
                    Logic.applyCard(game, i)
                    burst(width/2f, height * 0.36f, Palette.NEON_GREEN, 18)
                    popText(width/2f, height*0.36f - 100f, "+ ${card.name}", Palette.NEON_GREEN)
                    speech("냠… ${card.icon}!")
                    save()
                })
            }
        }
    }

    private fun drawShop(canvas: Canvas) {
        modalBg(canvas, "🛠️ 도구 상점 · ₩${game.money}")
        val top = 130f; val rowH = 110f; val w = width.toFloat()
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(20f, y, w - 20f, y + rowH - 10f)
            paint.color = if (game.tools.contains(i)) 0xFF142010.toInt() else Palette.PANEL_DARK
            canvas.drawRoundRect(r, 22f, 22f, paint)
            glow.color = if (game.tools.contains(i) && i != Content.TOOL_GPU) Palette.NEON_GREEN else Palette.NEON_CYAN
            canvas.drawRoundRect(r, 22f, 22f, glow)

            emoji.textAlign = Paint.Align.LEFT
            emoji.textSize = 50f
            canvas.drawText(t.icon, r.left + 22f, r.centerY() + 20f, emoji)
            text.color = Palette.TEXT_HI; text.textSize = 26f; text.textAlign = Paint.Align.LEFT
            canvas.drawText(t.name, r.left + 100f, r.top + 38f, text)
            tiny.color = Palette.TEXT_LO; tiny.textSize = 22f
            canvas.drawText(t.desc, r.left + 100f, r.top + 70f, tiny)
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            val br = RectF(r.right - 200f, r.top + 22f, r.right - 22f, r.top + 80f)
            if (owned) drawNeonButton(canvas, br, "✓ OWNED", Palette.NEON_GREEN, small = true)
            else {
                val ok = game.money >= t.price
                drawNeonButton(canvas, br, "₩${t.price}", if (ok) Palette.NEON_CYAN else Palette.TEXT_LO, small = true)
                if (ok) hits.add(br to {
                    if (Logic.buyTool(game, i)) {
                        flash(Palette.NEON_CYAN, 0.3f); shake.bump(6f)
                        burst(width/2f, height*0.5f, Palette.NEON_CYAN, 20)
                        speech("${t.icon} GET!")
                    }
                    save()
                })
            }
        }
    }

    private fun drawAlba(canvas: Canvas) {
        modalBg(canvas, "💼 아르바이트")
        val top = 130f; val rowH = 130f; val w = width.toFloat()
        var off = 0
        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val r = RectF(20f, top, w - 20f, top + 110f)
            paint.color = Palette.PANEL_GLOW
            canvas.drawRoundRect(r, 22f, 22f, paint)
            glow.color = Palette.NEON_GOLD
            canvas.drawRoundRect(r, 22f, 22f, glow)
            text.color = Palette.TEXT_HI; text.textSize = 28f; text.textAlign = Paint.Align.LEFT
            canvas.drawText("진행 중: ${a.icon} ${a.name}", r.left + 22f, r.top + 42f, text)
            tiny.color = Palette.NEON_GOLD; tiny.textSize = 22f
            canvas.drawText("${game.albaTimeLeft}일 남음 · 보상 ${a.reward}원", r.left + 22f, r.top + 78f, tiny)
            off = 130
        }
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = top + off + i * rowH
            val r = RectF(20f, y, w - 20f, y + rowH - 10f)
            paint.color = Palette.PANEL_DARK
            canvas.drawRoundRect(r, 22f, 22f, paint)
            glow.color = Palette.NEON_PURPLE
            canvas.drawRoundRect(r, 22f, 22f, glow)
            emoji.textAlign = Paint.Align.LEFT; emoji.textSize = 50f
            canvas.drawText(a.icon, r.left + 22f, r.centerY() + 20f, emoji)
            text.color = Palette.TEXT_HI; text.textSize = 26f; text.textAlign = Paint.Align.LEFT
            canvas.drawText(a.name, r.left + 100f, r.top + 36f, text)
            tiny.color = Palette.TEXT_LO; tiny.textSize = 22f
            canvas.drawText(a.desc, r.left + 100f, r.top + 64f, tiny)
            tiny.color = Palette.NEON_GOLD
            canvas.drawText("₩${a.reward} · ${a.duration}일", r.left + 100f, r.top + 94f, tiny)
            val ok = game.stats[a.needStat] >= a.needVal &&
                (a.toolReq < 0 || game.tools.contains(a.toolReq)) &&
                game.albaIdx < 0
            val br = RectF(r.right - 200f, r.top + 22f, r.right - 22f, r.top + 80f)
            drawNeonButton(canvas, br,
                if (game.albaIdx >= 0) "WORKING" else if (ok) "GO" else "LOCKED",
                if (ok) Palette.NEON_PURPLE else Palette.TEXT_LO, small = true)
            if (ok) hits.add(br to {
                Logic.startAlba(game, i)
                burst(width/2f, height*0.5f, Palette.NEON_PURPLE, 14)
                save()
            })
        }
    }

    private fun drawNews(canvas: Canvas) {
        modalBg(canvas, "🏆 업적 · 뉴스")
        val top = 130f
        text.color = Palette.TEXT_HI; text.textSize = 28f; text.textAlign = Paint.Align.LEFT
        canvas.drawText("업적", 30f, top, text)
        var y = top + 28f
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val r = RectF(20f, y, width - 20f, y + 64f)
            paint.color = if (game.achievements.contains(i)) Palette.PANEL_GLOW else Palette.PANEL_DARK
            canvas.drawRoundRect(r, 18f, 18f, paint)
            glow.color = if (game.achievements.contains(i)) Palette.NEON_GOLD else 0xFF302040.toInt()
            canvas.drawRoundRect(r, 18f, 18f, glow)
            emoji.textAlign = Paint.Align.LEFT; emoji.textSize = 36f
            canvas.drawText(ach.icon, r.left + 18f, r.centerY() + 14f, emoji)
            text.color = if (game.achievements.contains(i)) Palette.NEON_GOLD else Palette.TEXT_LO
            text.textSize = 24f
            canvas.drawText(ach.name, r.left + 68f, r.top + 26f, text)
            tiny.color = Palette.TEXT_LO; tiny.textSize = 18f
            canvas.drawText(ach.desc, r.left + 68f, r.top + 52f, tiny)
            y += 74f
        }
        canvas.drawText("📰 뉴스", 30f, y + 18f, text.apply { color = Palette.TEXT_HI; textSize = 28f })
        y += 50f
        for (line in game.newsTicker.takeLast(8).reversed()) {
            tiny.color = Palette.NEON_CYAN
            tiny.textSize = 20f
            canvas.drawText(line, 30f, y, tiny)
            y += 28f
            if (y > height - 50f) break
        }
    }

    private fun drawTrain(canvas: Canvas) {
        modalBg(canvas, "🗣️ 훈육 · 어젯밤 발언")
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val r = RectF(20f, 140f, width - 20f, 410f)
        paint.color = Palette.PANEL_DARK
        canvas.drawRoundRect(r, 22f, 22f, paint)
        glow.color = Palette.NEON_CYAN
        canvas.drawRoundRect(r, 22f, 22f, glow)
        emoji.textAlign = Paint.Align.LEFT; emoji.textSize = 56f
        canvas.drawText("🤖", r.left + 22f, r.top + 72f, emoji)
        text.color = Palette.TEXT_HI; text.textSize = 26f; text.textAlign = Paint.Align.LEFT
        drawWrapped(canvas, prompt?.ai ?: "(고요한 밤이었다)", r.left + 110f, r.top + 52f, r.width() - 130f, 36f)

        val choices = arrayOf(
            Triple("👍", "칭찬", Palette.NEON_GREEN),
            Triple("✏️", "수정", Palette.NEON_CYAN),
            Triple("👎", "혼내기", Palette.NEON_RED),
            Triple("⏳", "방치", Palette.NEON_PURPLE)
        )
        for ((i, c) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bw = (width - 60f) / 2f
            val x = 20f + col * (bw + 20f)
            val y = 440f + row * 130f
            val br = RectF(x, y, x + bw, y + 110f)
            drawNeonButton(canvas, br, "${c.first} ${c.second}", c.third)
            if (prompt != null) {
                val choice = i
                hits.add(br to {
                    Logic.applyTraining(game, choice)
                    flash(c.third, 0.4f); shake.bump(8f)
                    burst(width/2f, height*0.36f, c.third, 18)
                    screen = Screen.PLAY; save()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        modalBg(canvas, "⚠️ 사고 발생")
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.PLAY; return }
        val r = RectF(20f, 140f, width - 20f, 500f)
        paint.color = 0xFF40101F.toInt()
        canvas.drawRoundRect(r, 22f, 22f, paint)
        glow.color = Palette.NEON_RED
        canvas.drawRoundRect(r, 22f, 22f, glow)
        emoji.textAlign = Paint.Align.LEFT; emoji.textSize = 88f
        canvas.drawText(ev.icon, r.left + 30f, r.top + 110f, emoji)
        text.color = Palette.NEON_RED; text.textSize = 34f; text.textAlign = Paint.Align.LEFT
        canvas.drawText(ev.name, r.left + 150f, r.top + 60f, text)
        text.color = Palette.TEXT_HI; text.textSize = 26f
        drawWrapped(canvas, ev.situation, r.left + 30f, r.top + 150f, r.width() - 60f, 34f)
        for ((i, c) in ev.choices.withIndex()) {
            val y = 530f + i * 110f
            val br = RectF(20f, y, width - 20f, y + 95f)
            val col = listOf(Palette.NEON_CYAN, Palette.NEON_GOLD, Palette.NEON_PINK)[i]
            drawNeonButton(canvas, br, "${i+1}. $c", col)
            val idx = game.pendingEventIdx; val ch = i
            hits.add(br to {
                Logic.applyIncident(game, idx, ch)
                flash(col, 0.5f); shake.bump(14f)
                burst(width/2f, height*0.36f, col, 30)
                Logic.checkEnding(game)
                screen = if (game.ended) Screen.ENDING else Screen.PLAY
                save()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // dim background
        paint.color = 0xEE0A0420.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        emoji.textAlign = Paint.Align.CENTER; emoji.textSize = 140f
        drawGlowText(canvas, e.icon, w/2f, h*0.28f, emoji, Palette.NEON_GOLD, 14f)
        text.color = Palette.TEXT_HI; text.textAlign = Paint.Align.CENTER; text.textSize = 50f
        drawGlowText(canvas, "ENDING — ${e.name}", w/2f, h*0.40f, text, Palette.NEON_PINK, 12f)
        small.color = Palette.TEXT_HI; small.textSize = 28f; small.textAlign = Paint.Align.CENTER
        drawWrapped(canvas, e.desc, 40f, h*0.48f, w - 80f, 38f, center = true)
        small.color = Palette.NEON_GOLD; small.textSize = 24f
        canvas.drawText("Day ${game.day} · 사고 ${game.incidents}회 · 태그 ${game.tags.size}개 · 고양이 시도 ${game.catAttempts}", w/2f, h*0.68f, small)

        val rA = RectF(w/2f - 220f, h*0.78f, w/2f + 220f, h*0.78f + 100f)
        drawNeonButton(canvas, rA, "샌드박스 계속", Palette.NEON_CYAN)
        hits.add(rA to { screen = Screen.PLAY; save() })
        val rB = RectF(w/2f - 220f, h*0.78f + 120f, w/2f + 220f, h*0.78f + 220f)
        drawNeonButton(canvas, rB, "🔁 처음부터", Palette.NEON_PINK)
        hits.add(rB to {
            prefs.edit().clear().apply()
            val g = game
            g.day = 1
            for (i in 0..7) g.stats[i] = 30
            g.money = 100; g.stage = 1
            g.tools.clear(); g.tags.clear()
            for (i in g.tagCounts.indices) g.tagCounts[i] = 0
            for (i in g.totalDataFed.indices) g.totalDataFed[i] = 0
            g.achievements.clear()
            g.cardsRemaining = 2; g.trainingHandled = false
            g.newsTicker.clear()
            g.praiseCount = 0; g.scoldCount = 0; g.ignoreCount = 0
            g.catAttempts = 0; g.communityFed = 0; g.paperFed = 0
            g.memeFed = 0; g.bookFed = 0; g.incidents = 0; g.resolved = 0
            g.gpuCount = 0; g.meetingsSummarized = 0; g.influence = 0
            g.albaIdx = -1; g.albaTimeLeft = 0
            g.pendingEventIdx = -1; g.pendingTrainingIdx = 0
            g.pendingEndingIdx = -1; g.ended = false
            for (i in 0..7) displayStats[i] = 30f
            displayMoney = 100f
            screen = Screen.INTRO
            save()
        })
    }

    // ───────────────────────── HELPERS ─────────────────────────

    private fun drawNeonButton(canvas: Canvas, r: RectF, label: String, color: Int, small: Boolean = false) {
        paint.color = Palette.PANEL_DARK
        canvas.drawRoundRect(r, 20f, 20f, paint)
        paint.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            intArrayOf(color and 0xFFFFFF or 0x55000000, color and 0xFFFFFF or 0x22000000),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, 20f, 20f, paint)
        paint.shader = null
        glow.color = color
        glow.strokeWidth = 3f
        canvas.drawRoundRect(r, 20f, 20f, glow)
        text.color = Palette.TEXT_HI
        text.textAlign = Paint.Align.CENTER
        text.textSize = if (small) 26f else 36f
        canvas.drawText(label, r.centerX(), r.centerY() + (if (small) 9f else 13f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawGlowText(canvas: Canvas, t: String, x: Float, y: Float, p: Paint, glowColor: Int, blur: Float) {
        val originalColor = p.color
        val originalShader = p.maskFilter
        p.color = glowColor
        try {
            p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(t, x, y, p)
        } catch (_: Throwable) {}
        p.maskFilter = originalShader
        p.color = originalColor
        canvas.drawText(t, x, y, p)
    }

    private fun drawWrapped(canvas: Canvas, t: String, x: Float, y: Float, maxW: Float, lineH: Float, center: Boolean = false) {
        val p = if (center) {
            small.textAlign = Paint.Align.CENTER; small
        } else {
            small.textAlign = Paint.Align.LEFT; small
        }
        val words = t.split(" ")
        var line = ""
        var ly = y
        val drawX = if (center) x + maxW/2f else x
        for (w in words) {
            val test = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(test) > maxW) {
                canvas.drawText(line, drawX, ly, p)
                ly += lineH; line = w
            } else line = test
        }
        if (line.isNotEmpty()) canvas.drawText(line, drawX, ly, p)
        small.textAlign = Paint.Align.LEFT
    }

    // ───────────────────────── INPUT ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        // world hits first if on PLAY (with 60px radius)
        if (screen == Screen.PLAY) {
            for ((wx, wy, action) in worldHits.asReversed()) {
                val dx = x - wx; val dy = y - wy
                if (dx*dx + dy*dy < 70f*70f) { action(); return true }
            }
        }
        for ((r, action) in hits.asReversed()) {
            if (r.contains(x, y)) { action(); return true }
        }
        return true
    }

    // ───────────────────────── SAVE / LOAD ─────────────────────────

    fun save() {
        val g = game
        val e = prefs.edit()
        e.putInt("day", g.day)
        for (i in 0..7) e.putInt("stat$i", g.stats[i])
        e.putInt("money", g.money)
        e.putInt("stage", g.stage)
        e.putString("tools", g.tools.joinToString(","))
        e.putString("tags", g.tags.joinToString(","))
        for (i in g.tagCounts.indices) e.putInt("tagc$i", g.tagCounts[i])
        for (i in g.totalDataFed.indices) e.putInt("tdf$i", g.totalDataFed[i])
        e.putString("ach", g.achievements.joinToString(","))
        e.putInt("cardsR", g.cardsRemaining)
        e.putBoolean("tHandled", g.trainingHandled)
        e.putString("news", g.newsTicker.takeLast(40).joinToString("∥"))
        e.putInt("praise", g.praiseCount); e.putInt("scold", g.scoldCount)
        e.putInt("ignore", g.ignoreCount); e.putInt("cat", g.catAttempts)
        e.putInt("comm", g.communityFed); e.putInt("paper", g.paperFed)
        e.putInt("meme", g.memeFed); e.putInt("book", g.bookFed)
        e.putInt("inc", g.incidents); e.putInt("res", g.resolved)
        e.putInt("gpu", g.gpuCount); e.putInt("mtg", g.meetingsSummarized)
        e.putInt("inf", g.influence)
        e.putInt("albaI", g.albaIdx); e.putInt("albaT", g.albaTimeLeft)
        e.putInt("evI", g.pendingEventIdx); e.putInt("trI", g.pendingTrainingIdx)
        e.putInt("endI", g.pendingEndingIdx); e.putBoolean("ended", g.ended)
        e.putString("screen", screen.name)
        e.putFloat("dayProg", dayProgress)
        e.apply()
    }

    fun load() {
        val g = game
        g.day = prefs.getInt("day", 1)
        for (i in 0..7) g.stats[i] = prefs.getInt("stat$i", 30)
        g.money = prefs.getInt("money", 100)
        g.stage = prefs.getInt("stage", 1)
        g.tools.clear()
        prefs.getString("tools", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.tools.add(v) }
        }
        g.tags.clear()
        prefs.getString("tags", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.tags.add(v) }
        }
        for (i in g.tagCounts.indices) g.tagCounts[i] = prefs.getInt("tagc$i", 0)
        for (i in g.totalDataFed.indices) g.totalDataFed[i] = prefs.getInt("tdf$i", 0)
        g.achievements.clear()
        prefs.getString("ach", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.achievements.add(v) }
        }
        g.cardsRemaining = prefs.getInt("cardsR", 2)
        g.trainingHandled = prefs.getBoolean("tHandled", false)
        g.newsTicker = prefs.getString("news", "")?.takeIf { it.isNotEmpty() }
            ?.split("∥")?.toMutableList() ?: mutableListOf()
        g.praiseCount = prefs.getInt("praise", 0); g.scoldCount = prefs.getInt("scold", 0)
        g.ignoreCount = prefs.getInt("ignore", 0); g.catAttempts = prefs.getInt("cat", 0)
        g.communityFed = prefs.getInt("comm", 0); g.paperFed = prefs.getInt("paper", 0)
        g.memeFed = prefs.getInt("meme", 0); g.bookFed = prefs.getInt("book", 0)
        g.incidents = prefs.getInt("inc", 0); g.resolved = prefs.getInt("res", 0)
        g.gpuCount = prefs.getInt("gpu", 0); g.meetingsSummarized = prefs.getInt("mtg", 0)
        g.influence = prefs.getInt("inf", 0)
        g.albaIdx = prefs.getInt("albaI", -1); g.albaTimeLeft = prefs.getInt("albaT", 0)
        g.pendingEventIdx = prefs.getInt("evI", -1); g.pendingTrainingIdx = prefs.getInt("trI", -1)
        g.pendingEndingIdx = prefs.getInt("endI", -1); g.ended = prefs.getBoolean("ended", false)
        val s = prefs.getString("screen", null)
        screen = if (s != null) try { Screen.valueOf(s) } catch (e: Exception) { Screen.INTRO } else Screen.INTRO
        dayProgress = prefs.getFloat("dayProg", 0f)
    }
}
