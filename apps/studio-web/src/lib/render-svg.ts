// Rendu SVG d'une étiquette (aperçu dans l'éditeur, export image).
import { CodeError, codeGeometry } from './codes';
import { fill, fillText } from './fields';
import type { LabelElement, Template } from './model';
import { layoutText } from './text';

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const n = (v: number) => Math.round(v * 1000) / 1000;
let clipCounter = 0;

export interface ElementIssue {
  id: string;
  message: string;
}

export function renderElement(el: LabelElement, record: Record<string, string>, issues?: ElementIssue[]): string {
  const t = `translate(${n(el.x)} ${n(el.y)})`;
  switch (el.kind) {
    case 'code': {
      const value = fill(el.content, record);
      // Champ facultatif vide : pas de code (un champ obligatoire vide est signalé avant l'impression).
      if (!value.trim()) return '';
      try {
        const { rects } = codeGeometry(el, value);
        const d = rects.map((r) => `M${n(r.x)} ${n(r.y)}h${n(r.w)}v${n(r.h)}h${n(-r.w)}z`).join('');
        return `<g transform="${t}"><path d="${d}" fill="#000"/></g>`;
      } catch (e) {
        const message = e instanceof CodeError ? e.message : 'code impossible à générer';
        issues?.push({ id: el.id, message });
        return `<g transform="${t}"><rect width="${n(el.w)}" height="${n(el.h)}" fill="#fde8e8" stroke="#c81e1e" stroke-width="0.3" stroke-dasharray="1 1"/></g>`;
      }
    }
    case 'text': {
      const layout = layoutText(el, fillText(el.content, record));
      if (layout.overflow) issues?.push({ id: el.id, message: 'le texte dépasse du cadre' });
      const weight = el.bold ? 'bold' : 'normal';
      const size = n((layout.sizePt * 25.4) / 72);
      const tspans = layout.lines
        .map((l) => `<text x="${n(l.x)}" y="${n(l.y)}" xml:space="preserve">${esc(l.text)}</text>`)
        .join('');
      return `<g transform="${t}" font-family="Helvetica, Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="${el.color}">${tspans}</g>`;
    }
    case 'rect': {
      const s = el.filled ? 0 : el.stroke_mm;
      const attrs = el.filled
        ? `fill="${el.color}"`
        : `fill="none" stroke="${el.color}" stroke-width="${n(s)}"`;
      return `<rect x="${n(el.x + s / 2)}" y="${n(el.y + s / 2)}" width="${n(Math.max(0, el.w - s))}" height="${n(Math.max(0, el.h - s))}" rx="${n(el.radius_mm)}" ${attrs}/>`;
    }
    case 'image':
      if (!el.src) {
        return `<g transform="${t}"><rect width="${n(el.w)}" height="${n(el.h)}" fill="#eef2f7" stroke="#94a3b8" stroke-width="0.2" stroke-dasharray="1 1"/></g>`;
      }
      return `<image x="${n(el.x)}" y="${n(el.y)}" width="${n(el.w)}" height="${n(el.h)}" href="${el.src}" preserveAspectRatio="xMidYMid meet"/>`;
  }
}

export function renderLabelSvg(
  template: Template,
  record: Record<string, string>,
  opts: { issues?: ElementIssue[]; background?: string; title?: string } = {},
): string {
  const { width_mm: w, height_mm: h } = template;
  const body = template.elements.map((el) => renderElement(el, record, opts.issues)).join('');
  const clip = `kura-clip-${++clipCounter}`;
  const title = opts.title ? `<title>${esc(opts.title)}</title>` : '';
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n(w)} ${n(h)}" width="${n(w)}mm" height="${n(h)}mm">` +
    title +
    `<rect width="${n(w)}" height="${n(h)}" fill="${opts.background ?? '#fff'}"/>` +
    `<clipPath id="${clip}"><rect width="${n(w)}" height="${n(h)}"/></clipPath>` +
    `<g clip-path="url(#${clip})">${body}</g></svg>`
  );
}
