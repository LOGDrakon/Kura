'use strict';

// Logique du stock, indépendante du HTTP.
// Règle (voir spec/api-contract.yaml) :
//   quantité = set le plus récent (ou stock initial) + somme des delta postérieurs à ce set.
// Le calcul est refait à partir du journal, donc l'ordre d'arrivée des opérations n'a pas d'effet.

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function compareSet(a, b) {
  if (a.ts !== b.ts) return a.ts - b.ts;
  return a.op_id < b.op_id ? -1 : a.op_id > b.op_id ? 1 : 0;
}

function validate(op) {
  if (!op || typeof op !== 'object' || Array.isArray(op)) return 'opération absente ou non objet';
  if (typeof op.op_id !== 'string' || !UUID_RE.test(op.op_id)) return 'op_id doit être un UUID';
  if (typeof op.item_id !== 'string' || op.item_id.length === 0 || op.item_id.length > 128) {
    return 'item_id invalide';
  }
  if (op.type !== 'delta' && op.type !== 'set') return "type doit valoir 'delta' ou 'set'";
  if (!Number.isInteger(op.value)) return 'value doit être un entier';
  if (op.type === 'delta' && op.value === 0) return 'un delta ne peut pas être nul';
  if (op.type === 'set' && op.value < 0) return 'un set ne peut pas être négatif';
  if (typeof op.client_ts !== 'string' || Number.isNaN(Date.parse(op.client_ts))) {
    return 'client_ts doit être une date ISO 8601';
  }
  return null;
}

class Store {
  constructor(items, { now = () => new Date() } = {}) {
    this.now = now;
    this.items = new Map();
    for (const item of items) {
      this.items.set(item.ref, { ...item, initial: item.quantity ?? 0, ops: [] });
    }
    this.seen = new Map(); // op_id -> résultat déjà renvoyé
  }

  getItem(ref) {
    const entry = this.items.get(ref);
    if (!entry) return null;
    const { initial, ops, ...item } = entry;
    return { ...item, quantity: this.quantityOf(entry) };
  }

  quantityOf(entry) {
    let base = entry.initial;
    let lastSet = null;
    for (const op of entry.ops) {
      if (op.type === 'set' && (!lastSet || compareSet(op, lastSet) > 0)) lastSet = op;
    }
    if (lastSet) base = lastSet.value;
    let sum = 0;
    for (const op of entry.ops) {
      if (op.type === 'delta' && (!lastSet || op.ts > lastSet.ts)) sum += op.value;
    }
    return base + sum;
  }

  apply(op) {
    const server_received_at = this.now().toISOString();
    const op_id = op && typeof op.op_id === 'string' ? op.op_id : null;

    if (op_id && this.seen.has(op_id)) {
      const previous = this.seen.get(op_id);
      const entry = this.items.get(previous.item_id);
      return {
        op_id,
        status: 'duplicate',
        quantity: entry ? this.quantityOf(entry) : undefined,
        server_received_at,
      };
    }

    const error = validate(op);
    if (error) {
      return { op_id, status: 'rejected', reason: 'invalid', message: error, server_received_at };
    }

    const entry = this.items.get(op.item_id);
    if (!entry) {
      // Pas mémorisé dans `seen` : l'article peut être créé côté serveur puis l'op renvoyée.
      return {
        op_id,
        status: 'rejected',
        reason: 'unknown_item',
        message: `article inconnu : ${op.item_id}`,
        server_received_at,
      };
    }

    const record = {
      op_id,
      type: op.type,
      value: op.value,
      ts: Date.parse(op.client_ts),
      client_ts: op.client_ts,
      device_id: op.device_id,
      user: op.user,
      session_id: op.session_id,
      server_received_at,
    };

    let status = 'applied';
    if (record.type === 'set') {
      const newer = entry.ops.find((o) => o.type === 'set' && compareSet(o, record) > 0);
      if (newer) status = 'superseded';
    }

    entry.ops.push(record);
    this.seen.set(op_id, { item_id: op.item_id, status });
    return { op_id, status, quantity: this.quantityOf(entry), server_received_at };
  }

  journal(ref) {
    const entry = this.items.get(ref);
    return entry ? entry.ops.slice() : null;
  }
}

module.exports = { Store, validate };
