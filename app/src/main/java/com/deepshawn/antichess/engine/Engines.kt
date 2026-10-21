package com.deepshawn.antichess.engine

import android.content.Context
import android.os.Build
import com.deepshawn.antichess.core.EngineLine
import com.deepshawn.antichess.core.Game
import com.deepshawn.antichess.core.Position
import com.deepshawn.antichess.core.Searcher
import com.deepshawn.antichess.core.Uci
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.coroutines.resume

data class AnalysisRequest(
    val startFen: String, val moves: List<String>, val multipv: Int = 1,
    val depth: Int? = null, val movetimeMs: Long? = null, val infinite: Boolean = false,
)

/** Lines are scored from the side to move (UCI convention). */
data class AnalysisSnapshot(val depth: Int, val lines: List<EngineLine>, val nps: Long = 0, val bestmove: String? = null)

interface ChessEngine {
    val name: String
    val isStockfish: Boolean
    /** Runs one search. Cancelling the calling coroutine stops the search promptly. */
    suspend fun analyze(req: AnalysisRequest, onInfo: (AnalysisSnapshot) -> Unit): AnalysisSnapshot
    fun newGame()
    fun close()
}

object Engines {
    /** Stockfish 19 when the bundled binary runs on this device, otherwise the built-in engine. */
    suspend fun create(context: Context, preferStockfish: Boolean): ChessEngine =
        (if (preferStockfish) StockfishEngine.start(context) else null) ?: BuiltinEngine()
}

/**
 * Stockfish over UCI in a child process. The binary ships as lib/arm64-v8a/libstockfish.so so
 * Android extracts it to nativeLibraryDir — the only app location that may be executed on Android 10+.
 *
 * Synchronisation: Stockfish answers `go` commands strictly in order, one `bestmove` each. Every
 * search therefore gets a sequence number and output is routed by that number, never by whichever
 * job happens to be reading. A search that is slow to stop can no longer hand its lines and
 * bestmove to the next job (which used to leave every later analysis one position behind).
 * `ucinewgame` and `setoption` are only sent while the engine is idle (during a search,
 * `ucinewgame` blocks Stockfish's command loop), and an engine that will not go idle is restarted.
 */
class StockfishEngine private constructor(private val path: String) : ChessEngine {
    override val name = "Stockfish 19"
    override val isStockfish = true

    private class Search(val id: Long, val legal: Set<String>, multipv: Int, val onInfo: (AnalysisSnapshot) -> Unit) {
        val lines = arrayOfNulls<EngineLine>(multipv)
        var depth = 0; var nps = 0L
        val done = CompletableDeferred<AnalysisSnapshot>()
        fun snapshot(best: String? = null) = AnalysisSnapshot(depth, lines.filterNotNull(), nps, best)
    }

    private val lock = Any()
    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var generation = 0          // bumps on restart; output of an old process is ignored
    private var sent = 0L               // `go` commands sent to the current process
    private var finished = 0L           // `bestmove` lines received from it
    private var current: Search? = null
    private var alive = false
    private val waiters = ArrayList<Pair<String, CompletableDeferred<Unit>>>()
    private var multipv = 1
    @Volatile private var newGamePending = false

    private fun launch(): Boolean = synchronized(lock) {
        process?.destroy()
        val proc = runCatching { ProcessBuilder(path).redirectErrorStream(true).start() }.getOrNull() ?: return false
        val gen = ++generation
        process = proc; writer = proc.outputStream.bufferedWriter()
        sent = 0; finished = 0; current = null; alive = true; multipv = 1; waiters.clear()
        thread(isDaemon = true, name = "stockfish-reader") {
            runCatching { proc.inputStream.bufferedReader().forEachLine { onLine(gen, it) } }
            synchronized(lock) {
                if (gen != generation) return@synchronized
                alive = false
                current?.let { it.done.complete(it.snapshot()) }; current = null
            }
        }
        true
    }

    private fun send(cmd: String) = synchronized(lock) { runCatching { writer?.apply { write(cmd); newLine(); flush() } } }

    private fun onLine(gen: Int, line: String) = synchronized(lock) {
        if (gen != generation) return@synchronized
        if (line.startsWith("bestmove")) {
            finished++
            val s = current
            if (s != null && s.id == finished) {
                current = null
                s.done.complete(s.snapshot(line.split(' ').getOrNull(1)?.takeIf { it in s.legal }))
            }
            return@synchronized
        }
        val hit = waiters.filter { line.startsWith(it.first) }
        if (hit.isNotEmpty()) { waiters.removeAll(hit.toSet()); hit.forEach { it.second.complete(Unit) } }
        val s = current ?: return@synchronized
        if (s.id != finished + 1) return@synchronized          // output of an older, abandoned search
        val info = Uci.parseInfo(line) ?: return@synchronized
        if (info.pv.first() !in s.legal) return@synchronized   // never show a line that does not fit the position
        // Lines are replaced in place: clearing them per depth made the UI shrink and regrow.
        if (info.multipv == 1) s.depth = maxOf(s.depth, info.depth)
        if (info.multipv <= s.lines.size) s.lines[info.multipv - 1] = EngineLine(info.score, info.pv, info.depth)
        s.nps = info.nps
        s.onInfo(s.snapshot())
    }

    private suspend fun expect(prefix: String, cmd: String, timeoutMs: Long): Boolean {
        val d = CompletableDeferred<Unit>()
        synchronized(lock) { waiters.add(prefix to d) }
        send(cmd)
        return withTimeoutOrNull(timeoutMs) { d.await() } != null
    }

    private suspend fun handshake(): Boolean {
        if (!expect("uciok", "uci", 5000)) return false
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
        return expect("readyok", "isready", 5000)
    }

    /** Stops any running search and waits until every `go` has been answered; restarts a stuck engine. */
    private suspend fun ensureIdle() {
        if (synchronized(lock) { alive && finished >= sent }) return
        if (synchronized(lock) { alive }) {
            send("stop")
            val idle = withTimeoutOrNull(4000) { while (synchronized(lock) { finished < sent }) delay(15) } != null
            if (idle) return
        }
        withContext(Dispatchers.IO) { launch() && handshake() }
    }

    override suspend fun analyze(req: AnalysisRequest, onInfo: (AnalysisSnapshot) -> Unit): AnalysisSnapshot = mutex.withLock {
        val legal = runCatching { Game(req.startFen).apply { req.moves.forEach { move(it) ?: error(it) } }.legalUci().toSet() }.getOrDefault(emptySet())
        if (legal.isEmpty()) return@withLock AnalysisSnapshot(0, emptyList())
        ensureIdle()
        if (newGamePending) { newGamePending = false; send("ucinewgame") }
        if (multipv != req.multipv) { send("setoption name MultiPV value ${req.multipv}"); multipv = req.multipv }
        send("position fen ${req.startFen}" + if (req.moves.isEmpty()) "" else " moves " + req.moves.joinToString(" "))
        val search = synchronized(lock) { Search(++sent, legal, req.multipv, onInfo).also { current = it } }
        send(if (req.infinite) "go infinite" else buildString { append("go"); req.depth?.let { append(" depth $it") }; req.movetimeMs?.let { append(" movetime $it") } })
        try {
            search.done.await()
        } catch (e: CancellationException) {
            synchronized(lock) { if (current === search) current = null }
            send("stop") // its bestmove is accounted for by the next ensureIdle()
            throw e
        }
    }

    /** Deferred to the next search so it is never sent while Stockfish is thinking. */
    override fun newGame() { newGamePending = true }
    override fun close() { send("quit"); synchronized(lock) { generation++; process?.destroy() } }

    companion object {
        suspend fun start(context: Context): StockfishEngine? {
            val candidates = withContext(Dispatchers.IO) {
                listOfNotNull(
                    File(context.applicationInfo.nativeLibraryDir, "libstockfish.so"),
                    // Pre-Android-10 devices may still execute a copy from app storage.
                    if (Build.VERSION.SDK_INT < 29) copyAsset(context) else null,
                )
            }
            for (file in candidates) if (file.exists()) startAt(file.absolutePath)?.let { return it }
            return null
        }

        /** Starts and handshakes the engine binary at [path]; null if it does not speak UCI. */
        suspend fun startAt(path: String): StockfishEngine? = withContext(Dispatchers.IO) {
            val engine = StockfishEngine(path)
            if (engine.launch() && engine.handshake()) engine else { engine.close(); null }
        }

        private fun copyAsset(context: Context): File? = runCatching {
            val file = File(context.filesDir, "stockfish")
            context.assets.open("stockfish/stockfish").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.setExecutable(true); file
        }.getOrNull()
    }
}

/** The pure-Kotlin engine from :core, confined to one worker thread. */
class BuiltinEngine : ChessEngine {
    override val name = "谱着内置引擎"
    override val isStockfish = false
    private val searcher = Searcher()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "puzhe-search").apply { isDaemon = true } }

    override suspend fun analyze(req: AnalysisRequest, onInfo: (AnalysisSnapshot) -> Unit): AnalysisSnapshot = suspendCancellableCoroutine { cont ->
        val future = worker.submit {
            val game = Game(req.startFen)
            for (m in req.moves) game.move(m) ?: break
            val pos: Position = game.pos
            var lastNps = 0L
            val start = System.currentTimeMillis()
            val res = searcher.search(
                pos, depth = req.depth ?: 64, movetimeMs = req.movetimeMs ?: if (req.infinite) 30_000 else 1500,
                multipv = req.multipv, repetitions = game.keyHistory,
            ) { lines, depth, nodes ->
                lastNps = nodes * 1000 / maxOf(1, System.currentTimeMillis() - start)
                onInfo(AnalysisSnapshot(depth, lines.map { it.toEngineLine(pos) }, lastNps))
            }
            val snapshot = AnalysisSnapshot(res.lines.firstOrNull()?.depth ?: 0, res.lines.map { it.toEngineLine(pos) }, lastNps, if (res.bestmove != 0) pos.uci(res.bestmove) else null)
            if (cont.isActive) cont.resume(snapshot)
        }
        cont.invokeOnCancellation { searcher.stopRequested = true; future.cancel(false) }
    }

    private fun com.deepshawn.antichess.core.SearchLine.toEngineLine(pos: Position) =
        EngineLine(Searcher.toScore(score), pvToUci(pos, pv), depth)

    private fun pvToUci(pos: Position, pv: List<Int>): List<String> {
        val p = pos.copy(); val out = ArrayList<String>()
        for (m in pv) { out.add(p.uci(m)); if (!p.make(m)) break }
        return out
    }

    override fun newGame() { worker.execute { searcher.clear() } }
    override fun close() { searcher.stopRequested = true; worker.shutdownNow() }
}
