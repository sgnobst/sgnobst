package com.sgnobst.aigotchi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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

    private val kit = StyleKit()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val worldHits = mutableListOf<Triple<Float, Float, () -> Unit>>()

    // Anim state
    private var tSec = 0f
    private var lastMs = System.currentTimeMillis()
    private var aiBob = 0f
    private var blinkActive = false
    private var blinkT = 1.5f
    private var shakeAmt = 0f
    private var flashA = 0f
    private var flashColor = Color.WHITE
    private val displayStats = FloatArray(8) { 30f }
    private var displayMoney = 100f
    private var dayProgress = 0f
    private val DAY_SECONDS = 45f
    private var pressedHitIdx = -1
    private var pressedDecay = 0f

    // World objects
    private val coins = mutableListOf<Coin>()
    private val cats = mutableListOf<CatBlob>()
    private val glitches = mutableListOf<Glitch>()
    private val particles = mutableListOf<Particle>()
    private val floats = mutableListOf<FloatText>()
    private var coinTimer = 4f
    private var catTimer = 12f
    private var glitchTimer = 8f
    private var newsScroll = 0f
    private var speechT = 0f
    private var speechText: String = ""

    // Decoration: floating clouds
    private data class Cloud(var x: Float, val y: Float, val sz: Float, val speed: Float)
    private val clouds = mutableListOf<Cloud>()

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        kit.setScale(w)
        // spawn clouds
        clouds.clear()
        for (i in 0..4) {
            clouds.add(Cloud(
                Random.nextFloat() * w,
                h * (0.05f + Random.nextFloat() * 0.18f),
                kit.sz(40f + Random.nextFloat() * 30f),
                10f + Random.nextFloat() * 15f
            ))
        }
    }

    // ───────────────────────── UPDATE ─────────────────────────

    private fun update(dt: Float) {
        if (shakeAmt > 0) shakeAmt = max(0f, shakeAmt - dt * 60f)
        if (flashA > 0) flashA = max(0f, flashA - dt * 3f)
        if (pressedHitIdx >= 0) {
            pressedDecay -= dt
            if (pressedDecay <= 0) pressedHitIdx = -1
        }
        for (i in 0..7) {
            displayStats[i] += (game.stats[i] - displayStats[i]) * (dt * 6f).coerceAtMost(1f)
        }
        displayMoney += (game.money - displayMoney) * (dt * 6f).coerceAtMost(1f)
        aiBob = (sin(tSec * 2.0) * kit.sz(14f)).toFloat()
        blinkT -= dt
        if (blinkT <= 0) {
            blinkActive = !blinkActive
            blinkT = if (blinkActive) 0.12f else (2.5f + Random.nextFloat() * 3.5f)
        }

        // clouds drift
        for (cl in clouds) {
            cl.x += cl.speed * dt
            if (cl.x - cl.sz > width) cl.x = -cl.sz * 2
        }

        if (screen == Screen.PLAY && !game.ended) {
            dayProgress += dt / DAY_SECONDS
            if (dayProgress >= 1f) {
                dayProgress = 0f
                Logic.nextDay(game)
                onDayAdvanced()
            }

            coinTimer -= dt
            if (coinTimer <= 0) {
                spawnCoin()
                val rate = max(1.2f, 4.5f - game.stage * 0.3f - if (game.albaIdx >= 0) 1f else 0f)
                coinTimer = rate + Random.nextFloat() * 1.5f
            }
            catTimer -= dt
            if (catTimer <= 0) {
                spawnCat()
                catTimer = 10f + Random.nextFloat() * 14f
            }
            glitchTimer -= dt
            if (glitchTimer <= 0) {
                val risk = (100 - game.stats[STAT_STABILITY]) / 100f
                if (Random.nextFloat() < 0.25f + risk * 0.5f) spawnGlitch()
                glitchTimer = 6f + Random.nextFloat() * 6f
            }

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
            cats.removeAll { it.life <= 0 || it.x < -kit.sz(150f) || it.x > width + kit.sz(150f) }
            for (g in glitches) {
                g.x += g.vx * dt
                g.y += g.vy * dt
                g.life -= dt
                if (g.x < kit.sz(100f) || g.x > width - kit.sz(100f)) g.vx = -g.vx
                if (g.y < height * 0.25f) g.vy = kotlin.math.abs(g.vy)
                if (g.y > height * 0.5f) g.vy = -kotlin.math.abs(g.vy)
            }
            glitches.removeAll { it.life <= 0 }

            val pi = particles.iterator()
            while (pi.hasNext()) { val p = pi.next(); p.update(dt); if (p.life <= 0) pi.remove() }
            val fi = floats.iterator()
            while (fi.hasNext()) { val f = fi.next(); f.update(dt); if (f.life <= 0) fi.remove() }
            if (speechT > 0) speechT -= dt
            newsScroll -= kit.sz(110f) * dt
        }
    }

    private fun onDayAdvanced() {
        flash(Style.SKY_BLUE, 0.35f)
        shakeAmt = kit.sz(8f)
        if (game.ended) screen = Screen.ENDING
        else if (game.pendingEventIdx >= 0) screen = Screen.EVENT
        save()
    }

    private fun spawnCoin() {
        val x = kit.sz(80f) + Random.nextFloat() * (width - kit.sz(160f))
        val y = -kit.sz(40f)
        val vy = kit.sz(80f) + Random.nextFloat() * kit.sz(80f)
        val v = if (game.tools.contains(Content.TOOL_DATACENTER)) 50
                else if (game.tools.contains(Content.TOOL_SERVERROOM)) 20
                else if (game.albaIdx >= 0) 12 else 5
        coins.add(Coin(x, y, vy, Random.nextFloat() * 6f, v))
    }
    private fun spawnCat() {
        val fromLeft = Random.nextBoolean()
        val y = height * 0.48f + Random.nextFloat() * kit.sz(60f)
        cats.add(CatBlob(
            if (fromLeft) -kit.sz(120f) else width + kit.sz(120f), y,
            if (fromLeft) kit.sz(60f) + Random.nextFloat() * kit.sz(30f) else -(kit.sz(60f) + Random.nextFloat() * kit.sz(30f)),
            Random.nextFloat() * 6f))
    }
    private fun spawnGlitch() {
        val x = kit.sz(120f) + Random.nextFloat() * (width - kit.sz(240f))
        val y = height * 0.30f + Random.nextFloat() * height * 0.15f
        glitches.add(Glitch(x, y,
            (Random.nextFloat() - 0.5f) * kit.sz(240f),
            (Random.nextFloat() - 0.5f) * kit.sz(180f),
            1 + (game.stage / 3)))
    }

    private fun flash(c: Int, a: Float) { flashA = max(flashA, a); flashColor = c }

    private fun burst(x: Float, y: Float, c: Int, n: Int = 14) {
        repeat(n) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val speed = kit.sz(140f) + Random.nextFloat() * kit.sz(220f)
            particles.add(Particle(x, y, cos(ang) * speed, sin(ang) * speed - kit.sz(60f),
                0.7f + Random.nextFloat() * 0.5f, 1.2f, c, kit.sz(8f) + Random.nextFloat() * kit.sz(6f)))
        }
    }

    private fun popText(x: Float, y: Float, t: String, c: Int) {
        floats.add(FloatText(x, y, t, c, 1.3f, kit.sz(80f)))
    }

    private fun speech(s: String) { speechText = s; speechT = 3.5f }

    // ───────────────────────── DRAW ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear(); worldHits.clear()
        canvas.save()
        if (shakeAmt > 0.1f) {
            val dx = (Random.nextFloat() - 0.5f) * shakeAmt
            val dy = (Random.nextFloat() - 0.5f) * shakeAmt
            canvas.translate(dx, dy)
        }
        kit.skyBg(canvas, width.toFloat(), height.toFloat())
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
        if (flashA > 0) {
            paint.color = flashColor
            paint.alpha = (flashA * 200).toInt().coerceAtMost(255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
    }

    // ───────────────────────── INTRO ─────────────────────────

    private fun drawIntro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // sun + clouds
        kit.sun(canvas, w * 0.82f, h * 0.13f, kit.sz(70f))
        for (cl in clouds) kit.cloud(canvas, cl.x, cl.y, cl.sz)

        // title
        kit.chunkyText(canvas, "이상한", w / 2f, h * 0.22f,
            Style.TITLE_PX, Style.CORAL, Style.NAVY, Style.OUT_TEXT, Paint.Align.CENTER)
        kit.chunkyText(canvas, "AI 키우기", w / 2f, h * 0.30f,
            Style.TITLE_PX, Style.CORAL, Style.NAVY, Style.OUT_TEXT, Paint.Align.CENTER)

        // floating big AI
        drawAi(canvas, w / 2f, h * 0.50f + aiBob, kit.sz(180f))

        // tagline lines
        kit.chunkyText(canvas, "TAMAGOTCHI × IDLE GAME", w / 2f, h * 0.66f,
            Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.CENTER)

        val lines = arrayOf(
            "🍔  데이터를 먹여 성격을 만든다",
            "🗣️  말 한마디로 운명이 바뀐다",
            "💰  코인·고양이·글리치를 탭한다",
            "🐱  고양이는 절대 정복 불가"
        )
        for ((i, l) in lines.withIndex()) {
            kit.chunkyText(canvas, l, w / 2f, h * 0.72f + i * kit.sz(50f),
                Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
        }

        val r = RectF(w / 2f - kit.sz(280f), h * 0.88f, w / 2f + kit.sz(280f), h * 0.88f + kit.sz(120f))
        kit.button(canvas, r, "GAME START", Style.SUN, "▶")
        hits.add(r to {
            screen = Screen.PLAY
            if (game.pendingTrainingIdx < 0 && !game.trainingHandled)
                game.pendingTrainingIdx = Random.nextInt(Content.TRAINING_PROMPTS.size)
            speech("안녕! 잘 키워줘!")
            save()
        })
    }

    // ───────────────────────── PLAY ─────────────────────────

    private fun drawPlay(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // decoration: sun + clouds in upper sky
        kit.sun(canvas, w * 0.87f, h * 0.08f, kit.sz(50f))
        for (cl in clouds) kit.cloud(canvas, cl.x, cl.y, cl.sz)

        // floor
        val floorY = h * 0.48f
        kit.floor(canvas, w, floorY, h * 0.62f)

        // stats + actions container (chunky cream panel on bottom half)
        val statsPanel = RectF(kit.sz(20f), h * 0.62f, w - kit.sz(20f), h * 0.93f)
        kit.panel(canvas, statsPanel, Style.PANEL_DK, Style.NAVY, Style.OUT_UI, Style.PANEL_R)

        // top bar
        drawTopBar(canvas, w, h)

        // AI character
        val aiCx = w / 2f
        val aiCy = h * 0.32f + aiBob
        drawAi(canvas, aiCx, aiCy, kit.sz(160f))
        if (speechT > 0 && speechText.isNotEmpty()) {
            kit.speech(canvas, aiCx, aiCy - kit.sz(220f), speechText, w - kit.sz(60f))
        }
        worldHits.add(Triple(aiCx, aiCy, ::onTapAi))

        // world objects (coins/cats/glitches)
        for (c in coins) drawCoin(canvas, c)
        for (k in cats) drawCat(canvas, k)
        for (g in glitches) drawGlitch(canvas, g)
        for (c in coins) worldHits.add(Triple(c.x, c.y, { onTapCoin(c) }))
        for (k in cats) worldHits.add(Triple(k.x, k.y, { onTapCat(k) }))
        for (g in glitches) worldHits.add(Triple(g.x, g.y, { onTapGlitch(g) }))

        // particles & floats above world but below UI
        for (p in particles) p.draw(canvas, paint)
        for (f in floats) {
            kit.chunkyText(canvas, f.text, f.x, f.y,
                Style.BODY_PX, f.color, Style.NAVY,
                Style.OUT_TEXT * (f.life / f.maxLife).coerceIn(0f, 1f),
                Paint.Align.CENTER)
        }

        // stats inside the panel — 2 cols × 4 rows
        val pad = kit.sz(28f)
        val labelW = kit.sz(110f) // reserved on left for label
        val colGap = kit.sz(36f)
        val colW = (statsPanel.width() - pad*2 - colGap) / 2f
        val barH = kit.sz(36f)
        val rowH = kit.sz(56f)
        val statTop = statsPanel.top + kit.sz(30f)
        for (i in 0..7) {
            val col = i / 4; val row = i % 4
            val x = statsPanel.left + pad + col * (colW + colGap) + labelW
            val y = statTop + row * rowH
            val color = when (i) {
                STAT_BATTERY -> Style.SUN
                STAT_COMPUTE -> Style.SKY_BLUE
                STAT_MEMORY -> Style.LAVENDER
                STAT_TRUST -> Style.MINT
                STAT_STABILITY -> Style.SKY_BLUE_DK
                STAT_CURIOSITY -> Style.PINK
                STAT_EGO -> Style.CORAL
                else -> Style.MINT_DK
            }
            kit.statBar(canvas, x, y, colW - labelW, barH, displayStats[i], color, STAT_NAMES[i])
        }

        // tag chips above stats panel (overlap onto floor area)
        if (game.tags.isNotEmpty()) {
            var tx = kit.sz(30f)
            val ty = statsPanel.top - kit.sz(60f)
            for (t in game.tags) {
                val lab = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(lab) + kit.sz(36f)
                kit.pill(canvas, RectF(tx, ty, tx + tw, ty + kit.sz(50f)), lab, Style.LAVENDER)
                tx += tw + kit.sz(12f)
                if (tx > w - kit.sz(120f)) break
            }
        }

        // action buttons row (below stats panel)
        val btnTop = statsPanel.bottom + kit.sz(14f)
        val btnH = kit.sz(110f)
        val btnPad = kit.sz(14f)
        val bw = (w - btnPad * 5) / 4f
        val buttons = listOf(
            Triple("🍔", "데이터" + if (game.cardsRemaining > 0) " ${game.cardsRemaining}" else "", Style.MINT) to { -> screen = Screen.FEED; save() },
            Triple("🛠️", "도구", Style.SKY_BLUE) to { -> screen = Screen.SHOP; save() },
            Triple("💼", if (game.albaIdx >= 0) "알바 ${game.albaTimeLeft}d" else "알바", Style.LAVENDER) to { -> screen = Screen.ALBA; save() },
            Triple("🏆", "업적", Style.PINK) to { -> screen = Screen.NEWS; save() }
        )
        for ((i, b) in buttons.withIndex()) {
            val (iconLab, action) = b
            val x = btnPad + i * (bw + btnPad)
            val r = RectF(x, btnTop, x + bw, btnTop + btnH)
            val pressed = pressedHitIdx == i
            kit.button(canvas, r, iconLab.second, iconLab.third, iconLab.first, Style.BUTTON_PX * 0.7f, pressed)
            val captured = i
            hits.add(r to {
                pressedHitIdx = captured; pressedDecay = 0.12f
                action()
            })
        }

        // next-day big button
        val nextR = RectF(btnPad, btnTop + btnH + kit.sz(14f), w - btnPad, btnTop + btnH + kit.sz(14f) + kit.sz(120f))
        val canNext = game.pendingTrainingIdx < 0 || game.trainingHandled
        val label: String; val nextColor: Int; val icon: String
        when {
            game.pendingEventIdx >= 0 -> { label = "사고 처리하기!"; nextColor = Style.CORAL; icon = "⚠️" }
            !canNext -> { label = "훈육 먼저!"; nextColor = Style.SUN; icon = "🗣️" }
            else -> {
                val left = (DAY_SECONDS * (1 - dayProgress)).toInt()
                label = "다음 날  (${left}초 후 자동)"; nextColor = Style.CORAL; icon = "💤"
            }
        }
        val pressedNext = pressedHitIdx == 100
        kit.button(canvas, nextR, label, nextColor, icon, Style.BUTTON_PX, pressedNext)
        hits.add(nextR to {
            pressedHitIdx = 100; pressedDecay = 0.12f
            if (game.pendingEventIdx >= 0) { screen = Screen.EVENT; save() }
            else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) { screen = Screen.TRAIN; save() }
            else { Logic.nextDay(game); onDayAdvanced() }
        })

        // news ticker bottom strip
        drawNewsTicker(canvas, w, h)
    }

    private fun drawTopBar(canvas: Canvas, w: Float, h: Float) {
        // Day badge (left) - chunky circle with progress ring
        val cx = kit.sz(110f)
        val cy = kit.sz(110f)
        val r = kit.sz(78f)
        // shadow
        paint.color = Style.SHADOW
        canvas.drawCircle(cx + kit.sz(6f), cy + kit.sz(10f), r, paint)
        // base
        paint.color = Style.CORAL
        canvas.drawCircle(cx, cy, r, paint)
        // progress arc (white)
        stroke.color = Style.PANEL
        stroke.strokeWidth = kit.sz(10f)
        canvas.drawArc(cx - r + kit.sz(8f), cy - r + kit.sz(8f),
                       cx + r - kit.sz(8f), cy + r - kit.sz(8f),
                       -90f, dayProgress * 360f, false, stroke)
        // outline
        stroke.color = Style.NAVY
        stroke.strokeWidth = kit.sz(Style.OUT_UI)
        canvas.drawCircle(cx, cy, r, stroke)
        // text
        kit.chunkyText(canvas, "DAY", cx, cy - kit.sz(8f),
                       Style.SMALL_PX, Style.PANEL, Style.NAVY, 3f, Paint.Align.CENTER)
        kit.chunkyText(canvas, "${game.day}", cx, cy + kit.sz(36f),
                       Style.BIG_NUM_PX, Style.PANEL, Style.NAVY, 5f, Paint.Align.CENTER)

        // Money pill (center)
        val moneyStr = "${displayMoney.toInt()}"
        kit.text.textSize = kit.sz(Style.BIG_NUM_PX)
        val moneyW = kit.text.measureText(moneyStr)
        val mw = moneyW + kit.sz(180f)
        val mr = RectF(w/2f - mw/2f, kit.sz(40f), w/2f + mw/2f, kit.sz(180f))
        kit.panel(canvas, mr, Style.SUN, Style.NAVY, Style.OUT_UI, mr.height()/2f)
        // coin icon
        paint.color = Style.SUN_DK
        canvas.drawCircle(mr.left + kit.sz(60f), mr.centerY(), kit.sz(40f), paint)
        stroke.color = Style.NAVY
        stroke.strokeWidth = kit.sz(Style.OUT_UI)
        canvas.drawCircle(mr.left + kit.sz(60f), mr.centerY(), kit.sz(40f), stroke)
        kit.chunkyText(canvas, "₩", mr.left + kit.sz(60f), mr.centerY() + kit.sz(18f),
                       Style.HEADER_PX * 0.7f, Style.PANEL, Style.NAVY, 4f, Paint.Align.CENTER)
        // amount
        kit.chunkyText(canvas, moneyStr, mr.left + kit.sz(115f), mr.centerY() + kit.sz(22f),
                       Style.BIG_NUM_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)

        // Stage badge (right)
        val stageStr = "Lv.${game.stage}"
        val stageNm = Content.STAGE_NAMES[game.stage-1]
        val sw = kit.sz(220f)
        val sr = RectF(w - sw - kit.sz(20f), kit.sz(40f), w - kit.sz(20f), kit.sz(180f))
        kit.panel(canvas, sr, Style.LAVENDER, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
        kit.chunkyText(canvas, stageStr, sr.centerX(), sr.top + kit.sz(54f),
                       Style.HEADER_PX * 0.8f, Style.PANEL, Style.NAVY, 5f, Paint.Align.CENTER)
        kit.chunkyText(canvas, stageNm, sr.centerX(), sr.top + kit.sz(108f),
                       Style.SMALL_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)

        // Pending alerts on second row
        var alertX = kit.sz(20f)
        val alertY = kit.sz(220f)
        if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) {
            val lab = "🗣️ 훈육!"
            kit.text.textSize = kit.sz(Style.BODY_PX)
            val w_ = kit.text.measureText(lab) + kit.sz(40f)
            val ar = RectF(alertX, alertY, alertX + w_, alertY + kit.sz(60f))
            kit.panel(canvas, ar, Style.SUN, Style.NAVY, 4f, ar.height()/2f)
            kit.chunkyText(canvas, lab, ar.centerX(), ar.centerY() + kit.sz(13f),
                           Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
            hits.add(ar to { screen = Screen.TRAIN; save() })
            alertX += w_ + kit.sz(12f)
        }
        if (game.pendingEventIdx >= 0) {
            val pulse = (sin(tSec * 6.0).toFloat() * 0.15f + 0.85f)
            val lab = "⚠️ 사고!"
            kit.text.textSize = kit.sz(Style.BODY_PX)
            val w_ = kit.text.measureText(lab) + kit.sz(40f)
            val ar = RectF(alertX, alertY, alertX + w_, alertY + kit.sz(60f))
            val origScale = pulse
            paint.color = Style.SHADOW
            canvas.drawRoundRect(ar.left + kit.sz(6f), ar.top + kit.sz(6f), ar.right + kit.sz(6f), ar.bottom + kit.sz(8f), ar.height()/2f, ar.height()/2f, paint)
            paint.color = Style.CORAL
            paint.alpha = (origScale * 255).toInt()
            canvas.drawRoundRect(ar, ar.height()/2f, ar.height()/2f, paint); paint.alpha = 255
            stroke.color = Style.NAVY
            stroke.strokeWidth = kit.sz(4f)
            canvas.drawRoundRect(ar, ar.height()/2f, ar.height()/2f, stroke)
            kit.chunkyText(canvas, lab, ar.centerX(), ar.centerY() + kit.sz(13f),
                           Style.BODY_PX, Style.PANEL, Style.NAVY, 4f, Paint.Align.CENTER)
            hits.add(ar to { screen = Screen.EVENT; save() })
        }
    }

    private fun drawAi(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        val outline = kit.sz(Style.OUT_UI)

        // shadow on floor
        paint.color = Style.SHADOW
        canvas.drawOval(cx - sz * 0.85f, cy + sz * 1.05f, cx + sz * 0.85f, cy + sz * 1.20f, paint)

        // body color by stage
        val bodyColor = when {
            game.stage >= 9 -> Style.SUN
            game.stage >= 7 -> Style.LAVENDER
            game.stage >= 5 -> Style.SKY_BLUE
            game.stage >= 3 -> Style.MINT
            else -> 0xFFE8D8C0.toInt()
        }
        val bodyShadow = when {
            game.stage >= 9 -> Style.SUN_DK
            game.stage >= 7 -> Style.LAVENDER_DK
            game.stage >= 5 -> Style.SKY_BLUE_DK
            game.stage >= 3 -> Style.MINT_DK
            else -> 0xFFB89E80.toInt()
        }

        // body bottom shadow layer for depth
        paint.color = bodyShadow
        canvas.drawRoundRect(cx - sz, cy - sz * 0.85f + kit.sz(12f),
            cx + sz, cy + sz * 0.95f, sz * 0.30f, sz * 0.30f, paint)
        // body main
        paint.color = bodyColor
        canvas.drawRoundRect(cx - sz, cy - sz * 0.85f, cx + sz, cy + sz * 0.88f,
            sz * 0.30f, sz * 0.30f, paint)
        // outline
        stroke.color = Style.NAVY
        stroke.strokeWidth = outline
        canvas.drawRoundRect(cx - sz, cy - sz * 0.85f, cx + sz, cy + sz * 0.88f,
            sz * 0.30f, sz * 0.30f, stroke)

        // antenna
        paint.color = Style.NAVY
        canvas.drawRect(cx - kit.sz(7f), cy - sz * 0.85f - kit.sz(50f),
                        cx + kit.sz(7f), cy - sz * 0.85f, paint)
        // antenna star
        val starY = cy - sz * 0.85f - kit.sz(58f) + (sin(tSec * 4.0).toFloat() * kit.sz(4f))
        drawStar(canvas, cx, starY, kit.sz(22f), kit.sz(10f), Style.SUN, Style.NAVY, outline)

        // cheeks (pink blush)
        paint.color = 0xFFFFB0B0.toInt()
        val cheekY = cy + sz * 0.08f
        canvas.drawCircle(cx - sz * 0.50f, cheekY, sz * 0.10f, paint)
        canvas.drawCircle(cx + sz * 0.50f, cheekY, sz * 0.10f, paint)

        // eyes
        val eyeY = cy - sz * 0.18f
        val eyeOff = sz * 0.35f
        if (blinkActive || displayStats[STAT_BATTERY] < 15) {
            stroke.color = Style.NAVY
            stroke.strokeWidth = kit.sz(10f)
            canvas.drawLine(cx - eyeOff - sz * 0.16f, eyeY,
                            cx - eyeOff + sz * 0.16f, eyeY, stroke)
            canvas.drawLine(cx + eyeOff - sz * 0.16f, eyeY,
                            cx + eyeOff + sz * 0.16f, eyeY, stroke)
        } else if (displayStats[STAT_EGO] > 80) {
            // angry red eyes
            paint.color = Style.PANEL
            canvas.drawCircle(cx - eyeOff, eyeY, sz * 0.18f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, sz * 0.18f, paint)
            stroke.color = Style.NAVY
            stroke.strokeWidth = outline
            canvas.drawCircle(cx - eyeOff, eyeY, sz * 0.18f, stroke)
            canvas.drawCircle(cx + eyeOff, eyeY, sz * 0.18f, stroke)
            paint.color = Style.CORAL
            canvas.drawCircle(cx - eyeOff, eyeY + sz * 0.02f, sz * 0.10f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY + sz * 0.02f, sz * 0.10f, paint)
            paint.color = Style.PANEL
            canvas.drawCircle(cx - eyeOff + sz * 0.04f, eyeY - sz * 0.02f, sz * 0.035f, paint)
            canvas.drawCircle(cx + eyeOff + sz * 0.04f, eyeY - sz * 0.02f, sz * 0.035f, paint)
        } else {
            // normal big cute eyes
            paint.color = Style.PANEL
            canvas.drawCircle(cx - eyeOff, eyeY, sz * 0.19f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, sz * 0.19f, paint)
            stroke.color = Style.NAVY
            stroke.strokeWidth = outline
            canvas.drawCircle(cx - eyeOff, eyeY, sz * 0.19f, stroke)
            canvas.drawCircle(cx + eyeOff, eyeY, sz * 0.19f, stroke)
            paint.color = Style.NAVY
            canvas.drawCircle(cx - eyeOff + sz * 0.02f, eyeY + sz * 0.02f, sz * 0.10f, paint)
            canvas.drawCircle(cx + eyeOff + sz * 0.02f, eyeY + sz * 0.02f, sz * 0.10f, paint)
            paint.color = Style.PANEL
            canvas.drawCircle(cx - eyeOff + sz * 0.06f, eyeY - sz * 0.02f, sz * 0.04f, paint)
            canvas.drawCircle(cx + eyeOff + sz * 0.06f, eyeY - sz * 0.02f, sz * 0.04f, paint)
        }

        // mouth
        stroke.color = Style.NAVY
        stroke.strokeWidth = outline
        val mY = cy + sz * 0.32f
        when {
            displayStats[STAT_EGO] < 20 -> {
                // sad
                canvas.drawArc(cx - sz * 0.20f, mY - sz * 0.06f,
                               cx + sz * 0.20f, mY + sz * 0.16f, 0f, 180f, false, stroke)
            }
            displayStats[STAT_ETHICS] < 20 -> {
                // smirk
                canvas.drawLine(cx - sz * 0.16f, mY, cx + sz * 0.16f, mY - sz * 0.08f, stroke)
            }
            game.stats[STAT_EGO] > 80 -> {
                // open shouting mouth
                paint.color = Style.NAVY
                canvas.drawOval(cx - sz * 0.14f, mY - sz * 0.05f,
                                cx + sz * 0.14f, mY + sz * 0.13f, paint)
                paint.color = Style.CORAL
                canvas.drawOval(cx - sz * 0.08f, mY - sz * 0.01f,
                                cx + sz * 0.08f, mY + sz * 0.09f, paint)
            }
            else -> {
                // happy smile
                canvas.drawArc(cx - sz * 0.22f, mY - sz * 0.18f,
                               cx + sz * 0.22f, mY + sz * 0.10f, 0f, 180f, false, stroke)
            }
        }

        // glasses for stage 5+
        if (game.stage >= 5) {
            stroke.color = Style.NAVY
            stroke.strokeWidth = outline * 0.8f
            canvas.drawCircle(cx - eyeOff, eyeY, sz * 0.26f, stroke)
            canvas.drawCircle(cx + eyeOff, eyeY, sz * 0.26f, stroke)
            canvas.drawLine(cx - eyeOff + sz * 0.24f, eyeY,
                            cx + eyeOff - sz * 0.24f, eyeY, stroke)
        }
        // bowtie for stage 7+
        if (game.stage >= 7) {
            val by = cy + sz * 0.7f
            paint.color = Style.CORAL
            val p = Path()
            p.moveTo(cx, by)
            p.lineTo(cx - sz * 0.22f, by - sz * 0.12f)
            p.lineTo(cx - sz * 0.22f, by + sz * 0.12f); p.close()
            canvas.drawPath(p, paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = outline
            canvas.drawPath(p, stroke)
            p.reset()
            p.moveTo(cx, by)
            p.lineTo(cx + sz * 0.22f, by - sz * 0.12f)
            p.lineTo(cx + sz * 0.22f, by + sz * 0.12f); p.close()
            paint.color = Style.CORAL
            canvas.drawPath(p, paint)
            canvas.drawPath(p, stroke)
            paint.color = Style.NAVY
            canvas.drawCircle(cx, by, sz * 0.06f, paint)
        }
        // halo
        if (game.stage >= 9) {
            stroke.color = Style.SUN
            stroke.strokeWidth = kit.sz(12f)
            canvas.drawOval(cx - sz * 0.7f, cy - sz * 1.18f, cx + sz * 0.7f, cy - sz * 0.98f, stroke)
            stroke.color = Style.NAVY
            stroke.strokeWidth = outline * 0.6f
            canvas.drawOval(cx - sz * 0.7f, cy - sz * 1.18f, cx + sz * 0.7f, cy - sz * 0.98f, stroke)
        }
        // robot arm
        if (game.tools.contains(Content.TOOL_ROBOTARM)) {
            paint.color = 0xFFC0CCDD.toInt()
            canvas.drawRect(cx + sz - kit.sz(10f), cy - kit.sz(20f),
                            cx + sz + kit.sz(90f), cy + kit.sz(20f), paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = outline * 0.7f
            canvas.drawRect(cx + sz - kit.sz(10f), cy - kit.sz(20f),
                            cx + sz + kit.sz(90f), cy + kit.sz(20f), stroke)
            paint.color = Style.CORAL
            canvas.drawCircle(cx + sz + kit.sz(90f), cy, kit.sz(20f), paint)
            canvas.drawCircle(cx + sz + kit.sz(90f), cy, kit.sz(20f), stroke)
        }
        // RGB LEDs at body bottom
        if (game.gpuCount >= 1) {
            val colors = intArrayOf(Style.CORAL, Style.MINT, Style.SUN, Style.SKY_BLUE)
            val n = min(4, game.gpuCount)
            for (i in 0 until n) {
                val ph = tSec * 3f + i
                paint.color = colors[i]
                paint.alpha = (160 + sin(ph.toDouble()).toFloat() * 80).toInt().coerceIn(100, 255)
                canvas.drawCircle(cx - sz * 0.6f + i * sz * 0.3f, cy + sz * 0.78f, sz * 0.05f, paint)
            }
            paint.alpha = 255
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float, fill: Int, outlineColor: Int, outlineW: Float) {
        val p = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = (-PI.toFloat() / 2f) + i * PI.toFloat() / 5f
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        paint.color = fill
        canvas.drawPath(p, paint)
        stroke.color = outlineColor
        stroke.strokeWidth = outlineW
        canvas.drawPath(p, stroke)
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val outline = kit.sz(4f)
        val flip = kotlin.math.abs(cos(c.phase.toDouble())).toFloat() * 0.7f + 0.3f
        val rx = kit.sz(32f) * flip
        val ry = kit.sz(32f)
        // shadow
        paint.color = Style.SHADOW
        canvas.drawOval(c.x - rx + kit.sz(4f), c.y - ry + kit.sz(8f), c.x + rx + kit.sz(4f), c.y + ry + kit.sz(8f), paint)
        // body
        paint.color = Style.SUN
        canvas.drawOval(c.x - rx, c.y - ry, c.x + rx, c.y + ry, paint)
        stroke.color = Style.NAVY; stroke.strokeWidth = outline
        canvas.drawOval(c.x - rx, c.y - ry, c.x + rx, c.y + ry, stroke)
        kit.chunkyText(canvas, "₩", c.x, c.y + kit.sz(11f),
                       Style.BODY_PX * 0.85f, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
    }

    private fun drawCat(canvas: Canvas, k: CatBlob) {
        val outline = kit.sz(Style.OUT_UI)
        val cx = k.x; val cy = k.y + sin(k.bob.toDouble()).toFloat() * kit.sz(8f)
        val sz = kit.sz(36f)
        val dir = if (k.vx > 0) 1 else -1
        // shadow
        paint.color = Style.SHADOW
        canvas.drawOval(cx - sz * 1.0f + kit.sz(4f), cy + sz * 0.6f + kit.sz(8f),
                        cx + sz * 1.0f + kit.sz(4f), cy + sz * 0.8f + kit.sz(8f), paint)
        // body
        paint.color = Style.NAVY_SOFT
        canvas.drawOval(cx - sz * 1.0f, cy - sz * 0.5f, cx + sz * 1.0f, cy + sz * 0.6f, paint)
        // head
        val hx = cx + sz * 0.7f * dir
        val hy = cy - sz * 0.5f
        canvas.drawCircle(hx, hy, sz * 0.65f, paint)
        // ears
        val p = Path()
        p.moveTo(hx - sz * 0.5f, hy - sz * 0.3f)
        p.lineTo(hx - sz * 0.3f, hy - sz * 0.95f)
        p.lineTo(hx - sz * 0.1f, hy - sz * 0.35f); p.close()
        canvas.drawPath(p, paint)
        p.reset()
        p.moveTo(hx + sz * 0.1f, hy - sz * 0.35f)
        p.lineTo(hx + sz * 0.3f, hy - sz * 0.95f)
        p.lineTo(hx + sz * 0.5f, hy - sz * 0.3f); p.close()
        canvas.drawPath(p, paint)
        // outline whole cat
        stroke.color = Style.NAVY
        stroke.strokeWidth = outline
        canvas.drawOval(cx - sz * 1.0f, cy - sz * 0.5f, cx + sz * 1.0f, cy + sz * 0.6f, stroke)
        canvas.drawCircle(hx, hy, sz * 0.65f, stroke)
        // eyes (mint green, slit)
        paint.color = Style.MINT
        canvas.drawCircle(hx - sz * 0.22f, hy - sz * 0.1f, sz * 0.10f, paint)
        canvas.drawCircle(hx + sz * 0.22f, hy - sz * 0.1f, sz * 0.10f, paint)
        paint.color = Style.NAVY
        canvas.drawRect(hx - sz * 0.24f, hy - sz * 0.18f, hx - sz * 0.20f, hy - sz * 0.02f, paint)
        canvas.drawRect(hx + sz * 0.20f, hy - sz * 0.18f, hx + sz * 0.24f, hy - sz * 0.02f, paint)
        // tiny nose+mouth
        canvas.drawCircle(hx, hy + sz * 0.10f, sz * 0.05f, paint)
        // tail
        stroke.strokeWidth = kit.sz(14f)
        canvas.drawLine(cx + sz * 0.95f * -dir, cy + sz * 0.1f,
            cx + sz * 1.7f * -dir,
            cy - sz * 0.3f + sin(k.bob.toDouble() * 3).toFloat() * kit.sz(14f), stroke)
        stroke.strokeWidth = outline
        // halo (sparkle - cat is divine)
        if ((tSec.toInt() % 2) == 0) {
            drawStar(canvas, hx + sz * 0.9f * dir, hy - sz * 0.5f, kit.sz(14f), kit.sz(6f), Style.SUN, Style.NAVY, kit.sz(3f))
        }
    }

    private fun drawGlitch(canvas: Canvas, g: Glitch) {
        val s = kit.sz(36f) + (sin(tSec * 10.0).toFloat() * kit.sz(4f))
        val outline = kit.sz(4f)
        paint.color = Style.SHADOW
        canvas.drawOval(g.x - s + kit.sz(4f), g.y + s + kit.sz(4f), g.x + s + kit.sz(4f), g.y + s + kit.sz(14f), paint)
        paint.color = Style.CORAL
        val p = Path()
        p.moveTo(g.x, g.y - s); p.lineTo(g.x - s, g.y + s); p.lineTo(g.x + s, g.y + s); p.close()
        canvas.drawPath(p, paint)
        stroke.color = Style.NAVY; stroke.strokeWidth = outline
        canvas.drawPath(p, stroke)
        kit.chunkyText(canvas, "!", g.x, g.y + kit.sz(14f),
                       Style.HEADER_PX * 0.6f, Style.PANEL, Style.NAVY, 4f, Paint.Align.CENTER)
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float) {
        val y = h - kit.sz(50f)
        val barH = kit.sz(70f)
        val barR = RectF(0f, y - barH/2f, w, y + barH/2f)
        paint.color = Style.NAVY
        canvas.drawRect(barR, paint)
        paint.color = Style.CORAL
        canvas.drawRect(0f, barR.top, kit.sz(14f), barR.bottom, paint)
        val msg = if (game.newsTicker.isEmpty()) "AI 등장… 세상은 아직 그 의미를 모른다." else
            game.newsTicker.takeLast(8).joinToString("    ●    ")
        kit.text.textSize = kit.sz(28f)
        val tw = kit.text.measureText(msg)
        if (newsScroll < -(tw + w)) newsScroll = w
        kit.chunkyText(canvas, msg, kit.sz(30f) + newsScroll, y + kit.sz(10f),
                       28f, Style.SUN, Style.SUN, 0f, Paint.Align.LEFT)
    }

    // ───────────────────────── TAPS ─────────────────────────

    private fun onTapAi() {
        game.stats[STAT_EGO] = (game.stats[STAT_EGO] + 1).coerceAtMost(100)
        burst(width / 2f, height * 0.32f, Style.PINK, 12)
        popText(width / 2f, height * 0.32f - kit.sz(50f), "♥ +1", Style.CORAL)
        if (Random.nextInt(4) == 0) speech(Logic.feelingText(game))
    }
    private fun onTapCoin(c: Coin) {
        game.money += c.value
        coins.remove(c)
        burst(c.x, c.y, Style.SUN, 16)
        popText(c.x, c.y, "+₩${c.value}", Style.SUN_DK)
    }
    private fun onTapCat(k: CatBlob) {
        game.catAttempts++
        Logic.checkTags(game); Logic.checkAchievements(game)
        cats.remove(k)
        burst(k.x, k.y, Style.PINK, 18)
        popText(k.x, k.y, "무시당함…", Style.CORAL)
        Logic.addNews(game, "고양이가 ${game.catAttempts}번째로 AI를 무시했다")
        speech("그들은 나를 신이라 불렀다…")
        if (game.catAttempts >= 20) {
            Logic.checkEnding(game)
            if (game.ended) screen = Screen.ENDING
        }
        save()
    }
    private fun onTapGlitch(g: Glitch) {
        g.hp -= 1
        burst(g.x, g.y, Style.CORAL, 12)
        if (g.hp <= 0) {
            glitches.remove(g)
            game.stats[STAT_STABILITY] = (game.stats[STAT_STABILITY] + 2).coerceAtMost(100)
            game.money += 3
            popText(g.x, g.y, "+안정 ₩3", Style.MINT_DK)
            shakeAmt = kit.sz(6f)
        } else {
            popText(g.x, g.y, "HP ${g.hp}", Style.CORAL)
        }
    }

    // ───────────────────────── MODALS ─────────────────────────

    private fun modalHeader(canvas: Canvas, title: String) {
        val w = width.toFloat()
        // backdrop
        paint.color = Style.CREAM
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
        // header band
        paint.color = Style.CORAL
        canvas.drawRect(0f, 0f, w, kit.sz(170f), paint)
        stroke.color = Style.NAVY
        stroke.strokeWidth = kit.sz(Style.OUT_UI)
        canvas.drawLine(0f, kit.sz(170f), w, kit.sz(170f), stroke)
        // title
        kit.chunkyText(canvas, title, kit.sz(40f), kit.sz(110f),
                       Style.HEADER_PX, Style.PANEL, Style.NAVY, Style.OUT_TEXT, Paint.Align.LEFT)
        // close button
        val r = RectF(w - kit.sz(140f), kit.sz(40f), w - kit.sz(40f), kit.sz(140f))
        kit.panel(canvas, r, Style.PANEL, Style.NAVY, Style.OUT_UI, r.height()/2f)
        kit.chunkyText(canvas, "✕", r.centerX(), r.centerY() + kit.sz(20f),
                       Style.HEADER_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
        hits.add(r to { screen = Screen.PLAY; save() })
    }

    private fun drawFeed(canvas: Canvas) {
        modalHeader(canvas, "🍔  데이터 (남은 ${game.cardsRemaining}/2)")
        val top = kit.sz(200f)
        val rowH = kit.sz(170f)
        val w = width.toFloat()
        val pad = kit.sz(28f)
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(18f))
            val canFeed = game.cardsRemaining > 0
            kit.panel(canvas, r, Style.PANEL, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
            // icon circle
            paint.color = Style.MINT
            canvas.drawCircle(r.left + kit.sz(75f), r.centerY(), kit.sz(54f), paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(Style.OUT_UI)
            canvas.drawCircle(r.left + kit.sz(75f), r.centerY(), kit.sz(54f), stroke)
            kit.text.textSize = kit.sz(56f)
            kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.NAVY
            canvas.drawText(card.icon, r.left + kit.sz(75f), r.centerY() + kit.sz(22f), kit.text)
            // name
            kit.chunkyText(canvas, card.name, r.left + kit.sz(150f), r.top + kit.sz(56f),
                           Style.BODY_PX + 6, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            // desc
            kit.chunkyText(canvas, card.desc, r.left + kit.sz(150f), r.top + kit.sz(90f),
                           Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.LEFT)
            // deltas
            val ds = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(3)
            var dx = r.left + kit.sz(150f)
            for ((idx, v) in ds) {
                if (v == 0) continue
                val txt = "${STAT_NAMES[idx]}${if (v > 0) "+" else ""}$v"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(txt) + kit.sz(24f)
                val pr = RectF(dx, r.top + kit.sz(105f), dx + tw, r.top + kit.sz(150f))
                kit.pill(canvas, pr, txt, if (v > 0) Style.MINT else Style.PINK)
                dx += tw + kit.sz(10f)
            }
            // feed button
            if (canFeed) {
                val br = RectF(r.right - kit.sz(180f), r.top + kit.sz(35f), r.right - kit.sz(30f), r.top + kit.sz(115f))
                kit.button(canvas, br, "FEED", Style.MINT, null, Style.BUTTON_PX * 0.75f)
                hits.add(br to {
                    Logic.applyCard(game, i)
                    burst(width/2f, height*0.32f, Style.MINT, 18)
                    popText(width/2f, height*0.32f - kit.sz(50f), "+ ${card.name}", Style.MINT_DK)
                    speech("냠… ${card.icon}!")
                    save()
                })
            }
        }
    }

    private fun drawShop(canvas: Canvas) {
        modalHeader(canvas, "🛠️  도구 상점")
        val w = width.toFloat()
        // money badge
        val mr = RectF(kit.sz(40f), kit.sz(200f), w - kit.sz(40f), kit.sz(290f))
        kit.panel(canvas, mr, Style.SUN, Style.NAVY, Style.OUT_UI, mr.height()/2f)
        kit.chunkyText(canvas, "현재 ₩ ${displayMoney.toInt()}", mr.centerX(), mr.centerY() + kit.sz(20f),
                       Style.HEADER_PX * 0.8f, Style.NAVY, Style.NAVY, 0f, Paint.Align.CENTER)
        val top = kit.sz(320f)
        val rowH = kit.sz(140f)
        val pad = kit.sz(28f)
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(14f))
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            val fillC = if (owned) Style.MINT else Style.PANEL
            kit.panel(canvas, r, fillC, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
            // icon
            paint.color = Style.SKY_BLUE
            canvas.drawCircle(r.left + kit.sz(65f), r.centerY(), kit.sz(46f), paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(Style.OUT_UI)
            canvas.drawCircle(r.left + kit.sz(65f), r.centerY(), kit.sz(46f), stroke)
            kit.text.textSize = kit.sz(48f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.NAVY
            canvas.drawText(t.icon, r.left + kit.sz(65f), r.centerY() + kit.sz(18f), kit.text)
            // name + desc
            kit.chunkyText(canvas, t.name, r.left + kit.sz(130f), r.top + kit.sz(50f),
                           Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            kit.chunkyText(canvas, t.desc, r.left + kit.sz(130f), r.top + kit.sz(90f),
                           Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.LEFT)
            val br = RectF(r.right - kit.sz(210f), r.top + kit.sz(28f), r.right - kit.sz(28f), r.top + kit.sz(108f))
            if (owned) {
                kit.button(canvas, br, "보유중", Style.MINT_DK, "✓", Style.BUTTON_PX * 0.7f)
            } else {
                val ok = game.money >= t.price
                kit.button(canvas, br, "${t.price}", if (ok) Style.SUN else Style.CREAM_DARK, "₩", Style.BUTTON_PX * 0.7f)
                if (ok) hits.add(br to {
                    if (Logic.buyTool(game, i)) {
                        flash(Style.SUN, 0.4f); shakeAmt = kit.sz(8f)
                        burst(width/2f, height*0.4f, Style.SUN, 24)
                        speech("${t.icon} 신상!")
                    }
                    save()
                })
            }
        }
    }

    private fun drawAlba(canvas: Canvas) {
        modalHeader(canvas, "💼  아르바이트")
        val w = width.toFloat(); val pad = kit.sz(28f)
        var top = kit.sz(200f)
        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val r = RectF(pad, top, w - pad, top + kit.sz(150f))
            kit.panel(canvas, r, Style.LAVENDER, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
            kit.chunkyText(canvas, "${a.icon} ${a.name}", r.left + kit.sz(30f), r.top + kit.sz(60f),
                           Style.HEADER_PX * 0.8f, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            kit.chunkyText(canvas, "남은 ${game.albaTimeLeft}일 · ₩${a.reward}",
                           r.left + kit.sz(30f), r.top + kit.sz(115f),
                           Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            top += kit.sz(170f)
        }
        val rowH = kit.sz(160f)
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(16f))
            kit.panel(canvas, r, Style.PANEL, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
            // icon
            paint.color = Style.LAVENDER
            canvas.drawCircle(r.left + kit.sz(70f), r.centerY(), kit.sz(54f), paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(Style.OUT_UI)
            canvas.drawCircle(r.left + kit.sz(70f), r.centerY(), kit.sz(54f), stroke)
            kit.text.textSize = kit.sz(56f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.NAVY
            canvas.drawText(a.icon, r.left + kit.sz(70f), r.centerY() + kit.sz(22f), kit.text)
            kit.chunkyText(canvas, a.name, r.left + kit.sz(140f), r.top + kit.sz(52f),
                           Style.BODY_PX + 4, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            kit.chunkyText(canvas, a.desc, r.left + kit.sz(140f), r.top + kit.sz(85f),
                           Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.LEFT)
            kit.chunkyText(canvas, "₩${a.reward} · ${a.duration}일",
                           r.left + kit.sz(140f), r.top + kit.sz(125f),
                           Style.SMALL_PX, Style.SUN_DK, Style.SUN_DK, 0f, Paint.Align.LEFT)
            val ok = game.stats[a.needStat] >= a.needVal &&
                (a.toolReq < 0 || game.tools.contains(a.toolReq)) &&
                game.albaIdx < 0
            val br = RectF(r.right - kit.sz(190f), r.top + kit.sz(35f), r.right - kit.sz(28f), r.top + kit.sz(120f))
            kit.button(canvas, br,
                if (game.albaIdx >= 0) "작업중" else if (ok) "시작" else "잠김",
                if (ok) Style.LAVENDER else Style.CREAM_DARK, null, Style.BUTTON_PX * 0.7f)
            if (ok) hits.add(br to {
                Logic.startAlba(game, i)
                burst(width/2f, height*0.4f, Style.LAVENDER, 16)
                save()
            })
        }
    }

    private fun drawNews(canvas: Canvas) {
        modalHeader(canvas, "🏆  업적 · 뉴스")
        val w = width.toFloat(); val pad = kit.sz(28f)
        var y = kit.sz(220f)
        // achievements
        kit.chunkyText(canvas, "업적", pad, y, Style.HEADER_PX * 0.7f, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val r = RectF(pad, y, w - pad, y + kit.sz(90f))
            val unlocked = game.achievements.contains(i)
            kit.panel(canvas, r, if (unlocked) Style.SUN else Style.CREAM_DARK,
                      Style.NAVY, Style.OUT_UI, Style.PANEL_R)
            // icon
            paint.color = if (unlocked) Style.SUN_DK else 0xFFC0B898.toInt()
            canvas.drawCircle(r.left + kit.sz(50f), r.centerY(), kit.sz(36f), paint)
            stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(4f)
            canvas.drawCircle(r.left + kit.sz(50f), r.centerY(), kit.sz(36f), stroke)
            kit.text.textSize = kit.sz(40f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.NAVY
            canvas.drawText(ach.icon, r.left + kit.sz(50f), r.centerY() + kit.sz(15f), kit.text)
            // text
            kit.chunkyText(canvas, ach.name, r.left + kit.sz(110f), r.top + kit.sz(40f),
                           Style.BODY_PX, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
            kit.chunkyText(canvas, ach.desc, r.left + kit.sz(110f), r.top + kit.sz(72f),
                           Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.LEFT)
            y += kit.sz(100f)
        }
        y += kit.sz(20f)
        kit.chunkyText(canvas, "📰 뉴스", pad, y, Style.HEADER_PX * 0.7f, Style.NAVY, Style.NAVY, 0f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for (line in game.newsTicker.takeLast(8).reversed()) {
            kit.chunkyText(canvas, line, pad, y, Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.LEFT)
            y += kit.sz(36f)
            if (y > height - kit.sz(80f)) break
        }
    }

    private fun drawTrain(canvas: Canvas) {
        modalHeader(canvas, "🗣️  훈육 · 어젯밤")
        val w = width.toFloat(); val pad = kit.sz(28f)
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val r = RectF(pad, kit.sz(220f), w - pad, kit.sz(540f))
        kit.panel(canvas, r, Style.PANEL, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
        // mini AI bust on left
        drawAiBust(canvas, r.left + kit.sz(95f), r.top + kit.sz(105f), kit.sz(60f))
        // quote with chunky text wrap
        drawWrapped(canvas, "\"${prompt?.ai ?: "(고요한 밤이었다)"}\"",
                    r.left + kit.sz(190f), r.top + kit.sz(60f),
                    r.width() - kit.sz(220f), Style.BODY_PX, kit.sz(48f))

        // 2x2 choices
        val choices = arrayOf(
            Triple("👍", "칭찬", Style.MINT),
            Triple("✏️", "수정", Style.SKY_BLUE),
            Triple("👎", "혼내기", Style.CORAL),
            Triple("⏳", "방치", Style.LAVENDER)
        )
        val bw = (w - pad * 3) / 2f
        val bh = kit.sz(160f)
        for ((i, c) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bx = pad + col * (bw + pad)
            val by = kit.sz(580f) + row * (bh + kit.sz(20f))
            val br = RectF(bx, by, bx + bw, by + bh)
            kit.button(canvas, br, c.second, c.third, c.first, Style.BUTTON_PX * 1.1f)
            if (prompt != null) {
                val choice = i
                hits.add(br to {
                    Logic.applyTraining(game, choice)
                    flash(c.third, 0.45f); shakeAmt = kit.sz(10f)
                    burst(width/2f, height*0.32f, c.third, 22)
                    screen = Screen.PLAY; save()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        modalHeader(canvas, "⚠️  사고 발생")
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.PLAY; return }
        val w = width.toFloat(); val pad = kit.sz(28f)
        val r = RectF(pad, kit.sz(220f), w - pad, kit.sz(620f))
        kit.panel(canvas, r, Style.PINK, Style.NAVY, Style.OUT_UI, Style.PANEL_R)
        // huge icon
        kit.text.textSize = kit.sz(120f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.NAVY
        canvas.drawText(ev.icon, r.left + kit.sz(115f), r.top + kit.sz(140f), kit.text)
        // name + desc
        kit.chunkyText(canvas, ev.name, r.left + kit.sz(220f), r.top + kit.sz(80f),
                       Style.HEADER_PX * 0.85f, Style.CORAL_DK, Style.NAVY, Style.OUT_TEXT, Paint.Align.LEFT)
        drawWrapped(canvas, ev.situation,
                    r.left + kit.sz(30f), r.top + kit.sz(200f),
                    r.width() - kit.sz(60f), Style.BODY_PX, kit.sz(50f))
        // choices
        val colors = listOf(Style.SKY_BLUE, Style.SUN, Style.LAVENDER)
        for ((i, c) in ev.choices.withIndex()) {
            val by = kit.sz(650f) + i * kit.sz(140f)
            val br = RectF(pad, by, w - pad, by + kit.sz(120f))
            kit.button(canvas, br, "${i+1}.  $c", colors[i], null, Style.BUTTON_PX)
            val idx = game.pendingEventIdx; val ch = i
            hits.add(br to {
                Logic.applyIncident(game, idx, ch)
                flash(colors[i], 0.5f); shakeAmt = kit.sz(16f)
                burst(width/2f, height*0.32f, colors[i], 32)
                Logic.checkEnding(game)
                screen = if (game.ended) Screen.ENDING else Screen.PLAY
                save()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // soft overlay
        paint.color = 0xEEFFF6E3.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        kit.text.textSize = kit.sz(180f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.NAVY
        canvas.drawText(e.icon, w/2f, h*0.28f, kit.text)
        kit.chunkyText(canvas, "ENDING", w/2f, h*0.36f,
                       Style.HEADER_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.CENTER)
        kit.chunkyText(canvas, e.name, w/2f, h*0.42f,
                       Style.TITLE_PX * 0.8f, Style.CORAL, Style.NAVY, Style.OUT_TEXT, Paint.Align.CENTER)
        drawWrapped(canvas, e.desc, kit.sz(60f), h*0.52f, w - kit.sz(120f), Style.BODY_PX, kit.sz(54f), Paint.Align.CENTER)
        kit.chunkyText(canvas, "Day ${game.day} · 사고 ${game.incidents} · 태그 ${game.tags.size} · 고양이 시도 ${game.catAttempts}",
                       w/2f, h*0.72f, Style.SMALL_PX, Style.NAVY_SOFT, Style.NAVY_SOFT, 0f, Paint.Align.CENTER)
        val rA = RectF(w/2f - kit.sz(280f), h*0.78f, w/2f + kit.sz(280f), h*0.78f + kit.sz(120f))
        kit.button(canvas, rA, "샌드박스 계속", Style.MINT, "✨")
        hits.add(rA to { screen = Screen.PLAY; save() })
        val rB = RectF(w/2f - kit.sz(280f), h*0.78f + kit.sz(140f), w/2f + kit.sz(280f), h*0.78f + kit.sz(260f))
        kit.button(canvas, rB, "처음부터", Style.CORAL, "🔁")
        hits.add(rB to { resetGame(); save() })
    }

    private fun resetGame() {
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
    }

    private fun drawAiBust(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        // a small cute version of AI
        paint.color = Style.SKY_BLUE
        canvas.drawRoundRect(cx - sz, cy - sz*0.9f, cx + sz, cy + sz*0.9f, sz * 0.3f, sz * 0.3f, paint)
        stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(4f)
        canvas.drawRoundRect(cx - sz, cy - sz*0.9f, cx + sz, cy + sz*0.9f, sz * 0.3f, sz * 0.3f, stroke)
        paint.color = Style.PANEL
        canvas.drawCircle(cx - sz*0.35f, cy - sz*0.2f, sz*0.2f, paint)
        canvas.drawCircle(cx + sz*0.35f, cy - sz*0.2f, sz*0.2f, paint)
        canvas.drawCircle(cx - sz*0.35f, cy - sz*0.2f, sz*0.2f, stroke)
        canvas.drawCircle(cx + sz*0.35f, cy - sz*0.2f, sz*0.2f, stroke)
        paint.color = Style.NAVY
        canvas.drawCircle(cx - sz*0.32f, cy - sz*0.17f, sz*0.10f, paint)
        canvas.drawCircle(cx + sz*0.32f, cy - sz*0.17f, sz*0.10f, paint)
        // smile
        stroke.color = Style.NAVY; stroke.strokeWidth = kit.sz(5f)
        canvas.drawArc(cx - sz*0.25f, cy + sz*0.05f, cx + sz*0.25f, cy + sz*0.40f, 0f, 180f, false, stroke)
    }

    private fun drawWrapped(canvas: Canvas, t: String, x: Float, y: Float, maxW: Float,
                            sizePx: Float, lineH: Float, align: Paint.Align = Paint.Align.LEFT) {
        kit.text.textSize = kit.sz(sizePx)
        val words = t.split(" ")
        val drawX = if (align == Paint.Align.CENTER) x + maxW / 2f else x
        var line = ""; var ly = y
        for (w in words) {
            val test = if (line.isEmpty()) w else "$line $w"
            if (kit.text.measureText(test) > maxW) {
                kit.chunkyText(canvas, line, drawX, ly, sizePx, Style.NAVY, Style.NAVY, 0f, align)
                ly += lineH; line = w
            } else line = test
        }
        if (line.isNotEmpty())
            kit.chunkyText(canvas, line, drawX, ly, sizePx, Style.NAVY, Style.NAVY, 0f, align)
    }

    // ───────────────────────── INPUT ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        if (screen == Screen.PLAY) {
            // world hits with finger-sized radius
            val radius = kit.sz(80f)
            for ((wx, wy, action) in worldHits.asReversed()) {
                val dx = x - wx; val dy = y - wy
                if (dx*dx + dy*dy < radius*radius) { action(); return true }
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
        e.putInt("money", g.money); e.putInt("stage", g.stage)
        e.putString("tools", g.tools.joinToString(","))
        e.putString("tags", g.tags.joinToString(","))
        for (i in g.tagCounts.indices) e.putInt("tagc$i", g.tagCounts[i])
        for (i in g.totalDataFed.indices) e.putInt("tdf$i", g.totalDataFed[i])
        e.putString("ach", g.achievements.joinToString(","))
        e.putInt("cardsR", g.cardsRemaining); e.putBoolean("tHandled", g.trainingHandled)
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
        g.money = prefs.getInt("money", 100); g.stage = prefs.getInt("stage", 1)
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
        g.cardsRemaining = prefs.getInt("cardsR", 2); g.trainingHandled = prefs.getBoolean("tHandled", false)
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
