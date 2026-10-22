// Test de bout en bout du fichier construit (dist/index.html), dans Chromium.
// Usage : npm run build && npm run test:e2e   (SCREENSHOTS=dossier pour enregistrer des captures)
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import assert from 'node:assert/strict';

const dist = resolve(import.meta.dirname, '../dist/index.html');
const axeSource = readFileSync(resolve(import.meta.dirname, '../node_modules/axe-core/axe.min.js'), 'utf8');
const shots = process.env.SCREENSHOTS;
if (shots) mkdirSync(shots, { recursive: true });

const executablePath = ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome', process.env.CHROME_PATH].find(
  (p) => p && existsSync(p),
);
const browser = await chromium.launch({ executablePath });
const errors = [];

async function open(url, viewport = { width: 1440, height: 900 }) {
  const context = await browser.newContext({ viewport, acceptDownloads: true });
  const page = await context.newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(url);
  await page.getByRole('heading', { name: 'Kura Studio' }).waitFor();
  return { context, page };
}

async function axe(page, label) {
  await page.addScriptTag({ content: axeSource });
  const result = await page.evaluate(async () => {
    const r = await window.axe.run(document, { resultTypes: ['violations'] });
    return r.violations.map((v) => `${v.impact} ${v.id}: ${v.nodes.length} × ${v.help}`);
  });
  const serious = result.filter((v) => /^(serious|critical)/.test(v));
  if (result.length) console.log(`axe (${label}):\n  ` + result.join('\n  '));
  assert.deepEqual(serious, [], `violations d'accessibilité graves (${label})`);
}

async function shot(page, name) {
  if (shots) await page.screenshot({ path: join(shots, `${name}.png`), fullPage: true });
}

const step = (name) => console.log(`• ${name}`);

// --- Éditeur ---
const { context, page } = await open('file://' + dist);
step('modèle de départ affiché');
await page.getByRole('button', { name: /QR Code « \{ref\} »/ }).waitFor();
await axe(page, 'éditeur');
await shot(page, '1-editeur');

step('ajout et édition d’un texte');
await page.getByRole('toolbar').getByRole('button', { name: 'Texte' }).click();
const content = page.locator('#inspector-first');
await content.fill('Marque : {brand}');
await page.getByRole('button', { name: /Texte « Marque : \{brand\} »/ }).waitFor();

step('déplacement au clavier (Maj + flèche = 5 mm)');
const box = page.getByRole('button', { name: /Texte « Marque/ });
await box.focus();
await page.keyboard.press('Shift+ArrowDown');
await page.getByRole('button', { name: /Texte « Marque.*y 7 mm/ }).waitFor();

step('glisser-déposer à la souris');
const b = await box.boundingBox();
await page.mouse.move(b.x + 5, b.y + 5);
await page.mouse.down();
await page.mouse.move(b.x + 45, b.y + 5, { steps: 5 });
await page.mouse.up();
const label = await box.getAttribute('aria-label');
assert.ok(!/x 2 mm/.test(label), `l'élément aurait dû bouger : ${label}`);

step('annuler / rétablir');
await page.getByRole('button', { name: 'Annuler', exact: true }).click();
await page.getByRole('button', { name: /Texte « Marque.*x 2 mm, y 7 mm/ }).waitFor();
await page.getByRole('button', { name: 'Rétablir' }).click();
await shot(page, '2-editeur-texte');

step('renommer le modèle');
await page.keyboard.press('Escape');
await page.locator('.stage').click({ position: { x: 5, y: 5 } });
await page.getByLabel('Nom', { exact: true }).fill('Bac atelier');

// --- Impression ---
step('impression : liste de références et PDF');
await page.getByRole('tab', { name: 'Impression' }).click();
await page.getByLabel(/Une référence par ligne/).fill('REF-0042\nREF-0043\nREF-0100');
await page.getByRole('button', { name: 'Planche A4' }).click();
await axe(page, 'impression');
await shot(page, '3-impression');
const [pdf] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Télécharger le PDF' }).click()]);
const pdfPath = join(tmpdir(), 'kura-e2e.pdf');
await pdf.saveAs(pdfPath);
assert.equal(readFileSync(pdfPath).subarray(0, 5).toString(), '%PDF-');

// --- Configuration ---
step('configuration : validation et QR');
await page.getByRole('tab', { name: 'Configuration' }).click();
await page.getByLabel(/Adresse de l'API/).fill('https://gmao.exemple.fr/api');
await page.getByText('✓ Configuration valide').waitFor();
await page.getByRole('button', { name: 'Générer le QR' }).click();
await page.getByRole('img', { name: 'QR code de configuration' }).waitFor();
await axe(page, 'configuration');
await shot(page, '4-configuration');

// --- Partage ---
step('partage : l’éditeur exporté embarque les modèles');
await page.getByRole('tab', { name: 'Partage' }).click();
await page.getByLabel(/Nom de l'entreprise/).fill('Atelier Dupont');
await shot(page, '5-partage');
const [dl] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: /Télécharger l'éditeur/ }).click()]);
const sharedPath = join(tmpdir(), 'kura-studio-partage.html');
await dl.saveAs(sharedPath);
await context.close();

// Un autre poste (navigateur vierge) ouvre l'éditeur partagé.
const other = await open('file://' + sharedPath);
await other.page.getByText('Atelier Dupont').first().waitFor();
await other.page.getByRole('button', { name: /^Bac atelier/ }).waitFor();
await other.page.getByRole('button', { name: /Texte « Marque : \{brand\} »/ }).waitFor();
await other.page.getByRole('tab', { name: 'Configuration' }).click();
assert.equal(await other.page.getByLabel(/Adresse de l'API/).inputValue(), 'https://gmao.exemple.fr/api');
// Le fichier partagé peut lui-même être repartagé (le HTML d'origine reste intact).
const html = readFileSync(sharedPath, 'utf8');
assert.equal((html.match(/id="kura-data"/g) || []).length, 1);
await other.context.close();

// --- Base de l'entreprise (serveur de démo) ---
step('base de l’entreprise : nouvelle entrée → étiquette');
const { createServer } = await import(resolve(import.meta.dirname, '../../../tools/mock-server/server.js'));
const mock = createServer();
await new Promise((r) => mock.listen(0, '127.0.0.1', r));
const api = `http://127.0.0.1:${mock.address().port}`;
// Une entrée est créée dans la base (pas dans Kura).
await fetch(`${api}/_debug/items`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ ref: 'REF-0500', name: 'Joint torique 20x2', brand: 'Parker', service: 'Hydraulique' }),
});
await fetch(`${api}/_debug/items`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ ref: 'REF-0501', name: '', brand: 'Parker' }),
});

const db = await open('file://' + dist);
await db.page.getByRole('tab', { name: 'Configuration' }).click();
await db.page.getByLabel(/Adresse de l'API/).fill(api);
await db.page.getByLabel('Authentification').selectOption('none');
await db.page.getByRole('tab', { name: 'Modèles' }).click();
// {name} devient obligatoire en plus de {ref}.
await db.page.locator('.stage').click({ position: { x: 5, y: 5 } });
await db.page.locator('.fields li', { hasText: '{name}' }).getByLabel('Obligatoire').check();

await db.page.getByRole('tab', { name: 'Impression' }).click();
await db.page.getByRole('button', { name: "Base de l'entreprise" }).click();
await db.page.getByLabel(/Rechercher/).fill('parker');
await db.page.getByRole('button', { name: 'Rechercher' }).click();
await db.page.getByRole('list', { name: 'Articles du serveur' }).getByText('REF-0500').waitFor();
// REF-0501 n'a pas de désignation : écartée.
await db.page.getByText(/1 étiquette non imprimée/).waitFor();
// brand et service ne sont pas sur le modèle.
const unplacedBox = db.page.locator('.unplaced');
await unplacedBox.getByText('{brand}').waitFor();
await unplacedBox.locator('li', { hasText: '{service}' }).getByRole('button', { name: 'Ignorer pour ce modèle' }).click();
await unplacedBox.locator('li', { hasText: '{brand}' }).getByRole('button', { name: 'Ajouter au modèle' }).click();
await unplacedBox.waitFor({ state: 'detached' });
await axe(db.page, 'impression depuis la base');
await shot(db.page, '8-base-entreprise');
const [dbPdf] = await Promise.all([db.page.waitForEvent('download'), db.page.getByRole('button', { name: /Télécharger le PDF \(1 exclue\)/ }).click()]);
await dbPdf.saveAs(join(tmpdir(), 'kura-e2e-db.pdf'));
// Une fois imprimée, REF-0500 disparaît des nouveautés.
await db.page.getByRole('button', { name: 'Rechercher' }).click();
await db.page.getByText(/déjà imprimé.? masqué/).waitFor();
assert.equal(await db.page.getByRole('list', { name: 'Articles du serveur' }).getByText('REF-0500').count(), 0);
// Le champ ajouté est sur le modèle.
await db.page.getByRole('tab', { name: 'Modèles' }).click();
await db.page.getByRole('button', { name: /Texte « \{brand\} »/ }).waitFor();
await db.context.close();
mock.close();

// --- Mobile ---
step('affichage mobile');
const mobile = await open('file://' + dist, { width: 390, height: 844 });
const overflow = await mobile.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
assert.ok(overflow <= 1, `défilement horizontal sur mobile (${overflow}px)`);
await axe(mobile.page, 'mobile');
await shot(mobile.page, '6-mobile');
await mobile.page.getByRole('tab', { name: 'Impression' }).click();
await shot(mobile.page, '7-mobile-impression');
await mobile.context.close();

await browser.close();
assert.deepEqual(errors, [], 'erreurs dans la console');
console.log('✓ parcours complet OK');
writeFileSync(join(tmpdir(), 'kura-e2e.ok'), 'ok');
