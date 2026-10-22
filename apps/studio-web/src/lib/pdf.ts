// Génération du PDF d'impression : vectoriel, à l'échelle exacte.
import { PDFDocument, StandardFonts, rgb, type PDFFont, type PDFImage, type PDFPage } from 'pdf-lib';
import { codeGeometry } from './codes';
import { fill } from './fields';
import { planPages } from './layout';
import type { LabelElement, PrintOptions, Template } from './model';
import { layoutText } from './text';

const PT = 72 / 25.4;

function color(hex: string) {
  const v = Number.parseInt(hex.slice(1), 16);
  return rgb(((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255);
}

function roundedRectPath(w: number, h: number, r: number): string {
  const rr = Math.max(0, Math.min(r, w / 2, h / 2));
  if (rr === 0) return `M0 0H${w}V${h}H0Z`;
  return (
    `M${rr} 0H${w - rr}A${rr} ${rr} 0 0 1 ${w} ${rr}V${h - rr}A${rr} ${rr} 0 0 1 ${w - rr} ${h}` +
    `H${rr}A${rr} ${rr} 0 0 1 0 ${h - rr}V${rr}A${rr} ${rr} 0 0 1 ${rr} 0Z`
  );
}

interface Ctx {
  doc: PDFDocument;
  regular: PDFFont;
  bold: PDFFont;
  images: Map<string, PDFImage>;
}

async function image(ctx: Ctx, src: string): Promise<PDFImage | null> {
  if (!src) return null;
  let img = ctx.images.get(src);
  if (!img) {
    const bytes = Uint8Array.from(atob(src.slice(src.indexOf(',') + 1)), (c) => c.charCodeAt(0));
    img = src.startsWith('data:image/png') ? await ctx.doc.embedPng(bytes) : await ctx.doc.embedJpg(bytes);
    ctx.images.set(src, img);
  }
  return img;
}

async function drawElement(ctx: Ctx, page: PDFPage, el: LabelElement, ox: number, oy: number, record: Record<string, string>) {
  const pageH = page.getHeight();
  // Coin haut gauche de l'élément en points PDF (origine en bas à gauche).
  const left = (ox + el.x) * PT;
  const top = pageH - (oy + el.y) * PT;

  switch (el.kind) {
    case 'code': {
      let rects;
      try {
        rects = codeGeometry(el, fill(el.content, record)).rects;
      } catch {
        return; // Signalé dans l'interface avant l'export.
      }
      const d = rects.map((r) => `M${r.x} ${r.y}h${r.w}v${r.h}h${-r.w}z`).join('');
      if (d) page.drawSvgPath(d, { x: left, y: top, scale: PT, color: rgb(0, 0, 0), borderWidth: 0 });
      return;
    }
    case 'text': {
      const layout = layoutText(el, fill(el.content, record));
      const font = el.bold ? ctx.bold : ctx.regular;
      for (const line of layout.lines) {
        if (!line.text) continue;
        page.drawText(line.text, {
          x: left + line.x * PT,
          y: top - line.y * PT,
          size: layout.sizePt,
          font,
          color: color(el.color),
        });
      }
      return;
    }
    case 'rect': {
      const s = el.filled ? 0 : el.stroke_mm;
      const path = roundedRectPath(el.w - s, el.h - s, el.radius_mm);
      page.drawSvgPath(path, {
        x: left + (s / 2) * PT,
        y: top - (s / 2) * PT,
        scale: PT,
        ...(el.filled
          ? { color: color(el.color) }
          : { borderColor: color(el.color), borderWidth: s * PT }),
      });
      return;
    }
    case 'image': {
      const img = await image(ctx, el.src);
      if (!img) return;
      const ratio = Math.min(el.w / img.width, el.h / img.height);
      const w = img.width * ratio;
      const h = img.height * ratio;
      page.drawImage(img, {
        x: left + ((el.w - w) / 2) * PT,
        y: top - ((el.h - h) / 2 + h) * PT,
        width: w * PT,
        height: h * PT,
      });
    }
  }
}

export async function buildPdf(template: Template, records: Record<string, string>[], options: PrintOptions): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  doc.setTitle(`Étiquettes : ${template.name}`);
  doc.setCreator('Kura Studio');
  doc.setProducer('Kura Studio');
  const ctx: Ctx = {
    doc,
    regular: await doc.embedFont(StandardFonts.Helvetica),
    bold: await doc.embedFont(StandardFonts.HelveticaBold),
    images: new Map(),
  };

  const copies = Math.max(1, Math.floor(options.copies));
  const labels = records.flatMap((r) => Array.from({ length: copies }, () => r));
  const plan = planPages(options.layout, template, labels.length, options.skip);
  const pages: PDFPage[] = [];
  for (let i = 0; i < plan.pages; i++) pages.push(doc.addPage([plan.page_w * PT, plan.page_h * PT]));

  for (const p of plan.placements) {
    const page = pages[p.page];
    const record = labels[p.index];
    for (const el of template.elements) await drawElement(ctx, page, el, p.x, p.y, record);
    if (options.outline) {
      page.drawRectangle({
        x: p.x * PT,
        y: page.getHeight() - (p.y + template.height_mm) * PT,
        width: template.width_mm * PT,
        height: template.height_mm * PT,
        borderColor: rgb(0.6, 0.6, 0.6),
        borderWidth: 0.25,
      });
    }
  }
  return doc.save();
}
