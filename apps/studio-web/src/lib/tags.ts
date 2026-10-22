// Étiquettes de tri (service, catégorie, marque…) déduites des articles de la base :
// aucune configuration, Studio repère les colonnes dont les valeurs reviennent d'un article à l'autre.

export interface TagGroup {
  column: string;
  values: { value: string; count: number }[];
}

/** Colonnes jamais proposées : identifiants, textes libres, quantités et champs techniques. */
const EXCLUDED = new Set(['ref', 'id', 'name', 'designation', 'description', 'quantity', 'unit', 'updated_at', 'image_url', 'location']);

/** Colonnes de regroupement connues : proposées en premier, dans cet ordre. */
const PREFERRED = ['service', 'departement', 'department', 'category', 'categorie', 'famille', 'family', 'zone', 'type', 'brand', 'marque', 'fournisseur', 'supplier'];

const MAX_VALUES = 15;
const MAX_LENGTH = 40;

export function tagGroups(items: Record<string, string>[], idField = 'ref'): TagGroup[] {
  if (items.length < 2) return [];
  const columns = new Set(items.flatMap((it) => Object.keys(it)));
  const groups: TagGroup[] = [];
  for (const column of columns) {
    if (column === idField || EXCLUDED.has(column.toLowerCase())) continue;
    const counts = new Map<string, number>();
    let valid = true;
    for (const it of items) {
      const v = String(it[column] ?? '').trim();
      if (!v) continue;
      if (v.length > MAX_LENGTH || /^-?\d+([.,]\d+)?$/.test(v)) { valid = false; break; }
      counts.set(v, (counts.get(v) ?? 0) + 1);
    }
    // Une colonne de tri regroupe : peu de valeurs différentes, et au moins une partagée par plusieurs articles.
    if (!valid || counts.size === 0 || counts.size > MAX_VALUES || ![...counts.values()].some((c) => c > 1)) continue;
    groups.push({
      column,
      values: [...counts].map(([value, count]) => ({ value, count })).sort((a, b) => a.value.localeCompare(b.value, 'fr')),
    });
  }
  const rank = (c: string) => {
    const i = PREFERRED.indexOf(c.toLowerCase());
    return i < 0 ? PREFERRED.length : i;
  };
  return groups.sort((a, b) => rank(a.column) - rank(b.column) || a.column.localeCompare(b.column));
}

/** Libellé lisible d'une colonne (« service » → « Service »). */
export function columnLabel(column: string): string {
  const known: Record<string, string> = { brand: 'Marque', category: 'Catégorie', categorie: 'Catégorie', supplier: 'Fournisseur', family: 'Famille', department: 'Service', departement: 'Service' };
  const label = known[column.toLowerCase()] ?? column.replace(/_/g, ' ');
  return label.charAt(0).toUpperCase() + label.slice(1);
}
