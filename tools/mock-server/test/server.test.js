'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { createServer } = require('../server');

async function start(options) {
  const server = createServer(options);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base };
}

const json = (body) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

test('GET /items/{ref}', async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());

  const res = await fetch(`${base}/items/REF-0042`);
  assert.equal(res.status, 200);
  const item = await res.json();
  assert.equal(item.ref, 'REF-0042');
  assert.equal(item.quantity, 250);
  assert.equal(item.image_url, '/items/REF-0042/image');

  const img = await fetch(`${base}${item.image_url}`);
  assert.equal(img.status, 200);
  assert.match(img.headers.get('content-type'), /image\/svg\+xml/);

  assert.equal((await fetch(`${base}/items/NOPE`)).status, 404);
});

test('POST /operations puis /operations/batch', async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());

  const first = { op_id: randomUUID(), item_id: 'REF-0100', type: 'delta', value: -1, client_ts: '2026-10-22T13:55:12Z' };
  const r1 = await (await fetch(`${base}/operations`, json(first))).json();
  assert.equal(r1.status, 'applied');
  assert.equal(r1.quantity, 5);
  assert.ok(r1.server_received_at);

  const batch = {
    operations: [
      first, // renvoi : doublon
      { op_id: randomUUID(), item_id: 'REF-0100', type: 'delta', value: 2, client_ts: '2026-10-22T13:56:00Z' },
      { op_id: randomUUID(), item_id: 'INCONNU', type: 'delta', value: 1, client_ts: '2026-10-22T13:57:00Z' },
    ],
  };
  const r2 = await (await fetch(`${base}/operations/batch`, json(batch))).json();
  assert.deepEqual(r2.results.map((r) => r.status), ['duplicate', 'applied', 'rejected']);
  assert.equal(r2.results[2].reason, 'unknown_item');

  const item = await (await fetch(`${base}/items/REF-0100`)).json();
  assert.equal(item.quantity, 7);
});

test('requêtes mal formées', async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());

  const bad = await fetch(`${base}/operations`, { method: 'POST', body: '{pas du json' });
  assert.equal(bad.status, 400);
  const empty = await fetch(`${base}/operations/batch`, json({ operations: [] }));
  assert.equal(empty.status, 400);
});

test('authentification Bearer', async (t) => {
  const { server, base } = await start({ token: 's3cret' });
  t.after(() => server.close());

  assert.equal((await fetch(`${base}/health`)).status, 200);
  assert.equal((await fetch(`${base}/items/REF-0042`)).status, 401);
  const ok = await fetch(`${base}/items/REF-0042`, { headers: { Authorization: 'Bearer s3cret' } });
  assert.equal(ok.status, 200);
});

test('GET /items : recherche, nouveautés, pagination, CORS', async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());

  const all = await fetch(`${base}/items?limit=4`);
  assert.equal(all.headers.get('access-control-allow-origin'), '*');
  const page1 = await all.json();
  assert.equal(page1.items.length, 4);
  const page2 = await (await fetch(`${base}/items?limit=4&cursor=${page1.next_cursor}`)).json();
  assert.equal(page2.items.length, 2);
  assert.equal(page2.next_cursor, undefined);

  const search = await (await fetch(`${base}/items?q=w%C3%BCrth`)).json();
  assert.deepEqual(search.items.map((i) => i.ref).sort(), ['REF-0042', 'REF-0043']);

  const since = new Date().toISOString();
  await new Promise((r) => setTimeout(r, 5));
  const created = await fetch(`${base}/_debug/items`, json({ ref: 'REF-0500', name: 'Joint torique 20x2', service: 'Hydraulique' }));
  assert.equal(created.status, 201);
  const fresh = await (await fetch(`${base}/items?updated_since=${encodeURIComponent(since)}`)).json();
  assert.deepEqual(fresh.items.map((i) => i.ref), ['REF-0500']);

  const preflight = await fetch(`${base}/items`, { method: 'OPTIONS' });
  assert.equal(preflight.status, 204);
  assert.match(preflight.headers.get('access-control-allow-headers'), /Authorization/);
});
