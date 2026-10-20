// 谱着 · Chess Lab — application controller.
import { Game, START_FEN } from './core/chess.js';
import { OpeningBook } from './core/book.js';
import { EngineClient } from './engine/client.js';
import { LEVELS, PREFERENCES, RANK_NAMES, SAC_LABELS, choose, scoreToCp, sacrificedMaterial } from './engine/personality.js';
import { grade, accuracySummary, GRADES } from './engine/review.js';
import { Board } from './ui/board.js';
import { loadPieces, pieceSvg } from './ui/pieces.js';
import { EvalGraph } from './ui/graph.js';
import { icon, hydrateIcons } from './ui/icons.js';
import { play, sound } from './ui/sound.js';

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// ───────────────────────── persistence ─────────────────────────
const store = {
  get(k, d) { try { const v = localStorage.getItem('puzhe.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('puzhe.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

const DEFAULTS = { theme: 'midnight', boardTheme: 'jade', level: 2, preference: 'second', blunders: true, sound: true, arrows: true, coords: true, evalBar: true, engine: 'stockfish' };
const settings = { ...DEFAULTS, ...store.get('settings', {}) };
const saveSettings = () => store.set('settings', settings);

const THEMES = [['midnight', '深夜', '#76e0b5'], ['mist', '晨雾', '#2f9d77'], ['dusk', '暮色', '#ffb86b']];
const BOARDS = [['jade', '翡翠'], ['walnut', '胡桃'], ['slate', '青石'], ['rose', '樱粉']];

// ───────────────────────── state ─────────────────────────
const state = {
  game: new Game(), mode: 'play', playerColor: 'w', view: 0,
  evals: [], notes: [], choices: [], lines: [], live: null,
  thinking: false, hint: null, token: 0, reviewing: false, endShown: false,
};
let book = null, board = null, graph = null;
const engine = new EngineClient();

// ───────────────────────── helpers ─────────────────────────
const level = () => LEVELS[settings.level];
const turnAt = ply => (ply % 2 === 0) === (state.game.startFen.split(' ')[1] === 'w') ? 'w' : 'b';
const fenAt = ply => (ply === 0 ? state.game.startFen : state.game.history[ply - 1].fen);
const atEnd = () => state.view === state.game.ply;

function toast(text, kind = '') {
  const el = document.createElement('div');
  el.className = 'toast ' + kind; el.textContent = text;
  $('#toasts').appendChild(el);
  setTimeout(() => el.classList.add('out'), 2600);
  setTimeout(() => el.remove(), 3000);
}

function uciToSan(fen, uci) { try { return new Game(fen).move(uci)?.san ?? uci; } catch { return uci; } }
function pvToSan(fen, pv, max = 8) {
  const g = new Game(fen), out = [];
  for (const u of pv.slice(0, max)) { const r = g.move(u); if (!r) break; out.push(r.san); }
  return out;
}

/** UCI score (side to move) → white perspective. */
function whiteScore(line, side) {
  const s = side === 'w' ? 1 : -1;
  return line.mate !== undefined ? { mate: line.mate * s } : { cp: line.cp * s };
}

function fmtScore(ev, persp = 'w') {
  if (!ev) return '—';
  const s = persp === 'w' ? 1 : -1;
  if (ev.mate !== undefined) { const m = ev.mate * s; return (m > 0 ? '#' : '#-') + Math.abs(m); }
  const v = ev.cp * s / 100;
  return (v > 0 ? '+' : v < 0 ? '−' : '') + Math.abs(v).toFixed(2);
}

function setEval(ply, line, depth) {
  if (!line) return;
  if (line.mate === 0) line = { cp: -10000, pv: [] }; // side to move is checkmated
  const prev = state.evals[ply];
  if (prev && prev.depth > depth) return;
  state.evals[ply] = { ...whiteScore(line, turnAt(ply)), best: line.pv?.[0], depth };
  regrade(ply); regrade(ply + 1);
}

function regrade(p) {
  if (p < 1 || p > state.game.ply || !state.evals[p - 1] || !state.evals[p]) return;
  const rec = state.game.history[p - 1];
  const g = grade(state.evals[p - 1], state.evals[p], rec.color, state.evals[p - 1].best === rec.uci);
  state.notes[p] = { ...g, mover: rec.color };
}

function persist() {
  store.set('game', {
    pgn: state.game.pgn(), mode: state.mode, playerColor: state.playerColor,
    evals: state.evals, notes: state.notes, choices: state.choices, level: settings.level,
  });
}

// ───────────────────────── engine orchestration ─────────────────────────
function schedule() {
  const token = ++state.token;
  state.live = null; state.lines = [];
  const st = state.game.status();
  if (st.over) {
    state.thinking = false; engine.cancel();
    const ply = state.game.ply;
    state.evals[ply] = { cp: st.winner ? (st.winner === 'w' ? 10000 : -10000) : 0, depth: 99 };
    regrade(ply); persist(); finish(st); render(); return;
  }
  if (state.mode === 'play' && state.game.turn() !== state.playerColor) engineTurn(token);
  else analyzeCurrent(token);
}

async function analyzeCurrent(token) {
  const ply = state.game.ply, fen = state.game.fen();
  const infinite = state.mode === 'analysis';
  const sf = engine.kind === 'stockfish';
  const res = await engine.analyze({
    fen: state.game.startFen, moves: state.game.uciMoves(), multipv: 3,
    infinite, depth: infinite ? undefined : sf ? 16 : undefined, movetime: infinite ? undefined : sf ? 2500 : 1200,
  }, info => {
    if (token !== state.token) return;
    state.lines = info.lines; state.live = info;
    if (info.lines[0]) setEval(ply, info.lines[0], info.depth);
    scheduleRender();
  });
  if (token !== state.token) return;
  if (res.lines[0]) setEval(ply, res.lines[0], res.depth);
  state.lines = res.lines; state.live = { ...res, done: true };
  if (state.game.fen() === fen) persist();
  render();
}

async function engineTurn(token) {
  state.thinking = true; render();
  const lv = level(), ply = state.game.ply, fen = state.game.fen(), started = performance.now();
  const sf = engine.kind === 'stockfish';
  const bookMoves = lv.id === 'max' ? [] : book?.candidates(fen) ?? [];
  const res = await engine.analyze({
    fen: state.game.startFen, moves: state.game.uciMoves(), multipv: PREFERENCES[settings.preference].sacrifice ? Math.max(lv.multipv, 5) : lv.multipv, // wider search finds sacrifices
    depth: lv.depth, movetime: sf ? Math.round(lv.movetime * 1.6) : lv.movetime,
  }, info => {
    if (token !== state.token) return;
    state.live = info; scheduleRender();
  });
  if (token !== state.token || res.cancelled && !res.lines.length) return;
  if (res.lines[0]) setEval(ply, res.lines[0], res.depth);
  const legal = state.game.moves().map(m => m.uci);
  const decision = choose({ fen, book: bookMoves, lines: res.lines, legal, level: lv, preference: PREFERENCES[settings.preference], blunders: settings.blunders });
  const candidates = decision.source === 'book'
    ? bookMoves.map((b, i) => ({ uci: b.uci, san: b.san, label: b.name, prob: decision.probs[i] }))
    : res.lines.map((l, i) => ({ uci: l.pv[0], san: uciToSan(fen, l.pv[0]), score: whiteScore(l, turnAt(ply)), prob: decision.probs[i], sac: decision.sacs?.[i] || null }));
  if (decision.source === 'blunder' && !candidates.some(c => c.uci === decision.uci)) candidates.push({ uci: decision.uci, san: uciToSan(fen, decision.uci), prob: 0, slip: true });
  const wait = 380 + Math.random() * 380 - (performance.now() - started);
  if (wait > 0) await sleep(wait);
  if (token !== state.token) return;
  const sacLine = decision.sacrifice && res.lines[decision.rank];
  state.choices[ply + 1] = { source: decision.source, rank: decision.rank, uci: decision.uci, candidates, depth: res.depth, engine: engine.name,
    sacrifice: decision.sacrifice || null, sacAmount: sacLine ? sacrificedMaterial(fen, sacLine.pv) : 0 };
  state.thinking = false;
  const rec = state.game.move(decision.uci);
  if (rec) afterMove(rec);
}

function finish(st) {
  if (state.endShown) return;
  state.endShown = true;
  play('end');
  showGameOver(st);
}

// ───────────────────────── user actions ─────────────────────────
function onUserMove(m) {
  if (state.mode === 'play' && (state.thinking || state.game.turn() !== state.playerColor || !atEnd())) return;
  if (!atEnd()) truncateTo(state.view);
  const rec = state.game.move(m);
  if (!rec) { play('error'); return; }
  afterMove(rec);
}

function truncateTo(ply) {
  while (state.game.ply > ply) state.game.undo();
  state.evals.length = Math.min(state.evals.length, ply + 1);
  state.notes.length = Math.min(state.notes.length, ply + 1);
  state.choices.length = Math.min(state.choices.length, ply + 1);
  state.endShown = false;
}

function afterMove(rec) {
  state.view = state.game.ply; state.hint = null;
  play(rec.check ? 'check' : rec.captured ? 'capture' : 'move');
  persist(); render(true); schedule();
}

function seek(ply) {
  state.view = Math.max(0, Math.min(state.game.ply, ply));
  state.hint = null; render(true);
}

function undo() {
  if (!state.game.ply) return;
  state.token++; engine.cancel(); state.thinking = false;
  let target = state.game.ply - 1;
  if (state.mode === 'play') { while (target > 0 && turnAt(target) !== state.playerColor) target--; if (turnAt(target) !== state.playerColor) target = 0; }
  truncateTo(target);
  state.view = state.game.ply;
  persist(); render(true); schedule();
  toast('已悔棋');
}

function hint() {
  if (state.mode !== 'play' || !atEnd() || state.thinking) return;
  const ev = state.evals[state.game.ply];
  if (!ev?.best) { toast('引擎还在分析，稍等片刻再试'); return; }
  state.hint = ev.best; render();
  toast(`提示：${uciToSan(state.game.fen(), ev.best)}`);
}

function setMode(mode) {
  if (mode === state.mode) return;
  if (!atEnd()) truncateTo(state.view);
  state.mode = mode; state.hint = null;
  if (mode === 'play') { state.playerColor = state.game.turn(); state.endShown = false; board.setOrientation(state.playerColor); }
  toast(mode === 'analysis' ? '分析模式：双方自由走子，引擎持续分析' : `对局模式：你执${state.playerColor === 'w' ? '白' : '黑'}，从当前局面继续`);
  persist(); render(); schedule();
}

function newGame({ color = 'w', fen = START_FEN } = {}) {
  state.token++; engine.cancel(); engine.newGame();
  const side = color === 'random' ? (Math.random() < 0.5 ? 'w' : 'b') : color;
  try { state.game = new Game(fen || START_FEN); } catch (e) { toast(e.message, 'bad'); return; }
  Object.assign(state, { mode: 'play', playerColor: side, view: 0, evals: [], notes: [], choices: [], lines: [], live: null, thinking: false, hint: null, endShown: false });
  board.setOrientation(side);
  persist(); render(true); schedule();
  toast(`新对局 · 你执${side === 'w' ? '白' : '黑'} · ${level().name} ${level().elo}`);
}

function loadText(text) {
  const t = text.trim();
  if (!t) return false;
  try {
    const g = /^[rnbqkpRNBQKP1-8]+(\/[rnbqkpRNBQKP1-8]+){7}\s/.test(t) ? new Game(t) : Game.fromPgn(t);
    state.token++; engine.cancel();
    Object.assign(state, { game: g, mode: 'analysis', view: g.ply, evals: [], notes: [], choices: [], lines: [], live: null, thinking: false, hint: null, endShown: true });
    persist(); render(true); schedule();
    toast(g.ply ? `已导入 ${g.ply} 步棋谱，正在自动复盘` : '已载入局面，进入分析模式', 'good');
    if (g.ply > 1) reviewGame();
    return true;
  } catch (e) { toast(e.message || '无法识别的 PGN / FEN', 'bad'); return false; }
}

async function reviewGame() {
  if (state.reviewing || state.game.ply === 0) return;
  state.reviewing = true; const token = ++state.token;
  state.thinking = false;
  const btn = $('#reviewBtn'), n = state.game.ply, sf = engine.kind === 'stockfish';
  for (let p = 0; p <= n; p++) {
    if (token !== state.token) break;
    btn.dataset.progress = ''; btn.style.setProperty('--progress', Math.round(p / n * 100) + '%');
    btn.querySelector('span').textContent = `复盘中 ${Math.round(p / n * 100)}%`;
    if (state.evals[p]?.depth >= (sf ? 12 : 6)) continue;
    const res = await engine.analyze({ fen: state.game.startFen, moves: state.game.uciMoves().slice(0, p), multipv: 1, depth: sf ? 12 : 64, movetime: sf ? 1500 : 350 });
    if (token !== state.token) break;
    if (res.lines[0]) setEval(p, res.lines[0], res.depth);
    else { const st = new Game(fenAt(p)).status(); if (st.over) { state.evals[p] = { cp: st.winner ? (st.winner === 'w' ? 10000 : -10000) : 0, depth: 99 }; regrade(p); } }
    renderReview();
  }
  state.reviewing = false;
  btn.querySelector('span').textContent = '全盘复盘'; delete btn.dataset.progress;
  if (token === state.token) { persist(); render(); toast('复盘完成：曲线与每步评级已更新', 'good'); schedule(); }
}

// ───────────────────────── rendering ─────────────────────────
let renderQueued = false;
function scheduleRender() { if (!renderQueued) { renderQueued = true; requestAnimationFrame(() => { renderQueued = false; render(); }); } }

function render(animate = false) {
  const g = state.game, view = state.view, viewGame = view === g.ply ? g : new Game(fenAt(view));
  const last = view ? g.history[view - 1] : null;
  const st = viewGame.status();
  let checkSq = null;
  if (viewGame.inCheck()) for (const row of viewGame.board()) for (const p of row) if (p && p.type === 'k' && p.color === viewGame.turn()) checkSq = p.square;
  board.setPosition(viewGame.board(), { lastMove: last && { from: last.from, to: last.to }, check: checkSq, animate });

  // Interactivity
  let movable = null;
  if (!st.over) {
    if (state.mode === 'analysis') movable = 'both';
    else if (atEnd() && !state.thinking && g.turn() === state.playerColor) movable = state.playerColor;
  }
  const dests = new Map();
  if (movable) for (const m of viewGame.moves()) {
    if (!dests.has(m.from)) dests.set(m.from, []);
    const list = dests.get(m.from);
    if (!list.some(d => d.to === m.to)) list.push({ to: m.to, promotion: !!m.promotion });
  }
  board.setInteractive(movable, dests);

  // Arrows
  const arrows = [];
  if (state.hint && atEnd()) arrows.push({ from: state.hint.slice(0, 2), to: state.hint.slice(2, 4), kind: 'hint', width: 0.18 });
  if (state.mode === 'analysis' && settings.arrows && atEnd()) {
    state.lines.slice(0, 3).forEach((l, i) => l?.pv?.[0] && arrows.push({ from: l.pv[0].slice(0, 2), to: l.pv[0].slice(2, 4), kind: `pv${i}`, width: [0.19, 0.14, 0.11][i], opacity: [0.9, 0.62, 0.45][i], label: String(i + 1) }));
  }
  if (!atEnd() && state.mode === 'analysis' && state.evals[view]?.best) {
    const b = state.evals[view].best; arrows.push({ from: b.slice(0, 2), to: b.slice(2, 4), kind: 'pv0', width: 0.16, opacity: 0.75 });
  }
  board.setEngineArrows(arrows);

  renderPlayers(viewGame);
  renderEvalBar();
  renderOpening();
  renderEngine();
  renderMoves();
  renderReview();
  renderStatus(st);
  $('#modeSwitch').dataset.mode = state.mode;
  $$('#modeSwitch button').forEach(b => b.setAttribute('aria-pressed', b.dataset.mode === state.mode));
  $('#undoBtn').disabled = !g.ply;
  $('#hintBtn').disabled = state.mode !== 'play' || !atEnd() || state.thinking || st.over;
  $('#firstBtn').disabled = $('#prevBtn').disabled = view === 0;
  $('#nextBtn').disabled = $('#lastBtn').disabled = atEnd();
  document.body.classList.toggle('browsing', !atEnd());
}

function material(viewGame) {
  const start = { p: 8, n: 2, b: 2, r: 2, q: 1 }, val = { p: 1, n: 3, b: 3, r: 5, q: 9 };
  const count = { w: { p: 0, n: 0, b: 0, r: 0, q: 0 }, b: { p: 0, n: 0, b: 0, r: 0, q: 0 } };
  for (const row of viewGame.board()) for (const p of row) if (p && p.type !== 'k') count[p.color][p.type]++;
  const score = c => Object.entries(count[c]).reduce((s, [t, n]) => s + n * val[t], 0);
  const lost = c => Object.keys(start).flatMap(t => Array(Math.max(0, start[t] - count[c][t])).fill(t));
  return { w: { captured: lost('b'), diff: score('w') - score('b') }, b: { captured: lost('w'), diff: score('b') - score('w') } };
}

function renderPlayers(viewGame) {
  const mat = material(viewGame), bottomColor = board.orientation, topColor = bottomColor === 'w' ? 'b' : 'w';
  const lv = level();
  const info = color => {
    if (state.mode === 'analysis') return { name: color === 'w' ? '白方' : '黑方', sub: '分析模式 · 自由走子', ai: false };
    if (color === state.playerColor) return { name: '你', sub: `执${color === 'w' ? '白' : '黑'} · ${PREFERENCES[settings.preference].name}对手`, ai: false };
    return { name: `谱着 AI · ${lv.name}`, sub: `参考 ${lv.elo} Elo · ${engine.name}`, ai: true };
  };
  for (const [el, color] of [[$('#playerTop'), topColor], [$('#playerBottom'), bottomColor]]) {
    const p = info(color), m = mat[color];
    const active = !viewGame.status().over && viewGame.turn() === color;
    el.classList.toggle('active', active);
    el.classList.toggle('thinking', p.ai && state.thinking);
    el.innerHTML = `
      <div class="avatar ${color}">${pieceSvg(color + (p.ai ? 'n' : 'k'))}</div>
      <div class="pmeta"><div class="pname">${esc(p.name)}${p.ai && state.thinking ? '<span class="dots"><i></i><i></i><i></i></span>' : ''}</div><div class="psub">${esc(p.sub)}</div></div>
      <div class="captured">${m.captured.map(t => `<span class="cap">${pieceSvg((color === 'w' ? 'b' : 'w') + t)}</span>`).join('')}${m.diff > 0 ? `<b class="adv">+${m.diff}</b>` : ''}</div>`;
  }
}

function renderEvalBar() {
  const ev = state.evals[state.view];
  const bar = $('#evalBar');
  bar.hidden = !settings.evalBar;
  const cp = ev ? scoreToCp(ev) : 0;
  const pct = ev?.mate !== undefined ? (ev.mate > 0 ? 100 : 0) : 50 + 50 * (2 / (1 + Math.exp(-0.004 * cp)) - 1);
  bar.style.setProperty('--white', board.orientation === 'w' ? pct : 100 - pct);
  bar.classList.toggle('flipped', board.orientation === 'b');
  const label = bar.querySelector('.ev-label');
  label.textContent = ev ? fmtScore(ev).replace('+', '') : '0.0';
  label.classList.toggle('on-white', pct >= 50);
}

function renderOpening() {
  const fens = [state.game.startFen, ...state.game.history.slice(0, state.view).map(h => h.fen)];
  const hit = state.game.startFen === START_FEN && book ? book.identify(fens) : null;
  const inBook = book && book.candidates(fenAt(state.view)).length > 0;
  $('#openingEco').textContent = hit?.eco ?? (state.game.startFen === START_FEN ? '—' : 'FEN');
  $('#openingName').textContent = hit?.name ?? (state.game.startFen === START_FEN ? '初始局面' : '自定义局面');
  $('#openingEn').textContent = hit?.english ?? (state.game.startFen === START_FEN ? 'Starting position' : 'Custom setup');
  $('#openingBook').textContent = inBook ? '谱着库内' : '已出谱';
  $('#openingBook').classList.toggle('in', inBook);
}

function renderEngine() {
  const panel = $('#enginePanel'), list = $('#candidates'), meta = $('#engineMeta'), note = $('#engineNote');
  const live = state.live;
  meta.textContent = live?.depth ? `深度 ${live.depth}${live.lines?.[0]?.nps ? ' · ' + Math.round(live.lines[0].nps / 1000) + ' kn/s' : ''}` : engine.kind ? engine.name : '加载中…';
  panel.classList.toggle('busy', state.thinking || (state.mode === 'analysis' && !live?.done && atEnd()));

  // Decision view: the engine is choosing now, or we are looking at the position it chose from.
  const decidedPly = state.thinking ? null : (state.mode === 'play' ? (atEnd() ? lastEnginePly() : state.view + 1) : state.view + 1);
  const choice = decidedPly ? state.choices[decidedPly] : null;
  if (state.thinking) {
    const fen = state.game.fen();
    const rows = (live?.lines || []).slice(0, level().multipv).map((l, i) => ({ rank: i, san: uciToSan(fen, l.pv[0]), score: whiteScore(l, state.game.turn()), pv: pvToSan(fen, l.pv, 5).slice(1).join(' ') }));
    list.innerHTML = rows.length ? rows.map(r => candRow(r)).join('') : skeleton();
    $('#engineTitle').textContent = '谱着思考中';
    note.innerHTML = `${icon('sparkle')}<span>按「${PREFERENCES[settings.preference].name}」从候选中抽样，模拟真人选择</span>`;
    return;
  }
  if (choice) {
    $('#engineTitle').textContent = choice.sacrifice ? `弃子${SAC_LABELS[choice.sacrifice].name}！` : choice.source === 'book' ? '谱着库选择' : choice.source === 'blunder' ? '错棋注入' : '谱着候选';
    list.innerHTML = choice.candidates.map((c, i) => candRow({ rank: c.slip ? -1 : i, san: c.san, score: c.score, label: c.label, prob: c.prob, chosen: c.uci === choice.uci, slip: c.slip, sac: c.sac })).join('');
    const rank = choice.rank >= 0 ? RANK_NAMES[choice.rank] : '非候选';
    note.innerHTML = choice.source === 'book' ? `${icon('sparkle')}<span>开局阶段：从大师谱着中按权重抽中<b>${rank}</b></span>`
      : choice.source === 'blunder' ? `${icon('flag')}<span>低频错棋注入：这一步故意偏离引擎，给你反击机会</span>`
        : choice.sacrifice ? `${icon('sparkle')}<span>${choice.sacrifice === 'brilliant' ? '妙棋 !!' : '好棋 !'}：弃掉价值 <b>${choice.sacAmount}</b> 兵的子力换取攻势，引擎深度 ${choice.depth} 仍认可这步</span>`
          : `${icon('sparkle')}<span>引擎深度 ${choice.depth} 评估后，按偏好抽中<b>${rank}</b></span>`;
    return;
  }
  if (state.mode === 'analysis' || (!atEnd() && state.evals[state.view])) {
    const fen = fenAt(state.view);
    const lines = atEnd() ? state.lines : [];
    $('#engineTitle').textContent = '引擎分析';
    const rows = lines.slice(0, 3).filter(l => l?.pv?.length).map((l, i) => ({ rank: i, san: uciToSan(fen, l.pv[0]), score: whiteScore(l, turnAt(state.view)), pv: pvToSan(fen, l.pv, 7).slice(1).join(' ') }));
    if (!rows.length && state.evals[state.view]?.best) rows.push({ rank: 0, san: uciToSan(fen, state.evals[state.view].best), score: state.evals[state.view] });
    list.innerHTML = rows.length ? rows.map(r => candRow(r)).join('') : skeleton();
    note.innerHTML = `${icon('cpu')}<span>${state.mode === 'analysis' ? '持续分析中，棋盘箭头显示前三候选' : '历史局面评估'}</span>`;
    return;
  }
  $('#engineTitle').textContent = '谱着候选';
  list.innerHTML = `<div class="empty">轮到你走棋。引擎回应时，这里会展示它考虑的候选着法与抽样概率。</div>`;
  note.innerHTML = `${icon('bulb')}<span>需要帮助？点「提示」查看引擎推荐</span>`;
}

function lastEnginePly() {
  for (let p = state.game.ply; p > 0; p--) if (state.choices[p]) return p === state.game.ply || p === state.game.ply - 1 ? p : null;
  return null;
}

const skeleton = () => '<div class="cand skeleton"></div><div class="cand skeleton"></div><div class="cand skeleton"></div>';

function candRow({ rank, san, score, label, prob, chosen, pv, slip, sac }) {
  const rname = slip ? '错棋' : RANK_NAMES[rank] ?? '候选';
  return `<div class="cand ${chosen ? 'chosen' : ''} ${slip ? 'slip' : ''}">
    <span class="rank r${rank}">${rname}</span>
    <span class="san">${esc(san)}${sac ? `<b class="sac ${sac}" title="弃子${SAC_LABELS[sac].name}">${SAC_LABELS[sac].mark}</b>` : ''}</span>
    <span class="cinfo">${label ? `<span class="clabel">${esc(label)}</span>` : ''}${pv ? `<span class="pv">${esc(pv)}</span>` : ''}</span>
    ${score ? `<span class="score ${scoreToCp(score) >= 0 ? 'pos' : 'neg'}">${fmtScore(score)}</span>` : '<span></span>'}
    ${prob !== undefined ? `<span class="prob"><i style="width:${(prob * 100).toFixed(1)}%"></i></span><span class="pct">${Math.round(prob * 100)}%</span>` : '<span></span><span></span>'}
    <span class="tick">${chosen ? '✓' : ''}</span>
  </div>`;
}

function renderMoves() {
  const h = state.game.history, el = $('#moveList');
  if (!h.length) { el.innerHTML = '<div class="empty">尚未落子 · 拖动或点击棋子开始</div>'; return; }
  const blackFirst = state.game.startFen.split(' ')[1] === 'b', startNo = +state.game.startFen.split(' ')[5] || 1;
  const cells = [];
  if (blackFirst) cells.push({ ply: 0 });
  h.forEach((r, i) => cells.push({ ply: i + 1, rec: r }));
  let html = '';
  for (let i = 0; i < cells.length; i += 2) {
    html += `<div class="mrow"><span class="mno">${startNo + i / 2}.</span>${[cells[i], cells[i + 1]].map(c => moveCell(c)).join('')}</div>`;
  }
  el.innerHTML = html;
  const cur = el.querySelector('.mv.current');
  if (cur) { const top = cur.offsetTop - el.offsetTop; if (top < el.scrollTop || top > el.scrollTop + el.clientHeight - 30) el.scrollTop = top - el.clientHeight / 2; }
}

function moveCell(c) {
  if (!c) return '<span class="mv blank"></span>';
  if (!c.rec) return '<span class="mv blank">…</span>';
  const note = state.notes[c.ply], choice = state.choices[c.ply], g = note && GRADES[note.grade];
  const badge = choice ? (choice.sacrifice ? `<i class="tag sac-${choice.sacrifice}" title="弃子${SAC_LABELS[choice.sacrifice].name}">${SAC_LABELS[choice.sacrifice].mark}</i>` : choice.source === 'book' ? '<i class="tag book" title="谱着库">谱</i>' : choice.source === 'blunder' ? '<i class="tag slip" title="错棋注入">误</i>' : choice.rank > 0 ? `<i class="tag rank" title="${RANK_NAMES[choice.rank]}">${choice.rank + 1}</i>` : '') : '';
  return `<button class="mv ${state.view === c.ply ? 'current' : ''} ${g ? 'g-' + note.grade : ''}" data-ply="${c.ply}">${esc(c.rec.san)}${g?.mark ? `<b class="glyph">${g.mark}</b>` : ''}${badge}</button>`;
}

function renderReview() {
  const labels = ['开局', ...state.game.history.map((h, i) => `${Math.floor(i / 2) + 1}${i % 2 ? '…' : '.'} ${h.san}`)];
  graph.render(state.evals.slice(0, state.game.ply + 1).concat(Array(Math.max(0, state.game.ply + 1 - state.evals.length)).fill(undefined)), state.notes, labels, state.view);
  const acc = accuracySummary(state.notes);
  for (const c of ['w', 'b']) {
    const el = $(`#acc-${c}`);
    el.querySelector('b').textContent = acc[c] == null ? '—' : acc[c].toFixed(1) + '%';
    el.querySelector('small').textContent = acc[c] == null ? '暂无数据' : `漏着 ${acc[c + 'Counts'].blunder} · 失误 ${acc[c + 'Counts'].mistake} · 欠准 ${acc[c + 'Counts'].inaccuracy}`;
  }
}

function renderStatus(st) {
  const pill = $('#enginePill');
  pill.dataset.state = !engine.kind ? 'loading' : state.thinking || state.reviewing ? 'busy' : 'ready';
  $('#engineName').textContent = engine.kind ? engine.name : '引擎加载中';
  const turn = $('#turnLine');
  if (!atEnd()) turn.textContent = `查看第 ${state.view} 步 · 按 → 回到当前`;
  else if (st.over) turn.textContent = resultText(st);
  else if (state.mode === 'analysis') turn.textContent = `${state.game.turn() === 'w' ? '白' : '黑'}方走 · 分析模式`;
  else if (state.thinking) turn.textContent = '谱着正在思考…';
  else turn.textContent = `轮到你 · 执${state.playerColor === 'w' ? '白' : '黑'}${st.check ? ' · 被将军！' : ''}`;
}

function resultText(st) {
  const reason = { checkmate: '将杀', stalemate: '逼和', insufficient: '子力不足', threefold: '三次重复', fifty: '五十步规则' }[st.reason];
  return st.result === '1/2-1/2' ? `和棋 · ${reason}` : `${st.result === '1-0' ? '白方' : '黑方'}胜 · ${reason}`;
}

// ───────────────────────── dialogs ─────────────────────────
function showGameOver(st) {
  const d = $('#overDialog');
  const youWin = state.mode === 'play' && st.winner === state.playerColor, youLose = state.mode === 'play' && st.winner && st.winner !== state.playerColor;
  d.querySelector('.over-title').textContent = st.result === '1/2-1/2' ? '和棋' : state.mode === 'play' ? (youWin ? '你赢了！' : '谱着获胜') : `${st.winner === 'w' ? '白方' : '黑方'}获胜`;
  d.querySelector('.over-sub').textContent = `${resultText(st)} · 共 ${Math.ceil(state.game.ply / 2)} 回合`;
  d.querySelector('.over-crown').className = 'over-crown ' + (youWin ? 'win' : youLose ? 'lose' : 'draw');
  d.querySelector('.over-crown').innerHTML = pieceSvg((st.winner || 'w') + (st.result === '1/2-1/2' ? 'q' : 'k'));
  setTimeout(() => d.showModal(), 650);
}

function openNewGame() {
  const d = $('#newDialog');
  d.querySelector('#levelGrid').innerHTML = LEVELS.map((l, i) => `<button type="button" class="level ${i === settings.level ? 'on' : ''}" data-level="${i}"><b>${l.name}</b><span>${l.elo} Elo</span><i style="--p:${(i + 1) / LEVELS.length}"></i></button>`).join('');
  d.querySelector('#prefSelect').innerHTML = Object.entries(PREFERENCES).map(([k, p]) => `<option value="${k}" ${k === settings.preference ? 'selected' : ''}>${p.name} · ${p.note}</option>`).join('');
  d.querySelector('#blunderToggle').checked = settings.blunders;
  d.showModal();
}

function openSettings() {
  const d = $('#settingsDialog');
  d.querySelector('#themeGrid').innerHTML = THEMES.map(([k, n, c]) => `<button type="button" class="swatch ${settings.theme === k ? 'on' : ''}" data-theme="${k}" style="--c:${c}"><i></i>${n}</button>`).join('');
  d.querySelector('#boardGrid').innerHTML = BOARDS.map(([k, n]) => `<button type="button" class="bswatch ${settings.boardTheme === k ? 'on' : ''}" data-board="${k}"><i class="b-${k}"></i>${n}</button>`).join('');
  for (const k of ['sound', 'coords', 'arrows', 'evalBar', 'blunders']) d.querySelector(`[data-setting="${k}"]`).checked = settings[k];
  d.querySelector('#engineSelect').value = settings.engine;
  d.querySelector('#engineInfo').textContent = engine.kind === 'stockfish' ? '当前：Stockfish 19 Lite（WASM，单线程，本地运行）' : '当前：谱着内置引擎（PVS + 置换表，纯 JavaScript）';
  d.showModal();
}

function openShare() {
  const d = $('#shareDialog');
  d.querySelector('#pgnText').value = state.game.pgn({ White: state.mode === 'play' ? (state.playerColor === 'w' ? '你' : `谱着 ${level().name}`) : '?', Black: state.mode === 'play' ? (state.playerColor === 'b' ? '你' : `谱着 ${level().name}`) : '?' });
  d.querySelector('#fenText').value = fenAt(state.view);
  d.querySelector('#importText').value = '';
  d.showModal();
}

async function copy(text, what) {
  try { await navigator.clipboard.writeText(text); toast(`${what}已复制`, 'good'); } catch { toast('无法访问剪贴板', 'bad'); }
}

function applyAppearance() {
  document.body.dataset.theme = settings.theme;
  document.body.dataset.board = settings.boardTheme;
  document.body.classList.toggle('no-coords', !settings.coords);
  sound.enabled = settings.sound;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = getComputedStyle(document.body).getPropertyValue('--bg').trim() || '#0b1016';
}

// ───────────────────────── wiring ─────────────────────────
function wire() {
  $('#modeSwitch').addEventListener('click', e => { const b = e.target.closest('button'); if (b) setMode(b.dataset.mode); });
  $('#newBtn').onclick = openNewGame;
  $('#settingsBtn').onclick = openSettings;
  $('#shareBtn').onclick = openShare;
  $('#undoBtn').onclick = undo;
  $('#hintBtn').onclick = hint;
  $('#flipBtn').onclick = () => { board.setOrientation(board.orientation === 'w' ? 'b' : 'w'); render(); };
  $('#firstBtn').onclick = () => seek(0);
  $('#prevBtn').onclick = () => seek(state.view - 1);
  $('#nextBtn').onclick = () => seek(state.view + 1);
  $('#lastBtn').onclick = () => seek(state.game.ply);
  $('#reviewBtn').onclick = reviewGame;
  $('#moveList').addEventListener('click', e => { const b = e.target.closest('.mv[data-ply]'); if (b) seek(+b.dataset.ply); });

  // New game dialog
  const nd = $('#newDialog');
  let color = 'w';
  nd.addEventListener('click', e => {
    const lv = e.target.closest('.level'); if (lv) { settings.level = +lv.dataset.level; nd.querySelectorAll('.level').forEach(x => x.classList.toggle('on', x === lv)); }
    const c = e.target.closest('[data-color]'); if (c) { color = c.dataset.color; nd.querySelectorAll('[data-color]').forEach(x => x.classList.toggle('on', x === c)); }
  });
  nd.querySelector('#startBtn').onclick = e => {
    e.preventDefault();
    settings.preference = nd.querySelector('#prefSelect').value;
    settings.blunders = nd.querySelector('#blunderToggle').checked;
    saveSettings(); nd.close();
    newGame({ color, fen: nd.querySelector('#startFen').value.trim() || START_FEN });
  };

  // Settings dialog
  const sd = $('#settingsDialog');
  sd.addEventListener('click', e => {
    const t = e.target.closest('[data-theme]'); if (t) { settings.theme = t.dataset.theme; sd.querySelectorAll('[data-theme]').forEach(x => x.classList.toggle('on', x === t)); }
    const b = e.target.closest('[data-board]'); if (b) { settings.boardTheme = b.dataset.board; sd.querySelectorAll('[data-board]').forEach(x => x.classList.toggle('on', x === b)); }
    if (t || b) { applyAppearance(); saveSettings(); }
  });
  sd.addEventListener('change', async e => {
    const k = e.target.dataset.setting;
    if (k) { settings[k] = e.target.checked; saveSettings(); applyAppearance(); render(); }
    if (e.target.id === 'engineSelect') {
      settings.engine = e.target.value; saveSettings();
      state.token++; engine.cancel(); state.thinking = false;
      engine.kind = null; render();
      await engine.init({ prefer: settings.engine });
      toast(`已切换到 ${engine.name}`, 'good');
      sd.querySelector('#engineInfo').textContent = engine.kind === 'stockfish' ? '当前：Stockfish 19 Lite（WASM，单线程，本地运行）' : '当前：谱着内置引擎（PVS + 置换表，纯 JavaScript）';
      render(); schedule();
    }
  });

  // Share / import dialog
  const shd = $('#shareDialog');
  shd.querySelector('#copyPgn').onclick = () => copy(shd.querySelector('#pgnText').value, 'PGN ');
  shd.querySelector('#copyFen').onclick = () => copy(shd.querySelector('#fenText').value, 'FEN ');
  shd.querySelector('#copyLink').onclick = () => {
    const url = new URL(location.href); url.search = ''; url.hash = '';
    if (state.game.startFen !== START_FEN) url.searchParams.set('fen', state.game.startFen);
    if (state.game.ply) url.searchParams.set('moves', state.game.history.map(h => h.san).join(' '));
    copy(url.toString(), '分享链接');
  };
  shd.querySelector('#downloadPgn').onclick = () => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([shd.querySelector('#pgnText').value], { type: 'application/x-chess-pgn' }));
    a.download = `puzhe-${new Date().toISOString().slice(0, 10)}.pgn`; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  };
  shd.querySelector('#importBtn').onclick = e => { e.preventDefault(); if (loadText(shd.querySelector('#importText').value)) shd.close(); };
  shd.querySelector('#importFile').onchange = async e => { const f = e.target.files[0]; if (f && loadText(await f.text())) shd.close(); };

  // Game over dialog
  const od = $('#overDialog');
  od.querySelector('#overNew').onclick = () => { od.close(); openNewGame(); };
  od.querySelector('#overReview').onclick = () => { od.close(); setMode('analysis'); seek(0); reviewGame(); };
  od.querySelector('#overShare').onclick = () => { od.close(); openShare(); };

  for (const d of $$('dialog')) d.addEventListener('click', e => { if (e.target === d) d.close(); });
  for (const b of $$('[data-close]')) b.onclick = () => b.closest('dialog').close();

  // Drag & drop a PGN/FEN file anywhere.
  addEventListener('dragover', e => e.preventDefault());
  addEventListener('drop', async e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) loadText(await f.text()); });

  addEventListener('keydown', e => {
    if (e.target.matches('input, textarea, select') || document.querySelector('dialog[open]')) return;
    const k = e.key.toLowerCase();
    if (k === 'arrowleft') seek(state.view - 1);
    else if (k === 'arrowright') seek(state.view + 1);
    else if (k === 'home' || k === 'arrowup') seek(0);
    else if (k === 'end' || k === 'arrowdown') seek(state.game.ply);
    else if (k === 'f') $('#flipBtn').click();
    else if (k === 'n') openNewGame();
    else if (k === 'u') undo();
    else if (k === 'h') hint();
    else if (k === 'a') setMode(state.mode === 'play' ? 'analysis' : 'play');
    else return;
    e.preventDefault();
  });
}

function restore() {
  const params = new URLSearchParams(location.search);
  if (params.has('fen') || params.has('moves')) {
    try {
      const g = new Game(params.get('fen') || START_FEN);
      for (const s of (params.get('moves') || '').split(/\s+/).filter(Boolean)) if (!g.move(s)) break;
      Object.assign(state, { game: g, mode: 'analysis', view: g.ply, endShown: true });
      history.replaceState(null, '', location.pathname);
      return;
    } catch { /* fall through to the saved game */ }
  }
  const saved = store.get('game', null);
  if (!saved) return;
  try {
    const g = Game.fromPgn(saved.pgn);
    Object.assign(state, {
      game: g, mode: saved.mode === 'analysis' ? 'analysis' : 'play', playerColor: saved.playerColor === 'b' ? 'b' : 'w', view: g.ply,
      evals: saved.evals || [], notes: saved.notes || [], choices: saved.choices || [], endShown: g.status().over,
    });
  } catch { /* corrupt save: start fresh */ }
}

async function boot() {
  hydrateIcons();
  applyAppearance();
  const [pieces, bookText] = await Promise.all([loadPieces(), fetch(new URL('../data/openings.txt', import.meta.url)).then(r => r.text()).catch(() => '')]);
  book = bookText ? OpeningBook.parse(bookText) : null;
  board = new Board($('#board'), { onMove: onUserMove });
  graph = new EvalGraph($('#graph'), seek);
  restore();
  board.setOrientation(state.mode === 'play' ? state.playerColor : 'w');
  wire();
  render();
  document.body.classList.add('ready');
  engine.onStatus(() => render());
  await engine.init({ prefer: settings.engine });
  render();
  schedule();
  if (state.game.ply && state.mode === 'play' && !state.game.status().over) toast(`已恢复上次对局 · 第 ${Math.ceil(state.game.ply / 2)} 回合`);
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !/localhost|127\.0\.0\.1/.test(location.hostname)) navigator.serviceWorker.register('sw.js').catch(() => {});
}

boot().catch(e => { console.error(e); document.body.classList.add('ready'); toast('初始化失败：' + e.message, 'bad'); });
