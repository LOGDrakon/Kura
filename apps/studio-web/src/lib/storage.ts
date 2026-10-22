// Sauvegarde locale (navigateur). Toujours tolérante : navigation privée, stockage bloqué, quota plein.
import { normalizeData, type StudioData } from './model';

const KEY = 'kura-studio:data';
const PREFS = 'kura-studio:prefs';

export function loadLocal(): StudioData | null {
  try {
    const text = localStorage.getItem(KEY);
    return text ? normalizeData(JSON.parse(text)) : null;
  } catch {
    return null;
  }
}

export function saveLocal(data: StudioData): boolean {
  try {
    localStorage.setItem(KEY, JSON.stringify(data));
    return true;
  } catch {
    return false;
  }
}

export function loadPrefs<T extends object>(defaults: T): T {
  try {
    const text = localStorage.getItem(PREFS);
    return text ? { ...defaults, ...JSON.parse(text) } : defaults;
  } catch {
    return defaults;
  }
}

export function savePrefs(prefs: object): void {
  try {
    localStorage.setItem(PREFS, JSON.stringify(prefs));
  } catch {
    /* préférence de confort uniquement */
  }
}

export function download(name: string, content: BlobPart, type: string): void {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function slug(text: string): string {
  return (
    text
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '') || 'kura'
  );
}
