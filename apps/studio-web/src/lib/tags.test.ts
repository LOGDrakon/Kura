import { describe, expect, it } from 'vitest';
import { columnLabel, tagGroups } from './tags';

const items: Record<string, string>[] = [
  { ref: 'A1', name: 'Vis', service: 'Mécanique', brand: 'Würth', location: 'A12', unit: 'pcs', quantity: '250' },
  { ref: 'A2', name: 'Écrou', service: 'Mécanique', brand: 'Würth', location: 'A13', unit: 'pcs', quantity: '400' },
  { ref: 'B1', name: 'Courroie', service: 'Maintenance', brand: 'Optibelt', location: 'B04', unit: 'pcs', quantity: '6' },
  { ref: 'B2', name: 'Roulement', service: 'Maintenance', brand: 'SKF', location: 'C01', lot: '1234' },
  { ref: 'C1', name: 'Graisse', note: 'à commander' },
];

describe('étiquettes de tri', () => {
  it('propose les colonnes qui regroupent, service en premier', () => {
    const groups = tagGroups(items);
    expect(groups.map((g) => g.column)).toEqual(['service', 'brand']);
    expect(groups[0].values).toEqual([
      { value: 'Maintenance', count: 2 },
      { value: 'Mécanique', count: 2 },
    ]);
  });

  it('écarte identifiants, emplacements, unités, nombres et valeurs toutes différentes', () => {
    const columns = tagGroups(items).map((g) => g.column);
    for (const c of ['ref', 'name', 'location', 'unit', 'quantity', 'lot', 'note']) expect(columns).not.toContain(c);
  });

  it('rien à proposer pour un seul article', () => {
    expect(tagGroups(items.slice(0, 1))).toEqual([]);
  });

  it('libellés', () => {
    expect(columnLabel('service')).toBe('Service');
    expect(columnLabel('brand')).toBe('Marque');
    expect(columnLabel('code_atelier')).toBe('Code atelier');
  });
});
