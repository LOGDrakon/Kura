// Champs {colonne} dans les textes et contenus de codes.

const FIELD_RE = /\{([^{}\n]{1,60})\}/g;

/** Remplace {colonne} par la valeur de l'enregistrement. Un champ absent devient vide. */
export function fill(template: string, record: Record<string, string>): string {
  return template.replace(FIELD_RE, (_, name: string) => record[name.trim()] ?? '');
}

/** Liste des champs utilisés, dans l'ordre d'apparition, sans doublon. */
export function fieldsOf(template: string): string[] {
  const out: string[] = [];
  for (const m of template.matchAll(FIELD_RE)) {
    const name = m[1].trim();
    if (!out.includes(name)) out.push(name);
  }
  return out;
}
