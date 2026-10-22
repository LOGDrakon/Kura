// Mise en page du texte avec les métriques Helvetica de pdf-lib, pour que l'aperçu SVG et le PDF coïncident.
import { Encodings, Font, FontNames } from '@pdf-lib/standard-fonts';
import { MM_PER_PT, type TextElement } from './model';

const fonts = {
  regular: Font.load(FontNames.Helvetica),
  bold: Font.load(FontNames.HelveticaBold),
};

const LINE_HEIGHT = 1.2;
const MIN_SIZE_PT = 4;

/** Remplace les caractères hors WinAnsi (non imprimables avec les polices PDF standard). */
export function printable(text: string): string {
  let out = '';
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    if (ch === '\n' || Encodings.WinAnsi.canEncodeUnicodeCodePoint(cp)) out += ch;
    else out += '?';
  }
  return out;
}

/** Largeur d'un texte en mm. */
export function textWidth(text: string, sizePt: number, bold: boolean): number {
  const font = bold ? fonts.bold : fonts.regular;
  let units = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    if (!Encodings.WinAnsi.canEncodeUnicodeCodePoint(cp)) continue;
    const glyph = Encodings.WinAnsi.encodeUnicodeCodePoint(cp).name;
    units += font.getWidthOfGlyph(glyph) ?? 556;
  }
  return (units / 1000) * sizePt * MM_PER_PT;
}

function wrap(text: string, maxW: number, sizePt: number, bold: boolean): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split('\n')) {
    const words = paragraph.split(/ +/);
    let line = '';
    for (const word of words) {
      const candidate = line ? `${line} ${word}` : word;
      if (!line || textWidth(candidate, sizePt, bold) <= maxW) {
        line = candidate;
        continue;
      }
      lines.push(line);
      line = word;
    }
    // Un mot plus large que le cadre est coupé par caractères.
    while (textWidth(line, sizePt, bold) > maxW && line.length > 1) {
      let cut = line.length - 1;
      while (cut > 1 && textWidth(line.slice(0, cut), sizePt, bold) > maxW) cut--;
      lines.push(line.slice(0, cut));
      line = line.slice(cut);
    }
    lines.push(line);
  }
  return lines;
}

export interface TextLayout {
  sizePt: number;
  /** Lignes avec leur position (mm, relative à l'élément ; y = ligne de base). */
  lines: { text: string; x: number; y: number }[];
  overflow: boolean;
}

export function layoutText(el: Pick<TextElement, 'w' | 'h' | 'size_pt' | 'bold' | 'align' | 'valign' | 'shrink'>, raw: string): TextLayout {
  const text = printable(raw);
  let size = el.size_pt;
  let lines = wrap(text, el.w, size, el.bold);
  const height = (n: number, s: number) => n * s * MM_PER_PT * LINE_HEIGHT;

  if (el.shrink) {
    const fits = () =>
      height(lines.length, size) <= el.h + 1e-6 && lines.every((l) => textWidth(l, size, el.bold) <= el.w + 1e-6);
    while (!fits() && size > MIN_SIZE_PT) {
      size = Math.max(MIN_SIZE_PT, Math.round((size - 0.25) * 100) / 100);
      lines = wrap(text, el.w, size, el.bold);
    }
  }

  const lineH = size * MM_PER_PT * LINE_HEIGHT;
  const blockH = height(lines.length, size);
  const overflow = blockH > el.h + 1e-6;
  let top = 0;
  if (el.valign === 'middle') top = (el.h - blockH) / 2;
  if (el.valign === 'bottom') top = el.h - blockH;
  // Ligne de base : ascendante Helvetica ≈ 0,718 em, centrée dans l'interligne.
  const ascent = size * MM_PER_PT * (0.718 + (LINE_HEIGHT - 1) / 2);

  return {
    sizePt: size,
    overflow,
    lines: lines.map((l, i) => {
      const w = textWidth(l, size, el.bold);
      const x = el.align === 'center' ? (el.w - w) / 2 : el.align === 'right' ? el.w - w : 0;
      return { text: l, x, y: top + i * lineH + ascent };
    }),
  };
}
