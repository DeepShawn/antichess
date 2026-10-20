package com.deepshawn.antichess

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.deepshawn.antichess.core.EngineLine
import com.deepshawn.antichess.core.Game
import com.deepshawn.antichess.core.GameStatus
import com.deepshawn.antichess.core.LEVELS
import com.deepshawn.antichess.core.MoveGrade
import com.deepshawn.antichess.core.MoveRecord
import com.deepshawn.antichess.core.OpeningBook
import com.deepshawn.antichess.core.PREFERENCES
import com.deepshawn.antichess.core.Personality
import com.deepshawn.antichess.core.Review
import com.deepshawn.antichess.core.START_FEN
import com.deepshawn.antichess.core.Score
import com.deepshawn.antichess.engine.AnalysisRequest
import com.deepshawn.antichess.engine.AnalysisSnapshot
import com.deepshawn.antichess.engine.ChessEngine
import com.deepshawn.antichess.engine.Engines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Mode { PLAY, ANALYSIS }

/** White-perspective evaluation of the position after a given ply. */
data class Eval(val score: Score, val best: String?, val depth: Int)
data class Candidate(val uci: String, val san: String, val score: Score?, val label: String?, val prob: Double?, val slip: Boolean = false)
data class Decision(val source: Personality.Source, val rank: Int, val uci: String, val candidates: List<Candidate>, val depth: Int)

sealed interface UiEvent {
    data class Moved(val record: MoveRecord) : UiEvent
    data class Toast(val text: String) : UiEvent
    data class GameOver(val status: GameStatus) : UiEvent
}

class GameController(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("puzhe", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Settings
    var themeId = prefs.getString("theme", "midnight")!!
    var boardId = prefs.getString("board", "jade")!!
    var levelIndex = prefs.getInt("level", 2).coerceIn(0, LEVELS.lastIndex)
    var preferenceId = prefs.getString("preference", "second")!!
    var blunders = prefs.getBoolean("blunders", true)
    var soundOn = prefs.getBoolean("sound", true)
    var evalBar = prefs.getBoolean("evalBar", true)
    var arrows = prefs.getBoolean("arrows", true)
    var coords = prefs.getBoolean("coords", true)
    var preferStockfish = prefs.getBoolean("stockfish", true)

    val level get() = LEVELS[levelIndex]
    val preference get() = PREFERENCES.firstOrNull { it.id == preferenceId } ?: PREFERENCES[1]

    // Game state
    var game = Game(); private set
    var mode = Mode.PLAY; private set
    var playerWhite = true; private set
    var view = 0; private set
    val evals = HashMap<Int, Eval>()
    val grades = HashMap<Int, MoveGrade>()
    val decisions = HashMap<Int, Decision>()
    var thinking = false; private set
    var live: AnalysisSnapshot? = null; private set
    var hint: String? = null; private set
    var reviewProgress: Int? = null; private set
    var engine: ChessEngine? = null; private set
    var book: OpeningBook? = null; private set
    private var endShown = false
    private var job: Job? = null
    private var gen = 0

    var onChange: () -> Unit = {}
    var onEvent: (UiEvent) -> Unit = {}

    val atEnd get() = view == game.ply
    fun turnWhiteAt(ply: Int) = (ply % 2 == 0) == (game.startFen.split(' ')[1] == "w")
    fun fenAt(ply: Int) = if (ply == 0) game.startFen else game.history[ply - 1].fen

    fun start() {
        restore()
        scope.launch {
            book = withContext(Dispatchers.Default) { runCatching { OpeningBook.parse(appContext.assets.open("openings.txt").bufferedReader().readText()) }.getOrNull() }
            engine = Engines.create(appContext, preferStockfish)
            onChange()
            schedule()
            if (game.ply > 0 && mode == Mode.PLAY && !game.status().over) onEvent(UiEvent.Toast("已恢复上次对局 · 第 ${(game.ply + 1) / 2} 回合"))
        }
    }

    fun saveSettings() {
        prefs.edit().putString("theme", themeId).putString("board", boardId).putInt("level", levelIndex).putString("preference", preferenceId)
            .putBoolean("blunders", blunders).putBoolean("sound", soundOn).putBoolean("evalBar", evalBar).putBoolean("arrows", arrows)
            .putBoolean("coords", coords).putBoolean("stockfish", preferStockfish).apply()
    }

    suspend fun switchEngine(stockfish: Boolean) {
        preferStockfish = stockfish; saveSettings()
        job?.cancel(); thinking = false
        engine?.close(); engine = null; onChange()
        engine = Engines.create(appContext, stockfish)
        onEvent(UiEvent.Toast("已切换到 ${engineLabel()}"))
        onChange(); schedule()
    }

    fun engineLabel() = engine?.let { if (it.isStockfish) "Stockfish 19 · 本地 UCI" else it.name } ?: "引擎加载中"

    // ───────────── engine orchestration ─────────────
    private fun whiteScore(line: EngineLine, whiteToMove: Boolean) = if (whiteToMove) line.score else -line.score

    private fun setEval(ply: Int, line: EngineLine?, depth: Int) {
        line ?: return
        val prev = evals[ply]
        if (prev != null && prev.depth > depth) return
        val score = if (line.score.mate == 0) Score(cp = if (turnWhiteAt(ply)) -10000 else 10000) else whiteScore(line, turnWhiteAt(ply))
        evals[ply] = Eval(score, line.pv.firstOrNull(), depth)
        regrade(ply); regrade(ply + 1)
    }

    private fun regrade(p: Int) {
        if (p < 1 || p > game.ply) return
        val before = evals[p - 1] ?: return; val after = evals[p] ?: return
        val rec = game.history[p - 1]
        grades[p] = Review.grade(before.score, after.score, rec.white, before.best == rec.uci)
    }

    fun schedule() {
        job?.cancel()
        val g = ++gen
        live = null
        val st = game.status()
        if (st.over) {
            thinking = false
            evals[game.ply] = Eval(Score(cp = when (st.winnerWhite) { true -> 10000; false -> -10000; null -> 0 }), null, 99)
            regrade(game.ply); persist()
            if (!endShown) { endShown = true; onEvent(UiEvent.GameOver(st)) }
            onChange(); return
        }
        val eng = engine ?: return
        job = scope.launch {
            if (mode == Mode.PLAY && game.whiteToMove != playerWhite) engineTurn(eng, g) else analyzeCurrent(eng, g)
        }
    }

    private var renderPending = false
    /** Applies streamed engine info on the main thread, coalescing re-renders to ~8 per second. */
    private fun postInfo(g: Int, block: () -> Unit) = main.post {
        if (g != gen) return@post
        block()
        if (!renderPending) { renderPending = true; main.postDelayed({ renderPending = false; onChange() }, 120) }
    }

    private suspend fun analyzeCurrent(eng: ChessEngine, g: Int) {
        val ply = game.ply
        val infinite = mode == Mode.ANALYSIS
        val req = AnalysisRequest(game.startFen, game.uciMoves(), 3, if (infinite) null else if (eng.isStockfish) 16 else null,
            if (infinite) null else if (eng.isStockfish) 2500 else 1200, infinite)
        val res = eng.analyze(req) { snap -> postInfo(g) { live = snap; setEval(ply, snap.lines.firstOrNull(), snap.depth) } }
        if (g != gen) return
        setEval(ply, res.lines.firstOrNull(), res.depth)
        live = res; persist(); onChange()
    }

    private suspend fun engineTurn(eng: ChessEngine, g: Int) {
        thinking = true; onChange()
        val lv = level; val ply = game.ply; val fen = game.fen(); val started = System.currentTimeMillis()
        val bookMoves = if (lv.id == "max") emptyList() else book?.candidates(fen).orEmpty()
        val res = eng.analyze(AnalysisRequest(game.startFen, game.uciMoves(), lv.multipv, lv.depth, if (eng.isStockfish) (lv.movetime * 1.6).toLong() else lv.movetime.toLong())) { snap ->
            postInfo(g) { live = snap }
        }
        if (g != gen) return
        setEval(ply, res.lines.firstOrNull(), res.depth)
        val legal = game.legalUci()
        val choice = Personality.choose(bookMoves, res.lines, legal, lv, preference, blunders)
        val wTurn = game.whiteToMove
        val candidates = ArrayList<Candidate>()
        if (choice.source == Personality.Source.BOOK) bookMoves.forEachIndexed { i, b -> candidates.add(Candidate(b.uci, b.san, null, b.name, choice.probs[i])) }
        else res.lines.forEachIndexed { i, l -> candidates.add(Candidate(l.pv.first(), sanOf(fen, l.pv.first()), whiteScore(l, wTurn), null, choice.probs.getOrNull(i))) }
        if (choice.source == Personality.Source.BLUNDER && candidates.none { it.uci == choice.uci }) candidates.add(Candidate(choice.uci, sanOf(fen, choice.uci), null, null, 0.0, slip = true))
        val wait = 380 + (Math.random() * 380).toLong() - (System.currentTimeMillis() - started)
        if (wait > 0) delay(wait)
        if (g != gen) return
        decisions[ply + 1] = Decision(choice.source, choice.rank, choice.uci, candidates, res.depth)
        thinking = false
        game.move(choice.uci)?.let { afterMove(it) }
    }

    fun sanOf(fen: String, uci: String) = runCatching { Game(fen).move(uci)?.san }.getOrNull() ?: uci
    fun pvSan(fen: String, pv: List<String>, max: Int): List<String> {
        val g = runCatching { Game(fen) }.getOrNull() ?: return emptyList()
        return pv.take(max).mapNotNull { g.move(it)?.san }
    }

    // ───────────── user actions ─────────────
    fun userMove(from: String, to: String, promotion: Char?) {
        if (mode == Mode.PLAY && (thinking || game.whiteToMove != playerWhite || !atEnd)) return
        if (!atEnd) truncateTo(view)
        val rec = game.move(from + to + (promotion ?: "")) ?: return
        afterMove(rec)
    }

    private fun afterMove(rec: MoveRecord) {
        view = game.ply; hint = null
        onEvent(UiEvent.Moved(rec))
        persist(); onChange(); schedule()
    }

    private fun truncateTo(ply: Int) {
        while (game.ply > ply) game.undo()
        evals.keys.removeAll { it > ply }; grades.keys.removeAll { it > ply }; decisions.keys.removeAll { it > ply }
        endShown = false
    }

    fun seek(ply: Int) { view = ply.coerceIn(0, game.ply); hint = null; onChange() }

    fun undo() {
        if (game.ply == 0) return
        job?.cancel(); gen++; thinking = false
        var target = game.ply - 1
        if (mode == Mode.PLAY) { while (target > 0 && turnWhiteAt(target) != playerWhite) target--; if (turnWhiteAt(target) != playerWhite) target = 0 }
        truncateTo(target); view = game.ply
        persist(); onChange(); schedule()
        onEvent(UiEvent.Toast("已悔棋"))
    }

    fun showHint() {
        if (mode != Mode.PLAY || !atEnd || thinking) return
        val best = evals[game.ply]?.best
        if (best == null) { onEvent(UiEvent.Toast("引擎还在分析，稍等片刻再试")); return }
        hint = best; onChange()
        onEvent(UiEvent.Toast("提示：${sanOf(game.fen(), best)}"))
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        if (!atEnd) truncateTo(view)
        mode = m; hint = null
        if (m == Mode.PLAY) { playerWhite = game.whiteToMove; endShown = false }
        onEvent(UiEvent.Toast(if (m == Mode.ANALYSIS) "分析模式：双方自由走子，引擎持续分析" else "对局模式：你执${if (playerWhite) "白" else "黑"}，从当前局面继续"))
        persist(); onChange(); schedule()
    }

    fun newGame(color: String, fen: String = START_FEN) {
        job?.cancel(); gen++
        val white = when (color) { "w" -> true; "b" -> false; else -> Math.random() < 0.5 }
        game = runCatching { Game(fen.ifBlank { START_FEN }) }.getOrElse { onEvent(UiEvent.Toast(it.message ?: "FEN 不合法")); return }
        mode = Mode.PLAY; playerWhite = white; view = 0
        evals.clear(); grades.clear(); decisions.clear(); thinking = false; hint = null; live = null; endShown = false
        engine?.newGame()
        persist(); onChange(); schedule()
        onEvent(UiEvent.Toast("新对局 · 你执${if (white) "白" else "黑"} · ${level.name} ${level.elo}"))
    }

    fun load(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val g = runCatching { if (Regex("^[rnbqkpRNBQKP1-8]+(/[rnbqkpRNBQKP1-8]+){7}\\s").containsMatchIn(t)) Game(t) else Game.fromPgn(t) }
            .getOrElse { onEvent(UiEvent.Toast(it.message ?: "无法识别的 PGN / FEN")); return false }
        job?.cancel(); gen++
        game = g; mode = Mode.ANALYSIS; view = g.ply
        evals.clear(); grades.clear(); decisions.clear(); thinking = false; hint = null; live = null; endShown = true
        persist(); onChange()
        onEvent(UiEvent.Toast(if (g.ply > 0) "已导入 ${g.ply} 步棋谱，正在自动复盘" else "已载入局面，进入分析模式"))
        if (g.ply > 1) review() else schedule()
        return true
    }

    fun review() {
        val eng = engine ?: return
        if (reviewProgress != null || game.ply == 0) return
        job?.cancel(); val g = ++gen; thinking = false
        job = scope.launch {
            val n = game.ply
            for (p in 0..n) {
                if (g != gen) return@launch
                reviewProgress = p * 100 / n; onChange()
                if ((evals[p]?.depth ?: 0) >= if (eng.isStockfish) 12 else 6) continue
                val res = eng.analyze(AnalysisRequest(game.startFen, game.uciMoves().take(p), 1, if (eng.isStockfish) 12 else 64, if (eng.isStockfish) 1500 else 350)) {}
                if (g != gen) return@launch
                if (res.lines.isNotEmpty()) setEval(p, res.lines.first(), res.depth)
                else { val st = Game(fenAt(p)).status(); if (st.over) { evals[p] = Eval(Score(cp = when (st.winnerWhite) { true -> 10000; false -> -10000; null -> 0 }), null, 99); regrade(p) } }
            }
            reviewProgress = null; persist(); onChange()
            onEvent(UiEvent.Toast("复盘完成：曲线与每步评级已更新"))
            schedule()
        }.also { it.invokeOnCompletion { if (reviewProgress != null) { reviewProgress = null; main.post { onChange() } } } }
    }

    fun accuracy(white: Boolean) = Review.accuracy(grades.values, white)

    fun pgn(): String {
        val ai = "谱着 ${level.name}"
        return game.pgn(if (mode == Mode.PLAY) mapOf("White" to if (playerWhite) "你" else ai, "Black" to if (playerWhite) ai else "你") else emptyMap())
    }

    // ───────────── persistence ─────────────
    fun persist() {
        val ev = JSONObject(); for ((k, v) in evals) ev.put(k.toString(), JSONObject().apply { v.score.cp?.let { put("cp", it) }; v.score.mate?.let { put("mate", it) }; put("best", v.best ?: ""); put("depth", v.depth) })
        val dec = JSONObject(); for ((k, d) in decisions) dec.put(k.toString(), JSONObject().apply {
            put("source", d.source.name); put("rank", d.rank); put("uci", d.uci); put("depth", d.depth)
            put("candidates", JSONArray().apply { d.candidates.forEach { c -> put(JSONObject().apply { put("uci", c.uci); put("san", c.san); c.score?.cp?.let { put("cp", it) }; c.score?.mate?.let { put("mate", it) }; put("label", c.label ?: ""); put("prob", c.prob ?: -1.0); put("slip", c.slip) }) } })
        })
        prefs.edit().putString("game", JSONObject().apply {
            put("pgn", game.pgn()); put("mode", mode.name); put("playerWhite", playerWhite); put("evals", ev); put("decisions", dec)
        }.toString()).apply()
    }

    private fun scoreOf(o: JSONObject): Score? = when { o.has("mate") -> Score(mate = o.getInt("mate")); o.has("cp") -> Score(cp = o.getInt("cp")); else -> null }

    private fun restore() {
        val raw = prefs.getString("game", null) ?: return
        runCatching {
            val o = JSONObject(raw)
            val g = Game.fromPgn(o.getString("pgn"))
            game = g; mode = if (o.optString("mode") == "ANALYSIS") Mode.ANALYSIS else Mode.PLAY
            playerWhite = o.optBoolean("playerWhite", true); view = g.ply; endShown = g.status().over
            o.optJSONObject("evals")?.let { ev -> for (k in ev.keys()) { val e = ev.getJSONObject(k); scoreOf(e)?.let { evals[k.toInt()] = Eval(it, e.optString("best").ifEmpty { null }, e.optInt("depth")) } } }
            o.optJSONObject("decisions")?.let { dec ->
                for (k in dec.keys()) {
                    val d = dec.getJSONObject(k); val arr = d.getJSONArray("candidates")
                    val cands = (0 until arr.length()).map { i -> val c = arr.getJSONObject(i); Candidate(c.getString("uci"), c.getString("san"), scoreOf(c), c.optString("label").ifEmpty { null }, c.optDouble("prob", -1.0).takeIf { it >= 0 }, c.optBoolean("slip")) }
                    decisions[k.toInt()] = Decision(Personality.Source.valueOf(d.getString("source")), d.getInt("rank"), d.getString("uci"), cands, d.optInt("depth"))
                }
            }
            for (p in 1..g.ply) regrade(p)
        }.onFailure { game = Game(); evals.clear(); decisions.clear() }
    }

    fun close() { job?.cancel(); engine?.close() }
}
