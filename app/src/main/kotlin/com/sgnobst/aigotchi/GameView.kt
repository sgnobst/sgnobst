package com.sgnobst.aigotchi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : View(context) {

    enum class Screen { INTRO, PLAY, FEED, SHOP, ALBA, NEWS, TRAIN, EVENT, ENDING }

    // Rooms: visual + small flavor
    private val ROOM_NAMES = arrayOf("침실", "서버실", "고양이방", "옥상")
    private val ROOM_ICONS = arrayOf("🛏️", "🖥️", "🐱", "🌙")
    private var roomIdx = 0

    private val game = GameState()
    private var screen: Screen = Screen.INTRO

    private val kit = StyleKit()
    private val paint = Paint()           // pixel-friendly, no AA
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
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

    // Stars for night sky
    private var stars: MutableList<FloatArray> = mutableListOf()
    private data class PxCloud(var x: Float, val y: Float, val px: Float, val speed: Float)
    private val clouds = mutableListOf<PxCloud>()

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
        // generate sprite-sized stars
        stars = Stars.gen(60, w, h, kit.pxU * 0.5f)
        // spawn pixel clouds
        clouds.clear()
        repeat(4) {
            clouds.add(PxCloud(
                Random.nextFloat() * w,
                h * (0.05f + Random.nextFloat() * 0.18f),
                kit.pxU * (3f + Random.nextFloat() * 1.5f),
                kit.sz(15f + Random.nextFloat() * 20f)
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
        // Step-bobbing for retro feel (snap to integer steps)
        val bobRaw = (sin(tSec * 2.5)).toFloat()
        aiBob = (Math.round(bobRaw * 4f) / 4f) * kit.sz(12f)
        blinkT -= dt
        if (blinkT <= 0) {
            blinkActive = !blinkActive
            blinkT = if (blinkActive) 0.12f else (2.5f + Random.nextFloat() * 3.5f)
        }

        // clouds drift
        for (cl in clouds) {
            cl.x += cl.speed * dt
            if (cl.x > width + 200f) cl.x = -300f
        }

        if (screen == Screen.PLAY && !game.ended) {
            dayProgress += dt / DAY_SECONDS
            if (dayProgress >= 1f) {
                dayProgress = 0f
                Logic.nextDay(game)
                onDayAdvanced()
            }

            // room-flavored modifiers
            val coinRateBoost = when (roomIdx) {
                3 -> 0.7f  // rooftop: more coins
                else -> 1.0f
            }
            coinTimer -= dt
            if (coinTimer <= 0) {
                spawnCoin()
                val rate = max(1.0f, (4.2f - game.stage * 0.3f - if (game.albaIdx >= 0) 0.8f else 0f) * coinRateBoost)
                coinTimer = rate + Random.nextFloat() * 1.4f
            }
            catTimer -= dt
            if (catTimer <= 0) {
                if (roomIdx == 2) spawnCat()  // cat room: cats roam
                else if (Random.nextFloat() < 0.6f) spawnCat()
                catTimer = if (roomIdx == 2) 6f + Random.nextFloat() * 6f else 10f + Random.nextFloat() * 12f
            }
            glitchTimer -= dt
            if (glitchTimer <= 0) {
                val risk = (100 - game.stats[STAT_STABILITY]) / 100f
                val baseChance = if (roomIdx == 1) 0.45f else 0.25f
                if (Random.nextFloat() < baseChance + risk * 0.5f) spawnGlitch()
                glitchTimer = 5f + Random.nextFloat() * 6f
            }

            for (c in coins) {
                c.y += c.vy * dt
                c.phase += dt
                c.vy += 70f * dt
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
            newsScroll -= kit.sz(120f) * dt
        }
    }

    private fun onDayAdvanced() {
        flash(Style.NEON_CYAN, 0.35f)
        shakeAmt = kit.sz(6f)
        if (game.ended) screen = Screen.ENDING
        else if (game.pendingEventIdx >= 0) screen = Screen.EVENT
        save()
    }

    private fun spawnCoin() {
        val x = kit.sz(80f) + Random.nextFloat() * (width - kit.sz(160f))
        val y = -kit.sz(40f)
        val vy = kit.sz(90f) + Random.nextFloat() * kit.sz(80f)
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
            particles.add(Particle(x, y,
                cos(ang) * speed,
                sin(ang) * speed - kit.sz(60f),
                0.7f + Random.nextFloat() * 0.5f, 1.2f, c,
                kit.pxU * 1.2f + Random.nextFloat() * kit.pxU))
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
        // base sky for all screens (intro/play)
        when (screen) {
            Screen.INTRO   -> { drawIntro(canvas) }
            Screen.PLAY    -> { drawSky(canvas); drawPlay(canvas) }
            Screen.FEED    -> { drawModalBg(canvas); drawFeed(canvas) }
            Screen.SHOP    -> { drawModalBg(canvas); drawShop(canvas) }
            Screen.ALBA    -> { drawModalBg(canvas); drawAlba(canvas) }
            Screen.NEWS    -> { drawModalBg(canvas); drawNews(canvas) }
            Screen.TRAIN   -> { drawModalBg(canvas); drawTrain(canvas) }
            Screen.EVENT   -> { drawModalBg(canvas); drawEvent(canvas) }
            Screen.ENDING  -> drawEnding(canvas)
        }
        canvas.restore()
        if (flashA > 0) {
            paint.color = flashColor
            paint.alpha = (flashA * 200).toInt().coerceAtMost(255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
        // CRT scanlines overlay (very subtle)
        drawScanlines(canvas)
    }

    private fun drawScanlines(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0x14000000
        var y = 0f
        val gap = kit.pxU * 0.5f
        while (y < h) {
            canvas.drawRect(0f, y, w, y + gap * 0.6f, paint)
            y += gap * 2f
        }
    }

    private fun drawSky(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        kit.pxSky(canvas, w, h, dayProgress, stars)
        // parallax mountain bands
        kit.pxMountains(canvas, w, h * 0.42f, Style.BG_DUSK, kit.sz(80f), tSec * 0.02f)
        kit.pxMountains(canvas, w, h * 0.46f, Style.WALL_DARK, kit.sz(50f), tSec * 0.05f + 9f)
        // clouds (chunky pixel) — only in daytime-ish
        for (cl in clouds) {
            kit.pxSprite(canvas, cl.x, cl.y, PixelArt.CLOUD, PixelArt.CLOUD_PAL, cl.px)
        }
    }

    // ───────────────────────── INTRO ─────────────────────────

    private fun drawIntro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        kit.pxSky(canvas, w, h, 0.05f, stars)  // mostly night
        kit.pxMountains(canvas, w, h * 0.48f, Style.BG_DUSK, kit.sz(110f), 3f)
        kit.pxMountains(canvas, w, h * 0.55f, Style.WALL_DARK, kit.sz(70f), 7f)
        // floor
        val floorY = h * 0.62f
        kit.pxFloor(canvas, w, floorY, h, Style.FLOOR_A, Style.FLOOR_B, Style.NEON_PURPLE)

        // title — big chunky retro
        kit.pxText(canvas, "이상한", w / 2f, h * 0.18f,
                   Style.TITLE_PX, Style.NEON_YELLOW, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        kit.pxText(canvas, "AI 키우기", w / 2f, h * 0.26f,
                   Style.TITLE_PX, Style.NEON_PINK, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        // version badge
        val verR = RectF(w / 2f - kit.sz(150f), h * 0.30f, w / 2f + kit.sz(150f), h * 0.30f + kit.sz(60f))
        kit.pxChip(canvas, verR, "PIXEL RPG · v4.0", Style.NEON_CYAN)

        // floating big AI (stage 5 sample)
        drawAiSprite(canvas, w / 2f, h * 0.50f + aiBob, 5)

        kit.pxText(canvas, "─  TAMAGOTCHI × IDLE × PIXEL  ─", w / 2f, h * 0.70f,
                   Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 4f, Paint.Align.CENTER)

        val lines = arrayOf(
            "▶  데이터를 먹여 성격을 만든다",
            "▶  방을 옮기며 자원과 사고를 마주한다",
            "▶  코인·고양이·글리치를 탭한다",
            "▶  고양이는 절대 정복 불가"
        )
        for ((i, l) in lines.withIndex()) {
            kit.pxText(canvas, l, w / 2f, h * 0.75f + i * kit.sz(48f),
                       Style.BODY_PX, Style.TEXT_HI, Style.PX_BLACK, 4f, Paint.Align.CENTER)
        }

        val pressed = pressedHitIdx == 999
        val r = RectF(w / 2f - kit.sz(300f), h * 0.92f - kit.sz(120f), w / 2f + kit.sz(300f), h * 0.92f)
        kit.pxButton(canvas, r, "PRESS START", Style.NEON_RED, "▶", Style.BUTTON_PX * 1.2f, pressed)
        hits.add(r to {
            pressedHitIdx = 999; pressedDecay = 0.12f
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

        // Room-flavored back wall + floor
        drawRoomBackground(canvas, w, h)

        // Top HUD bar
        drawTopBar(canvas, w, h)

        // AI character
        val aiCx = w / 2f
        val aiCy = h * 0.36f + aiBob
        drawAiSprite(canvas, aiCx, aiCy, game.stage)
        if (speechT > 0 && speechText.isNotEmpty()) {
            kit.pxSpeech(canvas, aiCx, aiCy - kit.sz(220f), speechText, w - kit.sz(60f))
        }
        worldHits.add(Triple(aiCx, aiCy, ::onTapAi))

        // World objects
        for (c in coins) drawCoinSprite(canvas, c)
        for (k in cats) drawCatSprite(canvas, k)
        for (g in glitches) drawGlitchSprite(canvas, g)
        for (c in coins) worldHits.add(Triple(c.x, c.y, { onTapCoin(c) }))
        for (k in cats) worldHits.add(Triple(k.x, k.y, { onTapCat(k) }))
        for (g in glitches) worldHits.add(Triple(g.x, g.y, { onTapGlitch(g) }))

        // Particles & floats
        for (p in particles) p.draw(canvas, paint)
        for (f in floats) {
            val a = (f.life / f.maxLife).coerceIn(0f, 1f)
            kit.text.alpha = (a * 255).toInt()
            kit.pxText(canvas, f.text, f.x, f.y,
                       Style.BODY_PX, f.color, Style.PX_BLACK, 5f, Paint.Align.CENTER)
            kit.text.alpha = 255
        }

        // Room nav arrows (above stats panel)
        drawRoomNav(canvas, w, h)

        // Stats panel (pixel-styled)
        val statsPanel = RectF(kit.sz(20f), h * 0.62f, w - kit.sz(20f), h * 0.84f)
        kit.pxPanel(canvas, statsPanel, Style.UI_PANEL, Style.UI_BORDER, Style.UI_BORDER_DK)

        // Stat layout 2x4
        val pad = kit.sz(28f)
        val labelW = kit.sz(105f)
        val colGap = kit.sz(34f)
        val colW = (statsPanel.width() - pad * 2 - colGap) / 2f
        val barH = kit.sz(34f)
        val rowH = kit.sz(50f)
        val statTop = statsPanel.top + kit.sz(28f)
        for (i in 0..7) {
            val col = i / 4; val row = i % 4
            val x = statsPanel.left + pad + col * (colW + colGap) + labelW
            val y = statTop + row * rowH
            val color = when (i) {
                STAT_BATTERY -> Style.NEON_YELLOW
                STAT_COMPUTE -> Style.NEON_CYAN
                STAT_MEMORY -> Style.NEON_PURPLE
                STAT_TRUST -> Style.NEON_GREEN
                STAT_STABILITY -> Style.NEON_CYAN_DK
                STAT_CURIOSITY -> Style.NEON_PINK
                STAT_EGO -> Style.NEON_RED
                else -> Style.NEON_ORANGE
            }
            kit.pxStatBar(canvas, x, y, colW - labelW, barH, displayStats[i], color, STAT_NAMES[i])
        }

        // Numeric stat values on right side
        kit.pxText(canvas, "─ STATUS ─", statsPanel.centerX(), statsPanel.bottom - kit.sz(14f),
                   Style.TINY_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.CENTER)

        // Tag chips (between play and stats)
        if (game.tags.isNotEmpty()) {
            var tx = kit.sz(28f)
            val ty = statsPanel.top - kit.sz(62f)
            for (t in game.tags) {
                val lab = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(lab) + kit.sz(34f)
                kit.pxChip(canvas, RectF(tx, ty, tx + tw, ty + kit.sz(48f)), lab, Style.NEON_PURPLE)
                tx += tw + kit.sz(12f)
                if (tx > w - kit.sz(140f)) break
            }
        }

        // Action buttons row
        val btnTop = statsPanel.bottom + kit.sz(16f)
        val btnH = kit.sz(100f)
        val btnPad = kit.sz(14f)
        val bw = (w - btnPad * 5) / 4f
        val buttons = listOf(
            Triple("🍔", "DATA" + if (game.cardsRemaining > 0) " ${game.cardsRemaining}" else "", Style.NEON_GREEN) to { -> screen = Screen.FEED; save() },
            Triple("🛠", "TOOL", Style.NEON_CYAN) to { -> screen = Screen.SHOP; save() },
            Triple("💼", if (game.albaIdx >= 0) "JOB ${game.albaTimeLeft}d" else "JOB", Style.NEON_PURPLE) to { -> screen = Screen.ALBA; save() },
            Triple("🏆", "LOG", Style.NEON_PINK) to { -> screen = Screen.NEWS; save() }
        )
        for ((i, b) in buttons.withIndex()) {
            val (iconLab, action) = b
            val x = btnPad + i * (bw + btnPad)
            val r = RectF(x, btnTop, x + bw, btnTop + btnH)
            val pressed = pressedHitIdx == i
            kit.pxButton(canvas, r, iconLab.second, iconLab.third, iconLab.first, Style.BUTTON_PX * 0.7f, pressed)
            val captured = i
            hits.add(r to {
                pressedHitIdx = captured; pressedDecay = 0.12f
                action()
            })
        }

        // Next-day big button
        val nextR = RectF(btnPad, btnTop + btnH + kit.sz(14f),
                          w - btnPad, btnTop + btnH + kit.sz(14f) + kit.sz(110f))
        val canNext = game.pendingTrainingIdx < 0 || game.trainingHandled
        val label: String; val nextColor: Int; val icon: String
        when {
            game.pendingEventIdx >= 0 -> { label = "사고 처리!"; nextColor = Style.NEON_RED; icon = "⚠" }
            !canNext -> { label = "훈육 먼저!"; nextColor = Style.NEON_YELLOW; icon = "💬" }
            else -> {
                val left = (DAY_SECONDS * (1 - dayProgress)).toInt()
                label = "다음 날 ─ ${left}s"; nextColor = Style.NEON_BLUE; icon = "▶"
            }
        }
        val pressedNext = pressedHitIdx == 100
        kit.pxButton(canvas, nextR, label, nextColor, icon, Style.BUTTON_PX, pressedNext)
        hits.add(nextR to {
            pressedHitIdx = 100; pressedDecay = 0.12f
            if (game.pendingEventIdx >= 0) { screen = Screen.EVENT; save() }
            else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) { screen = Screen.TRAIN; save() }
            else { Logic.nextDay(game); onDayAdvanced() }
        })

        // News ticker (LED-style)
        drawNewsTicker(canvas, w, h)
    }

    private fun drawRoomBackground(canvas: Canvas, w: Float, h: Float) {
        // Room-specific backdrop
        val floorY = h * 0.50f
        // back wall band
        when (roomIdx) {
            0 -> {
                // bedroom: dusk sky + simple horizon
                kit.pxMountains(canvas, w, h * 0.38f, Style.BG_DUSK, kit.sz(80f), 2f)
                kit.pxMountains(canvas, w, h * 0.44f, Style.WALL_DARK, kit.sz(50f), 6f)
                // a lamp on left
                kit.pxSprite(canvas, kit.sz(40f), floorY - kit.sz(160f), PixelArt.LAMP, PixelArt.LAMP_PAL, kit.pxU * 1.2f)
                // pixel "Z" stars when battery low
                if (displayStats[STAT_BATTERY] < 30f) {
                    kit.pxText(canvas, "Z", w / 2f - kit.sz(120f), h * 0.30f + sin(tSec * 2.5).toFloat() * kit.sz(6f),
                               Style.HEADER_PX, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.CENTER)
                    kit.pxText(canvas, "z", w / 2f - kit.sz(80f), h * 0.27f + cos(tSec * 2.0).toFloat() * kit.sz(4f),
                               Style.BODY_PX, Style.PX_WHITE, Style.PX_BLACK, 4f, Paint.Align.CENTER)
                }
            }
            1 -> {
                // server room: dark, server rack pixel art on background
                paint.color = Style.WALL_DARK
                canvas.drawRect(0f, 0f, w, floorY, paint)
                // server racks across the wall
                val rackPx = kit.pxU * 1.4f
                for (i in 0..2) {
                    val rx = kit.sz(40f) + i * kit.sz(340f)
                    kit.pxSprite(canvas, rx, h * 0.16f, PixelArt.SERVER_RACK, PixelArt.SERVER_PAL, rackPx)
                    // blinking LED
                    paint.color = if ((tSec * (i + 1)).toInt() % 2 == 0) Style.NEON_GREEN else Style.NEON_RED
                    canvas.drawRect(rx + kit.sz(20f), h * 0.20f, rx + kit.sz(20f) + rackPx, h * 0.20f + rackPx, paint)
                }
            }
            2 -> {
                // cat shrine: warm tones + glowing eyes
                paint.color = blend(Style.BG_DUSK, Style.NEON_RED_DK, 0.3f)
                canvas.drawRect(0f, 0f, w, floorY, paint)
                kit.pxMountains(canvas, w, h * 0.40f, Style.WALL_DARK, kit.sz(40f), 12f)
                // pixel "CAT" idol in center back
                kit.pxText(canvas, "🐱", w / 2f, h * 0.20f, Style.TITLE_PX * 1.5f,
                           Style.NEON_YELLOW, Style.PX_BLACK, 8f, Paint.Align.CENTER)
                kit.pxText(canvas, "─ NEKO SHRINE ─", w / 2f, h * 0.30f,
                           Style.SMALL_PX, Style.NEON_PINK, Style.PX_BLACK, 4f, Paint.Align.CENTER)
            }
            3 -> {
                // rooftop: full night sky already in kit.pxSky → we draw atop a railing
                kit.pxMountains(canvas, w, h * 0.36f, Style.BG_DUSK, kit.sz(100f), 4f)
                kit.pxMountains(canvas, w, h * 0.42f, Style.WALL_DARK, kit.sz(60f), 9f)
                // railing
                paint.color = Style.PX_BLACK
                canvas.drawRect(0f, floorY - kit.pxU * 1.6f, w, floorY - kit.pxU * 0.8f, paint)
                for (rx in 0 until (w / kit.sz(80f)).toInt()) {
                    canvas.drawRect(rx * kit.sz(80f), floorY - kit.pxU * 4f,
                                    rx * kit.sz(80f) + kit.pxU * 0.6f, floorY - kit.pxU * 0.8f, paint)
                }
            }
        }
        // floor
        val (fa, fb) = when (roomIdx) {
            1 -> Pair(0xFF221848.toInt(), 0xFF161028.toInt())
            2 -> Pair(0xFF3A1830.toInt(), 0xFF221224.toInt())
            3 -> Pair(0xFF231C40.toInt(), 0xFF14102C.toInt())
            else -> Pair(Style.FLOOR_A, Style.FLOOR_B)
        }
        kit.pxFloor(canvas, w, floorY, h, fa, fb, Style.NEON_PURPLE)
    }

    private fun drawRoomNav(canvas: Canvas, w: Float, h: Float) {
        val y = h * 0.56f
        val sz = kit.sz(80f)
        val gap = kit.sz(14f)

        // left arrow
        val lr = RectF(kit.sz(16f), y, kit.sz(16f) + sz, y + sz)
        kit.pxButton(canvas, lr, "", Style.NEON_BLUE, "◀", Style.BUTTON_PX * 0.8f, pressedHitIdx == 200)
        hits.add(lr to {
            pressedHitIdx = 200; pressedDecay = 0.12f
            roomIdx = (roomIdx + ROOM_NAMES.size - 1) % ROOM_NAMES.size
            flash(Style.PX_WHITE, 0.18f)
        })
        // right arrow
        val rr = RectF(w - sz - kit.sz(16f), y, w - kit.sz(16f), y + sz)
        kit.pxButton(canvas, rr, "", Style.NEON_BLUE, "▶", Style.BUTTON_PX * 0.8f, pressedHitIdx == 201)
        hits.add(rr to {
            pressedHitIdx = 201; pressedDecay = 0.12f
            roomIdx = (roomIdx + 1) % ROOM_NAMES.size
            flash(Style.PX_WHITE, 0.18f)
        })
        // room label
        val lab = "${ROOM_ICONS[roomIdx]} ${ROOM_NAMES[roomIdx]}"
        kit.text.textSize = kit.sz(Style.BODY_PX)
        val lw = kit.text.measureText(lab) + kit.sz(60f)
        val labR = RectF(w / 2f - lw / 2f, y + sz * 0.15f, w / 2f + lw / 2f, y + sz * 0.85f)
        kit.pxChip(canvas, labR, lab, Style.NEON_ORANGE)
    }

    private fun drawTopBar(canvas: Canvas, w: Float, h: Float) {
        // Day badge — square pixel block w/ progress bar below
        val left = kit.sz(20f); val top = kit.sz(28f)
        val dayR = RectF(left, top, left + kit.sz(190f), top + kit.sz(130f))
        kit.pxPanel(canvas, dayR, Style.NEON_RED, Style.PX_BLACK, Style.NEON_RED_DK)
        kit.pxText(canvas, "DAY", dayR.centerX(), dayR.top + kit.sz(40f),
                   Style.SMALL_PX, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.CENTER)
        kit.pxText(canvas, "${game.day}", dayR.centerX(), dayR.top + kit.sz(110f),
                   Style.BIG_NUM_PX, Style.PX_WHITE, Style.PX_BLACK, 6f, Paint.Align.CENTER)
        // tiny progress under day
        val pBarY = dayR.bottom + kit.sz(4f)
        paint.color = Style.PX_BLACK
        canvas.drawRect(dayR.left, pBarY, dayR.right, pBarY + kit.sz(10f), paint)
        paint.color = Style.NEON_YELLOW
        canvas.drawRect(dayR.left + kit.pxU * 0.3f, pBarY + kit.pxU * 0.3f,
                        dayR.left + (dayR.width() - kit.pxU * 0.6f) * dayProgress, pBarY + kit.sz(10f) - kit.pxU * 0.3f, paint)

        // Money plate (center)
        val moneyStr = "${displayMoney.toInt()}"
        kit.text.textSize = kit.sz(Style.BIG_NUM_PX)
        val moneyW = kit.text.measureText(moneyStr)
        val mw = moneyW + kit.sz(180f)
        val mr = RectF(w / 2f - mw / 2f, top, w / 2f + mw / 2f, top + kit.sz(130f))
        kit.pxPanel(canvas, mr, Style.NEON_YELLOW, Style.PX_BLACK, Style.PX_BLACK)
        // coin pixel sprite on left side
        kit.pxSprite(canvas, mr.left + kit.sz(20f), mr.centerY() - kit.pxU * 4f,
                     PixelArt.COIN_SPRITE, PixelArt.COIN_PAL, kit.pxU * 1.0f)
        kit.pxText(canvas, "₩$moneyStr", mr.left + kit.sz(110f), mr.centerY() + kit.sz(22f),
                   Style.BIG_NUM_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.LEFT)

        // Stage badge (right)
        val sw = kit.sz(230f)
        val sr = RectF(w - sw - kit.sz(20f), top, w - kit.sz(20f), top + kit.sz(130f))
        kit.pxPanel(canvas, sr, Style.NEON_PURPLE, Style.PX_BLACK, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.4f))
        kit.pxText(canvas, "LV.${game.stage}", sr.centerX(), sr.top + kit.sz(50f),
                   Style.HEADER_PX * 0.75f, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.CENTER)
        kit.pxText(canvas, Content.STAGE_NAMES[game.stage - 1], sr.centerX(), sr.top + kit.sz(100f),
                   Style.SMALL_PX, Style.PX_WHITE, Style.PX_BLACK, 4f, Paint.Align.CENTER)

        // Pending alerts row
        var alertX = kit.sz(20f)
        val alertY = top + kit.sz(180f)
        if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) {
            val lab = "💬 훈육!"
            kit.text.textSize = kit.sz(Style.BODY_PX)
            val w_ = kit.text.measureText(lab) + kit.sz(40f)
            val ar = RectF(alertX, alertY, alertX + w_, alertY + kit.sz(58f))
            kit.pxChip(canvas, ar, lab, Style.NEON_YELLOW)
            hits.add(ar to { screen = Screen.TRAIN; save() })
            alertX += w_ + kit.sz(12f)
        }
        if (game.pendingEventIdx >= 0) {
            val pulse = (sin(tSec * 6.0).toFloat() * 0.5f + 0.5f)
            val lab = "⚠ 사고!"
            kit.text.textSize = kit.sz(Style.BODY_PX)
            val w_ = kit.text.measureText(lab) + kit.sz(40f)
            val ar = RectF(alertX, alertY, alertX + w_, alertY + kit.sz(58f))
            val col = blend(Style.NEON_RED, Style.NEON_YELLOW, pulse)
            kit.pxChip(canvas, ar, lab, col)
            hits.add(ar to { screen = Screen.EVENT; save() })
        }
    }

    // ───────────────────────── AI SPRITE ─────────────────────────

    private fun drawAiSprite(canvas: Canvas, cx: Float, cy: Float, stageOverride: Int = game.stage) {
        // pick sprite grid by stage
        val grid = when {
            stageOverride >= 9 -> PixelArt.AI_STAGE9
            stageOverride >= 7 -> PixelArt.AI_STAGE7
            stageOverride >= 5 -> PixelArt.AI_STAGE5
            stageOverride >= 3 -> PixelArt.AI_STAGE3
            else -> PixelArt.AI_STAGE1
        }
        // body color by stage
        val (bodyC, bodyD, bodyL) = when {
            stageOverride >= 9 -> Triple(Style.NEON_YELLOW, Style.NEON_ORANGE, blend(Style.NEON_YELLOW, Style.PX_WHITE, 0.5f))
            stageOverride >= 7 -> Triple(Style.NEON_PURPLE, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.35f), blend(Style.NEON_PURPLE, Style.PX_WHITE, 0.4f))
            stageOverride >= 5 -> Triple(Style.NEON_CYAN, Style.NEON_CYAN_DK, blend(Style.NEON_CYAN, Style.PX_WHITE, 0.4f))
            stageOverride >= 3 -> Triple(Style.NEON_GREEN, Style.NEON_GRN_DK, blend(Style.NEON_GREEN, Style.PX_WHITE, 0.4f))
            else -> Triple(0xFFC8B89A.toInt(), 0xFF7D6850.toInt(), 0xFFE8D8B8.toInt())
        }
        val palette = PixelArt.aiPalette(bodyC, bodyD, bodyL).toMutableMap()
        // mood-based eye swap
        if (blinkActive || displayStats[STAT_BATTERY] < 15f) {
            // closed eyes — paint white pixels as same as body skin (looks closed)
            palette['W'] = bodyC
        } else if (displayStats[STAT_EGO] > 80f) {
            // angry — red eye whites
            palette['W'] = Style.NEON_RED
        }
        // pixel size based on stage (bigger = late game)
        val px = kit.pxU * (1.1f + stageOverride * 0.08f)
        val gw = grid[0].length * px
        val gh = grid.size * px
        // shadow (pixel ellipse → row of rects)
        paint.color = 0x80000000.toInt()
        val shY = cy + gh / 2f + kit.pxU * 0.8f
        canvas.drawRect(cx - gw / 2f * 0.85f, shY, cx + gw / 2f * 0.85f, shY + kit.pxU * 1.2f, paint)

        kit.pxSprite(canvas, cx - gw / 2f, cy - gh / 2f, grid, palette, px)

        // RGB GPU LEDs at base
        if (game.gpuCount >= 1) {
            val n = kotlin.math.min(4, game.gpuCount)
            val colors = intArrayOf(Style.NEON_RED, Style.NEON_GREEN, Style.NEON_YELLOW, Style.NEON_CYAN)
            for (i in 0 until n) {
                val ph = tSec * 3f + i
                paint.color = colors[i]
                paint.alpha = (160 + sin(ph.toDouble()).toFloat() * 80).toInt().coerceIn(100, 255)
                canvas.drawRect(cx - gw / 2f + i * (gw / n.toFloat()) + kit.pxU,
                                cy + gh / 2f - kit.pxU * 1.5f,
                                cx - gw / 2f + i * (gw / n.toFloat()) + kit.pxU * 2f,
                                cy + gh / 2f - kit.pxU * 0.5f, paint)
            }
            paint.alpha = 255
        }
        // robot arm (after stage)
        if (game.tools.contains(Content.TOOL_ROBOTARM)) {
            paint.color = Style.PX_BLACK
            canvas.drawRect(cx + gw / 2f - kit.pxU * 0.5f, cy - kit.pxU * 1f,
                            cx + gw / 2f + kit.pxU * 7f, cy + kit.pxU * 1f, paint)
            paint.color = Style.PX_LIGHT
            canvas.drawRect(cx + gw / 2f - kit.pxU * 0.5f + kit.pxU * 0.4f, cy - kit.pxU * 1f + kit.pxU * 0.4f,
                            cx + gw / 2f + kit.pxU * 7f - kit.pxU * 0.4f, cy + kit.pxU * 1f - kit.pxU * 0.4f, paint)
            paint.color = Style.NEON_RED
            canvas.drawRect(cx + gw / 2f + kit.pxU * 6f, cy - kit.pxU * 1.5f,
                            cx + gw / 2f + kit.pxU * 8f, cy + kit.pxU * 1.5f, paint)
            paint.color = Style.PX_BLACK
            stroke.color = Style.PX_BLACK
            stroke.strokeWidth = kit.pxU * 0.4f
            canvas.drawRect(cx + gw / 2f + kit.pxU * 6f, cy - kit.pxU * 1.5f,
                            cx + gw / 2f + kit.pxU * 8f, cy + kit.pxU * 1.5f, stroke)
        }
    }

    // ───────────────────────── WORLD SPRITES ─────────────────────────

    private fun drawCoinSprite(canvas: Canvas, c: Coin) {
        // Spin via x-scale flip
        val phaseFlip = (cos(c.phase.toDouble())).toFloat()
        val px = kit.pxU * 1.0f * (0.4f + 0.6f * kotlin.math.abs(phaseFlip))
        val w = PixelArt.COIN_SPRITE[0].length * px
        val h = PixelArt.COIN_SPRITE.size * px
        // shadow
        paint.color = 0x70000000
        canvas.drawRect(c.x - w / 2f, c.y + h / 2f, c.x + w / 2f, c.y + h / 2f + kit.pxU * 0.5f, paint)
        kit.pxSprite(canvas, c.x - w / 2f, c.y - h / 2f, PixelArt.COIN_SPRITE, PixelArt.COIN_PAL, px)
    }

    private fun drawCatSprite(canvas: Canvas, k: CatBlob) {
        val bob = sin(k.bob.toDouble()).toFloat() * kit.pxU * 0.5f
        val px = kit.pxU * 1.2f
        val w = PixelArt.CAT_SPRITE[0].length * px
        val h = PixelArt.CAT_SPRITE.size * px
        // shadow
        paint.color = 0x70000000
        canvas.drawRect(k.x - w / 2f * 0.7f, k.y + h / 2f, k.x + w / 2f * 0.7f, k.y + h / 2f + kit.pxU * 0.6f, paint)
        // flip horizontally based on vx
        if (k.vx > 0) {
            kit.pxSprite(canvas, k.x - w / 2f, k.y - h / 2f + bob, PixelArt.CAT_SPRITE, PixelArt.CAT_PAL, px)
        } else {
            // mirror by manually drawing reversed strings
            val mirrored = PixelArt.CAT_SPRITE.map { it.reversed() }.toTypedArray()
            kit.pxSprite(canvas, k.x - w / 2f, k.y - h / 2f + bob, mirrored, PixelArt.CAT_PAL, px)
        }
        // sparkle (cat is divine)
        if ((tSec.toInt() % 2) == 0) {
            paint.color = Style.NEON_YELLOW
            canvas.drawRect(k.x + w * 0.4f, k.y - h * 0.4f, k.x + w * 0.4f + kit.pxU, k.y - h * 0.4f + kit.pxU, paint)
        }
    }

    private fun drawGlitchSprite(canvas: Canvas, g: Glitch) {
        val flicker = if ((tSec * 12f).toInt() % 2 == 0) 1.05f else 0.9f
        val px = kit.pxU * 1.2f * flicker
        val w = PixelArt.GLITCH_SPRITE[0].length * px
        val h = PixelArt.GLITCH_SPRITE.size * px
        // shadow
        paint.color = 0x70000000
        canvas.drawRect(g.x - w / 2f * 0.7f, g.y + h / 2f, g.x + w / 2f * 0.7f, g.y + h / 2f + kit.pxU * 0.6f, paint)
        kit.pxSprite(canvas, g.x - w / 2f, g.y - h / 2f, PixelArt.GLITCH_SPRITE, PixelArt.GLITCH_PAL, px)
        // HP indicator
        if (g.hp > 1) {
            kit.pxText(canvas, "${g.hp}", g.x, g.y + h / 2f + kit.sz(28f),
                       Style.SMALL_PX, Style.NEON_YELLOW, Style.PX_BLACK, 4f, Paint.Align.CENTER)
        }
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float) {
        val barH = kit.sz(70f)
        val barR = RectF(0f, h - barH, w, h)
        paint.color = Style.PX_BLACK
        canvas.drawRect(barR, paint)
        // top accent line
        paint.color = Style.NEON_GREEN
        canvas.drawRect(0f, barR.top, w, barR.top + kit.pxU * 0.5f, paint)
        // left padding bar
        paint.color = Style.NEON_RED
        canvas.drawRect(0f, barR.top + kit.pxU * 0.5f, kit.pxU * 1.5f, barR.bottom, paint)

        val msg = if (game.newsTicker.isEmpty()) "AI ONLINE. WORLD: WAITING…" else
            game.newsTicker.takeLast(10).joinToString("    ●    ")
        kit.text.textSize = kit.sz(28f)
        val tw = kit.text.measureText(msg)
        if (newsScroll < -(tw + w)) newsScroll = w
        kit.pxText(canvas, msg, kit.sz(30f) + newsScroll, barR.centerY() + kit.sz(12f),
                   28f, Style.TEXT_GREEN, Style.NEON_GRN_DK, 3f, Paint.Align.LEFT)
    }

    // ───────────────────────── TAPS ─────────────────────────

    private fun onTapAi() {
        game.stats[STAT_EGO] = (game.stats[STAT_EGO] + 1).coerceAtMost(100)
        burst(width / 2f, height * 0.36f, Style.NEON_PINK, 12)
        popText(width / 2f, height * 0.36f - kit.sz(50f), "♥ +1", Style.NEON_RED)
        if (Random.nextInt(4) == 0) speech(Logic.feelingText(game))
    }
    private fun onTapCoin(c: Coin) {
        game.money += c.value
        coins.remove(c)
        burst(c.x, c.y, Style.NEON_YELLOW, 16)
        popText(c.x, c.y, "+₩${c.value}", Style.NEON_YELLOW)
    }
    private fun onTapCat(k: CatBlob) {
        game.catAttempts++
        Logic.checkTags(game); Logic.checkAchievements(game)
        cats.remove(k)
        burst(k.x, k.y, Style.NEON_PURPLE, 18)
        popText(k.x, k.y, "무시당함…", Style.NEON_RED)
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
        burst(g.x, g.y, Style.NEON_RED, 12)
        if (g.hp <= 0) {
            glitches.remove(g)
            game.stats[STAT_STABILITY] = (game.stats[STAT_STABILITY] + 2).coerceAtMost(100)
            game.money += 3
            popText(g.x, g.y, "+안정 ₩3", Style.NEON_GREEN)
            shakeAmt = kit.sz(6f)
        } else {
            popText(g.x, g.y, "HP ${g.hp}", Style.NEON_RED)
        }
    }

    // ───────────────────────── MODALS ─────────────────────────

    private fun drawModalBg(canvas: Canvas) {
        paint.color = Style.UI_PANEL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // subtle background stars
        paint.color = Style.STAR
        paint.alpha = 60
        for (s in stars.take(20)) canvas.drawRect(s[0], s[1], s[0] + s[2], s[1] + s[2], paint)
        paint.alpha = 255
    }

    private fun modalHeader(canvas: Canvas, title: String, color: Int = Style.NEON_RED) {
        val w = width.toFloat()
        // top band
        val band = RectF(0f, 0f, w, kit.sz(160f))
        paint.color = color
        canvas.drawRect(band, paint)
        // bottom bevel
        paint.color = blend(color, Style.PX_BLACK, 0.4f)
        canvas.drawRect(0f, band.bottom - kit.pxU * 0.8f, w, band.bottom, paint)
        // hard outline strip
        paint.color = Style.PX_BLACK
        canvas.drawRect(0f, band.bottom, w, band.bottom + kit.pxU * 0.6f, paint)
        // title
        kit.pxText(canvas, title, kit.sz(40f), kit.sz(100f),
                   Style.HEADER_PX, Style.PX_WHITE, Style.PX_BLACK, 6f, Paint.Align.LEFT)
        // close ✕
        val r = RectF(w - kit.sz(140f), kit.sz(30f), w - kit.sz(40f), kit.sz(130f))
        kit.pxButton(canvas, r, "X", Style.PX_BLACK, null, Style.BUTTON_PX, false)
        hits.add(r to { screen = Screen.PLAY; save() })
    }

    private fun drawFeed(canvas: Canvas) {
        modalHeader(canvas, "🍔 DATA · ${game.cardsRemaining}/2", Style.NEON_GREEN)
        val top = kit.sz(190f)
        val rowH = kit.sz(170f)
        val w = width.toFloat()
        val pad = kit.sz(28f)
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(18f))
            kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
            // icon block
            val iconR = RectF(r.left + kit.sz(20f), r.top + kit.sz(20f),
                              r.left + kit.sz(120f), r.bottom - kit.sz(20f))
            kit.pxPanel(canvas, iconR, Style.NEON_GREEN, Style.PX_BLACK, Style.NEON_GRN_DK, false)
            kit.text.textSize = kit.sz(60f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(card.icon, iconR.centerX(), iconR.centerY() + kit.sz(22f), kit.text)
            // name + desc
            kit.pxText(canvas, card.name, r.left + kit.sz(140f), r.top + kit.sz(55f),
                       Style.BODY_PX + 6, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, card.desc, r.left + kit.sz(140f), r.top + kit.sz(92f),
                       Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            // deltas
            val ds = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(3)
            var dx = r.left + kit.sz(140f)
            for ((idx, v) in ds) {
                if (v == 0) continue
                val txt = "${STAT_NAMES[idx]}${if (v > 0) "+" else ""}$v"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(txt) + kit.sz(24f)
                val pr = RectF(dx, r.top + kit.sz(108f), dx + tw, r.top + kit.sz(150f))
                kit.pxChip(canvas, pr, txt, if (v > 0) Style.NEON_GREEN else Style.NEON_PINK)
                dx += tw + kit.sz(10f)
            }
            // feed button
            if (game.cardsRemaining > 0) {
                val br = RectF(r.right - kit.sz(180f), r.top + kit.sz(35f),
                               r.right - kit.sz(30f), r.top + kit.sz(115f))
                kit.pxButton(canvas, br, "FEED", Style.NEON_GREEN, null, Style.BUTTON_PX * 0.75f)
                hits.add(br to {
                    Logic.applyCard(game, i)
                    burst(width / 2f, height * 0.36f, Style.NEON_GREEN, 18)
                    popText(width / 2f, height * 0.36f - kit.sz(50f), "+ ${card.name}", Style.NEON_GREEN)
                    speech("냠… ${card.icon}!")
                    save()
                })
            }
        }
    }

    private fun drawShop(canvas: Canvas) {
        modalHeader(canvas, "🛠 TOOLS", Style.NEON_CYAN)
        val w = width.toFloat()
        // money badge
        val mr = RectF(kit.sz(40f), kit.sz(190f), w - kit.sz(40f), kit.sz(280f))
        kit.pxPanel(canvas, mr, Style.NEON_YELLOW, Style.PX_BLACK, Style.PX_BLACK)
        kit.pxText(canvas, "현재 ₩ ${displayMoney.toInt()}", mr.centerX(), mr.centerY() + kit.sz(20f),
                   Style.HEADER_PX * 0.75f, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
        val top = kit.sz(310f)
        val rowH = kit.sz(140f)
        val pad = kit.sz(28f)
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(14f))
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            val fillC = if (owned) Style.NEON_GREEN else Style.UI_PANEL_LT
            kit.pxPanel(canvas, r, fillC, Style.UI_BORDER, Style.UI_BORDER_DK)
            // icon block
            val iconR = RectF(r.left + kit.sz(20f), r.top + kit.sz(18f),
                              r.left + kit.sz(108f), r.bottom - kit.sz(18f))
            kit.pxPanel(canvas, iconR, Style.NEON_CYAN, Style.PX_BLACK, Style.NEON_CYAN_DK, false)
            kit.text.textSize = kit.sz(48f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(t.icon, iconR.centerX(), iconR.centerY() + kit.sz(18f), kit.text)
            val txtC = if (owned) Style.PX_BLACK else Style.PX_WHITE
            kit.pxText(canvas, t.name, r.left + kit.sz(125f), r.top + kit.sz(48f),
                       Style.BODY_PX, txtC, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, t.desc, r.left + kit.sz(125f), r.top + kit.sz(85f),
                       Style.SMALL_PX, blend(txtC, Style.PX_GRAY, 0.4f), Style.PX_BLACK, 3f, Paint.Align.LEFT)
            val br = RectF(r.right - kit.sz(210f), r.top + kit.sz(28f),
                           r.right - kit.sz(28f), r.top + kit.sz(108f))
            if (owned) {
                kit.pxButton(canvas, br, "OWNED", Style.NEON_GRN_DK, "✓", Style.BUTTON_PX * 0.7f)
            } else {
                val ok = game.money >= t.price
                kit.pxButton(canvas, br, "${t.price}",
                             if (ok) Style.NEON_YELLOW else Style.UI_PANEL_LT, "₩",
                             Style.BUTTON_PX * 0.7f)
                if (ok) hits.add(br to {
                    if (Logic.buyTool(game, i)) {
                        flash(Style.NEON_YELLOW, 0.4f); shakeAmt = kit.sz(8f)
                        burst(width / 2f, height * 0.4f, Style.NEON_YELLOW, 24)
                        speech("${t.icon} 신상!")
                    }
                    save()
                })
            }
        }
    }

    private fun drawAlba(canvas: Canvas) {
        modalHeader(canvas, "💼 JOB BOARD", Style.NEON_PURPLE)
        val w = width.toFloat(); val pad = kit.sz(28f)
        var top = kit.sz(190f)
        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val r = RectF(pad, top, w - pad, top + kit.sz(140f))
            kit.pxPanel(canvas, r, Style.NEON_PURPLE, Style.PX_BLACK, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.4f))
            kit.pxText(canvas, "${a.icon} ${a.name}", r.left + kit.sz(30f), r.top + kit.sz(55f),
                       Style.HEADER_PX * 0.75f, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, "남은 ${game.albaTimeLeft}일 · ₩${a.reward}",
                       r.left + kit.sz(30f), r.top + kit.sz(105f),
                       Style.BODY_PX, Style.PX_WHITE, Style.PX_BLACK, 4f, Paint.Align.LEFT)
            top += kit.sz(160f)
        }
        val rowH = kit.sz(160f)
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(16f))
            kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
            val iconR = RectF(r.left + kit.sz(20f), r.top + kit.sz(20f),
                              r.left + kit.sz(116f), r.bottom - kit.sz(20f))
            kit.pxPanel(canvas, iconR, Style.NEON_PURPLE, Style.PX_BLACK, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.4f), false)
            kit.text.textSize = kit.sz(56f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(a.icon, iconR.centerX(), iconR.centerY() + kit.sz(22f), kit.text)
            kit.pxText(canvas, a.name, r.left + kit.sz(135f), r.top + kit.sz(50f),
                       Style.BODY_PX + 4, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, a.desc, r.left + kit.sz(135f), r.top + kit.sz(85f),
                       Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            kit.pxText(canvas, "₩${a.reward} · ${a.duration}일",
                       r.left + kit.sz(135f), r.top + kit.sz(125f),
                       Style.SMALL_PX, Style.NEON_YELLOW, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            val ok = game.stats[a.needStat] >= a.needVal &&
                (a.toolReq < 0 || game.tools.contains(a.toolReq)) &&
                game.albaIdx < 0
            val br = RectF(r.right - kit.sz(190f), r.top + kit.sz(35f),
                           r.right - kit.sz(28f), r.top + kit.sz(120f))
            kit.pxButton(canvas, br,
                if (game.albaIdx >= 0) "WORKING" else if (ok) "START" else "LOCKED",
                if (ok) Style.NEON_PURPLE else Style.UI_PANEL_LT, null, Style.BUTTON_PX * 0.7f)
            if (ok) hits.add(br to {
                Logic.startAlba(game, i)
                burst(width / 2f, height * 0.4f, Style.NEON_PURPLE, 16)
                save()
            })
        }
    }

    private fun drawNews(canvas: Canvas) {
        modalHeader(canvas, "🏆 LOG · 업적/뉴스", Style.NEON_PINK)
        val w = width.toFloat(); val pad = kit.sz(28f)
        var y = kit.sz(210f)
        kit.pxText(canvas, "─ 업적 ─", pad, y, Style.HEADER_PX * 0.65f,
                   Style.NEON_YELLOW, Style.PX_BLACK, 5f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val r = RectF(pad, y, w - pad, y + kit.sz(88f))
            val unlocked = game.achievements.contains(i)
            kit.pxPanel(canvas, r,
                if (unlocked) Style.NEON_YELLOW else Style.UI_PANEL_LT,
                Style.PX_BLACK,
                if (unlocked) blend(Style.NEON_YELLOW, Style.PX_BLACK, 0.4f) else Style.UI_BORDER_DK)
            // icon
            val iconR = RectF(r.left + kit.sz(14f), r.top + kit.sz(14f),
                              r.left + kit.sz(80f), r.bottom - kit.sz(14f))
            kit.pxPanel(canvas, iconR, if (unlocked) Style.NEON_ORANGE else Style.PX_GRAY,
                        Style.PX_BLACK, Style.PX_BLACK, false)
            kit.text.textSize = kit.sz(40f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(ach.icon, iconR.centerX(), iconR.centerY() + kit.sz(15f), kit.text)
            val txtC = if (unlocked) Style.PX_BLACK else Style.PX_LIGHT
            kit.pxText(canvas, ach.name, r.left + kit.sz(100f), r.top + kit.sz(40f),
                       Style.BODY_PX, txtC, Style.PX_BLACK, 4f, Paint.Align.LEFT)
            kit.pxText(canvas, ach.desc, r.left + kit.sz(100f), r.top + kit.sz(72f),
                       Style.SMALL_PX, blend(txtC, Style.PX_GRAY, 0.3f), Style.PX_BLACK, 3f, Paint.Align.LEFT)
            y += kit.sz(98f)
        }
        y += kit.sz(20f)
        kit.pxText(canvas, "─ 뉴스 ─", pad, y, Style.HEADER_PX * 0.65f,
                   Style.TEXT_GREEN, Style.PX_BLACK, 5f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for (line in game.newsTicker.takeLast(10).reversed()) {
            kit.pxText(canvas, line, pad, y, Style.SMALL_PX, Style.TEXT_HI, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            y += kit.sz(36f)
            if (y > height - kit.sz(80f)) break
        }
    }

    private fun drawTrain(canvas: Canvas) {
        modalHeader(canvas, "💬 TRAINING · 어젯밤", Style.NEON_YELLOW)
        val w = width.toFloat(); val pad = kit.sz(28f)
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val r = RectF(pad, kit.sz(210f), w - pad, kit.sz(530f))
        kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
        // mini AI sprite (stage5) on left
        drawAiSprite(canvas, r.left + kit.sz(100f), r.top + kit.sz(150f), 3)
        // quote
        drawWrapped(canvas, "\"${prompt?.ai ?: "(고요한 밤이었다)"}\"",
                    r.left + kit.sz(220f), r.top + kit.sz(70f),
                    r.width() - kit.sz(250f), Style.BODY_PX, kit.sz(48f), Style.NEON_YELLOW)

        // 2x2 choices
        val choices = arrayOf(
            Triple("👍", "칭찬", Style.NEON_GREEN),
            Triple("✏", "수정", Style.NEON_CYAN),
            Triple("👎", "혼내기", Style.NEON_RED),
            Triple("⏳", "방치", Style.NEON_PURPLE)
        )
        val bw = (w - pad * 3) / 2f
        val bh = kit.sz(150f)
        for ((i, c) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bx = pad + col * (bw + pad)
            val by = kit.sz(570f) + row * (bh + kit.sz(20f))
            val br = RectF(bx, by, bx + bw, by + bh)
            kit.pxButton(canvas, br, c.second, c.third, c.first, Style.BUTTON_PX * 1.1f)
            if (prompt != null) {
                val choice = i
                hits.add(br to {
                    Logic.applyTraining(game, choice)
                    flash(c.third, 0.45f); shakeAmt = kit.sz(10f)
                    burst(width / 2f, height * 0.36f, c.third, 22)
                    screen = Screen.PLAY; save()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        modalHeader(canvas, "⚠ INCIDENT · 사고", Style.NEON_RED)
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.PLAY; return }
        val w = width.toFloat(); val pad = kit.sz(28f)
        val r = RectF(pad, kit.sz(210f), w - pad, kit.sz(620f))
        kit.pxPanel(canvas, r, Style.NEON_RED_DK, Style.NEON_RED, Style.PX_BLACK)
        // huge icon
        kit.text.textSize = kit.sz(120f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.PX_WHITE
        canvas.drawText(ev.icon, r.left + kit.sz(110f), r.top + kit.sz(150f), kit.text)
        // name + desc
        kit.pxText(canvas, ev.name, r.left + kit.sz(215f), r.top + kit.sz(80f),
                   Style.HEADER_PX * 0.85f, Style.NEON_YELLOW, Style.PX_BLACK, 6f, Paint.Align.LEFT)
        drawWrapped(canvas, ev.situation,
                    r.left + kit.sz(30f), r.top + kit.sz(210f),
                    r.width() - kit.sz(60f), Style.BODY_PX, kit.sz(50f), Style.PX_WHITE)
        // choices
        val colors = listOf(Style.NEON_CYAN, Style.NEON_YELLOW, Style.NEON_PURPLE)
        for ((i, c) in ev.choices.withIndex()) {
            val by = kit.sz(650f) + i * kit.sz(135f)
            val br = RectF(pad, by, w - pad, by + kit.sz(115f))
            kit.pxButton(canvas, br, "${i + 1}. $c", colors[i], null, Style.BUTTON_PX)
            val idx = game.pendingEventIdx; val ch = i
            hits.add(br to {
                Logic.applyIncident(game, idx, ch)
                flash(colors[i], 0.5f); shakeAmt = kit.sz(16f)
                burst(width / 2f, height * 0.36f, colors[i], 32)
                Logic.checkEnding(game)
                screen = if (game.ended) Screen.ENDING else Screen.PLAY
                save()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // pixel sky background
        kit.pxSky(canvas, w, h, 0.95f, stars)
        // darken
        paint.color = 0xCC000020.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        kit.text.textSize = kit.sz(180f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.NEON_YELLOW
        canvas.drawText(e.icon, w / 2f, h * 0.26f, kit.text)
        kit.pxText(canvas, "─  ENDING  ─", w / 2f, h * 0.34f,
                   Style.HEADER_PX, Style.NEON_CYAN, Style.PX_BLACK, 5f, Paint.Align.CENTER)
        kit.pxText(canvas, e.name, w / 2f, h * 0.42f,
                   Style.TITLE_PX * 0.8f, Style.NEON_PINK, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        drawWrapped(canvas, e.desc, kit.sz(60f), h * 0.52f, w - kit.sz(120f),
                    Style.BODY_PX, kit.sz(54f), Style.PX_WHITE, Paint.Align.CENTER)
        kit.pxText(canvas, "Day ${game.day} · 사고 ${game.incidents} · 태그 ${game.tags.size} · 고양이 ${game.catAttempts}",
                   w / 2f, h * 0.72f,
                   Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.CENTER)
        val rA = RectF(w / 2f - kit.sz(280f), h * 0.78f, w / 2f + kit.sz(280f), h * 0.78f + kit.sz(110f))
        kit.pxButton(canvas, rA, "샌드박스 계속", Style.NEON_GREEN, "▶")
        hits.add(rA to { screen = Screen.PLAY; save() })
        val rB = RectF(w / 2f - kit.sz(280f), h * 0.78f + kit.sz(130f),
                       w / 2f + kit.sz(280f), h * 0.78f + kit.sz(240f))
        kit.pxButton(canvas, rB, "처음부터", Style.NEON_RED, "↻")
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
        roomIdx = 0
        screen = Screen.INTRO
    }

    private fun drawWrapped(canvas: Canvas, t: String, x: Float, y: Float, maxW: Float,
                            sizePx: Float, lineH: Float, color: Int = Style.PX_WHITE,
                            align: Paint.Align = Paint.Align.LEFT) {
        kit.text.textSize = kit.sz(sizePx)
        val words = t.split(" ")
        val drawX = if (align == Paint.Align.CENTER) x + maxW / 2f else x
        var line = ""; var ly = y
        for (wd in words) {
            val test = if (line.isEmpty()) wd else "$line $wd"
            if (kit.text.measureText(test) > maxW) {
                kit.pxText(canvas, line, drawX, ly, sizePx, color, Style.PX_BLACK, 3f, align)
                ly += lineH; line = wd
            } else line = test
        }
        if (line.isNotEmpty())
            kit.pxText(canvas, line, drawX, ly, sizePx, color, Style.PX_BLACK, 3f, align)
    }

    // ───────────────────────── INPUT ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        if (screen == Screen.PLAY) {
            val radius = kit.sz(80f)
            for ((wx, wy, action) in worldHits.asReversed()) {
                val dx = x - wx; val dy = y - wy
                if (dx * dx + dy * dy < radius * radius) { action(); return true }
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
        e.putInt("room", roomIdx)
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
        roomIdx = prefs.getInt("room", 0).coerceIn(0, ROOM_NAMES.size - 1)
    }
}
