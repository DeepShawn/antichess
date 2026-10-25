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
import com.deepshawn.antichess.core.Sac
import com.deepshawn.antichess.core.Sacrifice
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

/** 人机对战 · 同屏双人 · AI 观战 · 分析 */
/** Stockfish analysis depth on the phone. */
const val ANALYSIS_DEPTH = 30

enum class Mode(val label: String) { PLAY("人机"), PVP("双人"), WATCH("观战"), ANALYSIS("分析") }

/** White-perspective evaluation of the position after a given ply. */
data class Eval(val score: Score, val best: String?, val depth: Int)
data class Candidate(val uci: String, val san: String, val score: Score?, val label: String?, val prob: Double?, val slip: Boolean = false, val sac: Sac? = null)
data class Decision(val source: Personality.Source, val rank: Int, val uci: String, val candidates: List<Candidate>, val depth: Int, val sacrifice: Sac? = null, val sacAmount: Int = 0)

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
    var autoFlip = prefs.getBoolean("autoFlip", true)

    val level get() = LEVELS[levelIndex]
    /** Levels of the white and black AI in watch mode. */
    var watchWhite = 2; private set
    var watchBlack = 2; private set
    fun isAI(white: Boolean) = mode == Mode.WATCH || (mode == Mode.PLAY && white != playerWhite)
    fun levelFor(white: Boolean) = if (mode == Mode.WATCH) LEVELS[if (white) watchWhite else watchBlack] else level
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
    var paused = false; private set
    var live: AnalysisSnapshot? = null; private set
    var hint: String? = null; private set
    var reviewProgress: Int? = null; private set
    var engine: ChessEngine? = null; private set
    var book: OpeningBook? = null; private set
    private var endShown = false
    private var background = false
    /** Each mode keeps its own game: switching modes parks the current one here and resumes the target's. */
    private val slots = HashMap<Mode, JSONObject>()
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
            .putBoolean("coords", coords).putBoolean("stockfish", preferStockfish).putBoolean("autoFlip", autoFlip).apply()
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
        if (background) return
        job = scope.launch {
            if (isAI(game.whiteToMove) && !(mode == Mode.WATCH && paused)) engineTurn(eng, g) else analyzeCurrent(eng, g)
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
        // Analysis mode stops at depth 30 on Stockfish (strong enough, and a phone should not
        // run a multi-threaded search forever); the built-in engine caps itself at 30 s.
        val analysis = mode == Mode.ANALYSIS
        val req = when {
            analysis && eng.isStockfish -> AnalysisRequest(game.startFen, game.uciMoves(), 3, depth = ANALYSIS_DEPTH)
            analysis -> AnalysisRequest(game.startFen, game.uciMoves(), 3, infinite = true)
            eng.isStockfish -> AnalysisRequest(game.startFen, game.uciMoves(), 3, depth = 16, movetimeMs = 2500)
            else -> AnalysisRequest(game.startFen, game.uciMoves(), 3, movetimeMs = 1200)
        }
        val res = eng.analyze(req) { snap -> postInfo(g) { live = snap; setEval(ply, snap.lines.firstOrNull(), snap.depth) } }
        if (g != gen) return
        setEval(ply, res.lines.firstOrNull(), res.depth)
        live = res; persist(); onChange()
    }

    private suspend fun engineTurn(eng: ChessEngine, g: Int) {
        thinking = true; onChange()
        val lv = levelFor(game.whiteToMove); val ply = game.ply; val fen = game.fen(); val started = System.currentTimeMillis()
        val bookMoves = if (lv.id == "max") emptyList() else book?.candidates(fen).orEmpty()
        val res = eng.analyze(AnalysisRequest(game.startFen, game.uciMoves(), if (preference.sacrifice) maxOf(lv.multipv, 5) else lv.multipv, lv.depth, if (eng.isStockfish) (lv.movetime * 1.6).toLong() else lv.movetime.toLong())) { snap ->
            postInfo(g) { live = snap }
        }
        if (g != gen) return
        setEval(ply, res.lines.firstOrNull(), res.depth)
        val legal = game.legalUci()
        val choice = Personality.choose(bookMoves, res.lines, legal, lv, preference, blunders, fen = fen)
        val wTurn = game.whiteToMove
        val candidates = ArrayList<Candidate>()
        if (choice.source == Personality.Source.BOOK) bookMoves.forEachIndexed { i, b -> candidates.add(Candidate(b.uci, b.san, null, b.name, choice.probs[i])) }
        else res.lines.forEachIndexed { i, l -> candidates.add(Candidate(l.pv.first(), sanOf(fen, l.pv.first()), whiteScore(l, wTurn), null, choice.probs.getOrNull(i), sac = choice.sacs.getOrNull(i))) }
        if (choice.source == Personality.Source.BLUNDER && candidates.none { it.uci == choice.uci }) candidates.add(Candidate(choice.uci, sanOf(fen, choice.uci), null, null, 0.0, slip = true))
        // Spectators need time to follow the game, so AI-vs-AI moves are paced more slowly.
        val wait = (if (mode == Mode.WATCH) 900 else 380) + (Math.random() * 380).toLong() - (System.currentTimeMillis() - started)
        if (wait > 0) delay(wait)
        if (g != gen) return
        val sac = if (choice.source == Personality.Source.ENGINE) choice.sacrifice else null
        decisions[ply + 1] = Decision(choice.source, choice.rank, choice.uci, candidates, res.depth, sac,
            if (sac != null) Sacrifice.material(fen, res.lines[choice.rank].pv) else 0)
        thinking = false
        game.move(choice.uci)?.let { afterMove(it) }
    }

    fun sanOf(fen: String, uci: String) = sanOrNull(fen, uci) ?: uci
    /** SAN of [uci] in [fen], or null when the move is not legal there. */
    fun sanOrNull(fen: String, uci: String) = runCatching { Game(fen).move(uci)?.san }.getOrNull()

    /** The app went to the background: stop the engine so it does not drain the battery. */
    fun onBackground() {
        if (background) return
        background = true
        job?.cancel(); gen++; thinking = false; live = null
        persist(); onChange()
    }

    /** Back in the foreground: resume whatever the current mode needs (the AI's move, analysis…). */
    fun onForeground() {
        if (!background) return
        background = false
        schedule()
    }
    fun pvSan(fen: String, pv: List<String>, max: Int): List<String> {
        val g = runCatching { Game(fen) }.getOrNull() ?: return emptyList()
        return pv.take(max).mapNotNull { g.move(it)?.san }
    }

    // ───────────── user actions ─────────────
    fun userMove(from: String, to: String, promotion: Char?) {
        if (mode == Mode.WATCH || (mode == Mode.PVP && !atEnd)) return
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
        if (mode == Mode.WATCH) paused = true // stepping back while the AIs keep playing would be pointless
        truncateTo(target); view = game.ply
        persist(); onChange(); schedule()
        onEvent(UiEvent.Toast(if (mode == Mode.WATCH) "已后退一步，观战暂停" else "已悔棋"))
    }

    fun togglePause() {
        if (mode != Mode.WATCH) return
        paused = !paused
        if (paused) { job?.cancel(); gen++; thinking = false }
        onEvent(UiEvent.Toast(if (paused) "观战已暂停" else "继续观战"))
        onChange(); schedule()
    }

    fun showHint() {
        if (mode != Mode.PLAY || !atEnd || thinking) return
        val best = evals[game.ply]?.best
        if (best == null) { onEvent(UiEvent.Toast("引擎还在分析，稍等片刻再试")); return }
        hint = best; onChange()
        onEvent(UiEvent.Toast("提示：${sanOf(game.fen(), best)}"))
    }

    /** Switches mode, resuming that mode's own game. [carry] copies the current game over instead (复盘本局 → analysis). */
    fun setMode(m: Mode, carry: Boolean = false) {
        if (m == mode) return
        job?.cancel(); gen++
        val current = slotOf()
        slots[mode] = current
        if (carry) slots[m] = JSONObject(current.toString()).put("paused", false)
        mode = m
        val resumed = applySlot(slots[m])
        engine?.newGame()
        onEvent(UiEvent.Toast(if (resumed && game.ply > 0) "${when (m) { Mode.PLAY -> "人机对战"; Mode.PVP -> "双人同屏"; Mode.WATCH -> "AI 观战"; Mode.ANALYSIS -> "分析模式" }} · 已恢复该模式的局面（第 ${(game.ply + 1) / 2} 回合）" else when (m) {
            Mode.PLAY -> "人机对战：你执${if (playerWhite) "白" else "黑"}，新的一局"
            Mode.PVP -> "双人同屏：轮流在同一块棋盘上走棋"
            Mode.WATCH -> "AI 观战：白方 ${levelFor(true).name} 对黑方 ${levelFor(false).name}"
            Mode.ANALYSIS -> "分析模式：双方自由走子，引擎持续分析"
        }))
        persist(); onChange(); schedule()
    }

    fun newGame(newMode: Mode, color: String, fen: String = START_FEN, whiteLevel: Int = watchWhite, blackLevel: Int = watchBlack) {
        job?.cancel(); gen++
        val white = when (color) { "w" -> true; "b" -> false; else -> Math.random() < 0.5 }
        val g = runCatching { Game(fen.ifBlank { START_FEN }) }.getOrElse { onEvent(UiEvent.Toast(it.message ?: "FEN 不合法")); return }
        if (newMode != mode) slots[mode] = slotOf()
        game = g
        mode = newMode; playerWhite = white; view = 0; paused = false
        watchWhite = whiteLevel.coerceIn(0, LEVELS.lastIndex); watchBlack = blackLevel.coerceIn(0, LEVELS.lastIndex)
        evals.clear(); grades.clear(); decisions.clear(); thinking = false; hint = null; live = null; endShown = false
        engine?.newGame()
        persist(); onChange(); schedule()
        onEvent(UiEvent.Toast(when (newMode) {
            Mode.PVP -> "新对局 · 双人同屏"
            Mode.WATCH -> "AI 观战 · 白方 ${levelFor(true).name} 对黑方 ${levelFor(false).name}"
            else -> "新对局 · 你执${if (white) "白" else "黑"} · ${level.name} ${level.elo}"
        }))
    }

    fun load(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val g = runCatching { if (Regex("^[rnbqkpRNBQKP1-8]+(/[rnbqkpRNBQKP1-8]+){7}\\s").containsMatchIn(t)) Game(t) else Game.fromPgn(t) }
            .getOrElse { onEvent(UiEvent.Toast(it.message ?: "无法识别的 PGN / FEN")); return false }
        job?.cancel(); gen++
        if (mode != Mode.ANALYSIS) slots[mode] = slotOf()
        game = g; mode = Mode.ANALYSIS; view = g.ply; paused = false
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
        fun who(white: Boolean) = when {
            mode == Mode.PVP -> if (white) "玩家一" else "玩家二"
            isAI(white) -> "谱着 ${levelFor(white).name}"
            mode == Mode.PLAY -> "你"
            else -> "?"
        }
        return game.pgn(mapOf("White" to who(true), "Black" to who(false)))
    }

    // ───────────── persistence ─────────────
    /** Serialisable snapshot of the current mode's game. */
    private fun slotOf(): JSONObject {
        val ev = JSONObject(); for ((k, v) in evals) ev.put(k.toString(), JSONObject().apply { v.score.cp?.let { put("cp", it) }; v.score.mate?.let { put("mate", it) }; put("best", v.best ?: ""); put("depth", v.depth) })
        val dec = JSONObject(); for ((k, d) in decisions) dec.put(k.toString(), JSONObject().apply {
            put("source", d.source.name); put("rank", d.rank); put("uci", d.uci); put("depth", d.depth); put("sac", d.sacrifice?.name ?: ""); put("sacAmount", d.sacAmount)
            put("candidates", JSONArray().apply { d.candidates.forEach { c -> put(JSONObject().apply { put("uci", c.uci); put("san", c.san); c.score?.cp?.let { put("cp", it) }; c.score?.mate?.let { put("mate", it) }; put("label", c.label ?: ""); put("prob", c.prob ?: -1.0); put("slip", c.slip); put("sac", c.sac?.name ?: "") }) } })
        })
        return JSONObject().apply { put("pgn", game.pgn()); put("playerWhite", playerWhite); put("paused", paused); put("evals", ev); put("decisions", dec) }
    }

    fun persist() {
        slots[mode] = slotOf()
        val all = JSONObject(); for ((m, o) in slots) all.put(m.name, o)
        prefs.edit().putString("game", JSONObject().apply {
            put("v", 2); put("mode", mode.name); put("watchWhite", watchWhite); put("watchBlack", watchBlack); put("slots", all)
        }.toString()).apply()
    }

    private fun sacOf(name: String): Sac? = Sac.entries.firstOrNull { it.name == name }

    private fun scoreOf(o: JSONObject): Score? = when { o.has("mate") -> Score(mate = o.getInt("mate")); o.has("cp") -> Score(cp = o.getInt("cp")); else -> null }

    /** Makes [o] (or a fresh game when absent or corrupt) the current game; true if a saved game was resumed. */
    private fun applySlot(o: JSONObject?): Boolean {
        evals.clear(); grades.clear(); decisions.clear()
        thinking = false; hint = null; live = null
        val ok = o != null && runCatching {
            val g = Game.fromPgn(o.getString("pgn"))
            val ev = HashMap<Int, Eval>(); val ds = HashMap<Int, Decision>()
            o.optJSONObject("evals")?.let { e -> for (k in e.keys()) { val x = e.getJSONObject(k); scoreOf(x)?.let { ev[k.toInt()] = Eval(it, x.optString("best").ifEmpty { null }, x.optInt("depth")) } } }
            o.optJSONObject("decisions")?.let { dec ->
                for (k in dec.keys()) {
                    val d = dec.getJSONObject(k); val arr = d.getJSONArray("candidates")
                    val cands = (0 until arr.length()).map { i -> val c = arr.getJSONObject(i); Candidate(c.getString("uci"), c.getString("san"), scoreOf(c), c.optString("label").ifEmpty { null }, c.optDouble("prob", -1.0).takeIf { it >= 0 }, c.optBoolean("slip"), sacOf(c.optString("sac"))) }
                    ds[k.toInt()] = Decision(Personality.Source.valueOf(d.getString("source")), d.getInt("rank"), d.getString("uci"), cands, d.optInt("depth"), sacOf(d.optString("sac")), d.optInt("sacAmount"))
                }
            }
            game = g; evals.putAll(ev); decisions.putAll(ds)
            playerWhite = o.optBoolean("playerWhite", true); paused = o.optBoolean("paused")
            view = g.ply; endShown = g.status().over
            for (p in 1..g.ply) regrade(p)
        }.isSuccess
        if (!ok) { game = Game(); playerWhite = true; paused = false; view = 0; endShown = false; evals.clear(); decisions.clear() }
        return ok
    }

    private fun restore() {
        val o = prefs.getString("game", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (o != null) {
            mode = Mode.entries.firstOrNull { it.name == o.optString("mode") } ?: Mode.PLAY
            watchWhite = o.optInt("watchWhite", 2).coerceIn(0, LEVELS.lastIndex); watchBlack = o.optInt("watchBlack", 2).coerceIn(0, LEVELS.lastIndex)
            val saved = o.optJSONObject("slots")
            if (saved != null) { for (m in Mode.entries) saved.optJSONObject(m.name)?.let { slots[m] = it } }
            else if (o.has("pgn")) slots[mode] = o // pre-2.2.2 save: a single game shared by every mode
        }
        applySlot(slots[mode])
    }

    fun close() { job?.cancel(); engine?.close() }
}
