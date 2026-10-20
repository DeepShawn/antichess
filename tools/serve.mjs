// Tiny static server for local development: `npm run serve` → http://localhost:4173
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const root = new URL('../web/', import.meta.url).pathname;
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.png': 'image/png', '.txt': 'text/plain', '.webmanifest': 'application/manifest+json' };
const port = +process.env.PORT || 4173;

createServer(async (req, res) => {
  let path;
  try { path = normalize(decodeURIComponent(req.url.split('?')[0])).replace(/^(\.\.[/\\])+/, ''); } catch { res.writeHead(400); res.end('Bad request'); return; }
  const file = join(root, path.endsWith('/') ? path + 'index.html' : path);
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': /^(text|application\/(json|manifest))/.test(types[extname(file)]) ? types[extname(file)] + '; charset=utf-8' : types[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(body);
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(port, () => console.log(`谱着 Chess Lab → http://localhost:${port}`));
