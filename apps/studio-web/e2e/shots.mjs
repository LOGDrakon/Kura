// Captures de toutes les vues (revue UX). Usage : node e2e/shots.mjs <dossier> <préfixe>
import { chromium } from 'playwright-core';
import { resolve } from 'node:path';

const [dir, prefix = 'shot'] = process.argv.slice(2);
const file = resolve(import.meta.dirname, '../dist/kura-studio-demo.html');
const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH });

for (const [vp, size] of [['desktop', { width: 1440, height: 900 }], ['mobile', { width: 390, height: 844 }]]) {
  const ctx = await browser.newContext({ viewport: size, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  await page.goto('file://' + file);
  await page.waitForTimeout(500);
  const shot = (name) => page.screenshot({ path: `${dir}/${prefix}-${vp}-${name}.png`, fullPage: true });
  await shot('01-home');
  const tabs = await page.getByRole('tab').all();
  for (let i = 0; i < tabs.length; i++) {
    const label = (await tabs[i].innerText()).trim().split('\n').pop().toLowerCase().replace(/[^a-z]+/g, '');
    await tabs[i].click();
    await page.waitForTimeout(300);
    await shot(`0${i + 2}-${label}`);
    // Dans la vue d'impression, lancer une recherche dans la base si possible.
    const search = page.getByRole('button', { name: /Rechercher|Charger les articles|Afficher les articles/ });
    if (await search.count()) {
      await search.first().click();
      await page.waitForTimeout(800);
      await shot(`0${i + 2}-${label}-search`);
    }
  }
  await ctx.close();
}
await browser.close();
