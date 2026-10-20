// 谱着 built-in engine: iterative-deepening PVS with a transposition table,
// null-move pruning, LMR, killer/history ordering, quiescence and a tapered
// evaluation. It is the offline fallback when Stockfish WASM is unavailable.
import {
  Position, WHITE, BLACK, PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING,
  moveFrom, moveTo, movePromo, moveFlags, typeOf, colorOf, FLAG_CAPTURE,
} from '../core/chess.js';

export const MATE = 30000;
const INF = 32000;
const VALUE_MG = [0, 82, 337, 365, 477, 1025, 0];
const VALUE_EG = [0, 94, 281, 297, 512, 936, 0];
const PHASE = [0, 0, 1, 1, 2, 4, 0];

// Piece-square tables, a8 first, from White's point of view.
const T = {
  [PAWN]: [0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10, 5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5, 5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0],
  [KNIGHT]: [-50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30, -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50],
  [BISHOP]: [-20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10, -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10, -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20],
  [ROOK]: [0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0],
  [QUEEN]: [-20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10, -10, 0, 5, 5, 5, 5, 0, -10, -5, 0, 5, 5, 5, 5, 0, -5, 0, 0, 5, 5, 5, 5, 0, -5, -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20],
  [KING]: [-30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30, -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10, 20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20],
};
const KING_EG = [-50, -40, -30, -20, -20, -30, -40, -50, -30, -20, -10, 0, 0, -10, -20, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 30, 40, 40, 30, -10, -30, -30, -10, 20, 30, 30, 20, -10, -30, -30, -30, 0, 0, 0, 0, -30, -30, -50, -30, -30, -30, -30, -30, -30, -50];
const PASSED = [0, 5, 10, 20, 35, 60, 100, 0];

const idx = (sq, color) => (color === WHITE ? (7 - (sq >> 4)) * 8 : (sq >> 4) * 8) + (sq & 7);

/** Static evaluation in centipawns from the side to move. */
export function evaluate(pos) {
  const b = pos.board;
  let mg = 0, eg = 0, phase = 0;
  const pawnFiles = [new Int8Array(8), new Int8Array(8)];
  const bishops = [0, 0], material = [0, 0];
  for (let sq = 0; sq < 128; sq++) {
    if (sq & 0x88) { sq += 7; continue; }
    const p = b[sq]; if (p && typeOf(p) === PAWN) pawnFiles[colorOf(p) >> 3][sq & 7]++;
  }
  for (let sq = 0; sq < 128; sq++) {
    if (sq & 0x88) { sq += 7; continue; }
    const p = b[sq]; if (!p) continue;
    const t = typeOf(p), c = colorOf(p), s = c === WHITE ? 1 : -1, i = idx(sq, c), side = c >> 3;
    phase += PHASE[t];
    if (t !== PAWN && t !== KING) material[side] += VALUE_MG[t];
    let m = VALUE_MG[t] + T[t][i], e = VALUE_EG[t] + (t === KING ? KING_EG[i] : t === PAWN ? 0 : T[t][i]);
    if (t === PAWN) {
      const file = sq & 7, rank = c === WHITE ? sq >> 4 : 7 - (sq >> 4);
      e += rank * 6;
      if (pawnFiles[side][file] > 1) { m -= 10; e -= 20; }
      if (!(file > 0 && pawnFiles[side][file - 1]) && !(file < 7 && pawnFiles[side][file + 1])) { m -= 12; e -= 10; }
      // Passed pawn: no enemy pawns ahead on adjacent files.
      let passed = true;
      const dir = c === WHITE ? 16 : -16, enemy = PAWN | (c ^ 8);
      for (let r = sq + dir; (r & 0x88) === 0 && passed; r += dir)
        for (let df = -1; df <= 1; df++) { const q = r + df; if ((q & 0x88) === 0 && b[q] === enemy) { passed = false; break; } }
      if (passed) { m += PASSED[rank] >> 1; e += PASSED[rank] * 1.3; }
    } else if (t === BISHOP) bishops[side]++;
    else if (t === ROOK) {
      const file = sq & 7;
      if (!pawnFiles[side][file]) { m += pawnFiles[side ^ 1][file] ? 10 : 20; e += 10; }
    }
    mg += s * m; eg += s * e;
  }
  if (bishops[0] >= 2) { mg += 30; eg += 50; }
  if (bishops[1] >= 2) { mg -= 30; eg -= 50; }
  // Mop-up: in won endgames drive the enemy king to the edge and bring ours closer.
  const diff = material[0] - material[1];
  if (Math.abs(diff) >= 300 && phase <= 8) {
    const strong = diff > 0 ? 0 : 1, weakK = pos.kings[strong ^ 1], strongK = pos.kings[strong];
    const centre = Math.max(3 - (weakK >> 4), (weakK >> 4) - 4) + Math.max(3 - (weakK & 7), (weakK & 7) - 4);
    const dist = Math.abs((weakK >> 4) - (strongK >> 4)) + Math.abs((weakK & 7) - (strongK & 7));
    eg += (strong === 0 ? 1 : -1) * (centre * 12 + (14 - dist) * 5);
  }
  const ph = Math.min(phase, 24);
  const score = (mg * ph + eg * (24 - ph)) / 24;
  return ((pos.side === WHITE ? score : -score) + 12) | 0; // +12 tempo
}

const TT_BITS = 20, TT_SIZE = 1 << TT_BITS, TT_MASK = TT_SIZE - 1;
const EXACT = 1, LOWER = 2, UPPER = 3;

export class Searcher {
  constructor() {
    this.ttKey = new Int32Array(TT_SIZE); this.ttMove = new Int32Array(TT_SIZE);
    this.ttScore = new Int16Array(TT_SIZE); this.ttDepth = new Int8Array(TT_SIZE); this.ttFlag = new Uint8Array(TT_SIZE);
    this.history = new Int32Array(16 * 128);
    this.killers = Array.from({ length: 128 }, () => [0, 0]);
  }

  clear() { this.ttKey.fill(0); this.ttFlag.fill(0); this.history.fill(0); }

  /**
   * @param {Position} pos  root position (not modified on return)
   * @param {{depth?:number, movetime?:number, multipv?:number, onInfo?:Function, repetitions?:string[]}} opts
   */
  search(pos, { depth = 64, movetime = 1e9, multipv = 1, onInfo, repetitions = [] } = {}) {
    this.pos = pos.clone();
    this.nodes = 0; this.aborted = false; this.seldepth = 0;
    this.start = Date.now(); this.deadline = this.start + movetime;
    this.path = repetitions.slice();
    for (const k of this.killers) k[0] = k[1] = 0;
    const root = this.pos.legalMoves();
    if (!root.length) return { bestmove: null, lines: [] };
    let lines = root.map(m => ({ move: m, score: -INF, pv: [m] }));
    const K = Math.min(multipv, root.length);
    let completed = [];
    for (let d = 1; d <= depth; d++) {
      const res = this.searchRoot(lines, d, K);
      if (this.aborted && completed.length) break;
      lines = res;
      completed = res.slice(0, K).map(l => ({ ...l, depth: d }));
      onInfo?.(completed, { depth: d, seldepth: this.seldepth, nodes: this.nodes, time: Date.now() - this.start });
      if (this.aborted || Math.abs(completed[0].score) > MATE - 200 && d >= 4 || root.length === 1 && d >= 4) break;
    }
    return { bestmove: completed[0].move, lines: completed, nodes: this.nodes };
  }

  searchRoot(lines, depth, K) {
    const pos = this.pos, results = [];
    for (let i = 0; i < lines.length; i++) {
      const m = lines[i].move;
      pos.make(m);
      this.path.push(pos.key);
      const kth = results.length >= K ? results.map(r => r.score).sort((a, b) => b - a)[K - 1] : -INF;
      let score;
      if (kth === -INF) score = -this.negamax(depth - 1, -INF, INF, 1, true);
      else {
        score = -this.negamax(depth - 1, -kth - 1, -kth, 1, true);
        if (score > kth && !this.aborted) score = -this.negamax(depth - 1, -INF, -kth, 1, true);
      }
      this.path.pop();
      const pv = [m, ...this.pvFrom(depth + 4)];
      pos.unmake();
      if (this.aborted) break;
      results.push({ move: m, score, pv });
    }
    // Moves not searched this iteration (abort) keep their previous order at the tail.
    const done = new Set(results.map(r => r.move));
    results.sort((a, b) => b.score - a.score);
    return results.concat(lines.filter(l => !done.has(l.move)));
  }

  pvFrom(max) {
    const pos = this.pos, pv = [];
    let n = 0;
    while (n < max) {
      const slot = pos.hashLo & TT_MASK;
      if (this.ttKey[slot] !== pos.hashHi || !this.ttMove[slot]) break;
      const m = this.ttMove[slot];
      if (!pos.legalMoves().includes(m) || !pos.make(m)) break;
      pv.push(m); n++;
    }
    for (let i = 0; i < n; i++) pos.unmake();
    return pv;
  }

  timeUp() {
    if ((this.nodes & 2047) === 0 && Date.now() > this.deadline) this.aborted = true;
    return this.aborted;
  }

  isRepetition() {
    const k = this.pos.key, path = this.path;
    for (let i = path.length - 3, n = 0; i >= 0 && n < this.pos.halfmove; i -= 2, n += 2) if (path[i] === k) return true;
    return false;
  }

  negamax(depth, alpha, beta, ply, allowNull) {
    const pos = this.pos;
    if (ply > this.seldepth) this.seldepth = ply;
    if (pos.halfmove >= 100 || this.isRepetition() || pos.insufficientMaterial()) return 0;
    const inCheck = pos.inCheck();
    if (inCheck) depth++;
    if (depth <= 0) return this.quiesce(alpha, beta, ply);
    this.nodes++;
    if (this.timeUp()) return 0;

    // Mate distance pruning.
    alpha = Math.max(alpha, -MATE + ply); beta = Math.min(beta, MATE - ply - 1);
    if (alpha >= beta) return alpha;

    const slot = pos.hashLo & TT_MASK, hit = this.ttKey[slot] === pos.hashHi && this.ttFlag[slot];
    const ttMove = hit ? this.ttMove[slot] : 0;
    const pvNode = beta - alpha > 1;
    if (hit && this.ttDepth[slot] >= depth && !pvNode) {
      let s = this.ttScore[slot];
      if (s > MATE - 500) s -= ply; else if (s < -MATE + 500) s += ply;
      const f = this.ttFlag[slot];
      if (f === EXACT || (f === LOWER && s >= beta) || (f === UPPER && s <= alpha)) return s;
    }

    const staticEval = evaluate(pos);
    // Reverse futility pruning.
    if (!pvNode && !inCheck && depth <= 3 && staticEval - 120 * depth >= beta) return staticEval;
    // Null-move pruning.
    if (allowNull && !pvNode && !inCheck && depth >= 3 && staticEval >= beta && this.hasPieces(pos.side)) {
      pos.makeNull(); this.path.push(pos.key);
      const s = -this.negamax(depth - 3, -beta, -beta + 1, ply + 1, false);
      this.path.pop(); pos.unmakeNull();
      if (this.aborted) return 0;
      if (s >= beta) return s > MATE - 500 ? beta : s;
    }

    const moves = this.order(pos.pseudoMoves(), ttMove, ply);
    let best = -INF, bestMove = 0, legal = 0, flag = UPPER;
    for (let i = 0; i < moves.length; i++) {
      const m = moves[i];
      if (!pos.make(m)) continue;
      legal++;
      this.path.push(pos.key);
      const quiet = !(moveFlags(m) & FLAG_CAPTURE) && !movePromo(m);
      let s;
      if (legal === 1) s = -this.negamax(depth - 1, -beta, -alpha, ply + 1, true);
      else {
        let r = 0;
        if (depth >= 3 && quiet && !inCheck && legal > 3 && !pos.inCheck()) r = legal > 8 ? 2 : 1;
        s = -this.negamax(depth - 1 - r, -alpha - 1, -alpha, ply + 1, true);
        if (s > alpha && (r || s < beta)) s = -this.negamax(depth - 1, -beta, -alpha, ply + 1, true);
      }
      this.path.pop();
      pos.unmake();
      if (this.aborted) return 0;
      if (s > best) {
        best = s; bestMove = m;
        if (s > alpha) {
          alpha = s; flag = EXACT;
          if (s >= beta) {
            flag = LOWER;
            if (quiet) {
              const k = this.killers[ply]; if (k[0] !== m) { k[1] = k[0]; k[0] = m; }
              this.history[pos.board[moveFrom(m)] * 128 + moveTo(m)] += depth * depth;
            }
            break;
          }
        }
      }
    }
    if (!legal) return inCheck ? -MATE + ply : 0;
    let stored = best;
    if (stored > MATE - 500) stored += ply; else if (stored < -MATE + 500) stored -= ply;
    this.ttKey[slot] = pos.hashHi; this.ttMove[slot] = bestMove; this.ttScore[slot] = stored;
    this.ttDepth[slot] = depth; this.ttFlag[slot] = flag;
    return best;
  }

  quiesce(alpha, beta, ply) {
    const pos = this.pos;
    this.nodes++;
    if (this.timeUp()) return 0;
    if (ply > this.seldepth) this.seldepth = ply;
    const stand = evaluate(pos);
    if (stand >= beta) return stand;
    if (stand > alpha) alpha = stand;
    if (ply > 100) return stand;
    const moves = this.order(pos.pseudoMoves([], true), 0, ply);
    for (const m of moves) {
      const victim = typeOf(pos.board[moveTo(m)]) || PAWN;
      if (stand + VALUE_MG[victim] + 200 < alpha && !movePromo(m)) continue; // delta pruning
      if (!pos.make(m)) continue;
      const s = -this.quiesce(-beta, -alpha, ply + 1);
      pos.unmake();
      if (this.aborted) return 0;
      if (s >= beta) return s;
      if (s > alpha) alpha = s;
    }
    return alpha;
  }

  hasPieces(side) {
    const b = this.pos.board;
    for (let sq = 0; sq < 128; sq++) {
      if (sq & 0x88) { sq += 7; continue; }
      const p = b[sq]; if (p && colorOf(p) === side && typeOf(p) > PAWN && typeOf(p) < KING) return true;
    }
    return false;
  }

  order(moves, ttMove, ply) {
    const b = this.pos.board, k = this.killers[ply] || [0, 0];
    const scores = new Int32Array(moves.length);
    for (let i = 0; i < moves.length; i++) {
      const m = moves[i];
      if (m === ttMove) scores[i] = 1 << 30;
      else if (moveFlags(m) & FLAG_CAPTURE) scores[i] = (1 << 20) + (typeOf(b[moveTo(m)]) || PAWN) * 16 - typeOf(b[moveFrom(m)]);
      else if (movePromo(m)) scores[i] = (1 << 19) + movePromo(m);
      else if (m === k[0]) scores[i] = 1 << 18;
      else if (m === k[1]) scores[i] = (1 << 18) - 1;
      else scores[i] = Math.min(this.history[b[moveFrom(m)] * 128 + moveTo(m)], (1 << 17));
    }
    const order = [...moves.keys()].sort((x, y) => scores[y] - scores[x]);
    return order.map(i => moves[i]);
  }
}

/** Formats a score the way UCI does: {cp} or {mate}. Score is from the side to move. */
export function uciScore(score) {
  if (Math.abs(score) > MATE - 500) {
    const plies = MATE - Math.abs(score);
    return { mate: score > 0 ? Math.ceil(plies / 2) : -Math.ceil(plies / 2) };
  }
  return { cp: score };
}
