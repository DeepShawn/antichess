package com.deepshawn.antichess

import android.app.*
import android.os.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import android.content.*
import kotlin.math.min
import kotlinx.coroutines.*

private const val BG = 0xff0b1118.toInt()
private const val PANEL = 0xff141f2a.toInt()
private const val PANEL_2 = 0xff1b2a37.toInt()
private const val LINE = 0xff2d4050.toInt()
private const val TEXT = 0xfff1f6f8.toInt()
private const val MUTED = 0xff8fa1b4.toInt()
private const val GREEN = 0xff76e0b5.toInt()

class MainActivity : Activity() {
    private lateinit var board: ChessBoardView
    private lateinit var engine: EngineController
    private lateinit var status: TextView
    private var level = LEVELS[2]
    private var analysis = false
    private val prefs by lazy { getSharedPreferences("puzhe-session", MODE_PRIVATE) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) } }
    private fun label(text: String, size: Float, color: Int = TEXT) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply { this.text = text; textSize = 12f; isAllCaps = false; setTextColor(if (primary) 0xff09251d.toInt() else TEXT); background = rounded(if (primary) GREEN else PANEL_2, 11, if (primary) null else LINE); setPadding(dp(10), 0, dp(10), 0); setOnClickListener { action() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12)); setBackgroundColor(BG) }
        val savedTheme = prefs.getInt("theme", 0)
        window.statusBarColor = if (savedTheme == 1) 0xffeef5f2.toInt() else if (savedTheme == 2) 0xff281b2b.toInt() else BG
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply { text = "♞"; textSize = 29f; setTextColor(GREEN); gravity = Gravity.CENTER; background = rounded(0xff17302e.toInt(), 14) }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(label("谱着 · Chess Lab", 21f).apply { setTypeface(null, Typeface.BOLD) })
        titleBox.addView(label("OPENING INTELLIGENCE", 10f, GREEN).apply { letterSpacing = .18f })
        header.addView(titleBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("☰") { showMenu() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)
        status = label("Stockfish 19 · 正在初始化", 12f, GREEN).apply { setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(PANEL, 12, LINE) }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12) })
        board = ChessBoardView(this)
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(8) })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(PANEL, 15, LINE) }
        val cardTitle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        cardTitle.addView(label("对局控制", 13f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(28), 1f))
        cardTitle.addView(label("${level.elo} Elo", 11f, GREEN)); card.addView(cardTitle)
        val controlRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val difficulty = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, LEVELS.map { "${it.name} · ${it.elo}" }); setSelection(2); onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) = Unit; override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { level = LEVELS[pos]; if (::engine.isInitialized) engine.configure(level); status.text = "Stockfish 19 · ${level.name} · ${level.elo} Elo" } } }
        controlRow.addView(difficulty, LinearLayout.LayoutParams(0, dp(44), 1f)); controlRow.addView(button("新局", true) { board.reset(); clearSession() }, LinearLayout.LayoutParams(dp(76), dp(44)).apply { marginStart = dp(8) }); card.addView(controlRow)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("保存") { saveSession() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("继续") { restoreSession() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("摆棋") { fenDialog() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(button("分析") { analysis = !analysis; status.text = if (analysis) "分析模式 · ${level.name} · 候选走法" else "对局模式 · ${level.name}" }, LinearLayout.LayoutParams(0, dp(42), 1f)); card.addView(actions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        root.addView(card, LinearLayout.LayoutParams(-1, dp(148))); setContentView(root)
        engine = EngineController(this); restoreSession(); CoroutineScope(Dispatchers.Main).launch { status.text = if (engine.start()) "Stockfish 19 · 本地 UCI · ${level.elo} Elo" else "本地引擎未就绪 · 请放入 Stockfish 19" }
    }
    private fun showMenu() { val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(10)) }; menu.addView(label("谱着 · 工作台菜单", 20f).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(14)) }); menu.addView(label("快速切换对局模式、主题与引擎偏好", 12f, MUTED).apply { setPadding(0, 0, 0, dp(12)) }); val dialog = AlertDialog.Builder(this).setView(menu).create(); menu.addView(button(if (analysis) "切换到对局模式" else "切换到分析模式", true) { analysis = !analysis; status.text = if (analysis) "分析模式 · ${level.name}" else "对局模式 · ${level.name}"; dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) }); menu.addView(button("主题：深夜 / 晨雾 / 暮色") { chooseTheme(); dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) }); menu.addView(button("引擎偏好：二选、三选或纯一选") { choosePreference(); dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(46))); dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(rounded(PANEL, 20, LINE)) }; dialog.show() }
    private fun chooseTheme() { AlertDialog.Builder(this).setTitle("选择主题").setItems(arrayOf("深夜 · 森林绿", "晨雾 · 浅色", "暮色 · 琥珀紫")) { _, which -> prefs.edit().putInt("theme", which).apply(); window.statusBarColor = if (which == 1) 0xffeef5f2.toInt() else if (which == 2) 0xff281b2b.toInt() else BG; status.text = "主题已切换 · ${if (which == 1) "晨雾" else if (which == 2) "暮色" else "深夜"}" }.show() }
    private fun choosePreference() { AlertDialog.Builder(this).setTitle("引擎偏好").setSingleChoiceItems(arrayOf("二选偏好 · 真人感", "一选主线 · 稳定", "三选偏好 · 探索", "纯一选 · 复盘"), 0) { dialog, _ -> dialog.dismiss(); status.text = "引擎偏好已更新 · ${level.name}" }.show() }
    private fun saveSession() { prefs.edit().putInt("moves", board.moveCount()).putString("fen", board.fen).putString("pgn", board.pgn()).apply(); status.text = "对局已保存 · 可继续" }
    private fun clearSession() { prefs.edit().clear().apply() }
    private fun restoreSession() { board.restore(prefs.getString("fen", null), prefs.getInt("moves", 0), prefs.getString("pgn", "") ?: ""); if (prefs.contains("fen")) status.text = "已恢复中断对局 · ${board.moveCount()} 步" }
    private fun fenDialog() { val input = EditText(this).apply { hint = "输入 FEN 摆棋"; setText(board.fen) }; AlertDialog.Builder(this).setTitle("摆棋 / 输入 FEN").setView(input).setPositiveButton("应用") { _, _ -> board.fen = input.text.toString(); board.invalidate(); saveSession() }.setNegativeButton("取消", null).show() }
    override fun onPause() { saveSession(); super.onPause() }; override fun onDestroy() { engine.stop(); super.onDestroy() }
}

class ChessBoardView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val light = Color.rgb(220, 232, 220); private val dark = Color.rgb(79, 125, 105); private var moves = 0; private var selectedCol = -1; private var selectedRow = -1; var fen = "startpos"; private var moveLog = mutableListOf<String>()
    init { setBackgroundColor(BG); isFocusable = true }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); val size = min(width, height).toFloat(); val left = (width - size) / 2f; val top = (height - size) / 2f; canvas.save(); canvas.translate(left, top); val s = size / 8f; p.setShadowLayer(18f, 0f, 8f, 0xaa000000.toInt()); for (r in 0..7) for (col in 0..7) { p.color = if ((r + col) % 2 == 0) light else dark; canvas.drawRect(col * s, r * s, col * s + s, r * s + s, p) }; p.clearShadowLayer(); if (selectedCol >= 0) { p.color = 0x6676e0b5; canvas.drawRect(selectedCol * s, selectedRow * s, selectedCol * s + s, selectedRow * s + s, p) }; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("serif", Typeface.NORMAL); p.textSize = s * .68f; val pieces = arrayOf("♜♞♝♛♚♝♞♜", "♟♟♟♟♟♟♟♟", "", "", "", "", "♙♙♙♙♙♙♙♙", "♖♘♗♕♔♗♘♖"); for (r in 0..7) for (col in 0..7) { val q = pieces[r].getOrNull(col) ?: ' '; if (q != ' ') { p.color = if (r < 2) 0xff15211d.toInt() else 0xfffffdf2.toInt(); p.setShadowLayer(2f, 0f, 2f, Color.BLACK); canvas.drawText(q.toString(), col * s + s / 2, r * s + s * .73f, p); p.clearShadowLayer() } }; canvas.restore() }
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.action == MotionEvent.ACTION_UP) { val size = min(width, height).toFloat(); val left = (width - size) / 2f; val top = (height - size) / 2f; if (e.x >= left && e.x <= left + size && e.y >= top && e.y <= top + size) { val cell = size / 8f; selectedCol = ((e.x - left) / cell).toInt().coerceIn(0, 7); selectedRow = ((e.y - top) / cell).toInt().coerceIn(0, 7); moves++; moveLog.add("move$moves"); invalidate() } }; return true }
    fun reset() { moves = 0; moveLog.clear(); selectedCol = -1; selectedRow = -1; fen = "startpos"; invalidate() }; fun moveCount() = moves; fun pgn() = moveLog.joinToString(" "); fun restore(savedFen: String?, count: Int, pgn: String) { fen = savedFen ?: "startpos"; moves = count; moveLog = if (pgn.isBlank()) mutableListOf() else pgn.split(" ").toMutableList(); invalidate() }
}
