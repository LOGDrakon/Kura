'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { Store } = require('../lib/store');

const op = (type, value, client_ts, extra = {}) => ({
  op_id: randomUUID(),
  item_id: 'A',
  type,
  value,
  client_ts,
  ...extra,
});

const fresh = () => new Store([{ ref: 'A', name: 'Article A', quantity: 10 }]);

test('les delta s\'additionnent', () => {
  const s = fresh();
  s.apply(op('delta', -1, '2026-10-22T10:00:00Z'));
  s.apply(op('delta', -1, '2026-10-22T10:00:01Z'));
  s.apply(op('delta', 5, '2026-10-22T10:00:02Z'));
  assert.equal(s.getItem('A').quantity, 13);
});

test('un op_id renvoyé est signalé duplicate et non réappliqué', () => {
  const s = fresh();
  const o = op('delta', -3, '2026-10-22T10:00:00Z');
  assert.equal(s.apply(o).status, 'applied');
  const again = s.apply({ ...o });
  assert.equal(again.status, 'duplicate');
  assert.equal(again.quantity, 7);
  assert.equal(s.getItem('A').quantity, 7);
});

test('le set le plus récent gagne, quel que soit l\'ordre d\'arrivée', () => {
  const s = fresh();
  assert.equal(s.apply(op('set', 50, '2026-10-22T12:00:00Z')).status, 'applied');
  const late = s.apply(op('set', 20, '2026-10-22T11:00:00Z'));
  assert.equal(late.status, 'superseded');
  assert.equal(s.getItem('A').quantity, 50);
});

test('un set efface les delta antérieurs mais pas les postérieurs', () => {
  const s = fresh();
  // Arrivées volontairement dans le désordre (file offline vidée tardivement).
  s.apply(op('delta', -2, '2026-10-22T13:00:00Z')); // après le set : compte
  s.apply(op('set', 30, '2026-10-22T12:00:00Z'));
  s.apply(op('delta', -5, '2026-10-22T11:00:00Z')); // avant le set : absorbé
  assert.equal(s.getItem('A').quantity, 28);
});

test('ordre d\'arrivée indifférent : toutes les permutations donnent le même stock', () => {
  const ops = [
    op('delta', -1, '2026-10-22T09:00:00Z'),
    op('set', 40, '2026-10-22T10:00:00Z'),
    op('delta', 3, '2026-10-22T11:00:00Z'),
    op('set', 15, '2026-10-22T08:00:00Z'),
    op('delta', -4, '2026-10-22T12:00:00Z'),
  ];
  const permutations = (arr) =>
    arr.length <= 1 ? [arr] : arr.flatMap((x, i) => permutations([...arr.slice(0, i), ...arr.slice(i + 1)]).map((p) => [x, ...p]));
  for (const order of permutations(ops)) {
    const s = fresh();
    order.forEach((o) => s.apply(o));
    assert.equal(s.getItem('A').quantity, 39);
  }
});

test('deux set au même horodatage : départage déterministe par op_id', () => {
  const ts = '2026-10-22T10:00:00Z';
  const a = op('set', 1, ts, { op_id: '00000000-0000-4000-8000-000000000001' });
  const b = op('set', 2, ts, { op_id: '00000000-0000-4000-8000-000000000002' });
  for (const order of [[a, b], [b, a]]) {
    const s = fresh();
    order.forEach((o) => s.apply(o));
    assert.equal(s.getItem('A').quantity, 2);
  }
});

test('rejets : validation et article inconnu', () => {
  const s = fresh();
  assert.equal(s.apply({}).reason, 'invalid');
  assert.equal(s.apply(op('delta', 0, '2026-10-22T10:00:00Z')).reason, 'invalid');
  assert.equal(s.apply(op('set', -1, '2026-10-22T10:00:00Z')).reason, 'invalid');
  assert.equal(s.apply(op('delta', 1.5, '2026-10-22T10:00:00Z')).reason, 'invalid');
  assert.equal(s.apply(op('delta', 1, 'hier')).reason, 'invalid');
  assert.equal(s.apply({ ...op('delta', 1, '2026-10-22T10:00:00Z'), op_id: 'abc' }).reason, 'invalid');
  assert.equal(s.apply(op('delta', 1, '2026-10-22T10:00:00Z', { item_id: 'ZZZ' })).reason, 'unknown_item');
  assert.equal(s.getItem('A').quantity, 10);
});
