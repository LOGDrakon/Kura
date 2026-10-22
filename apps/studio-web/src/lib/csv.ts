// Lecture CSV tolérante : séparateur détecté (; , tabulation), guillemets, BOM, fins de ligne Windows.

export interface Table {
  columns: string[];
  rows: Record<string, string>[];
}

function detectDelimiter(text: string): string {
  const firstLine = text.split(/\r?\n/, 1)[0] ?? '';
  const counts = [';', ',', '\t'].map((d) => [d, firstLine.split(d).length - 1] as const);
  counts.sort((a, b) => b[1] - a[1]);
  return counts[0][1] > 0 ? counts[0][0] : ';';
}

function parseRows(text: string, delimiter: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += c;
      continue;
    }
    if (c === '"' && field === '') quoted = true;
    else if (c === delimiter) {
      row.push(field);
      field = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else field += c;
  }
  if (field !== '' || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((v) => v.trim() !== ''));
}

/** Nom de colonne utilisable dans {champ} : minuscules, sans accent ni espace. */
export function columnKey(header: string, index: number): string {
  const key = header
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return key || `colonne_${index + 1}`;
}

export function parseCsv(input: string): Table {
  const text = input.replace(/^﻿/, '');
  const raw = parseRows(text, detectDelimiter(text));
  if (raw.length === 0) return { columns: [], rows: [] };
  const columns: string[] = [];
  raw[0].forEach((h, i) => {
    let key = columnKey(h, i);
    while (columns.includes(key)) key += '_';
    columns.push(key);
  });
  const rows = raw.slice(1).map((values) => {
    const record: Record<string, string> = {};
    columns.forEach((c, i) => (record[c] = (values[i] ?? '').trim()));
    return record;
  });
  return { columns, rows };
}

export function toCsv(table: Table): string {
  const q = (v: string) => (/[;"\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  return [table.columns.join(';'), ...table.rows.map((r) => table.columns.map((c) => q(r[c] ?? '')).join(';'))].join('\r\n');
}
