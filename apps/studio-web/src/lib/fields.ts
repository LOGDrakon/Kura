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

/**
 * Texte d'étiquette : comme fill(), mais une ligne dont tous les champs sont vides disparaît.
 * « Marque : {brand} » n'est donc pas imprimé pour un article sans marque : un même modèle
 * sert à plusieurs services, avec des champs facultatifs.
 */
export function fillText(template: string, record: Record<string, string>): string {
  const lines: string[] = [];
  for (const line of template.split('\n')) {
    const names = fieldsOf(line);
    if (names.length && names.every((n) => !(record[n] ?? '').trim())) continue;
    lines.push(fill(line, record));
  }
  return lines.join('\n');
}
