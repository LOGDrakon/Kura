import { describe, expect, it } from 'vitest';
import { codeGeometry, CodeError, moduleSize } from './codes';
import { parseCsv, toCsv } from './csv';
import { fieldsOf, fill } from './fields';
import { customSheet, planPages, sheetFromPreset } from './layout';
import { mergeData, normalizeData, starterTemplate, type StudioData, type Template } from './model';
import { buildPdf } from './pdf';
import { renderLabelSvg, type ElementIssue } from './render-svg';
import { layoutText, printable, textWidth } from './text';
import { scriptSafeJson } from './embed';

describe('champs', () => {
  it('remplit et liste les champs', () => {
    expect(fill('Réf. {ref} · {brand}', { ref: 'A1', brand: 'Würth' })).toBe('Réf. A1 · Würth');
    expect(fill('{absent}!', {})).toBe('!');
    expect(fill('{ ref }', { ref: 'x' })).toBe('x');
    expect(fieldsOf('{ref} {name} {ref}')).toEqual(['ref', 'name']);
  });
});

describe('codes', () => {
  it('QR : modules carrés, centrés, dans le cadre', () => {
    const { rects } = codeGeometry({ symbology: 'qrcode', w: 20, h: 30, eclevel: 'M' }, 'REF-0042');
    expect(rects.length).toBeGreaterThan(20);
    for (const r of rects) {
      expect(r.h).toBeCloseTo(rects[0].h);
      expect(r.x).toBeGreaterThanOrEqual(0);
      expect(r.x + r.w).toBeLessThanOrEqual(20 + 1e-9);
      expect(r.y + r.h).toBeLessThanOrEqual(30 + 1e-9);
    }
    // 21 modules + 2×2 de marge = 25 → 0,8 mm par module.
    expect(moduleSize({ symbology: 'qrcode', w: 20, h: 30, eclevel: 'M' }, 'REF-0042')).toBeCloseTo(0.8);
  });

  it('Code 128 : barres sur toute la hauteur, zone de silence respectée', () => {
    const { rects } = codeGeometry({ symbology: 'code128', w: 40, h: 10 }, 'REF-0042');
    const first = Math.min(...rects.map((r) => r.x));
    const last = Math.max(...rects.map((r) => r.x + r.w));
    expect(first).toBeGreaterThan(0);
    expect(40 - last).toBeCloseTo(first, 5);
    expect(rects.every((r) => r.h === 10)).toBe(true);
  });

  it('EAN-13 invalide : erreur lisible', () => {
    expect(() => codeGeometry({ symbology: 'ean13', w: 40, h: 10 }, '12ab')).toThrow(CodeError);
    expect(() => codeGeometry({ symbology: 'qrcode', w: 40, h: 10 }, '')).toThrow(/vide/);
  });
});

describe('texte', () => {
  it('mesure avec les métriques Helvetica', () => {
    // « Hello » en Helvetica 10 pt : 2,278 em → 22,78 pt ≈ 8,04 mm.
    expect(textWidth('Hello', 10, false)).toBeCloseTo(8.036, 2);
    expect(textWidth('Hello', 10, true)).toBeGreaterThan(textWidth('Hello', 10, false));
  });

  it('remplace les caractères non imprimables', () => {
    expect(printable('Vis Ø6 → A12 é ✓')).toBe('Vis Ø6 ? A12 é ?');
  });

  it('réduit la taille pour tenir dans le cadre', () => {
    const el = { w: 20, h: 5, size_pt: 14, bold: false, align: 'left', valign: 'top', shrink: true } as const;
    const l = layoutText(el, 'Courroie trapézoïdale SPZ 1250');
    expect(l.sizePt).toBeLessThan(14);
    expect(l.overflow).toBe(false);
    for (const line of l.lines) expect(textWidth(line.text, l.sizePt, false)).toBeLessThanOrEqual(20 + 1e-6);
  });

  it('signale le dépassement sans réduction', () => {
    const el = { w: 20, h: 3, size_pt: 14, bold: false, align: 'left', valign: 'top', shrink: false } as const;
    expect(layoutText(el, 'Courroie trapézoïdale SPZ 1250').overflow).toBe(true);
  });

  it('aligne à droite et au centre', () => {
    const base = { w: 30, h: 10, size_pt: 10, bold: false, valign: 'top', shrink: false } as const;
    const w = textWidth('A12', 10, false);
    expect(layoutText({ ...base, align: 'right' }, 'A12').lines[0].x).toBeCloseTo(30 - w);
    expect(layoutText({ ...base, align: 'center' }, 'A12').lines[0].x).toBeCloseTo((30 - w) / 2);
  });

  it('respecte les retours à la ligne', () => {
    const el = { w: 40, h: 20, size_pt: 8, bold: false, align: 'left', valign: 'top', shrink: true } as const;
    expect(layoutText(el, 'Ligne 1\nLigne 2').lines.map((l) => l.text)).toEqual(['Ligne 1', 'Ligne 2']);
  });
});

describe('rendu SVG', () => {
  it('produit un SVG à la taille du modèle et échappe le texte', () => {
    const t = starterTemplate();
    const svg = renderLabelSvg(t, { ref: 'A<1>', name: 'Vis & écrou' });
    expect(svg).toContain('viewBox="0 0 50 25"');
    expect(svg).toContain('A&lt;1&gt;');
    expect(svg).toContain('Vis &amp; écrou');
  });

  it('remonte les problèmes', () => {
    const t = starterTemplate();
    t.elements[0] = { ...(t.elements[0] as any), symbology: 'ean13' };
    const issues: ElementIssue[] = [];
    renderLabelSvg(t, { ref: 'pas un EAN' }, { issues });
    expect(issues.map((i) => i.id)).toContain(t.elements[0].id);
  });
});

describe('mise en page', () => {
  it('planche Avery : positions et pages', () => {
    const layout = sheetFromPreset('avery-l7160');
    const t = { width_mm: 63.5, height_mm: 38.1 };
    const plan = planPages(layout, t, 22);
    expect(plan.pages).toBe(2);
    expect(plan.placements[0]).toMatchObject({ page: 0, x: 7.2, y: 15.15 });
    expect(plan.placements[1].x).toBeCloseTo(7.2 + 63.5 + 2.5);
    expect(plan.placements[3].y).toBeCloseTo(15.15 + 38.1);
    expect(plan.placements[21]).toMatchObject({ page: 1, x: 7.2, y: 15.15 });
    expect(plan.oversize).toBe(false);
  });

  it('saute les étiquettes déjà utilisées et centre un modèle plus petit', () => {
    const layout = sheetFromPreset('avery-l7160');
    const plan = planPages(layout, { width_mm: 50, height_mm: 25 }, 1, 4);
    expect(plan.placements[0].x).toBeCloseTo(7.2 + 63.5 + 2.5 + (63.5 - 50) / 2);
    expect(plan.placements[0].y).toBeCloseTo(15.15 + 38.1 + (38.1 - 25) / 2);
  });

  it('planche personnalisée : la grille tient dans la page', () => {
    const s = customSheet({ width_mm: 50, height_mm: 25 });
    expect(s.cols).toBe(3);
    expect(s.rows).toBe(10);
    expect(s.margin_left).toBeGreaterThanOrEqual(8);
    expect(s.margin_left + s.cols * 50 + (s.cols - 1) * s.gap_x).toBeLessThanOrEqual(210);
  });

  it('rouleau : une page par étiquette, à la taille exacte', () => {
    const plan = planPages({ kind: 'roll' }, { width_mm: 50, height_mm: 25 }, 3);
    expect(plan).toMatchObject({ page_w: 50, page_h: 25, pages: 3 });
  });
});

describe('PDF', () => {
  it('génère un PDF valide avec le bon nombre de pages', async () => {
    const t = starterTemplate();
    const records = Array.from({ length: 5 }, (_, i) => ({ ref: `REF-${i}`, name: `Article ${i}` }));
    const bytes = await buildPdf(t, records, { layout: { kind: 'roll' }, copies: 2, skip: 0, outline: true });
    const head = new TextDecoder().decode(bytes.slice(0, 8));
    expect(head).toBe('%PDF-1.7');
    const { PDFDocument } = await import('pdf-lib');
    const doc = await PDFDocument.load(bytes);
    expect(doc.getPageCount()).toBe(10);
    const [w, h] = [doc.getPage(0).getWidth(), doc.getPage(0).getHeight()];
    expect(w).toBeCloseTo((50 * 72) / 25.4);
    expect(h).toBeCloseTo((25 * 72) / 25.4);
  });
});

describe('CSV', () => {
  it('détecte le séparateur, gère guillemets, BOM et accents dans les en-têtes', () => {
    const t = parseCsv('﻿Référence;Désignation;Marque\r\nREF-1;"Vis; M6 ""inox""";Würth\r\n\r\nREF-2;Écrou;\r\n');
    expect(t.columns).toEqual(['reference', 'designation', 'marque']);
    expect(t.rows).toEqual([
      { reference: 'REF-1', designation: 'Vis; M6 "inox"', marque: 'Würth' },
      { reference: 'REF-2', designation: 'Écrou', marque: '' },
    ]);
    expect(parseCsv(toCsv(t))).toEqual(t);
  });

  it('virgule et colonnes dupliquées', () => {
    const t = parseCsv('ref,ref,\nA,B,C');
    expect(t.columns).toEqual(['ref', 'ref_', 'colonne_3']);
  });
});

describe('données partagées', () => {
  const tpl = (id: string, updated_at: string, name = id): Template => ({ ...starterTemplate(name), id, updated_at });
  const data = (templates: Template[], deleted: Record<string, string> = {}): StudioData => ({
    kind: 'kura-studio', version: 1, company: '', templates, deleted,
  });

  it('la version la plus récente gagne, les suppressions locales tiennent', () => {
    const embedded = data([tpl('a', '2026-01-01', 'A partagé'), tpl('b', '2026-01-01'), tpl('c', '2026-03-01')]);
    const local = data([tpl('a', '2026-02-01', 'A local'), tpl('d', '2026-01-01')], { b: '2026-02-01', c: '2026-02-01' });
    const merged = mergeData(embedded, local);
    expect(merged.templates.map((t) => t.id).sort()).toEqual(['a', 'c', 'd']);
    expect(merged.templates.find((t) => t.id === 'a')!.name).toBe('A local');
  });

  it('nettoie les données importées', () => {
    const d = normalizeData({
      templates: [
        { name: 'X', width_mm: -5, elements: [{ kind: 'text', content: 'ok', size_pt: 9000 }, { kind: 'script' }, null] },
        'n importe quoi',
      ],
    });
    expect(d.templates).toHaveLength(1);
    expect(d.templates[0].width_mm).toBe(5);
    expect(d.templates[0].elements).toHaveLength(1);
    expect((d.templates[0].elements[0] as any).size_pt).toBe(200);
    expect(normalizeData({ templates: [{ elements: [{ kind: 'image', src: 'javascript:alert(1)' }] }] }).templates[0].elements[0]).toMatchObject({ src: '' });
  });

  it('JSON embarqué sans possibilité de fermer la balise script', () => {
    const s = scriptSafeJson({ name: '</script><script>alert(1)</script>' });
    expect(s).not.toContain('<');
    expect(JSON.parse(s).name).toBe('</script><script>alert(1)</script>');
  });
});
