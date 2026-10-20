// Advantage chart: win probability per ply, with mistake markers and click-to-seek.
import { winChance } from '../engine/review.js';
import { scoreToCp } from '../engine/personality.js';

const W = 600, H = 120;

export class EvalGraph {
  constructor(root, onSeek) {
    this.root = root; this.onSeek = onSeek;
    root.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" class="graph-svg" role="img" aria-label="评估曲线"></svg><div class="graph-tip" hidden></div><div class="graph-empty">走几步后，这里会出现双方优势曲线</div>`;
    this.svg = root.querySelector('svg'); this.tip = root.querySelector('.graph-tip'); this.empty = root.querySelector('.graph-empty');
    this.svg.addEventListener('pointermove', e => this.hover(e));
    this.svg.addEventListener('pointerleave', () => { this.tip.hidden = true; });
    this.svg.addEventListener('click', e => { const p = this.plyAt(e); if (p !== null) this.onSeek(p); });
  }

  plyAt(e) {
    if (!this.count) return null;
    const r = this.svg.getBoundingClientRect();
    return Math.max(0, Math.min(this.count, Math.round((e.clientX - r.left) / r.width * this.count)));
  }

  hover(e) {
    const p = this.plyAt(e);
    if (p === null) return;
    const ev = this.evals[p], label = this.labels[p] || '开局';
    const v = ev ? (ev.mate !== undefined ? `M${Math.abs(ev.mate)}` : (ev.cp >= 0 ? '+' : '') + (ev.cp / 100).toFixed(2)) : '…';
    this.tip.hidden = false;
    this.tip.textContent = `${label} · ${v}`;
    const r = this.svg.getBoundingClientRect();
    this.tip.style.left = Math.min(r.width - 90, Math.max(0, e.clientX - r.left - 40)) + 'px';
  }

  /** evals[i]: white-perspective score after ply i (0 = start); notes[i]: grade of ply i. */
  render(evals, notes, labels, current) {
    this.evals = evals; this.labels = labels;
    const n = Math.max(1, evals.length - 1); this.count = evals.length - 1;
    this.empty.hidden = evals.length > 2;
    const x = i => (i / n) * W;
    const y = ev => H - (winChance(scoreToCp(ev)) / 100) * H;
    const pts = [];
    let last = { cp: 0 };
    evals.forEach((ev, i) => { if (ev) last = ev; pts.push([x(i), y(last)]); });
    if (pts.length < 2) pts.push([W, H / 2]);
    // Smooth with a light Catmull-Rom → Bézier conversion.
    let d = `M${pts[0][0]},${pts[0][1]}`;
    for (let i = 0; i < pts.length - 1; i++) {
      const p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
      const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6], c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
      d += ` C${c1[0].toFixed(1)},${c1[1].toFixed(1)} ${c2[0].toFixed(1)},${c2[1].toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
    }
    const endX = pts[pts.length - 1][0];
    const area = `${d} L${endX},${H} L0,${H} Z`;
    const markers = notes.map((g, i) => g && ['mistake', 'blunder', 'inaccuracy'].includes(g.grade) && evals[i]
      ? `<circle class="mk ${g.grade}" cx="${x(i)}" cy="${y(evals[i])}" r="${g.grade === 'blunder' ? 5 : 4}"/>` : '').join('');
    const cx = x(Math.min(current, n));
    if (evals.length < 3) { this.svg.innerHTML = `<rect class="g-bg" width="${W}" height="${H}"/><line class="g-mid" x1="0" x2="${W}" y1="${H / 2}" y2="${H / 2}"/>`; return; }
    this.svg.innerHTML = `
      <defs><linearGradient id="gfill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--graph-white)" stop-opacity=".95"/><stop offset="1" stop-color="var(--graph-white)" stop-opacity=".55"/></linearGradient></defs>
      <rect class="g-bg" width="${W}" height="${H}"/>
      <path class="g-area" d="${area}" fill="url(#gfill)"/>
      <line class="g-mid" x1="0" x2="${W}" y1="${H / 2}" y2="${H / 2}"/>
      <path class="g-line" d="${d}"/>
      <line class="g-cursor" x1="${cx}" x2="${cx}" y1="0" y2="${H}"/>
      ${markers}`;
  }
}
