// Interactive board: animated pieces, drag & click moves, promotion picker,
// engine arrows and right-click annotations (drag = arrow, click = circle).
import { pieceSvg } from './pieces.js';

const FILES = 'abcdefgh';

export class Board {
  /**
   * @param {HTMLElement} root
   * @param {{onMove:(m:{from:string,to:string,promotion?:string})=>void, onSelect?:Function}} handlers
   */
  constructor(root, handlers) {
    this.root = root; this.handlers = handlers;
    this.orientation = 'w'; this.pieces = new Map(); this.selected = null;
    this.dests = new Map(); this.movable = null; this.marks = { arrows: [], circles: new Set() };
    this.engineArrows = [];
    root.classList.add('board');
    root.innerHTML = `<div class="squares"></div><div class="pieces"></div><svg class="arrows" viewBox="0 0 8 8" aria-hidden="true"></svg><div class="promo" hidden></div>`;
    this.squaresEl = root.querySelector('.squares');
    this.piecesEl = root.querySelector('.pieces');
    this.arrowsEl = root.querySelector('.arrows');
    this.promoEl = root.querySelector('.promo');
    this.squareEls = new Map();
    for (let i = 0; i < 64; i++) {
      const el = document.createElement('div');
      el.className = 'sq';
      this.squaresEl.appendChild(el);
    }
    this.layoutSquares();
    root.addEventListener('pointerdown', e => this.pointerDown(e));
    root.addEventListener('contextmenu', e => e.preventDefault());
  }

  coords(sq) {
    const file = FILES.indexOf(sq[0]), rank = +sq[1];
    return this.orientation === 'w' ? [file, 8 - rank] : [7 - file, rank - 1];
  }

  squareAt(clientX, clientY) {
    const r = this.root.getBoundingClientRect();
    const col = Math.floor((clientX - r.left) / r.width * 8), row = Math.floor((clientY - r.top) / r.height * 8);
    if (col < 0 || col > 7 || row < 0 || row > 7) return null;
    return this.orientation === 'w' ? FILES[col] + (8 - row) : FILES[7 - col] + (row + 1);
  }

  layoutSquares() {
    this.squareEls.clear();
    [...this.squaresEl.children].forEach((el, i) => {
      const col = i % 8, row = i >> 3;
      const sq = this.orientation === 'w' ? FILES[col] + (8 - row) : FILES[7 - col] + (row + 1);
      const light = (FILES.indexOf(sq[0]) + +sq[1]) % 2 === 1;
      el.className = 'sq ' + (light ? 'light' : 'dark');
      el.dataset.square = sq;
      el.innerHTML = (row === 7 ? `<span class="coord file">${sq[0]}</span>` : '') + (col === 0 ? `<span class="coord rank">${sq[1]}</span>` : '');
      this.squareEls.set(sq, el);
    });
  }

  setOrientation(color) {
    if (color === this.orientation) return;
    this.orientation = color;
    this.root.classList.add('flipping');
    this.layoutSquares();
    for (const [sq, el] of this.pieces) this.place(el, sq);
    this.renderHighlights(); this.renderArrows();
    setTimeout(() => this.root.classList.remove('flipping'), 350);
  }

  place(el, sq) { const [x, y] = this.coords(sq); el.style.setProperty('--x', x); el.style.setProperty('--y', y); el.dataset.square = sq; }

  createPiece(code, sq) {
    const el = document.createElement('div');
    el.className = 'piece'; el.dataset.code = code; el.innerHTML = pieceSvg(code);
    this.place(el, sq);
    this.piecesEl.appendChild(el);
    return el;
  }

  /**
   * Sync to a position. `board` is an 8×8 array (rank 8 first) of {type,color}|null.
   * Pieces that changed square are matched to the nearest previous piece of the same kind and slid there.
   */
  setPosition(board, { lastMove = null, check = null, animate = true } = {}) {
    const next = new Map();
    board.forEach((row, r) => row.forEach((p, c) => { if (p) next.set(FILES[c] + (8 - r), p.color + p.type); }));
    const sig = [...next].join();
    if (sig === this.sig) { this.lastMove = lastMove; this.check = check; this.renderHighlights(); return; }
    this.sig = sig;
    const old = new Map(this.pieces), result = new Map(), pending = [];
    for (const [sq, code] of next) {
      const el = old.get(sq);
      if (el && el.dataset.code === code) { result.set(sq, el); old.delete(sq); } else pending.push([sq, code]);
    }
    const dist = (a, b) => Math.hypot(FILES.indexOf(a[0]) - FILES.indexOf(b[0]), a[1] - b[1]);
    for (const [sq, code] of pending) {
      let bestSq = null;
      for (const [osq, el] of old) if (el.dataset.code === code && (!bestSq || dist(osq, sq) < dist(bestSq, sq))) bestSq = osq;
      let el;
      if (bestSq && animate) { el = old.get(bestSq); old.delete(bestSq); this.place(el, sq); el.classList.add('moving'); setTimeout(() => el.classList.remove('moving'), 260); }
      else { el = this.createPiece(code, sq); if (animate) { el.classList.add('appear'); requestAnimationFrame(() => el.classList.remove('appear')); } }
      result.set(sq, el);
    }
    for (const el of old.values()) {
      if (animate) { el.classList.add('captured'); setTimeout(() => el.remove(), 260); } else el.remove();
    }
    this.pieces = result;
    this.lastMove = lastMove; this.check = check;
    this.selected = null;
    this.renderHighlights();
  }

  /** dests: Map<from, Array<{to, promotion?:boolean}>>; movable: 'w' | 'b' | 'both' | null */
  setInteractive(movable, dests) { this.movable = movable; this.dests = dests || new Map(); this.renderHighlights(); }

  setEngineArrows(arrows) { this.engineArrows = arrows; this.renderArrows(); }

  clearMarks() { if (this.marks.arrows.length || this.marks.circles.size) { this.marks = { arrows: [], circles: new Set() }; this.renderArrows(); } }

  renderHighlights() {
    for (const [sq, el] of this.squareEls) {
      el.classList.toggle('last', !!this.lastMove && (this.lastMove.from === sq || this.lastMove.to === sq));
      el.classList.toggle('check', this.check === sq);
      el.classList.toggle('selected', this.selected === sq);
      el.classList.remove('dest', 'dest-capture');
    }
    if (this.selected) for (const d of this.dests.get(this.selected) || []) {
      this.squareEls.get(d.to).classList.add(this.pieces.has(d.to) ? 'dest-capture' : 'dest');
    }
    this.root.classList.toggle('interactive', !!this.movable);
  }

  arrowShape(from, to, width) {
    const [x1, y1] = this.coords(from).map(v => v + 0.5), [x2, y2] = this.coords(to).map(v => v + 0.5);
    const dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy), ux = dx / len, uy = dy / len, px = -uy, py = ux;
    const head = 0.42, hw = width * 1.9, w = width / 2, sx = x1 + ux * 0.22, sy = y1 + uy * 0.22, bx = x2 - ux * head, by = y2 - uy * head;
    const tipx = x2 - ux * 0.08, tipy = y2 - uy * 0.08;
    const pts = [[sx + px * w, sy + py * w], [bx + px * w, by + py * w], [bx + px * hw, by + py * hw], [tipx, tipy], [bx - px * hw, by - py * hw], [bx - px * w, by - py * w], [sx - px * w, sy - py * w]];
    return pts.map(p => p.map(v => v.toFixed(3)).join(',')).join(' ');
  }

  renderArrows() {
    const parts = [];
    for (const a of this.engineArrows) {
      parts.push(`<polygon class="arrow ${a.kind || ''}" style="opacity:${a.opacity ?? 0.85}" points="${this.arrowShape(a.from, a.to, a.width ?? 0.16)}"/>`);
      if (a.label) { const [x, y] = this.coords(a.to); parts.push(`<g class="arrow-label ${a.kind || ''}"><circle cx="${x + 0.84}" cy="${y + 0.16}" r="0.15"/><text x="${x + 0.84}" y="${y + 0.2}">${a.label}</text></g>`); }
    }
    for (const a of this.marks.arrows) parts.push(`<polygon class="arrow user" points="${this.arrowShape(a.from, a.to, 0.15)}"/>`);
    for (const sq of this.marks.circles) { const [x, y] = this.coords(sq); parts.push(`<circle class="mark-circle" cx="${x + 0.5}" cy="${y + 0.5}" r="0.44"/>`); }
    this.arrowsEl.innerHTML = parts.join('');
  }

  canPick(sq) {
    const el = this.pieces.get(sq);
    if (!el || !this.movable) return false;
    return this.movable === 'both' || el.dataset.code[0] === this.movable;
  }

  select(sq) { this.selected = sq; this.renderHighlights(); this.handlers.onSelect?.(sq); }

  pointerDown(e) {
    if (e.button === 2) return this.annotate(e);
    if (e.button !== 0) return;
    this.clearMarks();
    const sq = this.squareAt(e.clientX, e.clientY);
    if (!sq || !this.promoEl.hidden) return;
    if (this.selected && sq !== this.selected && (this.dests.get(this.selected) || []).some(d => d.to === sq)) {
      this.tryMove(this.selected, sq);
      return;
    }
    if (!this.canPick(sq)) { if (this.selected) this.select(null); return; }
    const wasSelected = this.selected === sq;
    this.select(sq);
    const el = this.pieces.get(sq), rect = this.root.getBoundingClientRect(), size = rect.width / 8;
    let dragging = false;
    const startX = e.clientX, startY = e.clientY;
    this.root.setPointerCapture(e.pointerId);
    const move = ev => {
      if (!dragging && Math.hypot(ev.clientX - startX, ev.clientY - startY) < 4) return;
      if (!dragging) { dragging = true; el.classList.add('dragging'); }
      el.style.transform = `translate(${ev.clientX - rect.left - size / 2}px, ${ev.clientY - rect.top - size / 2}px) scale(1.12)`;
      const over = this.squareAt(ev.clientX, ev.clientY);
      for (const [s, sel] of this.squareEls) sel.classList.toggle('hover', s === over && over !== sq);
    };
    const up = ev => {
      this.root.removeEventListener('pointermove', move); this.root.removeEventListener('pointerup', up); this.root.removeEventListener('pointercancel', up);
      for (const sel of this.squareEls.values()) sel.classList.remove('hover');
      if (!dragging) { if (wasSelected) this.select(null); return; }
      el.classList.remove('dragging'); el.style.transform = '';
      const target = this.squareAt(ev.clientX, ev.clientY);
      if (target && target !== sq && (this.dests.get(sq) || []).some(d => d.to === target)) {
        el.classList.add('dropped'); this.tryMove(sq, target, true);
        setTimeout(() => el.classList.remove('dropped'), 50);
      }
    };
    this.root.addEventListener('pointermove', move); this.root.addEventListener('pointerup', up); this.root.addEventListener('pointercancel', up);
  }

  async tryMove(from, to) {
    const dest = (this.dests.get(from) || []).find(d => d.to === to);
    let promotion;
    if (dest?.promotion) {
      promotion = await this.askPromotion(to, this.pieces.get(from).dataset.code[0]);
      if (!promotion) { this.select(null); return; }
    }
    this.selected = null;
    this.handlers.onMove({ from, to, promotion });
  }

  askPromotion(to, color) {
    return new Promise(resolve => {
      const [x, y] = this.coords(to), down = y === 0;
      this.promoEl.hidden = false;
      this.promoEl.innerHTML = ['q', 'n', 'r', 'b'].map((t, i) =>
        `<button class="promo-choice" data-type="${t}" style="--x:${x};--y:${down ? i : 7 - i}" aria-label="升变">${pieceSvg(color + t)}</button>`).join('');
      const done = v => { this.promoEl.hidden = true; this.promoEl.innerHTML = ''; resolve(v); };
      this.promoEl.onpointerdown = ev => { ev.stopPropagation(); const b = ev.target.closest('.promo-choice'); done(b ? b.dataset.type : null); };
    });
  }

  annotate(e) {
    const from = this.squareAt(e.clientX, e.clientY);
    if (!from) return;
    this.root.setPointerCapture(e.pointerId);
    const up = ev => {
      this.root.removeEventListener('pointerup', up);
      const to = this.squareAt(ev.clientX, ev.clientY);
      if (!to) return;
      if (to === from) { this.marks.circles.has(from) ? this.marks.circles.delete(from) : this.marks.circles.add(from); }
      else {
        const i = this.marks.arrows.findIndex(a => a.from === from && a.to === to);
        if (i >= 0) this.marks.arrows.splice(i, 1); else this.marks.arrows.push({ from, to });
      }
      this.renderArrows();
    };
    this.root.addEventListener('pointerup', up);
  }
}
