// Modèle de données de Kura Studio. Toutes les dimensions sont en millimètres.

export type Symbology =
  | 'qrcode'
  | 'datamatrix'
  | 'code128'
  | 'code39'
  | 'ean13'
  | 'ean8'
  | 'upca'
  | 'interleaved2of5';

export const SYMBOLOGIES: { id: Symbology; label: string; matrix: boolean }[] = [
  { id: 'qrcode', label: 'QR Code', matrix: true },
  { id: 'datamatrix', label: 'Data Matrix', matrix: true },
  { id: 'code128', label: 'Code 128', matrix: false },
  { id: 'code39', label: 'Code 39', matrix: false },
  { id: 'ean13', label: 'EAN-13', matrix: false },
  { id: 'ean8', label: 'EAN-8', matrix: false },
  { id: 'upca', label: 'UPC-A', matrix: false },
  { id: 'interleaved2of5', label: 'ITF', matrix: false },
];

export type Align = 'left' | 'center' | 'right';
export type VAlign = 'top' | 'middle' | 'bottom';

interface Box {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface CodeElement extends Box {
  kind: 'code';
  symbology: Symbology;
  /** Contenu encodé, avec des champs {colonne}. */
  content: string;
  /** Niveau de correction QR. */
  eclevel?: 'L' | 'M' | 'Q' | 'H';
}

export interface TextElement extends Box {
  kind: 'text';
  /** Texte libre, avec des champs {colonne}. Retours à la ligne autorisés. */
  content: string;
  size_pt: number;
  bold: boolean;
  align: Align;
  valign: VAlign;
  /** Réduit la taille du texte jusqu'à ce qu'il tienne dans le cadre. */
  shrink: boolean;
  color: string;
}

export interface RectElement extends Box {
  kind: 'rect';
  stroke_mm: number;
  radius_mm: number;
  filled: boolean;
  color: string;
}

export interface ImageElement extends Box {
  kind: 'image';
  /** Image PNG ou JPEG en data URL, embarquée dans le modèle. */
  src: string;
}

export type LabelElement = CodeElement | TextElement | RectElement | ImageElement;
export type ElementKind = LabelElement['kind'];

export interface Template {
  id: string;
  name: string;
  width_mm: number;
  height_mm: number;
  elements: LabelElement[];
  /** Exemple de données affiché dans l'éditeur. */
  sample: Record<string, string>;
  updated_at: string;
}

export interface SheetLayout {
  kind: 'sheet';
  preset: string;
  page_w: number;
  page_h: number;
  label_w: number;
  label_h: number;
  cols: number;
  rows: number;
  margin_left: number;
  margin_top: number;
  gap_x: number;
  gap_y: number;
}

export interface RollLayout {
  kind: 'roll';
}

export type Layout = SheetLayout | RollLayout;

export interface PrintOptions {
  layout: Layout;
  copies: number;
  /** Étiquettes déjà utilisées sur la première planche. */
  skip: number;
  /** Trace le contour de chaque étiquette (impression d'essai). */
  outline: boolean;
}

export interface StudioData {
  kind: 'kura-studio';
  version: 1;
  company: string;
  templates: Template[];
  /** Modèles supprimés localement : id → date, pour ne pas les voir revenir depuis l'éditeur partagé. */
  deleted: Record<string, string>;
  /** Brouillon de kura-config.json (sans aucun secret), partagé avec l'éditeur. */
  config?: Record<string, unknown>;
  config_updated_at?: string;
}

export const MM_PER_PT = 25.4 / 72;

export function uid(prefix = 'e'): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return prefix + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function now(): string {
  return new Date().toISOString();
}

export function emptyData(): StudioData {
  return { kind: 'kura-studio', version: 1, company: '', templates: [], deleted: {} };
}

export function newElement(kind: ElementKind, t: Pick<Template, 'width_mm' | 'height_mm'>): LabelElement {
  const id = uid();
  const side = Math.max(5, Math.min(t.width_mm, t.height_mm) - 4);
  switch (kind) {
    case 'code':
      return { id, kind, x: 2, y: 2, w: side, h: side, symbology: 'qrcode', content: '{ref}', eclevel: 'M' };
    case 'text':
      return {
        id, kind, x: 2, y: 2, w: Math.max(10, t.width_mm - 4), h: 6,
        content: 'Texte', size_pt: 9, bold: false, align: 'left', valign: 'middle', shrink: true, color: '#000000',
      };
    case 'rect':
      return { id, kind, x: 1, y: 1, w: t.width_mm - 2, h: t.height_mm - 2, stroke_mm: 0.3, radius_mm: 1, filled: false, color: '#000000' };
    case 'image':
      return { id, kind, x: 2, y: 2, w: 10, h: 10, src: '' };
  }
}

/** Modèle de départ : QR à gauche, référence et désignation à droite. */
export function starterTemplate(name = 'Étiquette 50 × 25'): Template {
  return {
    id: uid('t'),
    name,
    width_mm: 50,
    height_mm: 25,
    elements: [
      { id: uid(), kind: 'code', x: 2, y: 2, w: 21, h: 21, symbology: 'qrcode', content: '{ref}', eclevel: 'M' },
      {
        id: uid(), kind: 'text', x: 25, y: 3, w: 23, h: 7, content: '{ref}',
        size_pt: 11, bold: true, align: 'left', valign: 'top', shrink: true, color: '#000000',
      },
      {
        id: uid(), kind: 'text', x: 25, y: 10.5, w: 23, h: 12, content: '{name}',
        size_pt: 8, bold: false, align: 'left', valign: 'top', shrink: true, color: '#000000',
      },
    ],
    sample: { ref: 'REF-0042', name: 'Vis M6 x 20 inox', brand: 'Würth', location: 'A12' },
    updated_at: now(),
  };
}

const num = (v: unknown, d: number, min = -1e6, max = 1e6) =>
  typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : d;
const str = (v: unknown, d = '') => (typeof v === 'string' ? v : d);

function normalizeElement(raw: any): LabelElement | null {
  if (!raw || typeof raw !== 'object') return null;
  const box = {
    id: str(raw.id) || uid(),
    x: num(raw.x, 0, -1000, 1000),
    y: num(raw.y, 0, -1000, 1000),
    w: num(raw.w, 10, 0.5, 2000),
    h: num(raw.h, 10, 0.5, 2000),
  };
  switch (raw.kind) {
    case 'code':
      return {
        ...box, kind: 'code',
        symbology: SYMBOLOGIES.some((s) => s.id === raw.symbology) ? raw.symbology : 'qrcode',
        content: str(raw.content, '{ref}'),
        eclevel: ['L', 'M', 'Q', 'H'].includes(raw.eclevel) ? raw.eclevel : 'M',
      };
    case 'text':
      return {
        ...box, kind: 'text',
        content: str(raw.content),
        size_pt: num(raw.size_pt, 9, 2, 200),
        bold: !!raw.bold,
        align: ['left', 'center', 'right'].includes(raw.align) ? raw.align : 'left',
        valign: ['top', 'middle', 'bottom'].includes(raw.valign) ? raw.valign : 'top',
        shrink: raw.shrink !== false,
        color: /^#[0-9a-f]{6}$/i.test(raw.color) ? raw.color : '#000000',
      };
    case 'rect':
      return {
        ...box, kind: 'rect',
        stroke_mm: num(raw.stroke_mm, 0.3, 0, 20),
        radius_mm: num(raw.radius_mm, 0, 0, 100),
        filled: !!raw.filled,
        color: /^#[0-9a-f]{6}$/i.test(raw.color) ? raw.color : '#000000',
      };
    case 'image':
      return { ...box, kind: 'image', src: /^data:image\/(png|jpeg);base64,/.test(raw.src) ? raw.src : '' };
    default:
      return null;
  }
}

export function normalizeTemplate(raw: any): Template | null {
  if (!raw || typeof raw !== 'object') return null;
  const sample: Record<string, string> = {};
  if (raw.sample && typeof raw.sample === 'object') {
    for (const [k, v] of Object.entries(raw.sample)) if (typeof v === 'string') sample[k] = v;
  }
  return {
    id: str(raw.id) || uid('t'),
    name: str(raw.name, 'Sans nom').slice(0, 120) || 'Sans nom',
    width_mm: num(raw.width_mm, 50, 5, 1000),
    height_mm: num(raw.height_mm, 25, 5, 1000),
    elements: Array.isArray(raw.elements) ? raw.elements.map(normalizeElement).filter(Boolean) as LabelElement[] : [],
    sample,
    updated_at: str(raw.updated_at, now()),
  };
}

/** Lit des données venant de l'extérieur (fichier importé, éditeur partagé) sans leur faire confiance. */
export function normalizeData(raw: any): StudioData {
  const data = emptyData();
  if (!raw || typeof raw !== 'object') return data;
  data.company = str(raw.company).slice(0, 120);
  if (Array.isArray(raw.templates)) {
    data.templates = raw.templates.map(normalizeTemplate).filter(Boolean) as Template[];
  }
  if (raw.deleted && typeof raw.deleted === 'object') {
    for (const [k, v] of Object.entries(raw.deleted)) if (typeof v === 'string') data.deleted[k] = v;
  }
  if (raw.config && typeof raw.config === 'object' && !Array.isArray(raw.config)) {
    data.config = raw.config;
    data.config_updated_at = str(raw.config_updated_at, '');
  }
  return data;
}

/**
 * Fusionne les modèles embarqués dans l'éditeur partagé avec ceux du poste.
 * Pour un même id, la version la plus récente gagne. Une suppression locale plus récente que
 * la version embarquée l'emporte.
 */
export function mergeData(embedded: StudioData, local: StudioData | null): StudioData {
  if (!local) return structuredClone(embedded);
  const byId = new Map<string, Template>();
  for (const t of embedded.templates) byId.set(t.id, t);
  for (const t of local.templates) {
    const other = byId.get(t.id);
    if (!other || t.updated_at >= other.updated_at) byId.set(t.id, t);
  }
  const deleted = { ...embedded.deleted, ...local.deleted };
  for (const [id, ts] of Object.entries(deleted)) {
    const t = byId.get(id);
    if (t && ts >= t.updated_at) byId.delete(id);
  }
  const localConfigNewer = (local.config_updated_at ?? '') >= (embedded.config_updated_at ?? '');
  const configSource = local.config && localConfigNewer ? local : embedded.config ? embedded : local;
  return {
    kind: 'kura-studio',
    version: 1,
    company: local.company || embedded.company,
    templates: [...byId.values()],
    deleted,
    ...(configSource.config ? { config: configSource.config, config_updated_at: configSource.config_updated_at } : {}),
  };
}
