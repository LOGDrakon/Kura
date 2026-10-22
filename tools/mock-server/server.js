'use strict';

// Kura Mock Server : implémentation de référence du contrat (spec/api-contract.yaml).
// Zéro dépendance, données en mémoire (ou dans un fichier JSON avec KURA_MOCK_DB).
// Ne pas utiliser en production.

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { Store } = require('./lib/store');

const ADMIN_PAGE = path.join(__dirname, 'admin.html');

const MAX_BODY = 1024 * 1024;
const MAX_BATCH = 500;

// Kura Studio est une page web : il appelle le serveur depuis le navigateur.
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Api-Key',
  'Access-Control-Max-Age': '600',
};

function loadItems(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function send(res, status, body, headers = {}) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, {
    ...CORS,
    'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json; charset=utf-8',
    ...headers,
  });
  res.end(payload);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('corps trop volumineux'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(Object.assign(new Error('JSON illisible'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function placeholderImage(item) {
  const label = String(item.name || item.ref).replace(/[<>&"]/g, '');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200">
  <rect width="320" height="200" fill="#e8edf2"/>
  <text x="160" y="95" font-family="sans-serif" font-size="18" text-anchor="middle" fill="#334">${label}</text>
  <text x="160" y="125" font-family="monospace" font-size="14" text-anchor="middle" fill="#667">${item.ref}</text>
</svg>`;
}

function createServer({
  items = loadItems(path.join(__dirname, 'data', 'items.json')),
  token = null,
  config = null,
  latencyMs = 0,
  log = () => {},
  dbFile = null,
} = {}) {
  const store = dbFile && fs.existsSync(dbFile)
    ? Store.fromSnapshot(JSON.parse(fs.readFileSync(dbFile, 'utf8')))
    : new Store(items);

  // Écriture atomique : un arrêt brutal ne laisse jamais un fichier à moitié écrit.
  function persist() {
    if (!dbFile) return;
    const tmp = `${dbFile}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(store.snapshot(), null, 2));
    fs.renameSync(tmp, dbFile);
  }
  persist();

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
    log(`${req.method} ${url.pathname}`);

    if (latencyMs) await new Promise((r) => setTimeout(r, latencyMs));

    if (req.method === 'OPTIONS') {
      res.writeHead(204, CORS);
      return res.end();
    }

    if (req.method === 'GET' && url.pathname === '/health') {
      return send(res, 200, { status: 'ok' });
    }

    // Vue « base de l'entreprise » pour les essais : articles, stock, journal (hors contrat).
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/_admin')) {
      return send(res, 200, fs.readFileSync(ADMIN_PAGE, 'utf8'), { 'Content-Type': 'text/html; charset=utf-8' });
    }

    if (token && req.headers.authorization !== `Bearer ${token}`) {
      return send(res, 401, { error: 'unauthorized', message: 'Bearer token absent ou invalide' });
    }

    try {
      if (req.method === 'GET' && url.pathname === '/items') {
        const p = url.searchParams;
        return send(res, 200, store.listItems({
          q: p.get('q') ?? '',
          updated_since: p.get('updated_since'),
          limit: p.get('limit') ?? 50,
          cursor: p.get('cursor'),
        }));
      }

      // Simule une nouvelle entrée créée dans la GMAO (hors contrat).
      if (req.method === 'POST' && url.pathname === '/_debug/items') {
        const item = store.upsertItem(await readJson(req));
        if (!item) return send(res, 400, { error: 'invalid', message: "'ref' obligatoire" });
        persist();
        return send(res, 201, item);
      }

      if (req.method === 'GET' && parts[0] === 'items' && parts.length === 2) {
        const item = store.getItem(parts[1]);
        if (!item) return send(res, 404, { error: 'not_found', message: `article inconnu : ${parts[1]}` });
        return send(res, 200, { ...item, image_url: `/items/${encodeURIComponent(item.ref)}/image` });
      }

      if (req.method === 'GET' && parts[0] === 'items' && parts[2] === 'image' && parts.length === 3) {
        const item = store.getItem(parts[1]);
        if (!item) return send(res, 404, { error: 'not_found' });
        return send(res, 200, placeholderImage(item), {
          'Content-Type': 'image/svg+xml',
          'Cache-Control': 'max-age=86400',
          ETag: `"${item.ref}"`,
        });
      }

      // Journal d'un article : pratique pour vérifier ce que l'appli a envoyé (hors contrat).
      if (req.method === 'GET' && parts[0] === '_debug' && parts[1] === 'journal' && parts.length === 3) {
        const journal = store.journal(parts[2]);
        if (!journal) return send(res, 404, { error: 'not_found' });
        return send(res, 200, { ref: parts[2], operations: journal });
      }

      if (req.method === 'POST' && url.pathname === '/operations') {
        const result = store.apply(await readJson(req));
        if (result.status === 'applied' || result.status === 'superseded') persist();
        return send(res, 200, result);
      }

      if (req.method === 'POST' && url.pathname === '/operations/batch') {
        const body = await readJson(req);
        const ops = body && body.operations;
        if (!Array.isArray(ops) || ops.length === 0 || ops.length > MAX_BATCH) {
          return send(res, 400, {
            error: 'invalid',
            message: `'operations' doit être une liste de 1 à ${MAX_BATCH} éléments`,
          });
        }
        const results = ops.map((op) => store.apply(op));
        if (results.some((r) => r.status === 'applied' || r.status === 'superseded')) persist();
        return send(res, 200, { results });
      }

      if (req.method === 'GET' && url.pathname === '/config') {
        if (!config) return send(res, 404, { error: 'not_found', message: 'pas de config distante' });
        return send(res, 200, { config });
      }

      return send(res, 404, { error: 'not_found' });
    } catch (err) {
      const status = err.status || 500;
      return send(res, status, { error: status === 500 ? 'server_error' : 'invalid', message: err.message });
    }
  });

  server.store = store;
  return server;
}

module.exports = { createServer };

if (require.main === module) {
  const port = Number(process.env.PORT || 8787);
  const host = process.env.HOST || '0.0.0.0';
  const configFile = process.env.KURA_MOCK_CONFIG;
  const server = createServer({
    items: loadItems(process.env.KURA_MOCK_ITEMS || path.join(__dirname, 'data', 'items.json')),
    token: process.env.KURA_MOCK_TOKEN || null,
    config: configFile ? fs.readFileSync(configFile, 'utf8') : null,
    latencyMs: Number(process.env.KURA_MOCK_LATENCY_MS || 0),
    log: (line) => console.log(`${new Date().toISOString()} ${line}`),
    dbFile: process.env.KURA_MOCK_DB || null,
  });
  server.listen(port, host, () => {
    console.log(`Kura Mock Server sur http://${host}:${port} (base : http://localhost:${port}/)`);
    if (process.env.KURA_MOCK_DB) console.log(`Données enregistrées dans ${process.env.KURA_MOCK_DB}`);
    if (process.env.KURA_MOCK_TOKEN) console.log('Authentification Bearer activée');
  });
}
