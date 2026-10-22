// Géométrie des codes (QR, Data Matrix, 1D) en rectangles, pour un rendu vectoriel identique en SVG et en PDF.
import bwipjs from 'bwip-js/browser';
import type { CodeElement, Symbology } from './model';

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface CodeGeometry {
  /** Rectangles noirs, en millimètres, relatifs au coin haut gauche de l'élément. */
  rects: Rect[];
}

export class CodeError extends Error {}

// Marge blanche (zone de silence) incluse dans le cadre de l'élément, en modules.
const QUIET_MATRIX = 2;
const QUIET_LINEAR = 10;

const MATRIX = new Set<Symbology>(['qrcode', 'datamatrix']);

const cache = new Map<string, any>();

function raw(symbology: Symbology, text: string, eclevel: string) {
  const key = `${symbology}\u0000${eclevel}\u0000${text}`;
  if (cache.has(key)) return cache.get(key);
  let result: any;
  try {
    const opts: Record<string, unknown> = {};
    if (symbology === 'qrcode') opts.eclevel = eclevel;
    result = (bwipjs as any).raw(symbology, text, opts)[0];
  } catch (e) {
    const msg = String((e as Error).message || e).replace(/^bwipp\.\w+#\d+:\s*/, '');
    throw new CodeError(msg);
  }
  if (cache.size > 2000) cache.clear();
  cache.set(key, result);
  return result;
}

export function codeGeometry(el: Pick<CodeElement, 'symbology' | 'w' | 'h' | 'eclevel'>, text: string): CodeGeometry {
  if (!text) throw new CodeError('contenu vide');
  const r = raw(el.symbology, text, el.eclevel ?? 'M');
  const rects: Rect[] = [];

  if (MATRIX.has(el.symbology)) {
    const cols: number = r.pixx;
    const rows: number = r.pixy;
    const totalW = cols + 2 * QUIET_MATRIX;
    const totalH = rows + 2 * QUIET_MATRIX;
    const m = Math.min(el.w / totalW, el.h / totalH);
    const ox = (el.w - cols * m) / 2;
    const oy = (el.h - rows * m) / 2;
    for (let y = 0; y < rows; y++) {
      let start = -1;
      for (let x = 0; x <= cols; x++) {
        const on = x < cols && r.pixs[y * cols + x] === 1;
        if (on && start < 0) start = x;
        if (!on && start >= 0) {
          rects.push({ x: ox + start * m, y: oy + y * m, w: (x - start) * m, h: m });
          start = -1;
        }
      }
    }
    return { rects };
  }

  const sbs: number[] = r.sbs;
  const total = sbs.reduce((a, b) => a + b, 0) + 2 * QUIET_LINEAR;
  const m = el.w / total;
  let x = QUIET_LINEAR * m;
  sbs.forEach((width, i) => {
    if (i % 2 === 0) rects.push({ x, y: 0, w: width * m, h: el.h });
    x += width * m;
  });
  return { rects };
}

/** Taille de module en mm, pour avertir quand le code devient trop petit pour être lu. */
export function moduleSize(el: Pick<CodeElement, 'symbology' | 'w' | 'h' | 'eclevel'>, text: string): number {
  const r = raw(el.symbology, text, el.eclevel ?? 'M');
  if (MATRIX.has(el.symbology)) {
    return Math.min(el.w / (r.pixx + 2 * QUIET_MATRIX), el.h / (r.pixy + 2 * QUIET_MATRIX));
  }
  const total = (r.sbs as number[]).reduce((a, b) => a + b, 0) + 2 * QUIET_LINEAR;
  return el.w / total;
}

/** Seuil indicatif en dessous duquel un smartphone lit mal (impression thermique 203 dpi ≈ 0,125 mm/point). */
export const MIN_MODULE_MM = 0.25;
