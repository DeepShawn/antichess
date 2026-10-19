package com.deepshawn.antichess.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Built-in engine — mirrors web/src/engine/search.js. Used on Android when the
// bundled Stockfish binary is missing (debug builds, unsupported ABI).

const val MATE = 30000
private const val INF = 32000
private val VALUE_MG = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
private val VALUE_EG = intArrayOf(0, 94, 281, 297, 512, 936, 0)
private val PHASE = intArrayOf(0, 0, 1, 1, 2, 4, 0)
private val PASSED = intArrayOf(0, 5, 10, 20, 35, 60, 100, 0)

private val PST = arrayOf(
    IntArray(64),
    intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0),
    intArrayOf(-50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50),
    intArrayOf(-20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10, -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20),
    intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0),
    intArrayOf(-20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10, -5, 0, 5, 5, 5, 5, 0, -5, 0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20),
    intArrayOf(-30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20),
)
private val KING_EG = intArrayOf(-50, -40, -30, -20, -20, -30, -40, -50, -30, -20, -10, 0, 0, -10, -20, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -30, 0, 0, 0, 0, -30, -30, -50, -30, -30, -30, -30, -30, -30, -50)

private fun idx(sq: Int, color: Int) = (if (color == WHITE) (7 - (sq shr 4)) * 8 else (sq shr 4) * 8) + (sq and 7)

/** Static evaluation in centipawns from the side to move. */
fun evaluate(pos: Position): Int {
    val b = pos.board
    var mg = 0.0; var eg = 0.0; var phase = 0
    val pawnFiles = arrayOf(IntArray(8), IntArray(8))
    val bishops = IntArray(2); val material = IntArray(2)
    for (sq in 0 until 128) { if (sq and 0x88 != 0) continue; val p = b[sq]; if (p != 0 && typeOf(p) == PAWN) pawnFiles[colorOf(p) shr 3][sq and 7]++ }
    for (sq in 0 until 128) {
        if (sq and 0x88 != 0) continue
        val p = b[sq]; if (p == 0) continue
        val t = typeOf(p); val c = colorOf(p); val s = if (c == WHITE) 1 else -1; val i = idx(sq, c); val side = c shr 3
        phase += PHASE[t]
        if (t != PAWN && t != KING) material[side] += VALUE_MG[t]
        var m = (VALUE_MG[t] + PST[t][i]).toDouble()
        var e = (VALUE_EG[t] + when (t) { KING -> KING_EG[i]; PAWN -> 0; else -> PST[t][i] }).toDouble()
        when (t) {
            PAWN -> {
                val file = sq and 7; val rank = if (c == WHITE) sq shr 4 else 7 - (sq shr 4)
                e += rank * 6
                if (pawnFiles[side][file] > 1) { m -= 10; e -= 20 }
                if (!(file > 0 && pawnFiles[side][file - 1] > 0) && !(file < 7 && pawnFiles[side][file + 1] > 0)) { m -= 12; e -= 10 }
                var passed = true
                val dir = if (c == WHITE) 16 else -16; val enemy = PAWN or (c xor 8)
                var r = sq + dir
                while (r and 0x88 == 0 && passed) {
                    for (df in -1..1) { val q = r + df; if (q and 0x88 == 0 && b[q] == enemy) { passed = false; break } }
                    r += dir
                }
                if (passed) { m += PASSED[rank] shr 1; e += PASSED[rank] * 1.3 }
            }
            BISHOP -> bishops[side]++
            ROOK -> { val file = sq and 7; if (pawnFiles[side][file] == 0) { m += if (pawnFiles[side xor 1][file] > 0) 10 else 20; e += 10 } }
        }
        mg += s * m; eg += s * e
    }
    if (bishops[0] >= 2) { mg += 30; eg += 50 }
    if (bishops[1] >= 2) { mg -= 30; eg -= 50 }
    val diff = material[0] - material[1]
    if (abs(diff) >= 300 && phase <= 8) {
        val strong = if (diff > 0) 0 else 1; val weakK = pos.kings[strong xor 1]; val strongK = pos.kings[strong]
        val centre = max(3 - (weakK shr 4), (weakK shr 4) - 4) + max(3 - (weakK and 7), (weakK and 7) - 4)
        val dist = abs((weakK shr 4) - (strongK shr 4)) + abs((weakK and 7) - (strongK and 7))
        eg += (if (strong == 0) 1 else -1) * (centre * 12 + (14 - dist) * 5)
    }
    val ph = min(phase, 24)
    val score = (mg * ph + eg * (24 - ph)) / 24
    return ((if (pos.side == WHITE) score else -score) + 12).toInt()
}

data class SearchLine(val move: Int, val score: Int, val pv: List<Int>, val depth: Int)
data class SearchResult(val bestmove: Int, val lines: List<SearchLine>, val nodes: Long)

class Searcher(ttBits: Int = 19) {
    private val ttSize = 1 shl ttBits
    private val ttMask = ttSize - 1
    private val ttKey = LongArray(ttSize); private val ttMove = IntArray(ttSize); private val ttScore = ShortArray(ttSize)
    private val ttDepth = ByteArray(ttSize); private val ttFlag = ByteArray(ttSize)
    private val history = IntArray(16 * 128)
    private val killers = Array(128) { IntArray(2) }

    @Volatile var stopRequested = false
    private lateinit var pos: Position
    private var nodes = 0L
    private var aborted = false
    private var deadline = 0L
    private var path = LongArray(1024); private var pathLen = 0

    fun clear() { ttFlag.fill(0); history.fill(0) }

    fun search(root: Position, depth: Int = 64, movetimeMs: Long = 1_000_000, multipv: Int = 1, repetitions: List<Long> = emptyList(), onInfo: ((List<SearchLine>, Int, Long) -> Unit)? = null): SearchResult {
        pos = root.copy(); nodes = 0; aborted = false; stopRequested = false
        val start = System.currentTimeMillis(); deadline = start + movetimeMs
        pathLen = 0; for (k in repetitions) pushPath(k)
        for (k in killers) { k[0] = 0; k[1] = 0 }
        val rootMoves = pos.legalMoves()
        if (rootMoves.size == 0) return SearchResult(0, emptyList(), 0)
        var lines = rootMoves.toList().map { SearchLine(it, -INF, listOf(it), 0) }
        val k = min(multipv, rootMoves.size)
        var completed = emptyList<SearchLine>()
        for (d in 1..depth) {
            val res = searchRoot(lines, d, k)
            if (aborted && completed.isNotEmpty()) break
            lines = res
            completed = res.take(k).map { it.copy(depth = d) }
            onInfo?.invoke(completed, d, nodes)
            if (aborted || (abs(completed[0].score) > MATE - 200 && d >= 4) || (rootMoves.size == 1 && d >= 4)) break
        }
        return SearchResult(completed[0].move, completed, nodes)
    }

    private fun pushPath(k: Long) { if (pathLen == path.size) path = path.copyOf(pathLen * 2); path[pathLen++] = k }

    private fun searchRoot(lines: List<SearchLine>, depth: Int, k: Int): List<SearchLine> {
        val results = ArrayList<SearchLine>()
        for (line in lines) {
            val m = line.move
            pos.make(m); pushPath(pos.hash)
            val kth = if (results.size >= k) results.map { it.score }.sortedDescending()[k - 1] else -INF
            var score: Int
            if (kth == -INF) score = -negamax(depth - 1, -INF, INF, 1, true)
            else {
                score = -negamax(depth - 1, -kth - 1, -kth, 1, true)
                if (score > kth && !aborted) score = -negamax(depth - 1, -INF, -kth, 1, true)
            }
            pathLen--
            val pv = listOf(m) + pvFrom(depth + 4)
            pos.unmake()
            if (aborted) break
            results.add(SearchLine(m, score, pv, depth))
        }
        val done = results.map { it.move }.toSet()
        return results.sortedByDescending { it.score } + lines.filter { it.move !in done }
    }

    private fun pvFrom(max: Int): List<Int> {
        val pv = ArrayList<Int>(); var n = 0
        while (n < max) {
            val slot = (pos.hash and ttMask.toLong()).toInt()
            if (ttKey[slot] != pos.hash || ttMove[slot] == 0) break
            val m = ttMove[slot]
            if (!pos.legalMoves().contains(m) || !pos.make(m)) break
            pv.add(m); n++
        }
        repeat(n) { pos.unmake() }
        return pv
    }

    private fun timeUp(): Boolean {
        if (nodes and 2047L == 0L && (System.currentTimeMillis() > deadline || stopRequested)) aborted = true
        return aborted
    }

    private fun isRepetition(): Boolean {
        val k = pos.hash
        var i = pathLen - 3; var n = 0
        while (i >= 0 && n < pos.halfmove) { if (path[i] == k) return true; i -= 2; n += 2 }
        return false
    }

    private fun hasPieces(side: Int): Boolean {
        for (sq in 0 until 128) { if (sq and 0x88 != 0) continue; val p = pos.board[sq]; if (p != 0 && colorOf(p) == side && typeOf(p) in KNIGHT..QUEEN) return true }
        return false
    }

    private fun negamax(depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, allowNull: Boolean): Int {
        var depth = depthIn; var alpha = alphaIn; var beta = betaIn
        if (pos.halfmove >= 100 || isRepetition() || pos.insufficientMaterial()) return 0
        val inCheck = pos.inCheck()
        if (inCheck) depth++
        if (depth <= 0) return quiesce(alpha, beta, ply)
        nodes++
        if (timeUp()) return 0
        alpha = max(alpha, -MATE + ply); beta = min(beta, MATE - ply - 1)
        if (alpha >= beta) return alpha

        val slot = (pos.hash and ttMask.toLong()).toInt()
        val hit = ttKey[slot] == pos.hash && ttFlag[slot].toInt() != 0
        val ttMv = if (hit) ttMove[slot] else 0
        val pvNode = beta - alpha > 1
        if (hit && ttDepth[slot] >= depth && !pvNode) {
            var s = ttScore[slot].toInt()
            if (s > MATE - 500) s -= ply else if (s < -MATE + 500) s += ply
            val f = ttFlag[slot].toInt()
            if (f == 1 || (f == 2 && s >= beta) || (f == 3 && s <= alpha)) return s
        }
        val staticEval = evaluate(pos)
        if (!pvNode && !inCheck && depth <= 3 && staticEval - 120 * depth >= beta) return staticEval
        if (allowNull && !pvNode && !inCheck && depth >= 3 && staticEval >= beta && hasPieces(pos.side)) {
            pos.makeNull(); pushPath(pos.hash)
            val s = -negamax(depth - 3, -beta, -beta + 1, ply + 1, false)
            pathLen--; pos.unmakeNull()
            if (aborted) return 0
            if (s >= beta) return if (s > MATE - 500) beta else s
        }
        val moves = order(pos.pseudoMoves(), ttMv, ply)
        var best = -INF; var bestMove = 0; var legal = 0; var flag = 3
        for (m in moves) {
            if (!pos.make(m)) continue
            legal++
            pushPath(pos.hash)
            val quiet = moveFlags(m) and FLAG_CAPTURE == 0 && movePromo(m) == 0
            var s: Int
            if (legal == 1) s = -negamax(depth - 1, -beta, -alpha, ply + 1, true)
            else {
                var r = 0
                if (depth >= 3 && quiet && !inCheck && legal > 3 && !pos.inCheck()) r = if (legal > 8) 2 else 1
                s = -negamax(depth - 1 - r, -alpha - 1, -alpha, ply + 1, true)
                if (s > alpha && (r > 0 || s < beta)) s = -negamax(depth - 1, -beta, -alpha, ply + 1, true)
            }
            pathLen--
            pos.unmake()
            if (aborted) return 0
            if (s > best) {
                best = s; bestMove = m
                if (s > alpha) {
                    alpha = s; flag = 1
                    if (s >= beta) {
                        flag = 2
                        if (quiet) {
                            val k = killers[min(ply, 127)]; if (k[0] != m) { k[1] = k[0]; k[0] = m }
                            history[pos.board[moveFrom(m)] * 128 + moveTo(m)] += depth * depth
                        }
                        break
                    }
                }
            }
        }
        if (legal == 0) return if (inCheck) -MATE + ply else 0
        var stored = best
        if (stored > MATE - 500) stored += ply else if (stored < -MATE + 500) stored -= ply
        ttKey[slot] = pos.hash; ttMove[slot] = bestMove; ttScore[slot] = stored.toShort(); ttDepth[slot] = min(depth, 127).toByte(); ttFlag[slot] = flag.toByte()
        return best
    }

    private fun quiesce(alphaIn: Int, beta: Int, ply: Int): Int {
        var alpha = alphaIn
        nodes++
        if (timeUp()) return 0
        val stand = evaluate(pos)
        if (stand >= beta) return stand
        if (stand > alpha) alpha = stand
        if (ply > 100) return stand
        for (m in order(pos.pseudoMoves(IntList(), true), 0, ply)) {
            val victim = typeOf(pos.board[moveTo(m)]).takeIf { it != 0 } ?: PAWN
            if (stand + VALUE_MG[victim] + 200 < alpha && movePromo(m) == 0) continue
            if (!pos.make(m)) continue
            val s = -quiesce(-beta, -alpha, ply + 1)
            pos.unmake()
            if (aborted) return 0
            if (s >= beta) return s
            if (s > alpha) alpha = s
        }
        return alpha
    }

    private fun order(moves: IntList, ttMv: Int, ply: Int): IntArray {
        val b = pos.board; val k = killers[min(ply, 127)]
        val n = moves.size
        val scores = IntArray(n) { i ->
            val m = moves[i]
            when {
                m == ttMv -> 1 shl 30
                moveFlags(m) and FLAG_CAPTURE != 0 -> (1 shl 20) + (typeOf(b[moveTo(m)]).takeIf { it != 0 } ?: PAWN) * 16 - typeOf(b[moveFrom(m)])
                movePromo(m) != 0 -> (1 shl 19) + movePromo(m)
                m == k[0] -> 1 shl 18
                m == k[1] -> (1 shl 18) - 1
                else -> min(history[b[moveFrom(m)] * 128 + moveTo(m)], 1 shl 17)
            }
        }
        return (0 until n).sortedByDescending { scores[it] }.map { moves[it] }.toIntArray()
    }

    companion object {
        /** Converts an internal score (side to move) to a UCI-style Score. */
        fun toScore(score: Int): Score =
            if (abs(score) > MATE - 500) { val plies = MATE - abs(score); val mv = (plies + 1) / 2; Score(mate = if (score > 0) mv else -mv) } else Score(cp = score)
    }
}
