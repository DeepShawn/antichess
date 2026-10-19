import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Position } from '../web/src/core/chess.js';
import { Searcher, evaluate, MATE } from '../web/src/engine/search.js';
import { parseInfo } from '../web/src/engine/client.js';
import { LEVELS, PREFERENCES, engineDistribution, bookDistribution, choose, scoreToCp } from '../web/src/engine/personality.js';
import { grade, winChance, accuracySummary } from '../web/src/engine/review.js';

const best = (fen, ms = 1500) => { const p = new Position(fen); const r = new Searcher().search(p, { movetime: ms }); return { uci: p.uci(r.bestmove), score: r.lines[0].score }; };

test('finds mate in one', () => {
  assert.deepEqual(best('r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4').uci, 'f3f7');
  assert.equal(best('6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1').uci, 'd1d8');
});

test('finds mate in two and reports mate score', () => {
  const r = best('r2qkb1r/pp2nppp/3p4/2pNN1B1/2BnP3/3P4/PPP2PPP/R2bK2R w KQkq - 1 1', 3000);
  assert.equal(r.uci, 'd5f6');
  assert.ok(r.score > MATE - 10);
});

test('wins material when offered', () => {
  assert.equal(best('rnb1kbnr/pppp1ppp/8/4p1q1/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1', 1000).uci, 'c1g5');
});

test('evaluation is symmetric', () => {
  const a = evaluate(new Position('r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4'));
  const b = evaluate(new Position('rnbqk2r/pppp1ppp/5n2/2b1p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R b KQkq - 4 4'));
  assert.equal(a, b);
});

test('multipv returns distinct ranked lines', () => {
  const p = new Position();
  const r = new Searcher().search(p, { depth: 4, multipv: 3 });
  assert.equal(r.lines.length, 3);
  assert.equal(new Set(r.lines.map(l => l.move)).size, 3);
  assert.ok(r.lines[0].score >= r.lines[1].score && r.lines[1].score >= r.lines[2].score);
});

test('parses Stockfish info lines', () => {
  const i = parseInfo('info depth 18 seldepth 25 multipv 2 score cp -34 nodes 1000 nps 50000 hashfull 3 tbhits 0 time 20 pv e7e5 g1f3 b8c6');
  assert.deepEqual([i.depth, i.multipv, i.cp, i.pv[0]], [18, 2, -34, 'e7e5']);
  assert.equal(parseInfo('info depth 5 multipv 1 score mate -3 nodes 1 pv a1a2').mate, -3);
  assert.equal(parseInfo('info string NNUE evaluation enabled'), null);
});

test('personality: strong levels prefer the best move, weak levels spread out', () => {
  const lines = [{ cp: 40, pv: ['e2e4'] }, { cp: 10, pv: ['d2d4'] }, { cp: -60, pv: ['b1c3'] }, { cp: -400, pv: ['g2g4'] }];
  const max = engineDistribution(lines, LEVELS[5], PREFERENCES.second);
  assert.equal(max[0], 1);
  const club = engineDistribution(lines, LEVELS[2], PREFERENCES.second);
  assert.ok(club[1] > club[0], 'second-choice preference shows at club level');
  assert.equal(club[3], 0, 'moves beyond the loss tolerance are never chosen');
  const novice = engineDistribution(lines, LEVELS[0], PREFERENCES.random);
  assert.ok(novice[3] > 0);
  for (const d of [max, club, novice]) assert.ok(Math.abs(d.reduce((a, b) => a + b) - 1) < 1e-9);
  assert.ok(Math.abs(bookDistribution([{ share: .7 }, { share: .3 }], PREFERENCES.main).reduce((a, b) => a + b) - 1) < 1e-9);
  assert.ok(scoreToCp({ mate: 2 }) > scoreToCp({ mate: 5 }) && scoreToCp({ mate: -2 }) < scoreToCp({ cp: -900 }));
});

test('choose respects blunder toggle and book', () => {
  const lines = [{ cp: 40, pv: ['e2e4'] }, { cp: 10, pv: ['d2d4'] }];
  const always = () => 0.0001;
  assert.equal(choose({ lines, legal: ['e2e4', 'd2d4', 'a2a3'], level: LEVELS[0], preference: PREFERENCES.main, rng: always }).source, 'blunder');
  assert.equal(choose({ lines, legal: ['e2e4', 'd2d4', 'a2a3'], level: LEVELS[0], preference: PREFERENCES.main, blunders: false, rng: always }).uci, 'e2e4');
  assert.equal(choose({ book: [{ uci: 'c7c5', share: 1 }], lines, level: LEVELS[2], preference: PREFERENCES.main }).source, 'book');
});

test('review grades and accuracy', () => {
  assert.equal(winChance(0), 50);
  assert.equal(grade({ cp: 30 }, { cp: 20 }, 'w').grade, 'best');
  assert.equal(grade({ cp: 30 }, { cp: -500 }, 'w').grade, 'blunder');
  assert.equal(grade({ cp: 30 }, { cp: -500 }, 'b').grade, 'best');
  const s = accuracySummary([{ mover: 'w', accuracy: 90, grade: 'good' }, { mover: 'w', accuracy: 70, grade: 'mistake' }]);
  assert.ok(s.w > 70 && s.w < 90 && s.b === null && s.wCounts.mistake === 1);
});
