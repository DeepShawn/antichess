package com.deepshawn.antichess.core

// 谱着 core rules for Android — a line-for-line sibling of web/src/core/chess.js.
// 0x88 board, Zobrist hashing, SAN/FEN/PGN and draw detection; verified by perft.

const val WHITE = 0
const val BLACK = 8
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6
const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

const val FLAG_CAPTURE = 1
const val FLAG_DOUBLE = 2
const val FLAG_EP = 4
const val FLAG_CASTLE = 8
private const val CASTLE_WK = 1
private const val CASTLE_WQ = 2
private const val CASTLE_BK = 4
private const val CASTLE_BQ = 8

private const val PIECE_CHARS = " pnbrqk"
private val KNIGHT_DELTAS = intArrayOf(33, 31, 18, 14, -33, -31, -18, -14)
private val KING_DELTAS = intArrayOf(1, -1, 16, -16, 17, 15, -17, -15)
private val BISHOP_DELTAS = intArrayOf(17, 15, -17, -15)
private val ROOK_DELTAS = intArrayOf(1, -1, 16, -16)

private val CASTLE_MASK = IntArray(128) { 15 }.also {
    it[0x00] = 15 and CASTLE_WQ.inv(); it[0x07] = 15 and CASTLE_WK.inv(); it[0x04] = 15 and (CASTLE_WK or CASTLE_WQ).inv()
    it[0x70] = 15 and CASTLE_BQ.inv(); it[0x77] = 15 and CASTLE_BK.inv(); it[0x74] = 15 and (CASTLE_BK or CASTLE_BQ).inv()
}

fun moveFrom(m: Int) = m and 0xff
fun moveTo(m: Int) = (m shr 8) and 0xff
fun movePromo(m: Int) = (m shr 16) and 0xf
fun moveFlags(m: Int) = (m shr 20) and 0xf
private fun encode(from: Int, to: Int, promo: Int, flags: Int) = from or (to shl 8) or (promo shl 16) or (flags shl 20)

fun typeOf(p: Int) = p and 7
fun colorOf(p: Int) = p and 8
fun onBoard(sq: Int) = sq and 0x88 == 0
fun squareName(sq: Int) = "${'a' + (sq and 7)}${(sq shr 4) + 1}"
fun parseSquare(s: String) = (s[1] - '1') * 16 + (s[0] - 'a')

private object Zobrist {
    private var s = 0x2545F4914F6CDD1DL
    private fun next(): Long { s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17); return s }
    val piece = LongArray(16 * 128) { next() }
    val castle = LongArray(16) { next() }
    val ep = LongArray(8) { next() }
    val side = next()
}

class FenException(message: String) : IllegalArgumentException(message)

class Position(fen: String = START_FEN) {
    val board = IntArray(128)
    val kings = IntArray(2)
    var side = WHITE; private set
    var castling = 0; private set
    var ep = -1; private set
    var halfmove = 0; private set
    var fullmove = 1; private set
    var hash = 0L; private set

    private class Undo(val m: Int, val captured: Int, val castling: Int, val ep: Int, val halfmove: Int, val hash: Long)
    private val stack = ArrayList<Undo>(256)

    init { load(fen) }

    fun load(fen: String) {
        val parts = fen.trim().split(Regex("\\s+"))
        if (parts.size < 4) throw FenException("FEN 需要至少 4 个字段")
        val rows = parts[0].split('/')
        if (rows.size != 8) throw FenException("FEN 棋盘必须有 8 行")
        board.fill(0); kings[0] = -1; kings[1] = -1
        rows.forEachIndexed { i, row ->
            var file = 0
            val rank = 7 - i
            for (ch in row) {
                if (ch.isDigit()) { file += ch - '0'; continue }
                val type = PIECE_CHARS.indexOf(ch.lowercaseChar())
                if (type < 1 || file > 7) throw FenException("FEN 中有非法字符 \"$ch\"")
                val color = if (ch.isUpperCase()) WHITE else BLACK
                board[rank * 16 + file] = type or color
                if (type == KING) {
                    if (kings[color shr 3] != -1) throw FenException("每方只能有一个王")
                    kings[color shr 3] = rank * 16 + file
                }
                file++
            }
            if (file != 8) throw FenException("FEN 第 ${i + 1} 行格数不是 8")
        }
        if (kings[0] < 0 || kings[1] < 0) throw FenException("双方都必须有王")
        side = when (parts[1]) { "w" -> WHITE; "b" -> BLACK; else -> throw FenException("FEN 行棋方必须为 w 或 b") }
        var c = 0
        if (parts[2] != "-") for (ch in parts[2]) c = c or when (ch) { 'K' -> CASTLE_WK; 'Q' -> CASTLE_WQ; 'k' -> CASTLE_BK; 'q' -> CASTLE_BQ; else -> 0 }
        if (board[0x04] != KING) c = c and (CASTLE_WK or CASTLE_WQ).inv()
        if (board[0x74] != (KING or BLACK)) c = c and (CASTLE_BK or CASTLE_BQ).inv()
        if (board[0x07] != ROOK) c = c and CASTLE_WK.inv()
        if (board[0x00] != ROOK) c = c and CASTLE_WQ.inv()
        if (board[0x77] != (ROOK or BLACK)) c = c and CASTLE_BK.inv()
        if (board[0x70] != (ROOK or BLACK)) c = c and CASTLE_BQ.inv()
        castling = c
        ep = if (parts[3] != "-" && Regex("[a-h][36]").matches(parts[3])) parseSquare(parts[3]) else -1
        halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
        fullmove = maxOf(1, parts.getOrNull(5)?.toIntOrNull() ?: 1)
        if (ep >= 0 && !epCapturable(ep)) ep = -1
        stack.clear()
        computeHash()
        if (isAttacked(kings[(side xor 8) shr 3], side)) throw FenException("非行棋方的王正被将军")
    }

    fun copy(): Position = Position(fen())

    private fun computeHash() {
        var h = 0L
        for (sq in 0 until 128) { if (sq and 0x88 != 0) continue; val p = board[sq]; if (p != 0) h = h xor Zobrist.piece[p * 128 + sq] }
        h = h xor Zobrist.castle[castling]
        if (ep >= 0) h = h xor Zobrist.ep[ep and 7]
        if (side == BLACK) h = h xor Zobrist.side
        hash = h
    }

    private fun epCapturable(ep: Int): Boolean {
        val pawn = PAWN or side
        val dir = if (side == WHITE) -16 else 16
        for (d in intArrayOf(-1, 1)) { val s = ep + dir + d; if (onBoard(s) && board[s] == pawn) return true }
        return false
    }

    fun fen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board[rank * 16 + file]
                if (p == 0) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                val ch = PIECE_CHARS[typeOf(p)]
                sb.append(if (colorOf(p) == WHITE) ch.uppercaseChar() else ch)
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        val cs = buildString {
            if (castling and CASTLE_WK != 0) append('K'); if (castling and CASTLE_WQ != 0) append('Q')
            if (castling and CASTLE_BK != 0) append('k'); if (castling and CASTLE_BQ != 0) append('q')
        }.ifEmpty { "-" }
        return "$sb ${if (side == WHITE) 'w' else 'b'} $cs ${if (ep >= 0) squareName(ep) else "-"} $halfmove $fullmove"
    }

    fun isAttacked(sq: Int, by: Int): Boolean {
        val b = board
        val pawn = PAWN or by
        if (by == WHITE) {
            if ((sq - 15) and 0x88 == 0 && b[sq - 15] == pawn) return true
            if ((sq - 17) and 0x88 == 0 && b[sq - 17] == pawn) return true
        } else {
            if ((sq + 15) and 0x88 == 0 && b[sq + 15] == pawn) return true
            if ((sq + 17) and 0x88 == 0 && b[sq + 17] == pawn) return true
        }
        val knight = KNIGHT or by; val king = KING or by
        for (i in 0 until 8) {
            var s = sq + KNIGHT_DELTAS[i]; if (s and 0x88 == 0 && b[s] == knight) return true
            s = sq + KING_DELTAS[i]; if (s and 0x88 == 0 && b[s] == king) return true
        }
        val bishop = BISHOP or by; val rook = ROOK or by; val queen = QUEEN or by
        for (i in 0 until 4) {
            var d = BISHOP_DELTAS[i]; var s = sq + d
            while (s and 0x88 == 0) { val p = b[s]; if (p != 0) { if (p == bishop || p == queen) return true; break }; s += d }
            d = ROOK_DELTAS[i]; s = sq + d
            while (s and 0x88 == 0) { val p = b[s]; if (p != 0) { if (p == rook || p == queen) return true; break }; s += d }
        }
        return false
    }

    fun inCheck(color: Int = side) = isAttacked(kings[color shr 3], color xor 8)

    /** Pseudo-legal moves; capturesOnly (plus promotions) is used by quiescence search. */
    fun pseudoMoves(out: IntList = IntList(), capturesOnly: Boolean = false): IntList {
        val b = board; val us = side; val them = us xor 8
        for (from in 0 until 128) {
            if (from and 0x88 != 0) continue
            val p = b[from]
            if (p == 0 || colorOf(p) != us) continue
            when (val t = typeOf(p)) {
                PAWN -> {
                    val dir = if (us == WHITE) 16 else -16
                    val startRank = if (us == WHITE) 1 else 6
                    val lastRank = if (us == WHITE) 7 else 0
                    val one = from + dir
                    if (!capturesOnly || (one shr 4) == lastRank) {
                        if (onBoard(one) && b[one] == 0) {
                            if ((one shr 4) == lastRank) for (pr in QUEEN downTo KNIGHT) out.add(encode(from, one, pr, 0))
                            else {
                                out.add(encode(from, one, 0, 0))
                                val two = one + dir
                                if (!capturesOnly && (from shr 4) == startRank && b[two] == 0) out.add(encode(from, two, 0, FLAG_DOUBLE))
                            }
                        }
                    }
                    for (d in intArrayOf(dir - 1, dir + 1)) {
                        val to = from + d
                        if (!onBoard(to)) continue
                        val q = b[to]
                        if (q != 0 && colorOf(q) == them) {
                            if ((to shr 4) == lastRank) for (pr in QUEEN downTo KNIGHT) out.add(encode(from, to, pr, FLAG_CAPTURE))
                            else out.add(encode(from, to, 0, FLAG_CAPTURE))
                        } else if (to == ep) out.add(encode(from, to, 0, FLAG_CAPTURE or FLAG_EP))
                    }
                }
                KNIGHT, KING -> {
                    val deltas = if (t == KNIGHT) KNIGHT_DELTAS else KING_DELTAS
                    for (d in deltas) {
                        val to = from + d
                        if (to and 0x88 != 0) continue
                        val q = b[to]
                        if (q == 0) { if (!capturesOnly) out.add(encode(from, to, 0, 0)) }
                        else if (colorOf(q) == them) out.add(encode(from, to, 0, FLAG_CAPTURE))
                    }
                    if (t == KING && !capturesOnly) castleMoves(from, out)
                }
                else -> {
                    val deltas = when (t) { BISHOP -> BISHOP_DELTAS; ROOK -> ROOK_DELTAS; else -> KING_DELTAS }
                    for (d in deltas) {
                        var to = from + d
                        while (onBoard(to)) {
                            val q = b[to]
                            if (q == 0) { if (!capturesOnly) out.add(encode(from, to, 0, 0)) }
                            else { if (colorOf(q) == them) out.add(encode(from, to, 0, FLAG_CAPTURE)); break }
                            to += d
                        }
                    }
                }
            }
        }
        return out
    }

    private fun castleMoves(from: Int, out: IntList) {
        val us = side; val b = board; val them = us xor 8
        val kFlag = if (us == WHITE) CASTLE_WK else CASTLE_BK
        val qFlag = if (us == WHITE) CASTLE_WQ else CASTLE_BQ
        val home = if (us == WHITE) 0x04 else 0x74
        if (from != home || castling and (kFlag or qFlag) == 0 || isAttacked(home, them)) return
        if (castling and kFlag != 0 && b[home + 1] == 0 && b[home + 2] == 0 && !isAttacked(home + 1, them) && !isAttacked(home + 2, them))
            out.add(encode(home, home + 2, 0, FLAG_CASTLE))
        if (castling and qFlag != 0 && b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 && !isAttacked(home - 1, them) && !isAttacked(home - 2, them))
            out.add(encode(home, home - 2, 0, FLAG_CASTLE))
    }

    /** Applies a move; returns false (and restores) if it leaves the mover in check. */
    fun make(m: Int): Boolean {
        val b = board; val from = moveFrom(m); val to = moveTo(m); val flags = moveFlags(m); val promo = movePromo(m)
        val us = side; val them = us xor 8; val piece = b[from]
        var captured = b[to]; var capSq = to
        if (flags and FLAG_EP != 0) { capSq = to + if (us == WHITE) -16 else 16; captured = b[capSq] }
        stack.add(Undo(m, captured, castling, ep, halfmove, hash))
        var h = hash
        if (captured != 0) { b[capSq] = 0; h = h xor Zobrist.piece[captured * 128 + capSq] }
        b[from] = 0; h = h xor Zobrist.piece[piece * 128 + from]
        val placed = if (promo != 0) promo or us else piece
        b[to] = placed; h = h xor Zobrist.piece[placed * 128 + to]
        if (flags and FLAG_CASTLE != 0) {
            val rFrom = if (to > from) from + 3 else from - 4
            val rTo = if (to > from) from + 1 else from - 1
            val rook = b[rFrom]; b[rFrom] = 0; b[rTo] = rook
            h = h xor Zobrist.piece[rook * 128 + rFrom] xor Zobrist.piece[rook * 128 + rTo]
        }
        if (typeOf(piece) == KING) kings[us shr 3] = to
        h = h xor Zobrist.castle[castling]
        castling = castling and CASTLE_MASK[from] and CASTLE_MASK[to]
        h = h xor Zobrist.castle[castling]
        if (ep >= 0) h = h xor Zobrist.ep[ep and 7]
        ep = -1
        halfmove = if (typeOf(piece) == PAWN || captured != 0) 0 else halfmove + 1
        if (us == BLACK) fullmove++
        side = them
        h = h xor Zobrist.side
        if (flags and FLAG_DOUBLE != 0) {
            val e = (from + to) shr 1
            if (epCapturable(e)) { ep = e; h = h xor Zobrist.ep[e and 7] }
        }
        hash = h
        if (isAttacked(kings[us shr 3], them)) { unmake(); return false }
        return true
    }

    fun unmake() {
        val u = stack.removeAt(stack.size - 1); val m = u.m; val b = board
        val from = moveFrom(m); val to = moveTo(m); val flags = moveFlags(m)
        side = side xor 8
        val us = side
        val moved = if (movePromo(m) != 0) PAWN or us else b[to]
        b[from] = moved; b[to] = 0
        if (u.captured != 0) b[if (flags and FLAG_EP != 0) to + (if (us == WHITE) -16 else 16) else to] = u.captured
        if (flags and FLAG_CASTLE != 0) {
            val rFrom = if (to > from) from + 3 else from - 4
            val rTo = if (to > from) from + 1 else from - 1
            b[rFrom] = b[rTo]; b[rTo] = 0
        }
        if (typeOf(moved) == KING) kings[us shr 3] = from
        if (us == BLACK) fullmove--
        castling = u.castling; ep = u.ep; halfmove = u.halfmove; hash = u.hash
    }

    fun makeNull() {
        stack.add(Undo(0, 0, castling, ep, halfmove, hash))
        if (ep >= 0) hash = hash xor Zobrist.ep[ep and 7]
        ep = -1; side = side xor 8; hash = hash xor Zobrist.side
    }

    fun unmakeNull() { val u = stack.removeAt(stack.size - 1); side = side xor 8; ep = u.ep; hash = u.hash }

    fun legalMoves(): IntList {
        val out = IntList()
        val pseudo = pseudoMoves()
        for (i in 0 until pseudo.size) { val m = pseudo[i]; if (make(m)) { unmake(); out.add(m) } }
        return out
    }

    fun insufficientMaterial(): Boolean {
        var minors = 0; var bishopShade = -1; var mixed = false; var knights = 0
        for (sq in 0 until 128) {
            if (sq and 0x88 != 0) continue
            val p = board[sq]; val t = typeOf(p)
            if (p == 0 || t == KING) continue
            if (t == PAWN || t == ROOK || t == QUEEN) return false
            minors++
            if (t == KNIGHT) knights++
            else { val shade = ((sq shr 4) + (sq and 7)) and 1; if (bishopShade == -1) bishopShade = shade else if (shade != bishopShade) mixed = true }
        }
        if (minors <= 1) return true
        return knights == 0 && !mixed
    }

    fun uci(m: Int) = squareName(moveFrom(m)) + squareName(moveTo(m)) + (if (movePromo(m) != 0) PIECE_CHARS[movePromo(m)].toString() else "")

    fun san(m: Int, legal: IntList = legalMoves()): String {
        val from = moveFrom(m); val to = moveTo(m); val flags = moveFlags(m); val piece = board[from]; val t = typeOf(piece)
        val sb = StringBuilder()
        if (flags and FLAG_CASTLE != 0) sb.append(if (to > from) "O-O" else "O-O-O")
        else {
            if (t == PAWN) { if (flags and FLAG_CAPTURE != 0) sb.append('a' + (from and 7)) }
            else {
                sb.append(PIECE_CHARS[t].uppercaseChar())
                var any = false; var sameFile = false; var sameRank = false
                for (i in 0 until legal.size) {
                    val o = legal[i]
                    if (o == m || moveTo(o) != to || board[moveFrom(o)] != piece) continue
                    any = true
                    if (moveFrom(o) and 7 == from and 7) sameFile = true
                    if (moveFrom(o) shr 4 == from shr 4) sameRank = true
                }
                if (any) when {
                    !sameFile -> sb.append('a' + (from and 7))
                    !sameRank -> sb.append((from shr 4) + 1)
                    else -> sb.append(squareName(from))
                }
            }
            if (flags and FLAG_CAPTURE != 0) sb.append('x')
            sb.append(squareName(to))
            if (movePromo(m) != 0) sb.append('=').append(PIECE_CHARS[movePromo(m)].uppercaseChar())
        }
        make(m)
        if (inCheck()) sb.append(if (legalMoves().size > 0) '+' else '#')
        unmake()
        return sb.toString()
    }

    /** Resolves SAN ("Nf3", "O-O") or UCI ("g1f3", "e7e8q") to an encoded legal move, or 0. */
    fun parseMove(text: String, legal: IntList = legalMoves()): Int {
        val clean = text.trim().trimEnd('+', '#', '?', '!').replace('0', 'O')
        val uci = Regex("^([a-h][1-8])-?([a-h][1-8])([qrbnQRBN])?$").find(clean)
        if (uci != null) {
            val from = parseSquare(uci.groupValues[1]); val to = parseSquare(uci.groupValues[2])
            val promoChar = uci.groupValues[3].lowercase()
            val promo = if (promoChar.isEmpty()) 0 else PIECE_CHARS.indexOf(promoChar[0])
            for (i in 0 until legal.size) {
                val m = legal[i]
                if (moveFrom(m) == from && moveTo(m) == to && (movePromo(m) == promo || (promo == 0 && movePromo(m) == QUEEN))) return m
            }
        }
        val norm = { s: String -> s.replace("+", "").replace("#", "").replace("=", "") }
        val target = norm(clean)
        for (i in 0 until legal.size) if (norm(san(legal[i], legal)) == target) return legal[i]
        return 0
    }
}

/** Minimal growable int list (avoids boxing in move generation). */
class IntList(capacity: Int = 64) {
    var data = IntArray(capacity); private set
    var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    fun contains(v: Int): Boolean { for (i in 0 until size) if (data[i] == v) return true; return false }
    fun toList(): List<Int> = List(size) { data[it] }
}

fun perft(pos: Position, depth: Int): Long {
    if (depth == 0) return 1
    var n = 0L
    val moves = pos.pseudoMoves()
    for (i in 0 until moves.size) {
        if (!pos.make(moves[i])) continue
        n += if (depth == 1) 1 else perft(pos, depth - 1)
        pos.unmake()
    }
    return n
}

data class Piece(val type: Char, val white: Boolean) { val code get() = (if (white) "w" else "b") + type }

data class MoveRecord(
    val from: String, val to: String, val san: String, val uci: String, val piece: Char, val white: Boolean,
    val captured: Char?, val promotion: Char?, val fenBefore: String, val fen: String, val check: Boolean,
)

enum class EndReason(val label: String) { CHECKMATE("将杀"), STALEMATE("逼和"), INSUFFICIENT("子力不足"), THREEFOLD("三次重复"), FIFTY("五十步规则") }
data class GameStatus(val over: Boolean, val result: String = "*", val reason: EndReason? = null, val winnerWhite: Boolean? = null, val check: Boolean = false)

/** A Position plus SAN history, repetition tracking and PGN I/O. */
class Game(fen: String = START_FEN) {
    var pos = Position(fen); private set
    var startFen: String = pos.fen(); private set
    val history = ArrayList<MoveRecord>()
    private val keys = ArrayList<Long>().apply { add(pos.hash) }
    var headers: MutableMap<String, String> = linkedMapOf()

    val ply get() = history.size
    /** Zobrist keys of every position reached (index 0 = start), for repetition-aware search. */
    val keyHistory: List<Long> get() = keys
    fun fen() = pos.fen()
    val whiteToMove get() = pos.side == WHITE
    fun inCheck() = pos.inCheck()
    fun uciMoves() = history.map { it.uci }

    fun pieceAt(square: String): Piece? = pos.board[parseSquare(square)].let { if (it == 0) null else Piece(PIECE_CHARS[typeOf(it)], colorOf(it) == WHITE) }

    /** All legal moves as UCI strings. */
    fun legalUci(): List<String> { val l = pos.legalMoves(); return List(l.size) { pos.uci(l[it]) } }

    fun legalSan(): List<String> { val l = pos.legalMoves(); return List(l.size) { pos.san(l[it], l) } }

    /** Accepts SAN or UCI. Returns null when illegal. */
    fun move(input: String): MoveRecord? {
        val legal = pos.legalMoves()
        val m = pos.parseMove(input, legal)
        if (m == 0) return null
        val from = moveFrom(m); val to = moveTo(m); val flags = moveFlags(m); val p = pos.board[from]
        val capSq = if (flags and FLAG_EP != 0) to + (if (pos.side == WHITE) -16 else 16) else to
        val captured = if (flags and FLAG_CAPTURE != 0) PIECE_CHARS[typeOf(pos.board[capSq])] else null
        val san = pos.san(m, legal); val uci = pos.uci(m); val before = pos.fen()
        pos.make(m)
        val rec = MoveRecord(squareName(from), squareName(to), san, uci, PIECE_CHARS[typeOf(p)], colorOf(p) == WHITE, captured,
            if (movePromo(m) != 0) PIECE_CHARS[movePromo(m)] else null, before, pos.fen(), pos.inCheck())
        history.add(rec); keys.add(pos.hash)
        return rec
    }

    fun undo(): MoveRecord? {
        if (history.isEmpty()) return null
        pos.unmake(); keys.removeAt(keys.size - 1)
        return history.removeAt(history.size - 1)
    }

    fun status(): GameStatus {
        val legal = pos.legalMoves()
        val white = pos.side == WHITE
        if (legal.size == 0) {
            return if (pos.inCheck()) GameStatus(true, if (white) "0-1" else "1-0", EndReason.CHECKMATE, !white)
            else GameStatus(true, "1/2-1/2", EndReason.STALEMATE)
        }
        if (pos.insufficientMaterial()) return GameStatus(true, "1/2-1/2", EndReason.INSUFFICIENT)
        if (keys.count { it == pos.hash } >= 3) return GameStatus(true, "1/2-1/2", EndReason.THREEFOLD)
        if (pos.halfmove >= 100) return GameStatus(true, "1/2-1/2", EndReason.FIFTY)
        return GameStatus(false, check = pos.inCheck())
    }

    /** 8×8 view, rank 8 first. */
    fun board(): List<List<Piece?>> = (7 downTo 0).map { rank -> (0..7).map { file -> pos.board[rank * 16 + file].let { if (it == 0) null else Piece(PIECE_CHARS[typeOf(it)], colorOf(it) == WHITE) } } }

    fun pgn(extra: Map<String, String> = emptyMap()): String {
        val st = status()
        val result = extra["Result"] ?: if (st.over) st.result else "*"
        val h = linkedMapOf("Event" to "谱着 Chess Lab", "Site" to "Android", "White" to "?", "Black" to "?")
        h.putAll(headers); h.putAll(extra); h["Result"] = result
        if (startFen != START_FEN) { h["SetUp"] = "1"; h["FEN"] = startFen }
        val head = h.entries.joinToString("\n") { "[${it.key} \"${it.value.replace('"', '\'')}\"]" }
        val f = startFen.split(" ")
        val startMove = f[5].toIntOrNull() ?: 1; val blackFirst = f[1] == "b"
        val tokens = ArrayList<String>()
        history.forEachIndexed { i, rec ->
            val plyIndex = i + if (blackFirst) 1 else 0
            val num = startMove + plyIndex / 2
            if (plyIndex % 2 == 0) tokens.add("$num.") else if (i == 0) tokens.add("$num...")
            tokens.add(rec.san)
        }
        tokens.add(result)
        val lines = ArrayList<String>(); var line = ""
        for (t in tokens) { if ("$line $t".length > 80) { lines.add(line); line = t } else line = if (line.isEmpty()) t else "$line $t" }
        lines.add(line)
        return "$head\n\n${lines.joinToString("\n")}\n"
    }

    companion object {
        fun fromPgn(text: String): Game {
            val headers = linkedMapOf<String, String>()
            Regex("\\[(\\w+)\\s+\"([^\"]*)\"]").findAll(text).forEach { headers[it.groupValues[1]] = it.groupValues[2] }
            val body = text.replace(Regex("\\[[^]]*]"), " ").replace(Regex("\\{[^}]*\\}"), " ").replace(Regex(";[^\\n]*"), " ")
            val flat = StringBuilder(); var depth = 0
            for (ch in body) when (ch) { '(' -> depth++; ')' -> depth = maxOf(0, depth - 1); else -> if (depth == 0) flat.append(ch) }
            val game = Game(headers["FEN"] ?: START_FEN)
            for (tok in flat.split(Regex("\\s+"))) {
                val t = tok.replace(Regex("^\\d+\\.(\\.\\.)?"), "").replace(Regex("\\$\\d+"), "")
                if (t.isEmpty() || t in setOf("1-0", "0-1", "1/2-1/2", "*")) continue
                game.move(t) ?: throw IllegalArgumentException("PGN 中的走法 \"$t\" 不合法")
            }
            headers.remove("FEN"); headers.remove("SetUp")
            game.headers = headers
            return game
        }
    }
}
