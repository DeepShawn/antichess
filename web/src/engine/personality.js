// 谱着 personality: turns engine/book candidates into a human-like choice.
// The same tables are mirrored in Android (core/.../Personality.kt).
import { Position, PAWN, KNIGHT, BISHOP, ROOK, QUEEN, typeOf, colorOf } from '../core/chess.js';

export const LEVELS = [
  { id: 'novice', name: '入门', elo: 800, depth: 2, movetime: 250, multipv: 6, temperature: 320, maxLoss: 900, blunder: 0.14 },
  { id: 'casual', name: '初级', elo: 1100, depth: 4, movetime: 400, multipv: 5, temperature: 170, maxLoss: 450, blunder: 0.07 },
  { id: 'club', name: '中级', elo: 1400, depth: 7, movetime: 700, multipv: 4, temperature: 90, maxLoss: 260, blunder: 0.035 },
  { id: 'expert', name: '高级', elo: 1800, depth: 10, movetime: 1100, multipv: 4, temperature: 45, maxLoss: 140, blunder: 0.012 },
  { id: 'master', name: '大师', elo: 2200, depth: 14, movetime: 1600, multipv: 3, temperature: 18, maxLoss: 60, blunder: 0.003 },
  { id: 'max', name: 'Stockfish 19', elo: 2850, depth: 20, movetime: 2500, multipv: 3, temperature: 1, maxLoss: 0, blunder: 0 },
];

export const PREFERENCES = {
  main: { name: '一选主线', note: '稳定，接近引擎首选', weights: [0.72, 0.16, 0.08, 0.04] },
  second: { name: '二选偏好', note: '最像真人：常走次优但合理的着法', weights: [0.15, 0.52, 0.25, 0.08] },
  third: { name: '三选偏好', note: '更有探索性，局面更丰富', weights: [0.08, 0.28, 0.52, 0.12] },
  pure: { name: '纯一选', note: '复盘主线，几乎总走最佳着', weights: [0.96, 0.02, 0.01, 0.01] },
  random: { name: '均匀探索', note: '候选着法等概率', weights: [0.25, 0.25, 0.25, 0.25] },
  sacrifice: { name: '弃子打法', note: '敢于弃子：适时下出妙棋与好棋', weights: [0.4, 0.3, 0.18, 0.12], sacrifice: true },
};

// ── Sacrifices ──────────────────────────────────────────────────────────────
// A candidate is a sacrifice when the engine's own line has the mover give up
// material that is not won back within the next plies — and the engine still
// rates that line as sound. "Brilliant" (妙棋) gives up at least a minor piece
// and is (near) the best move; "good" (好棋) covers the remaining sound ones.
const PIECE_VALUE = { [PAWN]: 1, [KNIGHT]: 3, [BISHOP]: 3, [ROOK]: 5, [QUEEN]: 9 };
const SAC_WINDOW = 4;          // plies of the PV inspected: our move, their capture, and one more exchange
const BRILLIANT_MAX_LOSS = 30; // centipawns behind the best move
const SOUND_FLOOR = -60;       // mover's evaluation after the sacrifice must stay above this
const SAC_BOOST = { brilliant: 8, good: 3.5 };

export const SAC_LABELS = { brilliant: { mark: '!!', name: '妙棋' }, good: { mark: '!', name: '好棋' } };

function balance(pos, side) {
  let total = 0;
  for (let sq = 0; sq < 128; sq++) {
    if (sq & 0x88) { sq += 7; continue; }
    const p = pos.board[sq];
    if (p) total += (PIECE_VALUE[typeOf(p)] || 0) * (colorOf(p) === side ? 1 : -1);
  }
  return total;
}

/**
 * Material (in pawns) the side to move gives up along `pv`: it must be lost straight
 * away (after our move and the reply) and still be missing at the end of the first
 * SAC_WINDOW plies. Anything won back, or lost only later in an exchange, gives 0.
 */
export function sacrificedMaterial(fen, pv) {
  if (!pv || pv.length < 2) return 0;
  const pos = new Position(fen), side = pos.side, start = balance(pos, side);
  let plies = 0, immediate = 0;
  for (const uci of pv.slice(0, SAC_WINDOW)) {
    const m = pos.parseMove(uci);
    if (!m || !pos.make(m)) break;
    if (++plies === 2) immediate = start - balance(pos, side);
  }
  return plies >= 2 ? Math.max(0, Math.min(immediate, start - balance(pos, side))) : 0;
}

/** Classifies engine lines (best first, mover's scores) as brilliant / good / null sacrifices. */
export function classifySacrifices(fen, lines, level) {
  if (!lines.length) return [];
  const best = scoreToCp(lines[0]);
  return lines.map(l => {
    const amount = sacrificedMaterial(fen, l.pv);
    if (amount < 1) return null;
    const cp = scoreToCp(l), loss = best - cp;
    if (cp < SOUND_FLOOR || loss > level.maxLoss) return null;
    return amount >= 2 && loss <= BRILLIANT_MAX_LOSS ? 'brilliant' : 'good';
  });
}

export const RANK_NAMES = ['一选', '二选', '三选', '四选', '五选', '六选'];

/** Converts a UCI-style score ({cp}|{mate}) to centipawns for comparisons. */
export function scoreToCp(s) {
  if (s.mate !== undefined) return s.mate > 0 ? 10000 - s.mate * 10 : -10000 - s.mate * 10;
  return s.cp ?? 0;
}

const prefWeight = (pref, i) => pref.weights[i] ?? pref.weights[pref.weights.length - 1] * 0.5 ** (i - pref.weights.length + 1);

/**
 * Engine candidates (best first, scores from the mover's view) → probability per candidate.
 * Weak moves are filtered by the level's tolerance, then preference weights are
 * sharpened by exp(-loss / temperature) so strong levels converge on the best move.
 */
export function engineDistribution(candidates, level, preference) {
  if (!candidates.length) return [];
  const best = scoreToCp(candidates[0]);
  const raw = candidates.map((c, i) => {
    const loss = Math.max(0, best - scoreToCp(c));
    const allowed = i === 0 || loss <= level.maxLoss;
    return allowed ? prefWeight(preference, i) * Math.exp(-loss / Math.max(1, level.temperature)) : 0;
  });
  const total = raw.reduce((a, b) => a + b, 0) || 1;
  return raw.map(w => w / total);
}

/** Book candidates carry a popularity share; blend it with the preference weights. */
export function bookDistribution(candidates, preference) {
  const raw = candidates.map((c, i) => 0.5 * c.share + 0.5 * prefWeight(preference, i));
  const total = raw.reduce((a, b) => a + b, 0) || 1;
  return raw.map(w => w / total);
}

export function sample(probs, rng = Math.random) {
  let r = rng(), acc = 0;
  for (let i = 0; i < probs.length; i++) { acc += probs[i]; if (r < acc) return i; }
  return Math.max(0, probs.findIndex(p => p > 0));
}

/**
 * Picks the engine's move.
 * @returns {{uci:string, rank:number, source:'book'|'engine'|'blunder', probs:number[]}}
 */
export function choose({ book = [], lines = [], legal = [], level, preference, blunders = true, rng = Math.random, fen }) {
  if (book.length && level.id !== 'max') {
    const probs = bookDistribution(book, preference);
    const i = sample(probs, rng);
    return { uci: book[i].uci, rank: i, source: 'book', probs };
  }
  if (blunders && level.blunder > 0 && legal.length > 1 && rng() < level.blunder) {
    // A "human slip": any legal move outside the engine's top choice.
    const pool = legal.filter(u => u !== lines[0]?.pv[0]);
    return { uci: pool[Math.floor(rng() * pool.length)], rank: -1, source: 'blunder', probs: engineDistribution(lines, level, preference) };
  }
  let probs = engineDistribution(lines, level, preference);
  const sacs = preference.sacrifice && fen ? classifySacrifices(fen, lines, level) : [];
  if (sacs.some(Boolean)) {
    // Sacrifices are only ever boosted, never forced: without a sound one the style plays normally.
    const raw = probs.map((p, i) => p * (sacs[i] ? SAC_BOOST[sacs[i]] : 1));
    const total = raw.reduce((a, b) => a + b, 0) || 1;
    probs = raw.map(p => p / total);
  }
  const i = sample(probs, rng);
  return { uci: lines[i]?.pv[0] ?? legal[0], rank: i, source: 'engine', probs, sacs, sacrifice: sacs[i] || null };
}
