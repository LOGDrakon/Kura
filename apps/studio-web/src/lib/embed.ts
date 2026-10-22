// Kura Studio est un fichier HTML unique. Les modèles de l'entreprise peuvent y être embarqués :
// l'admin exporte « son » éditeur, les autres postes l'ouvrent et retrouvent les modèles sans rien recréer.
import { normalizeData, type StudioData } from './model';

export const EMBED_ID = 'kura-data';

let pristineHtml: string | null = null;

/** À appeler avant tout rendu : mémorise le HTML d'origine du fichier (sans les modifications du DOM). */
export function captureSource(doc: Document = document): void {
  pristineHtml = '<!doctype html>\n' + doc.documentElement.outerHTML;
}

export function readEmbedded(doc: Document = document): StudioData | null {
  const el = doc.getElementById(EMBED_ID);
  const text = el?.textContent?.trim();
  if (!text) return null;
  try {
    return normalizeData(JSON.parse(text));
  } catch {
    return null;
  }
}

/** JSON sûr à l'intérieur d'une balise <script> (pas de « </script> » ni de commentaire HTML). */
export function scriptSafeJson(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
}

/** Produit une copie autonome de l'éditeur avec ces données embarquées. */
export function exportEditorHtml(data: StudioData, source: string | null = pristineHtml): string {
  if (!source) throw new Error("Le code source de l'éditeur n'a pas été capturé.");
  const parsed = new DOMParser().parseFromString(source, 'text/html');
  let el = parsed.getElementById(EMBED_ID);
  if (!el) {
    el = parsed.createElement('script');
    el.id = EMBED_ID;
    el.setAttribute('type', 'application/json');
    parsed.head.appendChild(el);
  }
  el.textContent = scriptSafeJson({ ...data, exported_at: new Date().toISOString() });
  return '<!doctype html>\n' + parsed.documentElement.outerHTML;
}
