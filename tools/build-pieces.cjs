const { chromium } = require('playwright') // npm i -D playwright, then: node tools/build-pieces.cjs <cm-chessboard standard.svg> web/data/pieces.json;
const fs = require('fs');
(async () => {
  const svg = fs.readFileSync(process.argv[2], 'utf8').replace(/<\?xml[^>]*\?>/, '');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.setContent(`<body>${svg}</body>`);
  const data = await page.evaluate(() => {
    const root = document.querySelector('svg');
    const r = n => +(+n).toFixed(3);
    const out = {};
    for (const id of ['wk','wq','wr','wb','wn','wp','bk','bq','br','bb','bn','bp']) {
      const g = document.getElementById(id); const parts = [];
      for (const el of g.querySelectorAll('path,line,polygon,polyline,ellipse,circle,rect')) {
        let d;
        const a = n => el.getAttribute(n);
        switch (el.tagName) {
          case 'path': d = a('d').replace(/\s+/g, ' ').trim(); break;
          case 'line': d = `M${a('x1')} ${a('y1')} L${a('x2')} ${a('y2')}`; break;
          case 'polygon': case 'polyline': {
            const p = a('points').trim().split(/[\s,]+/);
            d = 'M' + p[0] + ' ' + p[1]; for (let i = 2; i < p.length; i += 2) d += ` L${p[i]} ${p[i+1]}`;
            if (el.tagName === 'polygon') d += ' Z'; break; }
          case 'ellipse': case 'circle': {
            const cx = +a('cx'), cy = +a('cy'), rx = +(a('rx') ?? a('r')), ry = +(a('ry') ?? a('r'));
            const k = 0.5522847498, ox = rx * k, oy = ry * k;
            d = `M${cx - rx} ${cy} C${cx - rx} ${cy - oy} ${cx - ox} ${cy - ry} ${cx} ${cy - ry} C${cx + ox} ${cy - ry} ${cx + rx} ${cy - oy} ${cx + rx} ${cy} C${cx + rx} ${cy + oy} ${cx + ox} ${cy + ry} ${cx} ${cy + ry} C${cx - ox} ${cy + ry} ${cx - rx} ${cy + oy} ${cx - rx} ${cy} Z`; break; }
        }
        // round numbers in path data
        d = d.replace(/-?\d*\.?\d+(e-?\d+)?/g, m => String(r(m)));
        const cs = getComputedStyle(el);
        const m = el.getCTM(); const rm = root.getCTM().inverse().multiply(m);
        const fillOn = cs.fill !== 'none' && +cs.fillOpacity > 0;
        const col = c => { const v = c.match(/\d+/g); return v ? '#' + v.slice(0, 3).map(x => (+x).toString(16).padStart(2, '0')).join('') : null; };
        parts.push({
          d,
          fill: fillOn ? col(cs.fill) : null,
          stroke: cs.stroke !== 'none' ? col(cs.stroke) : null,
          width: r(parseFloat(cs.strokeWidth)),
          cap: cs.strokeLinecap, join: cs.strokeLinejoin,
          m: [rm.a, rm.b, rm.c, rm.d, rm.e, rm.f].map(r),
        });
      }
      out[id] = parts;
    }
    return out;
  });
  await browser.close();
  const header = { license: 'Chess pieces by Colin M.L. Burnett (Cburnett), CC BY-SA 3.0, via cm-chessboard (MIT). https://creativecommons.org/licenses/by-sa/3.0/', viewBox: 40 };
  fs.writeFileSync(process.argv[3], JSON.stringify({ ...header, pieces: data }));
})();
