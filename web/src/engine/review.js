// Move quality and accuracy, using the win-probability model popularised by Lichess.
import { scoreToCp } from './personality.js';

export const winChance = cp => 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * Math.max(-1500, Math.min(1500, cp)))) - 1);

export const GRADES = {
  best: { mark: '', label: '最佳', color: 'best' },
  good: { mark: '', label: '好棋', color: 'good' },
  inaccuracy: { mark: '?!', label: '欠准', color: 'inaccuracy' },
  mistake: { mark: '?', label: '失误', color: 'mistake' },
  blunder: { mark: '??', label: '漏着', color: 'blunder' },
};

/**
 * Grade the move that led from `before` to `after` (both white-perspective evaluations).
 * @param {'w'|'b'} mover
 */
export function grade(before, after, mover, playedBest = false) {
  const sign = mover === 'w' ? 1 : -1;
  const wb = winChance(sign * scoreToCp(before)), wa = winChance(sign * scoreToCp(after));
  const drop = Math.max(0, wb - wa);
  const accuracy = Math.max(0, Math.min(100, 103.1668 * Math.exp(-0.04354 * drop) - 3.1669));
  let g = 'good';
  if (playedBest || drop < 2) g = 'best';
  else if (drop >= 30) g = 'blunder';
  else if (drop >= 20) g = 'mistake';
  else if (drop >= 10) g = 'inaccuracy';
  return { grade: g, drop, accuracy };
}

/** Harmonic-ish mean of per-move accuracies, per side. */
export function accuracySummary(notes) {
  const out = {};
  for (const side of ['w', 'b']) {
    const xs = notes.filter(n => n && n.mover === side).map(n => n.accuracy);
    out[side] = xs.length ? (xs.reduce((a, b) => a + b, 0) / xs.length + xs.length / xs.reduce((a, b) => a + 1 / Math.max(1, b), 0)) / 2 : null;
    out[side + 'Counts'] = Object.fromEntries(Object.keys(GRADES).map(k => [k, notes.filter(n => n && n.mover === side && n.grade === k).length]));
  }
  return out;
}
