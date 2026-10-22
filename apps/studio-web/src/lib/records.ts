// Contrôle des données avant impression : champs obligatoires, champs absents du modèle.
import { columnKey } from './csv';
import { fieldsOf } from './fields';
import type { Template } from './model';

/** Champs techniques ou vivants (le stock change sans cesse), jamais signalés comme « non placés ». */
const TECHNICAL = new Set(['updated_at', 'image_url', 'quantity']);

/** Champs {nom} utilisés par les textes et les codes du modèle. */
export function templateFields(t: Pick<Template, 'elements'>): string[] {
  const out = new Set<string>();
  for (const el of t.elements) {
    if (el.kind === 'text' || el.kind === 'code') for (const f of fieldsOf(el.content)) out.add(f);
  }
  return [...out];
}

export interface RecordCheck {
  /** Champs obligatoires vides : l'étiquette n'est pas imprimée. */
  missing: string[];
  /** Champs renseignés dans les données mais absents du modèle (ni placés, ni ignorés). */
  unplaced: string[];
}

export function checkRecord(t: Template, record: Record<string, string>, used = templateFields(t)): RecordCheck {
  const filled = (f: string) => (record[f] ?? '').trim() !== '';
  return {
    missing: t.required.filter((f) => !filled(f)),
    unplaced: Object.keys(record).filter(
      (k) => filled(k) && !used.includes(k) && !t.ignored.includes(k) && !TECHNICAL.has(k),
    ),
  };
}

export interface BatchCheck {
  /** Lignes imprimables. */
  valid: Record<string, string>[];
  /** Lignes écartées, avec leur numéro (1 = première ligne de données). */
  rejected: { index: number; record: Record<string, string>; missing: string[] }[];
  /** Champ non placé → nombre de lignes concernées. */
  unplaced: Map<string, number>;
}

export function checkBatch(t: Template, records: Record<string, string>[]): BatchCheck {
  const used = templateFields(t);
  const result: BatchCheck = { valid: [], rejected: [], unplaced: new Map() };
  records.forEach((record, index) => {
    const c = checkRecord(t, record, used);
    for (const f of c.unplaced) result.unplaced.set(f, (result.unplaced.get(f) ?? 0) + 1);
    if (c.missing.length) result.rejected.push({ index: index + 1, record, missing: c.missing });
    else result.valid.push(record);
  });
  return result;
}

/**
 * Article renvoyé par le serveur → enregistrement plat de chaînes.
 * Les noms de champs suivent la même règle que les colonnes CSV (minuscules, sans accents),
 * les objets imbriqués sont aplatis (stock.disponible → stock_disponible).
 */
export function flattenItem(item: Record<string, unknown>, prefix = '', out: Record<string, string> = {}): Record<string, string> {
  for (const [k, v] of Object.entries(item)) {
    const key = columnKey(prefix ? `${prefix}_${k}` : k, 0);
    if (v === null || v === undefined) out[key] = '';
    else if (Array.isArray(v)) out[key] = v.map((x) => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join(', ');
    else if (typeof v === 'object') flattenItem(v as Record<string, unknown>, key, out);
    else out[key] = String(v);
  }
  return out;
}

type Box = { x: number; y: number; w: number; h: number };

const overlaps = (a: Box, b: Box) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/**
 * Cherche une zone libre de hauteur h pour une ligne de texte : candidats alignés sur les bords
 * des éléments existants, on garde la plus large. null si l'étiquette est pleine.
 */
export function freeSpot(t: Pick<Template, 'width_mm' | 'height_mm' | 'elements'>, h: number, margin = 1): Box | null {
  const xs = new Set([margin, ...t.elements.map((e) => e.x + e.w + margin)]);
  const ys = new Set([margin, ...t.elements.map((e) => e.y + e.h + 0.5)]);
  let best: Box | null = null;
  for (const x of xs) {
    for (const y of ys) {
      if (y + h > t.height_mm - margin + 1e-6 || x >= t.width_mm - margin) continue;
      // Largeur jusqu'au prochain obstacle sur cette bande.
      let right = t.width_mm - margin;
      for (const e of t.elements) {
        if (e.y < y + h && y < e.y + e.h && e.x + e.w > x) {
          if (e.x <= x) right = -1;
          else right = Math.min(right, e.x - margin);
        }
      }
      const box = { x, y, w: right - x, h };
      if (box.w >= 8 && !t.elements.some((e) => overlaps(box, e)) && (!best || box.w > best.w || (box.w === best.w && box.y < best.y))) {
        best = box;
      }
    }
  }
  return best;
}

/** Ajoute le champ au modèle : une ligne de texte dans une zone libre (sinon en bas), à ajuster ensuite. */
export function placeFieldElement(t: Pick<Template, 'width_mm' | 'height_mm' | 'elements'>, field: string) {
  const h = Math.min(5, Math.max(3, t.height_mm / 6));
  const spot = freeSpot(t, h) ?? { x: 2, y: Math.max(0, t.height_mm - h - 1), w: Math.max(5, t.width_mm - 4), h };
  const round = (v: number) => Math.round(v * 2) / 2;
  return {
    kind: 'text' as const,
    x: round(spot.x),
    y: round(spot.y),
    w: Math.floor(spot.w * 2) / 2,
    h: spot.h,
    content: `{${field}}`,
    size_pt: 7,
  };
}
