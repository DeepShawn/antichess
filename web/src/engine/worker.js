// Built-in engine as a Web Worker that speaks a UCI subset, so the app can
// drive it exactly like Stockfish: uci, isready, ucinewgame, setoption MultiPV,
// position [startpos|fen …] [moves …], go [depth N] [movetime N] [infinite], stop.
import { Position, START_FEN } from '../core/chess.js';
import { Searcher, uciScore } from './search.js';

const searcher = new Searcher();
let position = new Position(), keys = [position.key], multipv = 1;
const send = line => postMessage(line);

function setPosition(args) {
  const movesAt = args.indexOf('moves');
  const head = movesAt < 0 ? args : args.slice(0, movesAt);
  position = new Position(head[0] === 'fen' ? head.slice(1).join(' ') : START_FEN);
  keys = [position.key];
  if (movesAt >= 0) for (const uci of args.slice(movesAt + 1)) {
    const m = position.parseMove(uci);
    if (!m) break;
    position.make(m); keys.push(position.key);
  }
}

function go(args) {
  const opt = name => { const i = args.indexOf(name); return i >= 0 ? +args[i + 1] : undefined; };
  const infinite = args.includes('infinite');
  const depth = opt('depth') ?? (infinite ? 64 : 64);
  const movetime = opt('movetime') ?? (infinite ? 20000 : opt('depth') ? 60000 : 1500);
  const result = searcher.search(position, {
    depth, movetime, multipv, repetitions: keys,
    onInfo(lines, info) {
      const nps = Math.round(info.nodes / Math.max(1, info.time) * 1000);
      lines.forEach((l, i) => {
        const s = uciScore(l.score);
        const score = s.mate !== undefined ? `mate ${s.mate}` : `cp ${s.cp}`;
        send(`info depth ${info.depth} seldepth ${info.seldepth} multipv ${i + 1} score ${score} nodes ${info.nodes} nps ${nps} time ${info.time} pv ${l.pv.map(m => position.uci(m)).join(' ')}`);
      });
    },
  });
  send(`bestmove ${result.bestmove ? position.uci(result.bestmove) : '(none)'}`);
}

onmessage = ({ data }) => {
  const [cmd, ...args] = String(data).trim().split(/\s+/);
  switch (cmd) {
    case 'uci': send('id name PuZhe Engine 2'); send('id author DeepShawn'); send('option name MultiPV type spin default 1 min 1 max 8'); send('uciok'); break;
    case 'isready': send('readyok'); break;
    case 'ucinewgame': searcher.clear(); break;
    case 'setoption': { const v = args.indexOf('value'); if (/multipv/i.test(args.slice(1, v).join(' '))) multipv = Math.max(1, +args[v + 1] || 1); break; }
    case 'position': setPosition(args); break;
    case 'go': go(args); break;
    default: break; // 'stop' is handled by the host terminating a busy worker.
  }
};
