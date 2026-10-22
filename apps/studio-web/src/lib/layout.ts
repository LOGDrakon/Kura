// Disposition des étiquettes sur les pages : planches A4 prédécoupées ou rouleau.
import type { Layout, SheetLayout, Template } from './model';

type Preset = Omit<SheetLayout, 'kind' | 'preset'> & { label: string };

const A4 = { page_w: 210, page_h: 297 };

// Dimensions publiées par les fabricants (mm). Les marges peuvent varier de ±0,5 mm selon les lots :
// une impression d'essai avec contours est conseillée.
export const SHEET_PRESETS: Record<string, Preset> = {
  'avery-l7160': { label: 'Avery L7160 · 63,5 × 38,1 · 21 par page', ...A4, label_w: 63.5, label_h: 38.1, cols: 3, rows: 7, margin_left: 7.2, margin_top: 15.15, gap_x: 2.5, gap_y: 0 },
  'avery-l7163': { label: 'Avery L7163 · 99,1 × 38,1 · 14 par page', ...A4, label_w: 99.1, label_h: 38.1, cols: 2, rows: 7, margin_left: 4.65, margin_top: 15.15, gap_x: 2.5, gap_y: 0 },
  'avery-l7173': { label: 'Avery L7173 · 99,1 × 57 · 10 par page', ...A4, label_w: 99.1, label_h: 57, cols: 2, rows: 5, margin_left: 4.65, margin_top: 6, gap_x: 2.5, gap_y: 0 },
  'avery-l7651': { label: 'Avery L7651 · 38,1 × 21,2 · 65 par page', ...A4, label_w: 38.1, label_h: 21.2, cols: 5, rows: 13, margin_left: 4.75, margin_top: 10.7, gap_x: 2.5, gap_y: 0 },
  'avery-3474': { label: 'Avery 3474 · 70 × 37 · 24 par page', ...A4, label_w: 70, label_h: 37, cols: 3, rows: 8, margin_left: 0, margin_top: 0.5, gap_x: 0, gap_y: 0 },
  'avery-3475': { label: 'Avery 3475 · 70 × 36 · 24 par page', ...A4, label_w: 70, label_h: 36, cols: 3, rows: 8, margin_left: 0, margin_top: 4.5, gap_x: 0, gap_y: 0 },
};

export function sheetFromPreset(id: string): SheetLayout {
  const { label: _, ...p } = SHEET_PRESETS[id] ?? SHEET_PRESETS['avery-l7160'];
  return { kind: 'sheet', preset: id, ...p };
}

/** Planche personnalisée : grille calculée pour remplir une page A4 avec le modèle. */
export function customSheet(t: Pick<Template, 'width_mm' | 'height_mm'>, margin = 8, gap = 2): SheetLayout {
  const cols = Math.max(1, Math.floor((A4.page_w - 2 * margin + gap) / (t.width_mm + gap)));
  const rows = Math.max(1, Math.floor((A4.page_h - 2 * margin + gap) / (t.height_mm + gap)));
  const usedW = cols * t.width_mm + (cols - 1) * gap;
  const usedH = rows * t.height_mm + (rows - 1) * gap;
  return {
    kind: 'sheet', preset: 'custom', ...A4,
    label_w: t.width_mm, label_h: t.height_mm, cols, rows,
    margin_left: (A4.page_w - usedW) / 2, margin_top: (A4.page_h - usedH) / 2,
    gap_x: gap, gap_y: gap,
  };
}

export interface Placement {
  page: number;
  /** Coin haut gauche de l'étiquette sur la page, en mm. */
  x: number;
  y: number;
  index: number;
}

export interface PagePlan {
  page_w: number;
  page_h: number;
  pages: number;
  placements: Placement[];
  /** Le modèle est plus grand que la case de la planche. */
  oversize: boolean;
}

export function planPages(layout: Layout, t: Pick<Template, 'width_mm' | 'height_mm'>, count: number, skip = 0): PagePlan {
  if (layout.kind === 'roll') {
    return {
      page_w: t.width_mm,
      page_h: t.height_mm,
      pages: count,
      oversize: false,
      placements: Array.from({ length: count }, (_, i) => ({ page: i, x: 0, y: 0, index: i })),
    };
  }
  const perPage = layout.cols * layout.rows;
  const first = Math.min(Math.max(0, Math.floor(skip)), perPage - 1);
  // Le modèle est centré dans sa case quand il est plus petit qu'elle.
  const dx = (layout.label_w - t.width_mm) / 2;
  const dy = (layout.label_h - t.height_mm) / 2;
  const placements: Placement[] = [];
  for (let i = 0; i < count; i++) {
    const slot = first + i;
    const page = Math.floor(slot / perPage);
    const inPage = slot % perPage;
    const col = inPage % layout.cols;
    const row = Math.floor(inPage / layout.cols);
    placements.push({
      page,
      index: i,
      x: layout.margin_left + col * (layout.label_w + layout.gap_x) + dx,
      y: layout.margin_top + row * (layout.label_h + layout.gap_y) + dy,
    });
  }
  return {
    page_w: layout.page_w,
    page_h: layout.page_h,
    pages: count === 0 ? 0 : placements[placements.length - 1].page + 1,
    placements,
    oversize: t.width_mm > layout.label_w + 0.01 || t.height_mm > layout.label_h + 0.01,
  };
}
