package com.deepshawn.antichess.engine

import android.content.Context
import android.os.Build
import com.deepshawn.antichess.core.EngineLine
import com.deepshawn.antichess.core.Game
import com.deepshawn.antichess.core.Position
import com.deepshawn.antichess.core.Searcher
import com.deepshawn.antichess.core.Uci
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
 */
class StockfishEngine private constructor(private val process: Process) : ChessEngine {
    override val name = "Stockfish 19"
    override val isStockfish = true
    private val writer = process.outputStream.bufferedWriter()
    private val output = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var multipv = 1

    init {
        thread(isDaemon = true, name = "stockfish-reader") {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.trySend(it) } }
            output.close()
        }
    }

    @Synchronized private fun send(cmd: String) { runCatching { writer.write(cmd); writer.newLine(); writer.flush() } }

    private suspend fun waitFor(prefix: String): String? { for (line in output) if (line.startsWith(prefix)) return line; return null }

    override suspend fun analyze(req: AnalysisRequest, onInfo: (AnalysisSnapshot) -> Unit): AnalysisSnapshot = mutex.withLock {
        if (multipv != req.multipv) { send("setoption name MultiPV value ${req.multipv}"); multipv = req.multipv }
        send("position fen ${req.startFen}" + if (req.moves.isEmpty()) "" else " moves " + req.moves.joinToString(" "))
        send(if (req.infinite) "go infinite" else buildString { append("go"); req.depth?.let { append(" depth $it") }; req.movetimeMs?.let { append(" movetime $it") } })
        var depth = 0; var nps = 0L
        val lines = arrayOfNulls<EngineLine>(req.multipv)
        fun snapshot(best: String? = null) = AnalysisSnapshot(depth, lines.filterNotNull(), nps, best)
        try {
            for (line in output) {
                if (line.startsWith("bestmove")) return@withLock snapshot(line.split(' ').getOrNull(1))
                val info = Uci.parseInfo(line) ?: continue
                // Replace lines in place: clearing them per depth made the UI shrink and regrow.
                if (info.multipv == 1) depth = maxOf(depth, info.depth)
                if (info.multipv <= lines.size) lines[info.multipv - 1] = EngineLine(info.score, info.pv, info.depth)
                nps = info.nps
                onInfo(snapshot())
            }
            snapshot()
        } catch (e: CancellationException) {
            // Stop the search and consume its bestmove so the next job starts clean.
            withContext(NonCancellable) { send("stop"); withTimeoutOrNull(3000) { waitFor("bestmove") } }
            throw e
        }
    }

    override fun newGame() { send("ucinewgame") }
    override fun close() { send("quit"); process.destroy() }

    companion object {
        suspend fun start(context: Context): StockfishEngine? = withContext(Dispatchers.IO) {
            val candidates = listOfNotNull(
                File(context.applicationInfo.nativeLibraryDir, "libstockfish.so"),
                // Pre-Android-10 devices may still execute a copy from app storage.
                if (Build.VERSION.SDK_INT < 29) copyAsset(context) else null,
            )
            for (file in candidates) {
                if (!file.exists()) continue
                val engine = runCatching { StockfishEngine(ProcessBuilder(file.absolutePath).redirectErrorStream(true).start()) }.getOrNull() ?: continue
                engine.send("uci")
                val ok = withTimeoutOrNull(5000) { engine.waitFor("uciok") } != null
                if (!ok) { engine.close(); continue }
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
                engine.send("setoption name Threads value $threads")
                engine.send("setoption name Hash value 64")
                engine.send("isready")
                if (withTimeoutOrNull(5000) { engine.waitFor("readyok") } != null) return@withContext engine
                engine.close()
            }
            null
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
