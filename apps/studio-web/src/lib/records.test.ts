import { describe, expect, it } from 'vitest';
import { fillText } from './fields';
import { starterTemplate } from './model';
import { checkBatch, checkRecord, flattenItem, templateFields } from './records';
import { listItems, ServerError } from './server';
import { renderLabelSvg, type ElementIssue } from './render-svg';

describe('champs facultatifs', () => {
  it('une ligne dont tous les champs sont vides disparaît', () => {
    const tpl = 'Réf. {ref}\nMarque : {brand}\nService : {service} / {location}\nTexte fixe';
    expect(fillText(tpl, { ref: 'A1', location: 'B4' })).toBe('Réf. A1\nService :  / B4\nTexte fixe');
    expect(fillText(tpl, { ref: 'A1', brand: '  ' })).toBe('Réf. A1\nTexte fixe');
  });

  it('un code dont le contenu est vide n’est pas dessiné et ne lève pas d’erreur', () => {
    const t = starterTemplate();
    const issues: ElementIssue[] = [];
    const svg = renderLabelSvg(t, { name: 'Sans réf' }, { issues });
    expect(issues).toEqual([]);
    expect(svg).not.toContain('<path');
  });
});

describe('contrôle des lignes', () => {
  const t = { ...starterTemplate(), required: ['ref', 'name'], ignored: ['quantity'] };

  it('champs du modèle', () => {
    expect(templateFields(t).sort()).toEqual(['name', 'ref']);
  });

  it('obligatoire vide → écartée ; champ non placé → signalé ; ignorés et techniques → silencieux', () => {
    expect(checkRecord(t, { ref: 'A1', name: '', brand: 'SKF', quantity: '4', updated_at: 'x' })).toEqual({
      missing: ['name'],
      unplaced: ['brand'],
    });
    const batch = checkBatch(t, [
      { ref: 'A1', name: 'Vis', brand: 'SKF' },
      { ref: '', name: 'Écrou', brand: 'Würth', service: 'Méca' },
      { ref: 'A3', name: 'Joint', brand: '' },
    ]);
    expect(batch.valid.map((r) => r.ref)).toEqual(['A1', 'A3']);
    expect(batch.rejected).toEqual([{ index: 2, record: expect.any(Object), missing: ['ref'] }]);
    expect([...batch.unplaced]).toEqual([['brand', 2], ['service', 1]]);
  });
});

describe('articles du serveur', () => {
  it('aplatit et normalise les champs comme les colonnes CSV', () => {
    expect(flattenItem({ ref: 'A1', Désignation: 'Vis', stock: { disponible: 4 }, tags: ['a', 'b'], prix: null })).toEqual({
      ref: 'A1', designation: 'Vis', stock_disponible: '4', tags: 'a, b', prix: '',
    });
  });

  const config = { server: { base_url: 'https://gmao.fr/api/', auth: { type: 'bearer' } } };

  it('appelle GET /items avec recherche, date et jeton', async () => {
    let seen: { url: string; init: RequestInit } | null = null;
    const fake = (async (url: URL, init: RequestInit) => {
      seen = { url: String(url), init };
      return new Response(JSON.stringify({ items: [{ ref: 'A1', name: 'Vis' }], next_cursor: '50' }), { status: 200 });
    }) as unknown as typeof fetch;
    const page = await listItems(config, 'tok', { q: 'vis', updatedSince: '2026-10-01T00:00:00.000Z' }, fake);
    expect(page).toEqual({ items: [{ ref: 'A1', name: 'Vis' }], next: '50' });
    expect(seen!.url).toBe('https://gmao.fr/api/items?q=vis&updated_since=2026-10-01T00%3A00%3A00.000Z&limit=100');
    expect((seen!.init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
  });

  it('messages clairs en cas d’échec', async () => {
    const status = (code: number) => (async () => new Response('', { status: code })) as unknown as typeof fetch;
    await expect(listItems(config, '', {}, status(401))).rejects.toThrow(/jeton/);
    await expect(listItems(config, '', {}, status(404))).rejects.toThrow(/CSV/);
    const down = (async () => { throw new TypeError('Failed to fetch'); }) as unknown as typeof fetch;
    await expect(listItems(config, '', {}, down)).rejects.toBeInstanceOf(ServerError);
  });
});

describe('placement d’un champ ajouté', async () => {
  const { freeSpot, placeFieldElement } = await import('./records');
  it('trouve la bande libre la plus large, sans chevauchement', () => {
    const t = { ...starterTemplate(), height_mm: 30 };
    const spot = freeSpot(t, 4)!;
    expect(spot).toMatchObject({ x: 1, y: 23, w: 48 });
    for (const e of t.elements) {
      const apart = spot.x >= e.x + e.w || e.x >= spot.x + spot.w || spot.y >= e.y + e.h || e.y >= spot.y + spot.h;
      expect(apart).toBe(true);
    }
  });
  it('étiquette pleine : en bas, à ajuster', () => {
    const t = starterTemplate();
    expect(freeSpot(t, 4)).toBeNull();
    expect(placeFieldElement(t, 'brand')).toMatchObject({ x: 2, content: '{brand}' });
  });
});
