// Renders the shared vector piece set (web/data/pieces.json, also used by Android).
let SET = null;

export async function loadPieces(url = new URL('../../data/pieces.json', import.meta.url)) {
  SET = await (await fetch(url)).json();
  return SET;
}

const cache = new Map();

/** SVG markup for a piece code like "wq" or "bn". */
export function pieceSvg(code) {
  if (cache.has(code)) return cache.get(code);
  const parts = SET.pieces[code].map(p => {
    const attrs = [
      `d="${p.d}"`,
      `fill="${p.fill ?? 'none'}"`,
      p.stroke ? `stroke="${p.stroke}" stroke-width="${p.width}" stroke-linecap="${p.cap}" stroke-linejoin="${p.join}"` : '',
      `transform="matrix(${p.m.join(' ')})"`,
    ];
    return `<path ${attrs.join(' ')}/>`;
  }).join('');
  const svg = `<svg viewBox="0 0 ${SET.viewBox} ${SET.viewBox}" aria-hidden="true">${parts}</svg>`;
  cache.set(code, svg);
  return svg;
}
