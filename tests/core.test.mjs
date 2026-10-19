import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Position, Game, perft, START_FEN } from '../web/src/core/chess.js';
import { OpeningBook } from '../web/src/core/book.js';

// Reference node counts from https://www.chessprogramming.org/Perft_Results
const PERFT = [
  [START_FEN, [20, 400, 8902, 197281]],
  ['r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1', [48, 2039, 97862]],
  ['8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1', [14, 191, 2812, 43238]],
  ['r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1', [6, 264, 9467]],
  ['rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8', [44, 1486, 62379]],
  ['r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10', [46, 2079, 89890]],
];

for (const [fen, counts] of PERFT) {
  test(`perft ${fen.split(' ')[0].slice(0, 24)}…`, () => {
    const pos = new Position(fen);
    counts.forEach((n, i) => assert.equal(perft(pos, i + 1), n, `depth ${i + 1}`));
    assert.equal(pos.fen(), new Position(fen).fen(), 'make/unmake restores the position');
  });
}

test('FEN round trip and hashing are consistent', () => {
  const g = new Game();
  for (const san of ['e4', 'c5', 'Nf3', 'd6', 'd4', 'cxd4', 'Nxd4', 'Nf6', 'Nc3', 'a6']) assert.ok(g.move(san), san);
  const fen = g.fen();
  assert.equal(fen, 'rnbqkb1r/1p2pppp/p2p1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 0 6');
  assert.equal(new Position(fen).key, g.pos.key);
});

test('SAN disambiguation, castling, promotion and mate', () => {
  const g = new Game('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1');
  const sans = g.moves().map(m => m.san);
  assert.ok(sans.includes('O-O') && sans.includes('O-O-O'));
  const d = new Game('7k/8/8/8/8/8/8/R5RK w - - 0 1');
  assert.ok(d.moves().map(m => m.san).includes('Rab1'));
  const p = new Game('8/P6k/8/8/8/8/8/K7 w - - 0 1');
  assert.equal(p.move('a8=Q').san, 'a8=Q');
  const m = new Game();
  for (const s of ['f3', 'e5', 'g4']) m.move(s);
  assert.equal(m.move('Qh4').san, 'Qh4#');
  assert.deepEqual(m.status(), { over: true, result: '0-1', reason: 'checkmate', winner: 'b' });
});

test('draw detection', () => {
  assert.equal(new Game('8/8/8/4k3/8/8/8/4KB2 w - - 0 1').status().reason, 'insufficient');
  assert.equal(new Game('k7/8/1Q6/8/8/8/8/7K b - - 0 1').status().reason, 'stalemate');
  const g = new Game();
  for (const s of ['Nf3', 'Nf6', 'Ng1', 'Ng8', 'Nf3', 'Nf6', 'Ng1', 'Ng8']) g.move(s);
  assert.equal(g.status().reason, 'threefold');
});

test('en passant square only when capturable, UCI input accepted', () => {
  const g = new Game();
  g.move('e2e4');
  assert.equal(g.fen().split(' ')[3], '-');
  for (const s of ['a6', 'e5', 'd5']) g.move(s);
  assert.equal(g.fen().split(' ')[3], 'd6');
  assert.equal(g.move('exd6').san, 'exd6');
});

test('PGN export → import round trip', () => {
  const g = new Game();
  for (const s of ['e4', 'e5', 'Nf3', 'Nc6', 'Bb5', 'a6', 'Ba4', 'Nf6', 'O-O']) g.move(s);
  const back = Game.fromPgn(g.pgn({ White: 'A', Black: 'B' }));
  assert.equal(back.fen(), g.fen());
  assert.equal(back.headers.White, 'A');
  const fromFen = new Game('r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 3 3');
  fromFen.move('Nf6');
  assert.match(fromFen.pgn(), /3\.\.\. Nf6/);
  assert.equal(Game.fromPgn(fromFen.pgn()).fen(), fromFen.fen());
});

test('rejects malformed FEN', () => {
  assert.throws(() => new Position('8/8/8/8/8/8/8/8 w - - 0 1'));
  assert.throws(() => new Position('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1'));
});

test('every opening book line is legal and the book suggests moves', () => {
  const book = OpeningBook.parse(readFileSync(new URL('../web/data/openings.txt', import.meta.url), 'utf8'));
  for (const line of book.lines) {
    const g = new Game();
    for (const san of line.moves) assert.ok(g.move(san), `${line.name}: ${san} after ${line.moves.slice(0, g.ply).join(' ')}`);
  }
  const first = book.candidates([]);
  assert.ok(first.length >= 4);
  assert.ok(Math.abs(first.reduce((s, c) => s + c.share, 0) - 1) < 1e-9);
  assert.equal(book.identify(['e4', 'c5', 'Nf3', 'd6', 'd4', 'cxd4', 'Nxd4', 'Nf6', 'Nc3', 'a6']).name, '西西里·纳道尔夫');
  assert.equal(book.identify(['e4', 'c5', 'Nf3', 'd6']).name, '西西里防御');
});
