// UCI client that drives either Stockfish 19 (WASM) or the built-in worker.
// Jobs are serialised: starting a new analysis stops the previous one and
// waits for its `bestmove` before the next `go`, so stale output never leaks.

const STOCKFISH_URL = new URL('../../vendor/stockfish/stockfish-19-lite-single.js', import.meta.url);
const BUILTIN_URL = new URL('./worker.js', import.meta.url);

export function parseInfo(line) {
  if (!line.startsWith('info ') || !line.includes(' pv ')) return null;
  const t = line.split(' ');
  const num = k => { const i = t.indexOf(k); return i > 0 ? +t[i + 1] : undefined; };
  const s = t.indexOf('score');
  if (s < 0 || t[s + 2] === undefined) return null;
  const info = { depth: num('depth') || 0, multipv: num('multipv') || 1, nodes: num('nodes') || 0, nps: num('nps') || 0, pv: t.slice(t.indexOf('pv') + 1) };
  if (t[s + 1] === 'mate') info.mate = +t[s + 2]; else info.cp = +t[s + 2];
  if (t[s + 3] === 'lowerbound' || t[s + 3] === 'upperbound') info.bound = true;
  return info;
}

export class EngineClient {
  constructor() {
    this.kind = null; this.name = '引擎加载中'; this.worker = null;
    this.job = null; this.chain = Promise.resolve(); this.multipv = 1;
    this.listeners = new Set();
  }

  onStatus(fn) { this.listeners.add(fn); }
  emit() { for (const fn of this.listeners) fn(this); }

  async init({ prefer = 'stockfish' } = {}) {
    if (prefer === 'stockfish' && await this.boot('stockfish')) return this;
    await this.boot('builtin');
    return this;
  }

  boot(kind) {
    return new Promise(resolve => {
      let worker;
      try {
        worker = kind === 'stockfish' ? new Worker(STOCKFISH_URL) : new Worker(BUILTIN_URL, { type: 'module' });
      } catch { resolve(false); return; }
      const timer = setTimeout(() => { worker.terminate(); resolve(false); }, kind === 'stockfish' ? 12000 : 5000);
      worker.onerror = () => { clearTimeout(timer); worker.terminate(); resolve(false); };
      worker.onmessage = ({ data }) => {
        const line = String(data);
        if (line.startsWith('id name')) this.pendingName = line.slice(8);
        if (line === 'uciok') {
          clearTimeout(timer);
          this.worker?.terminate();
          this.worker = worker; this.kind = kind; this.multipv = 1;
          this.name = kind === 'stockfish' ? 'Stockfish 19 · WASM' : '谱着内置引擎';
          worker.onmessage = ({ data: d }) => this.handle(String(d));
          worker.onerror = () => this.recover();
          this.emit();
          resolve(true);
        }
      };
      worker.postMessage('uci');
    });
  }

  async recover() {
    // A crashed Stockfish falls back to the built-in engine rather than leaving the game stuck.
    const job = this.job; this.job = null;
    await this.boot('builtin');
    job?.finish(true);
  }

  handle(line) {
    const job = this.job;
    if (!job) return;
    if (line.startsWith('bestmove')) { job.bestmove = line.split(' ')[1]; job.finish(false); return; }
    const info = parseInfo(line);
    if (!info || info.bound) return;
    // Lines are replaced in place as each depth arrives; clearing them on a new depth made
    // the candidate list shrink and regrow (layout jitter) and could leave a single line at stop.
    if (info.multipv === 1) job.depth = Math.max(job.depth, info.depth);
    job.lines[info.multipv - 1] = info;
    job.onInfo?.(job.snapshot());
  }

  /**
   * Analyse a position. Resolves with {bestmove, lines, depth, cancelled}.
   * Score in each line is from the side to move (UCI convention).
   */
  analyze({ fen, moves = [], multipv = 1, depth, movetime, infinite = false }, onInfo) {
    this.cancel();
    const run = async () => {
      if (!this.worker) await this.boot(this.kind || 'builtin');
      return new Promise(resolve => {
        const job = {
          lines: [], depth: 0, onInfo, cancelled: false,
          snapshot: () => ({ depth: job.depth, lines: job.lines.filter(Boolean).map(l => ({ ...l })), engine: this.name }),
          finish: cancelled => {
            if (this.job !== job) return;
            this.job = null;
            resolve({ bestmove: job.bestmove, cancelled: cancelled || job.cancelled, ...job.snapshot() });
          },
        };
        this.job = job;
        if (this.multipv !== multipv) { this.worker.postMessage(`setoption name MultiPV value ${multipv}`); this.multipv = multipv; }
        this.worker.postMessage(`position fen ${fen}${moves.length ? ' moves ' + moves.join(' ') : ''}`);
        const go = infinite ? 'go infinite' : `go${depth ? ' depth ' + depth : ''}${movetime ? ' movetime ' + movetime : ''}`;
        this.worker.postMessage(go);
      });
    };
    const p = this.chain.then(run, run);
    this.chain = p.catch(() => {});
    return p;
  }

  /** Stops the running job; its promise resolves with cancelled=true and the best lines so far. */
  cancel() {
    const job = this.job;
    if (!job) return;
    job.cancelled = true;
    if (this.kind === 'stockfish') { this.worker.postMessage('stop'); return; }
    // The built-in worker searches synchronously: resolve now and replace the worker.
    job.finish(true);
    this.worker.terminate(); this.worker = null;
    this.chain = this.chain.then(() => this.boot('builtin'));
  }

  newGame() { if (this.worker && !this.job) this.worker.postMessage('ucinewgame'); }
}
