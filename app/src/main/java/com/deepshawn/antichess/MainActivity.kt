package com.deepshawn.antichess

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.deepshawn.antichess.core.EndReason
import com.deepshawn.antichess.core.GameStatus
import com.deepshawn.antichess.core.Grade
import com.deepshawn.antichess.core.LEVELS
import com.deepshawn.antichess.core.PREFERENCES
import com.deepshawn.antichess.core.Personality
import com.deepshawn.antichess.core.RANK_NAMES
import com.deepshawn.antichess.core.Sac
import com.deepshawn.antichess.core.START_FEN
import com.deepshawn.antichess.core.Score
import com.deepshawn.antichess.ui.Arrow
import com.deepshawn.antichess.ui.BOARD_THEMES
import com.deepshawn.antichess.ui.BoardView
import com.deepshawn.antichess.ui.Dest
import com.deepshawn.antichess.ui.EvalBarView
import com.deepshawn.antichess.ui.EvalGraphView
import com.deepshawn.antichess.ui.FlowLayout
import com.deepshawn.antichess.ui.IconView
import com.deepshawn.antichess.ui.PALETTES
import com.deepshawn.antichess.ui.Palette
import com.deepshawn.antichess.ui.PieceSet
import com.deepshawn.antichess.ui.Sounds
import com.deepshawn.antichess.ui.dp
import com.deepshawn.antichess.ui.dpf
import com.deepshawn.antichess.ui.gradient
import com.deepshawn.antichess.ui.rounded
import com.deepshawn.antichess.ui.withAlpha
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var ctl: GameController
    private lateinit var pieces: PieceSet
    private val sounds = Sounds()
    private lateinit var pal: Palette

    private lateinit var root: FrameLayout
    private lateinit var board: BoardView
    private lateinit var evalBar: EvalBarView
    private lateinit var graph: EvalGraphView
    private lateinit var playerTop: LinearLayout
    private lateinit var playerBottom: LinearLayout
    private lateinit var modeTabs: List<TextView>
    private lateinit var turnLine: TextView
    private lateinit var tools: Map<String, View>
    private lateinit var enginePill: TextView
    private lateinit var openingEco: TextView
    private lateinit var openingName: TextView
    private lateinit var openingEn: TextView
    private lateinit var openingBook: TextView
    private lateinit var engineTitle: TextView
    private lateinit var engineMeta: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var engineNote: TextView
    private lateinit var reviewBtn: TextView
    private lateinit var accWhite: TextView
    private lateinit var accBlack: TextView
    private lateinit var moveList: FlowLayout
    private var whiteBottom = true
    private var lastPosKey = ""
    // Content signatures: views are rebuilt only when what they show changes (rebuilding
    // them on every engine update made the scrolling content jump).
    private var playersSig = ""
    private var engineSig = ""
    private var movesSig = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installCrashRecorder()
        pieces = PieceSet(assets.open("pieces.json").bufferedReader().readText())
        ctl = GameController(this, scope)
        ctl.onChange = { render() }
        ctl.onEvent = ::handleEvent
        buildUi()
        ctl.start()
        syncOrientation()
        render()
        showLastCrash()
    }

    // ───────────── crash reporting ─────────────
    // Without logcat access, a crash is written to app storage and shown on the next launch.
    private val crashFile get() = java.io.File(filesDir, "last-crash.txt")

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText("谱着 Chess Lab ${packageManager.getPackageInfo(packageName, 0).versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\nthread: ${thread.name}\n\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLastCrash() {
        val report = runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        crashFile.delete()
        sheet("上次异常退出") { dialog ->
            addView(label("应用上次意外关闭。复制下面的信息发给开发者，可以帮助定位问题。", 13f, pal.muted), lp(-1, -2) { bottomMargin = dp(10) })
            addView(label(report.take(4000), 10.5f, pal.text).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(pal.panel2, dpf(10)) }, lp(-1, -2))
            addView(button("复制错误信息", primary = true) { clipboard().setPrimaryClip(ClipData.newPlainText("crash", report)); toast("已复制"); dialog.dismiss() }, lp(-1, -2) { topMargin = dp(12) })
        }
    }

    override fun onPause() { ctl.persist(); super.onPause() }
    override fun onStart() { super.onStart(); ctl.onForeground() }
    override fun onStop() { ctl.onBackground(); super.onStop() }
    override fun onDestroy() { ctl.close(); scope.cancel(); super.onDestroy() }

    // ───────────── building blocks ─────────────
    private fun label(text: String, size: Float, color: Int = pal.text, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(pal.panel, dpf(18), pal.line, dp(1)); elevation = dpf(2)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, block: LinearLayout.LayoutParams.() -> Unit = {}) = LinearLayout.LayoutParams(w, h, weight).apply(block)

    private fun iconButton(kind: String, accent: Boolean = false, action: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = if (accent) gradient(pal.accent, pal.accentDeep, dpf(12)) else rounded(pal.panel, dpf(12), pal.line, dp(1))
        addView(IconView(context, kind, if (accent) pal.onAccent else pal.text), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
        foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(withAlpha(pal.accent, 0.25f)), null, rounded(Color.WHITE, dpf(12)))
    }

    private fun pill(text: String, on: Boolean, action: () -> Unit) = label(text, 13f, if (on) pal.onAccent else pal.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(10), dp(14), dp(10))
        background = if (on) gradient(pal.accent, pal.accentDeep, dpf(11)) else rounded(pal.panel2, dpf(11), pal.line, dp(1))
        setOnClickListener { action() }
    }

    private inner class PieceIcon(private val code: String, private val bg: Int?) : View(this@MainActivity) {
        override fun onDraw(canvas: Canvas) {
            bg?.let { val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it }; canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dpf(11), dpf(11), p) }
            val s = minOf(width, height) * if (bg != null) 0.84f else 1f
            pieces.draw(canvas, code, (width - s) / 2, (height - s) / 2, s)
        }
    }

    private inner class CapturedView : View(this@MainActivity) {
        var codes: List<String> = emptyList(); var adv = 0
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(16) * codes.size + dp(if (codes.isEmpty()) 0 else 10) + if (adv > 0) dp(28) else 0, dp(22))
        override fun onDraw(canvas: Canvas) {
            var x = 0f
            for (c in codes) { pieces.draw(canvas, c, x, 0f, dpf(22)); x += dpf(16) }
            if (adv > 0) { tp.color = pal.muted; tp.textSize = dpf(12); canvas.drawText("+$adv", x + dpf(10), dpf(16), tp) }
        }
    }

    // ───────────── layout ─────────────
    private fun buildUi() {
        pal = PALETTES.firstOrNull { it.id == ctl.themeId } ?: PALETTES[0]
        sounds.enabled = ctl.soundOn
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (pal.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        root = FrameLayout(this).apply { background = gradient(pal.bg2, pal.bg, 0f) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            column.setPadding(0, top, 0, bottom); insets
        }

        // Top bar
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        val logo = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                val bg = gradient(0xff8af0c6.toInt(), 0xff2a8f6c.toInt(), dpf(12)); bg.setBounds(0, 0, width, height); bg.draw(canvas)
                pieces.draw(canvas, "wn", width * 0.12f, height * 0.1f, width * 0.76f)
            }
        }
        top.addView(logo, lp(dp(40), dp(40)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        titles.addView(label("OPENING INTELLIGENCE", 9f, pal.accent, true).apply { letterSpacing = 0.2f })
        titles.addView(TextView(this).apply {
            text = SpannableStringBuilder("谱着 Chess Lab").apply { setSpan(ForegroundColorSpan(pal.muted), 3, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            textSize = 19f; setTextColor(pal.text); typeface = Typeface.DEFAULT_BOLD
        })
        top.addView(titles, lp(0, -2, 1f))
        top.addView(iconButton("plus", accent = true) { newGameDialog() }, lp(dp(40), dp(40)))
        top.addView(iconButton("share") { shareDialog() }, lp(dp(40), dp(40)) { marginStart = dp(8) })
        top.addView(iconButton("settings") { settingsDialog() }, lp(dp(40), dp(40)) { marginStart = dp(8) })
        column.addView(top)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        column.addView(scroll, lp(-1, 0, 1f))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        scroll.addView(content)

        // Mode switch + engine pill
        val seg = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)); background = rounded(pal.panel, dpf(13), pal.line, dp(1)) }
        modeTabs = Mode.entries.map { m ->
            label(m.label, 14f, pal.muted, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); tag = m; setOnClickListener { ctl.setMode(m); syncOrientation(); render() } }
        }
        modeTabs.forEach { seg.addView(it, lp(0, -2, 1f)) }
        content.addView(seg, lp(-1, -2) { bottomMargin = dp(8) })

        playerTop = LinearLayout(this); content.addView(playerTop, lp(-1, dp(52)))

        val boardRow = LinearLayout(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        evalBar = EvalBarView(this)
        boardRow.addView(evalBar, lp(dp(12), -1) { topMargin = dp(6); bottomMargin = dp(6); marginEnd = dp(6) })
        val frame = FrameLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); background = rounded(pal.panel, dpf(16), pal.line, dp(1)); elevation = dpf(6) }
        board = BoardView(this, pieces).apply { onMove = { f, t, p -> ctl.userMove(f, t, p) }; clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; background = rounded(Color.BLACK, dpf(9)) }
        frame.addView(board)
        boardRow.addView(frame, lp(0, -2, 1f))
        content.addView(boardRow)

        playerBottom = LinearLayout(this); content.addView(playerBottom, lp(-1, dp(52)))

        // Toolbar
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tools = linkedMapOf(
            "undo" to iconButton("undo") { ctl.undo() }, "bulb" to iconButton("bulb") { ctl.showHint() },
            "pause" to iconButton("pause") { ctl.togglePause() },
            "flip" to iconButton("flip") { whiteBottom = !whiteBottom; render() },
            "first" to iconButton("first") { ctl.seek(0) }, "prev" to iconButton("prev") { ctl.seek(ctl.view - 1) },
            "next" to iconButton("next") { ctl.seek(ctl.view + 1) }, "last" to iconButton("last") { ctl.seek(ctl.game.ply) },
        )
        tools.values.forEachIndexed { i, v -> toolbar.addView(v, lp(0, dp(42), 1f) { if (i > 0) marginStart = dp(6) }) }
        content.addView(toolbar, lp(-1, -2) { topMargin = dp(4) })
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        turnLine = label("", 12.5f, pal.muted).apply { maxLines = 1 }
        enginePill = label("引擎加载中", 11.5f, pal.muted).apply { maxLines = 1 }
        statusRow.addView(turnLine, lp(0, -2, 1f)); statusRow.addView(enginePill)
        content.addView(statusRow, lp(-1, -2))

        // Opening card
        val opening = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        openingEco = label("—", 13f, pal.accent, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(withAlpha(pal.accent, 0.16f), dpf(10)); minWidth = dp(48) }
        opening.addView(openingEco)
        val otext = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        openingName = label("", 15f, pal.text, true); openingEn = label("", 11.5f, pal.muted).apply { maxLines = 1 }
        otext.addView(openingName); otext.addView(openingEn, lp(-2, -2) { topMargin = dp(3) })
        opening.addView(otext, lp(0, -2, 1f))
        openingBook = label("", 11f, pal.muted).apply { setPadding(dp(9), dp(4), dp(9), dp(4)) }
        opening.addView(openingBook)
        content.addView(opening, lp(-1, -2) { topMargin = dp(10) })

        // Engine card
        val engineCard = card()
        val eh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        engineTitle = label("谱着候选", 14.5f, pal.text, true); engineMeta = label("", 11.5f, pal.muted)
        eh.addView(engineTitle, lp(0, -2, 1f)); eh.addView(engineMeta)
        engineCard.addView(eh, lp(-1, -2) { bottomMargin = dp(10) })
        // A stable minimum height keeps the cards below from moving while lines stream in.
        candidates = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; minimumHeight = dp(3 * 46) }
        engineCard.addView(candidates)
        engineNote = label("", 12f, pal.accent).apply { setPadding(dp(11), dp(9), dp(11), dp(9)); background = rounded(withAlpha(pal.accent, 0.09f), dpf(10)) }
        engineCard.addView(engineNote, lp(-1, -2) { topMargin = dp(10) })
        content.addView(engineCard, lp(-1, -2) { topMargin = dp(12) })

        // Review card
        val reviewCard = card()
        val rh = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rh.addView(label("评估曲线", 14.5f, pal.text, true), lp(0, -2, 1f))
        reviewBtn = label("全盘复盘", 12f, pal.text, true).apply { setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(pal.panel2, dpf(9), pal.line, dp(1)); setOnClickListener { ctl.review() } }
        rh.addView(reviewBtn)
        reviewCard.addView(rh, lp(-1, -2) { bottomMargin = dp(10) })
        graph = EvalGraphView(this).apply { palette = pal; onSeek = { ctl.seek(it) } }
        reviewCard.addView(graph, lp(-1, dp(110)))
        val accRow = LinearLayout(this)
        accWhite = label("", 12f, pal.muted).apply { setPadding(dp(11), dp(9), dp(11), dp(9)); background = rounded(pal.panel2, dpf(11)) }
        accBlack = label("", 12f, pal.muted).apply { setPadding(dp(11), dp(9), dp(11), dp(9)); background = rounded(pal.panel2, dpf(11)) }
        accRow.addView(accWhite, lp(0, -2, 1f) { marginEnd = dp(4) }); accRow.addView(accBlack, lp(0, -2, 1f) { marginStart = dp(4) })
        reviewCard.addView(accRow, lp(-1, -2) { topMargin = dp(10) })
        content.addView(reviewCard, lp(-1, -2) { topMargin = dp(12) })

        // Moves card
        val movesCard = card()
        movesCard.addView(label("走法记录", 14.5f, pal.text, true), lp(-1, -2) { bottomMargin = dp(10) })
        moveList = FlowLayout(this, dp(4), dp(4))
        movesCard.addView(moveList)
        content.addView(movesCard, lp(-1, -2) { topMargin = dp(12) })

        content.addView(label("谱着 Chess Lab · 引擎 Stockfish 19（GPLv3）· 棋子 Cburnett（CC BY-SA 3.0）", 10.5f, pal.faint).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }, lp(-1, -2))

        setContentView(root)
        lastPosKey = ""; playersSig = ""; engineSig = ""; movesSig = ""
    }

    private fun syncOrientation() {
        whiteBottom = when (ctl.mode) {
            Mode.PLAY -> ctl.playerWhite
            Mode.PVP -> if (ctl.autoFlip) ctl.game.whiteToMove else whiteBottom
            else -> true
        }
    }

    // ───────────── rendering ─────────────
    private fun render() {
        if (!::board.isInitialized) return
        val g = ctl.game; val view = ctl.view
        val viewGame = if (view == g.ply) g else com.deepshawn.antichess.core.Game(ctl.fenAt(view))
        val st = viewGame.status()
        val last = if (view > 0) g.history[view - 1] else null
        var checkSq: String? = null
        if (viewGame.inCheck()) viewGame.board().forEachIndexed { r, row -> row.forEachIndexed { c, p -> if (p != null && p.type == 'k' && p.white == viewGame.whiteToMove) checkSq = "${'a' + c}${8 - r}" } }
        val posKey = "${g.startFen}|$view|${g.ply}"
        board.theme = BOARD_THEMES.firstOrNull { it.id == ctl.boardId } ?: BOARD_THEMES[0]
        board.accent = pal.accent; board.showCoords = ctl.coords; board.whiteBottom = whiteBottom
        board.setPosition(viewGame.board(), last?.let { it.from to it.to }, checkSq, animate = lastPosKey.isNotEmpty() && posKey != lastPosKey)
        lastPosKey = posKey

        val analysis = ctl.mode == Mode.ANALYSIS
        val canMove = !st.over && when (ctl.mode) {
            Mode.ANALYSIS -> true
            Mode.PVP -> ctl.atEnd
            Mode.PLAY -> ctl.atEnd && !ctl.thinking && g.whiteToMove == ctl.playerWhite
            Mode.WATCH -> false
        }
        val dests = HashMap<String, MutableList<Dest>>()
        if (canMove) {
            val legal = viewGame.legalUci()
            for (u in legal) { val list = dests.getOrPut(u.substring(0, 2)) { mutableListOf() }; val to = u.substring(2, 4); if (list.none { it.to == to }) list.add(Dest(to, u.length == 5)) }
        }
        board.setInteractive(if (canMove && !analysis) g.whiteToMove else null, canMove && analysis, dests)

        val arrows = ArrayList<Arrow>()
        ctl.hint?.takeIf { ctl.atEnd }?.let { arrows.add(Arrow(it.substring(0, 2), it.substring(2, 4), Color.argb(235, 245, 166, 35), 0.18f)) }
        if (analysis && ctl.arrows && ctl.atEnd) ctl.live?.lines?.take(3)?.forEachIndexed { i, l ->
            val u = l.pv.firstOrNull() ?: return@forEachIndexed
            val color = listOf(Color.argb(230, 31, 140, 100), Color.argb(160, 60, 127, 216), Color.argb(115, 142, 99, 201))[i]
            arrows.add(Arrow(u.substring(0, 2), u.substring(2, 4), color, listOf(0.19f, 0.14f, 0.11f)[i], "${i + 1}"))
        }
        board.setArrows(arrows)

        evalBar.visibility = if (ctl.evalBar) View.VISIBLE else View.GONE
        evalBar.whiteBottom = whiteBottom
        evalBar.set(ctl.evals[view]?.score)

        modeTabs.forEach { t ->
            val on = t.tag == ctl.mode
            t.setTextColor(if (on) pal.onAccent else pal.muted)
            t.background = if (on) gradient(pal.accent, pal.accentDeep, dpf(9)) else null
        }
        enginePill.text = (if (ctl.thinking || ctl.reviewProgress != null) "● " else "") + ctl.engineLabel()
        enginePill.setTextColor(if (ctl.thinking) pal.gold else pal.muted)

        renderPlayers(viewGame)
        renderOpening()
        renderEngine()
        renderMoves()
        graph.set((0..g.ply).map { ctl.evals[it]?.score }, ctl.grades, view)
        reviewBtn.text = ctl.reviewProgress?.let { "复盘中 $it%" } ?: "全盘复盘"
        accWhite.text = accText("白方准确率", ctl.accuracy(true), true)
        accBlack.text = accText("黑方准确率", ctl.accuracy(false), false)

        tools["undo"]?.isEnabled = g.ply > 0
        tools["bulb"]?.isEnabled = ctl.mode == Mode.PLAY && ctl.atEnd && !ctl.thinking && !st.over
        tools["bulb"]?.visibility = if (ctl.mode == Mode.WATCH) View.GONE else View.VISIBLE
        tools["pause"]?.visibility = if (ctl.mode == Mode.WATCH) View.VISIBLE else View.GONE
        tools["pause"]?.isEnabled = !st.over
        ((tools["pause"] as? FrameLayout)?.getChildAt(0) as? IconView)?.kind = if (ctl.paused) "play" else "pause"
        tools["first"]?.isEnabled = view > 0; tools["prev"]?.isEnabled = view > 0
        tools["next"]?.isEnabled = !ctl.atEnd; tools["last"]?.isEnabled = !ctl.atEnd
        tools.values.forEach { v -> (v as FrameLayout).getChildAt(0).isEnabled = v.isEnabled; v.alpha = if (v.isEnabled) 1f else 0.5f }

        turnLine.setTextColor(if (!ctl.atEnd) pal.gold else pal.muted)
        turnLine.text = when {
            !ctl.atEnd -> "查看第 $view 步 · 点 › 回到当前"
            st.over -> resultText(st)
            analysis -> "${if (g.whiteToMove) "白" else "黑"}方走 · 分析模式"
            ctl.mode == Mode.PVP -> "${if (g.whiteToMove) "白" else "黑"}方走棋 · 双人同屏${if (st.check) " · 被将军！" else ""}"
            ctl.mode == Mode.WATCH -> if (ctl.paused) "观战已暂停" else "${if (g.whiteToMove) "白" else "黑"}方 AI 思考中…"
            ctl.thinking -> "谱着正在思考…"
            else -> "轮到你 · 执${if (ctl.playerWhite) "白" else "黑"}${if (st.check) " · 被将军！" else ""}"
        }
    }

    private fun accText(title: String, v: Double?, white: Boolean): CharSequence {
        val counts = ctl.grades.values.filter { it.whiteMoved == white }
        val b = SpannableStringBuilder("$title\n")
        val start = b.length
        b.append(v?.let { "%.1f%%".format(it) } ?: "—")
        b.setSpan(ForegroundColorSpan(pal.text), start, b.length, 0)
        b.setSpan(android.text.style.RelativeSizeSpan(1.55f), start, b.length, 0)
        b.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, b.length, 0)
        b.append("\n漏着 ${counts.count { it.grade == Grade.BLUNDER }} · 失误 ${counts.count { it.grade == Grade.MISTAKE }} · 欠准 ${counts.count { it.grade == Grade.INACCURACY }}")
        return b
    }

    private fun renderPlayers(viewGame: com.deepshawn.antichess.core.Game) {
        val count = mapOf(true to IntArray(6), false to IntArray(6))
        val types = "pnbrq"
        viewGame.board().forEach { row -> row.forEach { p -> if (p != null && p.type != 'k') count[p.white]!![types.indexOf(p.type)]++ } }
        val start = intArrayOf(8, 2, 2, 2, 1); val value = intArrayOf(1, 3, 3, 5, 9)
        fun score(w: Boolean) = (0..4).sumOf { count[w]!![it] * value[it] }
        fun lost(w: Boolean) = (0..4).flatMap { t -> List(maxOf(0, start[t] - count[w]!![t])) { (if (w) "w" else "b") + types[t] } }
        val over = viewGame.status().over
        data class P(val white: Boolean, val name: String, val sub: String, val ai: Boolean, val active: Boolean, val thinking: Boolean, val codes: List<String>, val adv: Int)
        val players = listOf(!whiteBottom, whiteBottom).map { white ->
            val side = if (white) "白" else "黑"
            val ai = ctl.isAI(white)
            val lv = ctl.levelFor(white)
            val (name, sub) = when {
                ctl.mode == Mode.ANALYSIS -> "${side}方" to "分析模式 · 自由走子"
                ctl.mode == Mode.PVP -> "${side}方 · 玩家${if (white) "一" else "二"}" to "双人同屏"
                ai -> "${if (ctl.mode == Mode.WATCH) "${side}方 " else ""}谱着 AI · ${lv.name}" to "参考 ${lv.elo} Elo · ${ctl.preference.name} · ${ctl.engine?.name ?: "加载中"}"
                else -> "你" to "执$side · ${ctl.preference.name}对手"
            }
            val active = !over && viewGame.whiteToMove == white
            P(white, name, sub, ai, active, ai && ctl.thinking && active, lost(!white), score(white) - score(!white))
        }
        val sig = players.joinToString("|")
        if (sig == playersSig) return
        playersSig = sig
        for ((container, p) in listOf(playerTop, playerBottom).zip(players)) {
            container.removeAllViews()
            container.gravity = Gravity.CENTER_VERTICAL
            container.setPadding(dp(8), dp(6), dp(12), dp(6))
            container.background = if (p.active) rounded(pal.panel, dpf(14), pal.line, dp(1)) else null
            container.addView(PieceIcon((if (p.white) "w" else "b") + if (p.ai) "n" else "k", if (p.white) 0xffe6ece9.toInt() else 0xff27313a.toInt()), lp(dp(38), dp(38)))
            val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            meta.addView(label(p.name + if (p.thinking) "  · 思考中" else "", 14f, if (p.thinking) pal.accent else pal.text, true).apply { maxLines = 1 })
            meta.addView(label(p.sub, 11.5f, pal.muted).apply { maxLines = 1 }, lp(-2, -2) { topMargin = dp(3) })
            container.addView(meta, lp(0, -2, 1f))
            container.addView(CapturedView().apply { codes = p.codes; adv = p.adv })
        }
    }

    private fun renderOpening() {
        val g = ctl.game
        val fens = listOf(g.startFen) + g.history.take(ctl.view).map { it.fen }
        val standard = g.startFen == START_FEN
        val hit = if (standard) ctl.book?.identify(fens) else null
        val inBook = ctl.book?.candidates(ctl.fenAt(ctl.view))?.isNotEmpty() == true
        openingEco.text = hit?.eco ?: if (standard) "—" else "FEN"
        openingName.text = hit?.name ?: if (standard) "初始局面" else "自定义局面"
        openingEn.text = hit?.english ?: if (standard) "Starting position" else "Custom setup"
        openingBook.text = if (inBook) "谱着库内" else "已出谱"
        openingBook.setTextColor(if (inBook) pal.gold else pal.muted)
        openingBook.background = rounded(Color.TRANSPARENT, dpf(99), if (inBook) withAlpha(pal.gold, 0.5f) else pal.line, dp(1))
    }

    private fun candidateRow(rank: Int, san: String, score: Score?, info: String?, prob: Double?, chosen: Boolean, slip: Boolean, sac: Sac? = null): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9))
            background = when {
                chosen -> rounded(withAlpha(pal.accent, 0.13f), dpf(11), withAlpha(pal.accent, 0.65f), dp(1))
                slip -> rounded(pal.panel2, dpf(11), withAlpha(pal.bad, 0.6f), dp(1))
                else -> rounded(pal.panel2, dpf(11))
            }
        }
        val rankColor = when (rank) { 0 -> pal.accent; 1 -> pal.accent2; 2 -> pal.gold; -1 -> pal.bad; else -> pal.muted }
        row.addView(label(if (slip) "错棋" else RANK_NAMES.getOrElse(rank) { "候选" }, 11f, rankColor, true), lp(dp(34), -2))
        row.addView(TextView(this).apply {
            val b = SpannableStringBuilder(san)
            sac?.let { val s0 = b.length; b.append(it.mark); b.setSpan(ForegroundColorSpan(sacColor(it)), s0, b.length, 0) }
            text = b; textSize = 15f; setTextColor(pal.text); typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
        }, lp(dp(64), -2))
        row.addView(label(info ?: "", 11f, pal.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, -2, 1f))
        score?.let {
            val pos = it.centipawns >= 0
            row.addView(label(it.format(), 11.5f, if (pos) Color.rgb(34, 34, 34) else Color.rgb(238, 238, 238), true).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setPadding(dp(6), dp(3), dp(6), dp(3))
                background = rounded(if (pos) Color.argb(235, 255, 255, 255) else Color.rgb(32, 37, 42), dpf(6))
            }, lp(-2, -2) { marginStart = dp(6) })
        }
        prob?.let { row.addView(label("${Math.round(it * 100)}%", 11.5f, pal.gold, true).apply { gravity = Gravity.END }, lp(dp(40), -2)) }
        if (chosen) row.addView(label("✓", 14f, pal.accent, true), lp(-2, -2) { marginStart = dp(4) })
        return row
    }

    private fun renderEngine() {
        val live = ctl.live
        engineMeta.text = live?.takeIf { it.depth > 0 }?.let { "深度 ${it.depth}" + if (it.nps > 0) " · ${it.nps / 1000} kn/s" else "" } ?: ctl.engineLabel()
        // Rows are described first (key + factory) so identical content does not rebuild views.
        val rows = ArrayList<Pair<String, () -> View>>()
        fun row(rank: Int, san: String, score: Score?, info: String?, prob: Double?, chosen: Boolean, slip: Boolean, sac: Sac? = null) =
            rows.add("$rank|$san|${score?.format()}|$info|${prob?.let { Math.round(it * 100) }}|$chosen|$slip|$sac" to { candidateRow(rank, san, score, info, prob, chosen, slip, sac) })
        fun text(t: String) = rows.add(t to { label(t, 12.5f, pal.muted) })
        val fen = ctl.fenAt(ctl.view)
        val side = if (ctl.game.whiteToMove) "白" else "黑"
        val decidedPly = if ((ctl.mode == Mode.PLAY || ctl.mode == Mode.WATCH) && ctl.atEnd) (ctl.game.ply downTo maxOf(1, ctl.game.ply - 1)).firstOrNull { ctl.decisions.containsKey(it) } else ctl.view + 1
        val d = if (ctl.thinking) null else decidedPly?.let { ctl.decisions[it] }
        when {
            ctl.thinking -> {
                engineTitle.text = if (ctl.mode == Mode.WATCH) "${side}方 AI 思考中" else "谱着思考中"
                val whiteTurn = ctl.game.whiteToMove
                live?.lines?.take(ctl.levelFor(whiteTurn).multipv)?.forEachIndexed { i, l ->
                    val san = l.pv.firstOrNull()?.let { ctl.sanOrNull(fen, it) } ?: return@forEachIndexed
                    row(i, san, if (whiteTurn) l.score else -l.score, ctl.pvSan(fen, l.pv, 5).drop(1).joinToString(" "), null, false, false)
                }
                if (rows.isEmpty()) text("正在计算候选着法…")
                engineNote.text = "✦ 按「${ctl.preference.name}」从候选中抽样，模拟真人选择"
            }
            d != null -> {
                engineTitle.text = d.sacrifice?.let { "弃子${it.label}！" } ?: when (d.source) { Personality.Source.BOOK -> "谱着库选择"; Personality.Source.BLUNDER -> "错棋注入"; else -> "谱着候选" }
                d.candidates.forEachIndexed { i, c -> row(if (c.slip) -1 else i, c.san, c.score, c.label, c.prob, c.uci == d.uci, c.slip, c.sac) }
                val rank = if (d.rank >= 0) RANK_NAMES.getOrElse(d.rank) { "候选" } else "非候选"
                engineNote.text = when (d.source) {
                    Personality.Source.BOOK -> "✦ 开局阶段：从大师谱着中按权重抽中$rank"
                    Personality.Source.BLUNDER -> "⚑ 低频错棋注入：这一步故意偏离引擎，给对手反击机会"
                    else -> d.sacrifice?.let { "✦ ${it.label} ${it.mark}：弃掉价值 ${d.sacAmount} 兵的子力换取攻势，引擎深度 ${d.depth} 仍认可这步" }
                        ?: "✦ 引擎深度 ${d.depth} 评估后，按偏好抽中$rank"
                }
            }
            ctl.mode == Mode.ANALYSIS || (!ctl.atEnd && ctl.evals[ctl.view] != null) -> {
                engineTitle.text = "引擎分析"
                val whiteTurn = ctl.turnWhiteAt(ctl.view)
                val lines = if (ctl.atEnd) live?.lines.orEmpty() else emptyList()
                lines.take(3).forEachIndexed { i, l ->
                    val san = l.pv.firstOrNull()?.let { ctl.sanOrNull(fen, it) } ?: return@forEachIndexed
                    row(i, san, if (whiteTurn) l.score else -l.score, ctl.pvSan(fen, l.pv, 7).drop(1).joinToString(" "), null, false, false)
                }
                if (rows.isEmpty()) ctl.evals[ctl.view]?.let { e -> e.best?.let { row(0, ctl.sanOf(fen, it), e.score, null, null, false, false) } }
                if (rows.isEmpty()) text("分析中…")
                engineNote.text = if (ctl.mode == Mode.ANALYSIS) "◎ 棋盘箭头显示前三候选 · ${if (ctl.engine?.isStockfish == true) "分析到 $ANALYSIS_DEPTH 层后自动停止以省电" else "持续分析中"}" else "◎ 历史局面评估"
            }
            ctl.mode == Mode.PVP -> {
                engineTitle.text = "双人同屏"
                text("${side}方走棋。引擎只在后台记录评估，不给提示；终局后可看评估曲线、准确率并一键复盘。")
                engineNote.text = "⇄ 自动翻转棋盘：${if (ctl.autoFlip) "开（可在设置中关闭）" else "关（可在设置中开启）"}"
            }
            ctl.mode == Mode.WATCH -> {
                engineTitle.text = "AI 观战"
                text(if (ctl.paused) "观战已暂停，点 ▶ 继续。" else "双方 AI 轮流思考，这里会展示每一步的候选着法与抽样概率。")
                engineNote.text = "◎ 白方 ${ctl.levelFor(true).name} 对黑方 ${ctl.levelFor(false).name}"
            }
            else -> {
                engineTitle.text = "谱着候选"
                text("轮到你走棋。引擎回应时，这里会展示它考虑的候选着法与抽样概率。")
                engineNote.text = "💡 需要帮助？点灯泡查看引擎推荐"
            }
        }
        val sig = rows.joinToString("\n") { it.first }
        if (sig == engineSig) return
        engineSig = sig
        candidates.removeAllViews()
        for ((_, make) in rows) candidates.addView(make(), lp(-1, -2) { bottomMargin = dp(6) })
    }

    private fun renderMoves() {
        val g = ctl.game
        val sig = "${g.startFen}|${g.ply}|${ctl.view}|${(1..g.ply).joinToString(",") { "${ctl.grades[it]?.grade}${ctl.decisions[it]?.let { d -> "${d.source}${d.rank}${d.sacrifice}" }}" }}"
        if (sig == movesSig) return
        movesSig = sig
        moveList.removeAllViews()
        if (g.ply == 0) { moveList.addView(label("尚未落子 · 拖动或点击棋子开始", 12.5f, pal.muted)); return }
        val blackFirst = g.startFen.split(' ')[1] == "b"; val startNo = g.startFen.split(' ')[5].toIntOrNull() ?: 1
        g.history.forEachIndexed { i, rec ->
            val ply = i + 1; val idx = i + if (blackFirst) 1 else 0
            val b = SpannableStringBuilder()
            if (idx % 2 == 0 || i == 0) { b.append("${startNo + idx / 2}${if (idx % 2 == 0) "." else "…"} "); b.setSpan(ForegroundColorSpan(pal.faint), 0, b.length, 0) }
            b.append(rec.san)
            ctl.grades[ply]?.takeIf { it.grade.mark.isNotEmpty() }?.let { gr ->
                val s = b.length; b.append(gr.grade.mark)
                b.setSpan(ForegroundColorSpan(when (gr.grade) { Grade.INACCURACY -> 0xffe8c547.toInt(); Grade.MISTAKE -> 0xfff08a3c.toInt(); else -> 0xffe5484d.toInt() }), s, b.length, 0)
            }
            ctl.decisions[ply]?.let { d ->
                val tag = when { d.sacrifice != null -> " ${d.sacrifice.mark}"; d.source == Personality.Source.BOOK -> " 谱"; d.source == Personality.Source.BLUNDER -> " 误"; d.rank > 0 -> " ${d.rank + 1}"; else -> null }
                tag?.let { val s = b.length; b.append(it); b.setSpan(ForegroundColorSpan(if (d.sacrifice != null) sacColor(d.sacrifice) else if (d.source == Personality.Source.BOOK) pal.gold else if (d.source == Personality.Source.BLUNDER) pal.bad else pal.accent2), s, b.length, 0); b.setSpan(android.text.style.RelativeSizeSpan(0.8f), s, b.length, 0) }
            }
            val current = ctl.view == ply
            moveList.addView(TextView(this).apply {
                text = b; textSize = 13.5f; setTextColor(pal.text); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = if (current) rounded(withAlpha(pal.accent, 0.22f), dpf(8), withAlpha(pal.accent, 0.55f), dp(1)) else rounded(pal.panel2, dpf(8))
                setOnClickListener { ctl.seek(ply) }
            })
        }
    }

    private fun sacColor(s: Sac) = if (s == Sac.BRILLIANT) 0xff26c6da.toInt() else 0xff7cc0ff.toInt()

    private fun resultText(st: GameStatus): String {
        val reason = st.reason?.label ?: ""
        return if (st.result == "1/2-1/2") "和棋 · $reason" else "${if (st.result == "1-0") "白方" else "黑方"}胜 · $reason"
    }

    // ───────────── events ─────────────
    private fun handleEvent(e: UiEvent) {
        when (e) {
            is UiEvent.Moved -> {
                sounds.play(if (e.record.check) "check" else if (e.record.captured != null) "capture" else "move")
                // Hot-seat: turn the board to the player whose move it is, after the move animation.
                if (ctl.mode == Mode.PVP && ctl.autoFlip) {
                    val ply = ctl.game.ply
                    board.postDelayed({ if (ctl.mode == Mode.PVP && ctl.game.ply == ply && !ctl.game.status().over) { whiteBottom = ctl.game.whiteToMove; render() } }, 520)
                }
            }
            is UiEvent.Toast -> toast(e.text)
            is UiEvent.GameOver -> { sounds.play("end"); board.postDelayed({ gameOverDialog(e.status) }, 650) }
        }
    }

    private fun toast(text: String) {
        val t = label(text, 13f, pal.text).apply {
            setPadding(dp(16), dp(11), dp(16), dp(11)); background = rounded(pal.panel, dpf(12), withAlpha(pal.accent, 0.4f), dp(1)); elevation = dpf(10)
            alpha = 0f; translationY = dpf(14)
        }
        root.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(56) })
        t.animate().alpha(1f).translationY(0f).setDuration(260).start()
        t.postDelayed({ t.animate().alpha(0f).translationY(dpf(10)).setDuration(260).withEndAction { root.removeView(t) }.start() }, 2400)
    }

    // ───────────── dialogs ─────────────
    private fun sheet(title: String, build: LinearLayout.(Dialog) -> Unit): Dialog {
        val dialog = Dialog(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(22)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title, 19f, pal.text, true), lp(0, -2, 1f))
        head.addView(iconButton("close") { dialog.dismiss() }.apply { background = null }, lp(dp(36), dp(36)))
        body.addView(head, lp(-1, -2) { bottomMargin = dp(8) })
        body.build(dialog)
        val scroll = ScrollView(this).apply { addView(body); background = rounded(pal.panel, dpf(24)).apply { cornerRadii = floatArrayOf(dpf(24), dpf(24), dpf(24), dpf(24), 0f, 0f, 0f, 0f) } }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
        return dialog
    }

    private fun LinearLayout.section(title: String) = addView(label(title, 11.5f, pal.muted, true).apply { letterSpacing = 0.1f; setPadding(0, dp(14), 0, dp(8)) })

    private fun <T> LinearLayout.choiceRow(items: List<Pair<T, String>>, selected: T, columns: Int, onPick: (T) -> Unit) {
        var current = selected
        val views = ArrayList<TextView>()
        var row: LinearLayout? = null
        items.forEachIndexed { i, (value, text) ->
            if (i % columns == 0) { row = LinearLayout(context); addView(row, lp(-1, -2) { bottomMargin = dp(8) }) }
            val v = label(text, 13f, pal.text, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12)); tag = value }
            fun paint() = views.forEach { tv -> val on = tv.tag == current; tv.background = rounded(if (on) withAlpha(pal.accent, 0.14f) else pal.panel2, dpf(12), if (on) pal.accent else pal.line, dp(if (on) 2 else 1)) }
            v.setOnClickListener { current = value; paint(); onPick(value) }
            views.add(v)
            row!!.addView(v, lp(0, -2, 1f) { if (i % columns > 0) marginStart = dp(8) })
            paint()
        }
        val pad = (columns - items.size % columns) % columns
        repeat(pad) { row!!.addView(View(context), lp(0, 1, 1f) { marginStart = dp(8) }) }
    }

    private fun LinearLayout.toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 14f, pal.text, true)); texts.addView(label(sub, 11.5f, pal.muted), lp(-2, -2) { topMargin = dp(3) })
        row.addView(texts, lp(0, -2, 1f))
        @Suppress("DEPRECATION")
        row.addView(Switch(context).apply { isChecked = value; setOnCheckedChangeListener { _, c -> onChange(c) } })
        addView(row)
    }

    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = label(text, 14f, if (primary) pal.onAccent else pal.text, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(13), dp(14), dp(13))
        background = if (primary) gradient(pal.accent, pal.accentDeep, dpf(13)) else rounded(pal.panel2, dpf(13), pal.line, dp(1))
        setOnClickListener { action() }
    }

    private fun newGameDialog(): Dialog = sheet("新对局") { dialog ->
        var mode = if (ctl.mode == Mode.ANALYSIS) Mode.PLAY else ctl.mode
        var color = "w"; var levelIdx = ctl.levelIndex; var blackIdx = ctl.watchBlack; var pref = ctl.preferenceId; var blunders = ctl.blunders
        fun group() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val playOnly = group(); val aiOnly = group(); val watchOnly = group()
        lateinit var levelTitle: TextView
        fun applyMode() {
            playOnly.visibility = if (mode == Mode.PLAY) View.VISIBLE else View.GONE
            aiOnly.visibility = if (mode == Mode.PVP) View.GONE else View.VISIBLE
            watchOnly.visibility = if (mode == Mode.WATCH) View.VISIBLE else View.GONE
            levelTitle.text = if (mode == Mode.WATCH) "白方 AI 难度 · 参考等级分" else "难度 · 参考等级分"
        }
        section("模式")
        choiceRow(listOf(Mode.PLAY to "♟ 人机对战", Mode.PVP to "⇄ 双人同屏", Mode.WATCH to "◉ AI 观战"), mode, 3) { mode = it; applyMode() }
        playOnly.section("执子")
        playOnly.choiceRow(listOf("w" to "♔ 白方", "random" to "◐ 随机", "b" to "♚ 黑方"), color, 3) { color = it }
        addView(playOnly)
        aiOnly.section("难度 · 参考等级分")
        levelTitle = aiOnly.getChildAt(aiOnly.childCount - 1) as TextView
        aiOnly.choiceRow(LEVELS.indices.map { it to "${LEVELS[it].name}\n${LEVELS[it].elo} Elo" }, levelIdx, 3) { levelIdx = it }
        watchOnly.section("黑方 AI 难度")
        watchOnly.choiceRow(LEVELS.indices.map { it to "${LEVELS[it].name}\n${LEVELS[it].elo} Elo" }, blackIdx, 3) { blackIdx = it }
        aiOnly.addView(watchOnly)
        aiOnly.section("引擎偏好")
        aiOnly.choiceRow(PREFERENCES.map { it.id to it.name }, pref, 3) { pref = it }
        aiOnly.toggle("低频错棋注入", "AI 偶尔走出人类式失误", blunders) { blunders = it }
        addView(aiOnly)
        applyMode()
        section("从 FEN 局面开始（可选）")
        val fen = EditText(context).apply { hint = "留空即标准初始局面"; textSize = 12f; setTextColor(pal.text); setHintTextColor(pal.faint); typeface = Typeface.MONOSPACE; background = rounded(pal.panel2, dpf(11), pal.line, dp(1)); setPadding(dp(12), dp(10), dp(12), dp(10)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        addView(fen, lp(-1, -2))
        addView(button("开始", primary = true) {
            ctl.levelIndex = levelIdx; ctl.preferenceId = pref; ctl.blunders = blunders; ctl.saveSettings()
            dialog.dismiss()
            ctl.newGame(mode, color, fen.text.toString().trim().ifEmpty { START_FEN }, levelIdx, blackIdx)
            syncOrientation(); render()
        }, lp(-1, -2) { topMargin = dp(18) })
    }

    private fun settingsDialog(): Dialog = sheet("设置") { dialog ->
        section("界面主题")
        choiceRow(PALETTES.map { it.id to it.label }, ctl.themeId, 3) { ctl.themeId = it; ctl.saveSettings(); dialog.dismiss(); buildUi(); render(); settingsDialog() }
        section("棋盘")
        choiceRow(BOARD_THEMES.map { it.id to it.label }, ctl.boardId, 4) { ctl.boardId = it; ctl.saveSettings(); render() }
        section("偏好")
        toggle("落子音效", "实时合成，无需音频文件", ctl.soundOn) { ctl.soundOn = it; sounds.enabled = it; ctl.saveSettings() }
        toggle("评估条", "棋盘左侧显示优势", ctl.evalBar) { ctl.evalBar = it; ctl.saveSettings(); render() }
        toggle("分析箭头", "分析模式下显示前三候选", ctl.arrows) { ctl.arrows = it; ctl.saveSettings(); render() }
        toggle("棋盘坐标", "a–h / 1–8", ctl.coords) { ctl.coords = it; ctl.saveSettings(); render() }
        toggle("错棋注入", "AI 偶尔犯人类式错误", ctl.blunders) { ctl.blunders = it; ctl.saveSettings() }
        toggle("双人自动翻转", "同屏对战时棋盘转向走棋方", ctl.autoFlip) { ctl.autoFlip = it; ctl.saveSettings(); syncOrientation(); render() }
        section("引擎")
        choiceRow(listOf(true to "Stockfish 19", false to "谱着内置引擎"), ctl.preferStockfish, 2) { sf -> scope.launch { ctl.switchEngine(sf) } }
        addView(label(if (ctl.engine?.isStockfish == true) "当前：Stockfish 19（arm64 本地进程，多线程）" else "当前：谱着内置引擎（Kotlin 实现，PVS + 置换表）", 12f, pal.muted))
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun shareDialog(): Dialog = sheet("导入 / 导出") { dialog ->
        section("导出")
        val row = LinearLayout(context)
        row.addView(button("分享 PGN", primary = true) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ctl.pgn()); putExtra(Intent.EXTRA_SUBJECT, "谱着 Chess Lab 棋谱") }, "分享棋谱"))
        }, lp(0, -2, 1f))
        row.addView(button("复制 PGN") { clipboard().setPrimaryClip(ClipData.newPlainText("PGN", ctl.pgn())); toast("PGN 已复制") }, lp(0, -2, 1f) { marginStart = dp(8) })
        row.addView(button("复制 FEN") { clipboard().setPrimaryClip(ClipData.newPlainText("FEN", ctl.fenAt(ctl.view))); toast("FEN 已复制") }, lp(0, -2, 1f) { marginStart = dp(8) })
        addView(row)
        section("导入 PGN 或 FEN")
        val input = EditText(context).apply { hint = "粘贴 PGN 棋谱或 FEN 局面"; textSize = 12f; setTextColor(pal.text); setHintTextColor(pal.faint); typeface = Typeface.MONOSPACE; minLines = 4; gravity = Gravity.TOP; background = rounded(pal.panel2, dpf(11), pal.line, dp(1)); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        addView(input, lp(-1, -2))
        val row2 = LinearLayout(context)
        row2.addView(button("从剪贴板粘贴") { input.setText(clipboard().primaryClip?.getItemAt(0)?.coerceToText(context) ?: "") }, lp(0, -2, 1f))
        row2.addView(button("导入并分析", primary = true) { if (ctl.load(input.text.toString())) { dialog.dismiss(); syncOrientation(); render() } }, lp(0, -2, 1f) { marginStart = dp(8) })
        addView(row2, lp(-1, -2) { topMargin = dp(10) })
    }

    private fun gameOverDialog(st: GameStatus) {
        if (isFinishing) return
        val playing = ctl.mode == Mode.PLAY
        val youWin = playing && st.winnerWhite == ctl.playerWhite
        val winner = if (st.winnerWhite == true) "白方" else "黑方"
        val title = when {
            st.result == "1/2-1/2" -> "和棋"
            playing -> if (youWin) "你赢了！" else "谱着获胜"
            ctl.mode == Mode.WATCH -> "$winner AI（${ctl.levelFor(st.winnerWhite == true).name}）获胜"
            else -> "${winner}获胜"
        }
        sheet(title) { dialog ->
            val crown = PieceIcon((if (st.winnerWhite == false) "b" else "w") + if (st.reason == EndReason.CHECKMATE) "k" else "q", withAlpha(if (youWin) pal.gold else pal.accent, 0.18f))
            addView(crown, lp(dp(88), dp(88)) { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
            crown.animate().scaleX(1.06f).scaleY(1.06f).setDuration(900).withEndAction { crown.animate().scaleX(1f).scaleY(1f).setDuration(900).start() }.start()
            addView(label("${resultText(st)} · 共 ${(ctl.game.ply + 1) / 2} 回合", 13.5f, pal.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16)) }, lp(-1, -2))
            addView(button("复盘本局", primary = true) { dialog.dismiss(); ctl.setMode(Mode.ANALYSIS, carry = true); ctl.seek(0); ctl.review(); syncOrientation(); render() }, lp(-1, -2))
            val row = LinearLayout(context)
            row.addView(button("再来一局") { dialog.dismiss(); newGameDialog() }, lp(0, -2, 1f))
            row.addView(button("导出") { dialog.dismiss(); shareDialog() }, lp(0, -2, 1f) { marginStart = dp(8) })
            addView(row, lp(-1, -2) { topMargin = dp(8) })
        }
    }
}
