// Opening book shared with Android (web/data/openings.txt).
// Positions are keyed by the first four FEN fields, so transpositions are recognised.
import { Game, START_FEN } from './chess.js';

const keyOf = fen => fen.split(' ').slice(0, 4).join(' ');

export class OpeningBook {
  constructor() { this.lines = []; this.edges = new Map(); this.named = new Map(); }

  static parse(text) {
    const book = new OpeningBook();
    for (const raw of text.split('\n')) {
      const row = raw.trim();
      if (!row || row.startsWith('#')) continue;
      const [eco, name, english, weight, moves] = row.split('|').map(s => s.trim());
      const line = { eco, name, english, weight: Number.isFinite(+weight) ? +weight : 1, moves: moves.split(/\s+/) };
      book.lines.push(line);
      const g = new Game();
      for (const san of line.moves) {
        const key = keyOf(g.fen());
        const rec = g.move(san);
        if (!rec) break;
        let edges = book.edges.get(key);
        if (!edges) book.edges.set(key, edges = new Map());
        const e = edges.get(rec.san) || { san: rec.san, uci: rec.uci, weight: 0, line };
        e.weight += line.weight;
        if (line.weight > e.line.weight) e.line = line;
        edges.set(rec.san, e);
      }
      const end = keyOf(g.fen());
      const prev = book.named.get(end);
      if (!prev || prev.moves.length > line.moves.length) book.named.set(end, line);
    }
    return book;
  }

  static fenAfter(sans) { const g = new Game(); for (const s of sans) g.move(s); return g.fen(); }

  /** Book moves for a position (FEN or SAN history), most popular first; share sums to 1. */
  candidates(position = START_FEN) {
    const fen = Array.isArray(position) ? OpeningBook.fenAfter(position) : position;
    const edges = [...(this.edges.get(keyOf(fen))?.values() || [])].sort((a, b) => b.weight - a.weight);
    const total = edges.reduce((s, e) => s + e.weight, 0);
    return edges.map(e => ({ san: e.san, uci: e.uci, weight: e.weight, share: total ? e.weight / total : 1 / edges.length, name: e.line.name, eco: e.line.eco }));
  }

  /** Deepest named opening reached along a game (array of FENs or SANs). */
  identify(path) {
    const fens = path.length && !path[0].includes('/') ? path.map((_, i) => OpeningBook.fenAfter(path.slice(0, i + 1))) : path;
    for (let i = fens.length - 1; i >= 0; i--) { const hit = this.named.get(keyOf(fens[i])); if (hit) return hit; }
    return null;
  }
}
