// 谱着 core rules — a dependency-free 0x88 chess implementation.
// Shared by the UI (via Game) and the built-in search engine (via Position).
// Verified against the standard perft suite in tests/core.test.mjs.

export const WHITE = 0, BLACK = 8;
export const PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
export const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

export const FLAG_CAPTURE = 1, FLAG_DOUBLE = 2, FLAG_EP = 4, FLAG_CASTLE = 8;
const CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;

const PIECE_CHARS = ' pnbrqk';
const KNIGHT_DELTAS = [33, 31, 18, 14, -33, -31, -18, -14];
const KING_DELTAS = [1, -1, 16, -16, 17, 15, -17, -15];
const BISHOP_DELTAS = [17, 15, -17, -15];
const ROOK_DELTAS = [1, -1, 16, -16];

// Castling rights are cleared when a move touches one of these squares.
const CASTLE_MASK = new Uint8Array(128).fill(15);
CASTLE_MASK[0x00] = 15 & ~CASTLE_WQ; CASTLE_MASK[0x07] = 15 & ~CASTLE_WK; CASTLE_MASK[0x04] = 15 & ~(CASTLE_WK | CASTLE_WQ);
CASTLE_MASK[0x70] = 15 & ~CASTLE_BQ; CASTLE_MASK[0x77] = 15 & ~CASTLE_BK; CASTLE_MASK[0x74] = 15 & ~(CASTLE_BK | CASTLE_BQ);

export const moveFrom = m => m & 0xff;
export const moveTo = m => (m >> 8) & 0xff;
export const movePromo = m => (m >> 16) & 0xf;
export const moveFlags = m => (m >> 20) & 0xf;
const encode = (from, to, promo, flags) => from | (to << 8) | (promo << 16) | (flags << 20);

export const typeOf = p => p & 7;
export const colorOf = p => p & 8;
export const squareName = sq => 'abcdefgh'[sq & 7] + ((sq >> 4) + 1);
export const parseSquare = s => (s.charCodeAt(1) - 49) * 16 + (s.charCodeAt(0) - 97);
export const onBoard = sq => (sq & 0x88) === 0;

// Deterministic Zobrist keys (two 32-bit halves → effectively 64-bit hashing).
function* rng(seed) { let s = seed >>> 0; for (;;) { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; yield s; } }
const gen = rng(0x9e3779b9);
const Z_PIECE_LO = new Int32Array(16 * 128), Z_PIECE_HI = new Int32Array(16 * 128);
for (let i = 0; i < Z_PIECE_LO.length; i++) { Z_PIECE_LO[i] = gen.next().value | 0; Z_PIECE_HI[i] = gen.next().value | 0; }
const Z_CASTLE_LO = Int32Array.from({ length: 16 }, () => gen.next().value | 0), Z_CASTLE_HI = Int32Array.from({ length: 16 }, () => gen.next().value | 0);
const Z_EP_LO = Int32Array.from({ length: 8 }, () => gen.next().value | 0), Z_EP_HI = Int32Array.from({ length: 8 }, () => gen.next().value | 0);
const Z_SIDE_LO = gen.next().value | 0, Z_SIDE_HI = gen.next().value | 0;

export class Position {
  constructor(fen = START_FEN) { this.load(fen); }

  load(fen) {
    const parts = fen.trim().split(/\s+/);
    if (parts.length < 4) throw new Error('FEN 需要至少 4 个字段');
    const board = new Int8Array(128);
    const rows = parts[0].split('/');
    if (rows.length !== 8) throw new Error('FEN 棋盘必须有 8 行');
    const kings = [-1, -1];
    rows.forEach((row, i) => {
      let file = 0; const rank = 7 - i;
      for (const ch of row) {
        if (/\d/.test(ch)) { file += +ch; continue; }
        const type = PIECE_CHARS.indexOf(ch.toLowerCase());
        if (type < 1 || file > 7) throw new Error(`FEN 中有非法字符 "${ch}"`);
        const color = ch === ch.toUpperCase() ? WHITE : BLACK;
        board[rank * 16 + file] = type | color;
        if (type === KING) { if (kings[color >> 3] !== -1) throw new Error('每方只能有一个王'); kings[color >> 3] = rank * 16 + file; }
        file++;
      }
      if (file !== 8) throw new Error(`FEN 第 ${i + 1} 行格数不是 8`);
    });
    if (kings[0] < 0 || kings[1] < 0) throw new Error('双方都必须有王');
    if (!/^[wb]$/.test(parts[1])) throw new Error('FEN 行棋方必须为 w 或 b');
    this.board = board;
    this.kings = kings;
    this.side = parts[1] === 'w' ? WHITE : BLACK;
    let c = 0;
    if (parts[2] !== '-') for (const ch of parts[2]) c |= { K: CASTLE_WK, Q: CASTLE_WQ, k: CASTLE_BK, q: CASTLE_BQ }[ch] || 0;
    // Drop rights that the piece placement cannot support.
    if (board[0x04] !== KING) c &= ~(CASTLE_WK | CASTLE_WQ);
    if (board[0x74] !== (KING | BLACK)) c &= ~(CASTLE_BK | CASTLE_BQ);
    if (board[0x07] !== ROOK) c &= ~CASTLE_WK; if (board[0x00] !== ROOK) c &= ~CASTLE_WQ;
    if (board[0x77] !== (ROOK | BLACK)) c &= ~CASTLE_BK; if (board[0x70] !== (ROOK | BLACK)) c &= ~CASTLE_BQ;
    this.castling = c;
    this.ep = parts[3] !== '-' && /^[a-h][36]$/.test(parts[3]) ? parseSquare(parts[3]) : -1;
    this.halfmove = parseInt(parts[4] ?? '0', 10) || 0;
    this.fullmove = Math.max(1, parseInt(parts[5] ?? '1', 10) || 1);
    if (this.ep >= 0 && !this.epCapturable(this.ep)) this.ep = -1;
    this.stack = [];
    this.computeHash();
    if (this.isAttacked(this.kings[(this.side ^ 8) >> 3], this.side)) throw new Error('非行棋方的王正被将军');
    return this;
  }

  clone() { const p = Object.create(Position.prototype); Object.assign(p, this, { board: this.board.slice(), kings: this.kings.slice(), stack: [] }); return p; }

  computeHash() {
    let lo = 0, hi = 0;
    for (let sq = 0; sq < 128; sq++) {
      if (sq & 0x88) { sq += 7; continue; }
      const p = this.board[sq];
      if (p) { lo ^= Z_PIECE_LO[p * 128 + sq]; hi ^= Z_PIECE_HI[p * 128 + sq]; }
    }
    lo ^= Z_CASTLE_LO[this.castling]; hi ^= Z_CASTLE_HI[this.castling];
    if (this.ep >= 0) { lo ^= Z_EP_LO[this.ep & 7]; hi ^= Z_EP_HI[this.ep & 7]; }
    if (this.side === BLACK) { lo ^= Z_SIDE_LO; hi ^= Z_SIDE_HI; }
    this.hashLo = lo; this.hashHi = hi;
  }

  get key() { return (this.hashHi >>> 0).toString(16).padStart(8, '0') + (this.hashLo >>> 0).toString(16).padStart(8, '0'); }

  epCapturable(ep) {
    const pawn = PAWN | this.side, dir = this.side === WHITE ? -16 : 16;
    for (const d of [-1, 1]) { const s = ep + dir + d; if (onBoard(s) && this.board[s] === pawn) return true; }
    return false;
  }

  fen() {
    let s = '';
    for (let rank = 7; rank >= 0; rank--) {
      let empty = 0;
      for (let file = 0; file < 8; file++) {
        const p = this.board[rank * 16 + file];
        if (!p) { empty++; continue; }
        if (empty) { s += empty; empty = 0; }
        const ch = PIECE_CHARS[typeOf(p)];
        s += colorOf(p) === WHITE ? ch.toUpperCase() : ch;
      }
      if (empty) s += empty;
      if (rank) s += '/';
    }
    const c = this.castling;
    const cs = (c & CASTLE_WK ? 'K' : '') + (c & CASTLE_WQ ? 'Q' : '') + (c & CASTLE_BK ? 'k' : '') + (c & CASTLE_BQ ? 'q' : '') || '-';
    return `${s} ${this.side === WHITE ? 'w' : 'b'} ${cs} ${this.ep >= 0 ? squareName(this.ep) : '-'} ${this.halfmove} ${this.fullmove}`;
  }

  isAttacked(sq, by) {
    const b = this.board;
    // Pawns
    const pawn = PAWN | by;
    if (by === WHITE) { if ((sq - 15 & 0x88) === 0 && b[sq - 15] === pawn) return true; if ((sq - 17 & 0x88) === 0 && b[sq - 17] === pawn) return true; }
    else { if ((sq + 15 & 0x88) === 0 && b[sq + 15] === pawn) return true; if ((sq + 17 & 0x88) === 0 && b[sq + 17] === pawn) return true; }
    const knight = KNIGHT | by, king = KING | by;
    for (let i = 0; i < 8; i++) {
      let s = sq + KNIGHT_DELTAS[i]; if ((s & 0x88) === 0 && b[s] === knight) return true;
      s = sq + KING_DELTAS[i]; if ((s & 0x88) === 0 && b[s] === king) return true;
    }
    const bishop = BISHOP | by, rook = ROOK | by, queen = QUEEN | by;
    for (let i = 0; i < 4; i++) {
      let d = BISHOP_DELTAS[i], s = sq + d;
      while ((s & 0x88) === 0) { const p = b[s]; if (p) { if (p === bishop || p === queen) return true; break; } s += d; }
      d = ROOK_DELTAS[i]; s = sq + d;
      while ((s & 0x88) === 0) { const p = b[s]; if (p) { if (p === rook || p === queen) return true; break; } s += d; }
    }
    return false;
  }

  inCheck(side = this.side) { return this.isAttacked(this.kings[side >> 3], side ^ 8); }

  /** Pseudo-legal moves (king may be left in check). capturesOnly is used by quiescence search. */
  pseudoMoves(out = [], capturesOnly = false) {
    const b = this.board, us = this.side, them = us ^ 8;
    for (let from = 0; from < 128; from++) {
      if (from & 0x88) { from += 7; continue; }
      const p = b[from];
      if (!p || colorOf(p) !== us) continue;
      const t = typeOf(p);
      if (t === PAWN) {
        const dir = us === WHITE ? 16 : -16, startRank = us === WHITE ? 1 : 6, lastRank = us === WHITE ? 7 : 0;
        const one = from + dir;
        if (!capturesOnly || (one >> 4) === lastRank) {
          if (onBoard(one) && !b[one]) {
            if ((one >> 4) === lastRank) for (let pr = QUEEN; pr >= KNIGHT; pr--) out.push(encode(from, one, pr, 0));
            else {
              out.push(encode(from, one, 0, 0));
              const two = one + dir;
              if (!capturesOnly && (from >> 4) === startRank && !b[two]) out.push(encode(from, two, 0, FLAG_DOUBLE));
            }
          }
        }
        for (const d of [dir - 1, dir + 1]) {
          const to = from + d;
          if (!onBoard(to)) continue;
          const q = b[to];
          if (q && colorOf(q) === them) {
            if ((to >> 4) === lastRank) for (let pr = QUEEN; pr >= KNIGHT; pr--) out.push(encode(from, to, pr, FLAG_CAPTURE));
            else out.push(encode(from, to, 0, FLAG_CAPTURE));
          } else if (to === this.ep) out.push(encode(from, to, 0, FLAG_CAPTURE | FLAG_EP));
        }
        continue;
      }
      if (t === KNIGHT || t === KING) {
        const deltas = t === KNIGHT ? KNIGHT_DELTAS : KING_DELTAS;
        for (let i = 0; i < 8; i++) {
          const to = from + deltas[i];
          if (to & 0x88) continue;
          const q = b[to];
          if (!q) { if (!capturesOnly) out.push(encode(from, to, 0, 0)); }
          else if (colorOf(q) === them) out.push(encode(from, to, 0, FLAG_CAPTURE));
        }
        if (t === KING && !capturesOnly) this.castleMoves(from, out);
        continue;
      }
      const deltas = t === BISHOP ? BISHOP_DELTAS : t === ROOK ? ROOK_DELTAS : KING_DELTAS;
      for (const d of deltas) {
        let to = from + d;
        while (onBoard(to)) {
          const q = b[to];
          if (!q) { if (!capturesOnly) out.push(encode(from, to, 0, 0)); }
          else { if (colorOf(q) === them) out.push(encode(from, to, 0, FLAG_CAPTURE)); break; }
          to += d;
        }
      }
    }
    return out;
  }

  castleMoves(from, out) {
    const us = this.side, b = this.board, them = us ^ 8;
    const [kFlag, qFlag, home] = us === WHITE ? [CASTLE_WK, CASTLE_WQ, 0x04] : [CASTLE_BK, CASTLE_BQ, 0x74];
    if (from !== home || !(this.castling & (kFlag | qFlag)) || this.isAttacked(home, them)) return;
    if (this.castling & kFlag && !b[home + 1] && !b[home + 2] && !this.isAttacked(home + 1, them) && !this.isAttacked(home + 2, them))
      out.push(encode(home, home + 2, 0, FLAG_CASTLE));
    if (this.castling & qFlag && !b[home - 1] && !b[home - 2] && !b[home - 3] && !this.isAttacked(home - 1, them) && !this.isAttacked(home - 2, them))
      out.push(encode(home, home - 2, 0, FLAG_CASTLE));
  }

  /** Applies a move; returns false (and restores) if it leaves the mover in check. */
  make(m) {
    const b = this.board, from = moveFrom(m), to = moveTo(m), flags = moveFlags(m), promo = movePromo(m);
    const us = this.side, them = us ^ 8, piece = b[from];
    let captured = b[to], capSq = to;
    if (flags & FLAG_EP) { capSq = to + (us === WHITE ? -16 : 16); captured = b[capSq]; }
    this.stack.push({ m, captured, castling: this.castling, ep: this.ep, halfmove: this.halfmove, lo: this.hashLo, hi: this.hashHi });

    let lo = this.hashLo, hi = this.hashHi;
    if (captured) { b[capSq] = 0; lo ^= Z_PIECE_LO[captured * 128 + capSq]; hi ^= Z_PIECE_HI[captured * 128 + capSq]; }
    b[from] = 0; lo ^= Z_PIECE_LO[piece * 128 + from]; hi ^= Z_PIECE_HI[piece * 128 + from];
    const placed = promo ? promo | us : piece;
    b[to] = placed; lo ^= Z_PIECE_LO[placed * 128 + to]; hi ^= Z_PIECE_HI[placed * 128 + to];
    if (flags & FLAG_CASTLE) {
      const [rFrom, rTo] = to > from ? [from + 3, from + 1] : [from - 4, from - 1];
      const rook = b[rFrom]; b[rFrom] = 0; b[rTo] = rook;
      lo ^= Z_PIECE_LO[rook * 128 + rFrom] ^ Z_PIECE_LO[rook * 128 + rTo]; hi ^= Z_PIECE_HI[rook * 128 + rFrom] ^ Z_PIECE_HI[rook * 128 + rTo];
    }
    if (typeOf(piece) === KING) this.kings[us >> 3] = to;

    lo ^= Z_CASTLE_LO[this.castling]; hi ^= Z_CASTLE_HI[this.castling];
    this.castling &= CASTLE_MASK[from] & CASTLE_MASK[to];
    lo ^= Z_CASTLE_LO[this.castling]; hi ^= Z_CASTLE_HI[this.castling];
    if (this.ep >= 0) { lo ^= Z_EP_LO[this.ep & 7]; hi ^= Z_EP_HI[this.ep & 7]; }
    this.ep = -1;
    this.halfmove = typeOf(piece) === PAWN || captured ? 0 : this.halfmove + 1;
    if (us === BLACK) this.fullmove++;
    this.side = them;
    lo ^= Z_SIDE_LO; hi ^= Z_SIDE_HI;
    if (flags & FLAG_DOUBLE) {
      const ep = (from + to) >> 1;
      if (this.epCapturable(ep)) { this.ep = ep; lo ^= Z_EP_LO[ep & 7]; hi ^= Z_EP_HI[ep & 7]; }
    }
    this.hashLo = lo; this.hashHi = hi;
    if (this.isAttacked(this.kings[us >> 3], them)) { this.unmake(); return false; }
    return true;
  }

  unmake() {
    const u = this.stack.pop(), m = u.m, b = this.board;
    const from = moveFrom(m), to = moveTo(m), flags = moveFlags(m);
    this.side ^= 8;
    const us = this.side;
    const moved = movePromo(m) ? PAWN | us : b[to];
    b[from] = moved; b[to] = 0;
    if (u.captured) b[flags & FLAG_EP ? to + (us === WHITE ? -16 : 16) : to] = u.captured;
    if (flags & FLAG_CASTLE) {
      const [rFrom, rTo] = to > from ? [from + 3, from + 1] : [from - 4, from - 1];
      b[rFrom] = b[rTo]; b[rTo] = 0;
    }
    if (typeOf(moved) === KING) this.kings[us >> 3] = from;
    if (us === BLACK) this.fullmove--;
    this.castling = u.castling; this.ep = u.ep; this.halfmove = u.halfmove; this.hashLo = u.lo; this.hashHi = u.hi;
  }

  /** Null move for null-move pruning in search. */
  makeNull() {
    this.stack.push({ m: 0, captured: 0, castling: this.castling, ep: this.ep, halfmove: this.halfmove, lo: this.hashLo, hi: this.hashHi });
    if (this.ep >= 0) { this.hashLo ^= Z_EP_LO[this.ep & 7]; this.hashHi ^= Z_EP_HI[this.ep & 7]; }
    this.ep = -1; this.side ^= 8; this.hashLo ^= Z_SIDE_LO; this.hashHi ^= Z_SIDE_HI;
  }
  unmakeNull() { const u = this.stack.pop(); this.side ^= 8; this.ep = u.ep; this.hashLo = u.lo; this.hashHi = u.hi; }

  legalMoves() {
    const out = [];
    for (const m of this.pseudoMoves()) if (this.make(m)) { this.unmake(); out.push(m); }
    return out;
  }

  insufficientMaterial() {
    const minors = [];
    for (let sq = 0; sq < 128; sq++) {
      if (sq & 0x88) { sq += 7; continue; }
      const p = this.board[sq], t = typeOf(p);
      if (!p || t === KING) continue;
      if (t === PAWN || t === ROOK || t === QUEEN) return false;
      minors.push({ t, shade: ((sq >> 4) + (sq & 7)) & 1 });
    }
    if (minors.length <= 1) return true;
    // Any number of bishops all on the same square colour cannot mate.
    return minors.every(m => m.t === BISHOP && m.shade === minors[0].shade);
  }

  uci(m) { return squareName(moveFrom(m)) + squareName(moveTo(m)) + (movePromo(m) ? PIECE_CHARS[movePromo(m)] : ''); }

  /** SAN for a legal move in this position. */
  san(m, legal = this.legalMoves()) {
    const from = moveFrom(m), to = moveTo(m), flags = moveFlags(m), piece = this.board[from], t = typeOf(piece);
    let s;
    if (flags & FLAG_CASTLE) s = to > from ? 'O-O' : 'O-O-O';
    else {
      s = '';
      if (t === PAWN) { if (flags & FLAG_CAPTURE) s = 'abcdefgh'[from & 7]; }
      else {
        s = PIECE_CHARS[t].toUpperCase();
        const rivals = legal.filter(o => o !== m && moveTo(o) === to && this.board[moveFrom(o)] === piece);
        if (rivals.length) {
          const sameFile = rivals.some(o => (moveFrom(o) & 7) === (from & 7));
          const sameRank = rivals.some(o => (moveFrom(o) >> 4) === (from >> 4));
          if (!sameFile) s += 'abcdefgh'[from & 7];
          else if (!sameRank) s += (from >> 4) + 1;
          else s += squareName(from);
        }
      }
      if (flags & FLAG_CAPTURE) s += 'x';
      s += squareName(to);
      if (movePromo(m)) s += '=' + PIECE_CHARS[movePromo(m)].toUpperCase();
    }
    this.make(m);
    if (this.inCheck()) s += this.legalMoves().length ? '+' : '#';
    this.unmake();
    return s;
  }

  /** Resolves SAN ("Nf3", "exd5", "O-O") or UCI ("g1f3", "e7e8q") to an encoded legal move, or 0. */
  parseMove(text, legal = this.legalMoves()) {
    const clean = String(text).trim().replace(/[+#?!]+$/, '').replace(/0/g, 'O').replace(/^([a-h][1-8])-?([a-h][1-8])/, '$1$2');
    const uci = clean.toLowerCase().match(/^([a-h][1-8])([a-h][1-8])([qrbn])?$/);
    if (uci) {
      const from = parseSquare(uci[1]), to = parseSquare(uci[2]), promo = uci[3] ? PIECE_CHARS.indexOf(uci[3]) : 0;
      const hit = legal.find(m => moveFrom(m) === from && moveTo(m) === to && movePromo(m) === (promo || (movePromo(m) === QUEEN && !uci[3] ? QUEEN : 0)));
      if (hit) return hit;
    }
    const norm = s => s.replace(/[+#]/g, '').replace(/=/, '');
    const target = norm(clean);
    for (const m of legal) if (norm(this.san(m, legal)) === target) return m;
    return 0;
  }
}

export function perft(pos, depth) {
  if (depth === 0) return 1;
  let n = 0;
  for (const m of pos.pseudoMoves()) {
    if (!pos.make(m)) continue;
    n += depth === 1 ? 1 : perft(pos, depth - 1);
    pos.unmake();
  }
  return n;
}

const TYPE_NAMES = { 1: 'p', 2: 'n', 3: 'b', 4: 'r', 5: 'q', 6: 'k' };

/** Game: a Position plus history, SAN records, draw detection, and PGN I/O. */
export class Game {
  constructor(fen = START_FEN) { this.reset(fen); }

  reset(fen = START_FEN) {
    this.pos = new Position(fen);
    this.startFen = this.pos.fen();
    this.history = [];
    this.keys = [this.pos.key];
    this.headers = {};
    return this;
  }

  fen() { return this.pos.fen(); }
  turn() { return this.pos.side === WHITE ? 'w' : 'b'; }
  inCheck() { return this.pos.inCheck(); }
  get ply() { return this.history.length; }

  get(square) {
    const p = this.pos.board[parseSquare(square)];
    return p ? { type: TYPE_NAMES[typeOf(p)], color: colorOf(p) === WHITE ? 'w' : 'b' } : null;
  }

  /** 8×8 array, rank 8 first, like chess.js. */
  board() {
    const rows = [];
    for (let rank = 7; rank >= 0; rank--) {
      const row = [];
      for (let file = 0; file < 8; file++) {
        const sq = rank * 16 + file, p = this.pos.board[sq];
        row.push(p ? { type: TYPE_NAMES[typeOf(p)], color: colorOf(p) === WHITE ? 'w' : 'b', square: squareName(sq) } : null);
      }
      rows.push(row);
    }
    return rows;
  }

  describe(m, legal) {
    const pos = this.pos, from = moveFrom(m), to = moveTo(m), flags = moveFlags(m), p = pos.board[from];
    const capSq = flags & FLAG_EP ? to + (pos.side === WHITE ? -16 : 16) : to;
    return {
      from: squareName(from), to: squareName(to), san: pos.san(m, legal), uci: pos.uci(m),
      piece: TYPE_NAMES[typeOf(p)], color: colorOf(p) === WHITE ? 'w' : 'b',
      captured: flags & FLAG_CAPTURE ? TYPE_NAMES[typeOf(pos.board[capSq])] : undefined,
      captureSquare: flags & FLAG_CAPTURE ? squareName(capSq) : undefined,
      promotion: movePromo(m) ? TYPE_NAMES[movePromo(m)] : undefined,
      castle: flags & FLAG_CASTLE ? (to > from ? 'k' : 'q') : undefined,
      encoded: m,
    };
  }

  moves({ square } = {}) {
    const legal = this.pos.legalMoves();
    const list = square ? legal.filter(m => moveFrom(m) === parseSquare(square)) : legal;
    return list.map(m => this.describe(m, legal));
  }

  /** Accepts SAN, UCI, or {from, to, promotion}. Returns the move record or null when illegal. */
  move(input) {
    const legal = this.pos.legalMoves();
    let m = 0;
    if (typeof input === 'string') m = this.pos.parseMove(input, legal);
    else if (input && input.from) m = this.pos.parseMove(input.from + input.to + (input.promotion || ''), legal);
    if (!m) return null;
    const record = this.describe(m, legal);
    record.fenBefore = this.pos.fen();
    this.pos.make(m);
    record.fen = this.pos.fen();
    record.check = this.pos.inCheck();
    this.history.push(record);
    this.keys.push(this.pos.key);
    return record;
  }

  undo() {
    if (!this.history.length) return null;
    this.pos.unmake();
    this.keys.pop();
    return this.history.pop();
  }

  repetitions() { const k = this.pos.key; return this.keys.filter(x => x === k).length; }

  status() {
    const legal = this.pos.legalMoves();
    const white = this.pos.side === WHITE;
    if (!legal.length) {
      if (this.pos.inCheck()) return { over: true, result: white ? '0-1' : '1-0', reason: 'checkmate', winner: white ? 'b' : 'w' };
      return { over: true, result: '1/2-1/2', reason: 'stalemate' };
    }
    if (this.pos.insufficientMaterial()) return { over: true, result: '1/2-1/2', reason: 'insufficient' };
    if (this.repetitions() >= 3) return { over: true, result: '1/2-1/2', reason: 'threefold' };
    if (this.pos.halfmove >= 100) return { over: true, result: '1/2-1/2', reason: 'fifty' };
    return { over: false, check: this.pos.inCheck() };
  }

  uciMoves() { return this.history.map(h => h.uci); }

  pgn(headers = {}) {
    const st = this.status();
    const result = headers.Result || (st.over ? st.result : '*');
    const h = { Event: '谱着 Chess Lab', Site: 'https://deepshawn.github.io/antichess/', Date: new Date().toISOString().slice(0, 10).replace(/-/g, '.'), White: '?', Black: '?', ...this.headers, ...headers, Result: result };
    if (this.startFen !== START_FEN) { h.SetUp = '1'; h.FEN = this.startFen; }
    const head = Object.entries(h).map(([k, v]) => `[${k} "${String(v).replace(/"/g, "'")}"]`).join('\n');
    const startMove = +this.startFen.split(' ')[5] || 1, blackFirst = this.startFen.split(' ')[1] === 'b';
    const tokens = [];
    this.history.forEach((rec, i) => {
      const plyIndex = i + (blackFirst ? 1 : 0), num = startMove + Math.floor(plyIndex / 2);
      if (plyIndex % 2 === 0) tokens.push(`${num}.`);
      else if (i === 0) tokens.push(`${num}...`);
      tokens.push(rec.san);
    });
    tokens.push(result);
    const lines = []; let line = '';
    for (const t of tokens) { if ((line + ' ' + t).length > 80) { lines.push(line); line = t; } else line = line ? line + ' ' + t : t; }
    lines.push(line);
    return `${head}\n\n${lines.join('\n')}\n`;
  }

  static fromPgn(text) {
    const headers = {};
    for (const m of text.matchAll(/\[(\w+)\s+"([^"]*)"\]/g)) headers[m[1]] = m[2];
    const body = text.replace(/\[[^\]]*\]/g, ' ').replace(/\{[^}]*\}/g, ' ').replace(/;[^\n]*/g, ' ');
    // Strip nested variations.
    let flat = '', depth = 0;
    for (const ch of body) { if (ch === '(') depth++; else if (ch === ')') depth = Math.max(0, depth - 1); else if (!depth) flat += ch; }
    const game = new Game(headers.FEN || START_FEN);
    for (const tok of flat.split(/\s+/)) {
      const t = tok.replace(/^\d+\.(\.\.)?/, '').replace(/\$\d+/, '');
      if (!t || /^(1-0|0-1|1\/2-1\/2|\*)$/.test(t)) continue;
      if (!game.move(t)) throw new Error(`PGN 中的走法 "${t}" 不合法（第 ${Math.floor(game.ply / 2) + 1} 回合）`);
    }
    delete headers.FEN; delete headers.SetUp;
    game.headers = headers;
    return game;
  }
}
