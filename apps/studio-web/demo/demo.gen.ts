// Prépare l'essai grandeur nature (lancé par demo/build-demo.sh, pas par `npm test`) :
//  - dist/kura-studio-demo.html : Studio avec la config du serveur de démo et deux modèles ;
//  - dist/etiquettes-existantes.pdf : étiquettes « déjà imprimées » de quelques articles de la base.
import { test } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { buildPdf } from '../src/lib/pdf';
import { customSheet } from '../src/lib/layout';
import { scriptSafeJson } from '../src/lib/embed';
import { starterTemplate, uid, now, type StudioData, type Template } from '../src/lib/model';

const BASE_URL = process.env.KURA_DEMO_URL ?? 'http://localhost:8787';
const PREMADE = ['REF-0042', 'REF-0100', '3760123456789'];

function fullTemplate(): Template {
  return {
    id: uid('t'),
    name: 'Étiquette complète 60 × 30',
    width_mm: 60,
    height_mm: 30,
    elements: [
      { id: uid(), kind: 'code', x: 2, y: 2, w: 26, h: 26, symbology: 'qrcode', content: '{ref}', eclevel: 'M' },
      { id: uid(), kind: 'text', x: 30, y: 2.5, w: 28, h: 6, content: '{ref}', size_pt: 11, bold: true, align: 'left', valign: 'top', shrink: true, color: '#000000' },
      { id: uid(), kind: 'text', x: 30, y: 9, w: 28, h: 10, content: '{name}', size_pt: 8, bold: false, align: 'left', valign: 'top', shrink: true, color: '#000000' },
      { id: uid(), kind: 'text', x: 30, y: 19.5, w: 28, h: 4, content: '{brand}', size_pt: 7, bold: false, align: 'left', valign: 'top', shrink: true, color: '#444444' },
      { id: uid(), kind: 'text', x: 30, y: 24, w: 28, h: 4, content: 'Empl. {location}', size_pt: 7, bold: true, align: 'left', valign: 'top', shrink: true, color: '#000000' },
    ],
    sample: { ref: 'REF-0042', name: 'Vis M6 x 20 inox', brand: 'Würth', location: 'A12' },
    required: ['ref', 'name'],
    ignored: [],
    updated_at: now(),
  };
}

test('génère les fichiers de démo', async () => {
  const starter = starterTemplate();
  const full = fullTemplate();
  const config = JSON.parse(readFileSync('../../tools/mock-server/demo/kura-config.demo.json', 'utf8'));
  config.server.base_url = BASE_URL;
  const data: StudioData = {
    kind: 'kura-studio', version: 1, company: 'Démo Kura',
    templates: [starter, full], deleted: {}, config, config_updated_at: now(),
  };
  const html = readFileSync('dist/index.html', 'utf8').replace(
    /(<script type="application\/json" id="kura-data">)[\s\S]*?(<\/script>)/,
    (_, open, close) => open + scriptSafeJson({ ...data, exported_at: now() }) + close,
  );
  if (!html.includes('"Démo Kura"')) throw new Error('bloc kura-data introuvable dans dist/index.html');
  writeFileSync('dist/kura-studio-demo.html', html);

  const items = JSON.parse(readFileSync('../../tools/mock-server/data/items.json', 'utf8')) as Record<string, unknown>[];
  const rows = items.filter((it) => PREMADE.includes(String(it.ref)))
    .map((it) => Object.fromEntries(Object.entries(it).map(([k, v]) => [k, String(v)])));
  const pdf = await buildPdf(full, rows, { layout: customSheet(full), copies: 1, skip: 0, outline: true });
  writeFileSync('dist/etiquettes-existantes.pdf', pdf);
});
