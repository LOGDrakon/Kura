<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { parseCsv, toCsv } from '../lib/csv';
  import { fieldsOf } from '../lib/fields';
  import { SHEET_PRESETS, customSheet, planPages, sheetFromPreset } from '../lib/layout';
  import { renderLabelSvg, type ElementIssue } from '../lib/render-svg';
  import { buildPdf } from '../lib/pdf';
  import { download, slug } from '../lib/storage';
  import type { SheetLayout } from '../lib/model';
  import { checkBatch } from '../lib/records';
  import ServerSource from './ServerSource.svelte';

  let { onopenconfig }: { onopenconfig?: () => void } = $props();

  const t = $derived(studio.current);
  const serverConfigured = /^https?:\/\/.+/.test(String((studio.data.config as any)?.server?.base_url ?? ''));
  let source = $state<'server' | 'csv' | 'list'>(studio.table.rows.length ? 'csv' : serverConfigured ? 'server' : 'list');
  const idField = $derived(String((studio.data.config as any)?.scan?.id_field ?? 'ref'));
  let listText = $state('');
  let busy = $state(false);
  let error = $state('');

  /** Champs utilisés par le modèle, pour vérifier que le fichier les contient. */
  const usedFields = $derived(
    t ? [...new Set(t.elements.flatMap((e) => (e.kind === 'text' || e.kind === 'code' ? fieldsOf(e.content) : [])))] : [],
  );
  /** Contrôle des lignes : champs obligatoires vides (écartées), champs non placés sur le modèle. */
  const check = $derived(t && studio.table.rows.length ? checkBatch(t, studio.table.rows) : null);
  const unplaced = $derived(check ? [...check.unplaced.entries()] : []);

  function applyList() {
    const refs = listText.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    studio.table = { columns: ['ref'], rows: refs.map((ref) => ({ ref })) };
  }

  async function importFile(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    error = '';
    const buffer = await file.arrayBuffer();
    // Excel exporte souvent en Windows-1252 : on bascule si l'UTF-8 est invalide.
    let text: string;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
    } catch {
      text = new TextDecoder('windows-1252').decode(buffer);
    }
    const table = parseCsv(text);
    if (!table.rows.length) {
      error = 'Aucune ligne trouvée. Le fichier doit avoir une ligne d’en-têtes puis une ligne par article.';
      return;
    }
    studio.table = table;
    source = 'csv';
    studio.announce(`${table.rows.length} lignes importées`);
  }

  // --- Mise en page ---

  const layoutKind = $derived(studio.print.layout.kind);

  function setRoll() {
    studio.print.layout = { kind: 'roll' };
  }
  function setSheet(preset: string) {
    if (!t) return;
    studio.print.layout = preset === 'custom' ? customSheet(t) : sheetFromPreset(preset);
  }

  const records = $derived(check ? check.valid : t ? [t.sample] : []);
  const total = $derived(records.length * Math.max(1, studio.print.copies));
  const plan = $derived(t ? planPages(studio.print.layout, t, total, studio.print.skip) : null);

  /** Erreurs de génération de codes sur l'ensemble des lignes (ex. EAN invalide). */
  const rowIssues = $derived.by(() => {
    if (!t || !check) return [];
    const out: { row: number; message: string }[] = [];
    check.valid.forEach((r, i) => {
      if (out.length >= 8) return;
      const issues: ElementIssue[] = [];
      renderLabelSvg({ ...t, elements: t.elements.filter((e) => e.kind === 'code') }, r, { issues });
      for (const issue of issues) out.push({ row: i + 2, message: issue.message });
    });
    return out;
  });

  /** Aperçu de la première page. */
  const pagePreview = $derived.by(() => {
    if (!t || !plan) return '';
    const n = (v: number) => Math.round(v * 100) / 100;
    const copies = Math.max(1, studio.print.copies);
    const placements = plan.placements.filter((p) => p.page === 0).slice(0, 120);
    const cells: string[] = [];
    const layout = studio.print.layout;
    if (layout.kind === 'sheet') {
      for (let i = 0; i < layout.cols * layout.rows; i++) {
        const x = layout.margin_left + (i % layout.cols) * (layout.label_w + layout.gap_x);
        const y = layout.margin_top + Math.floor(i / layout.cols) * (layout.label_h + layout.gap_y);
        cells.push(`<rect x="${n(x)}" y="${n(y)}" width="${n(layout.label_w)}" height="${n(layout.label_h)}" rx="1.5" fill="none" stroke="#c7ccd4" stroke-width="0.3"/>`);
      }
    }
    const labels = placements.map((p) => {
      const svg = renderLabelSvg(t, records[Math.floor(p.index / copies)] ?? {});
      const inner = svg.replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '');
      return `<svg x="${n(p.x)}" y="${n(p.y)}" width="${n(t.width_mm)}" height="${n(t.height_mm)}" viewBox="0 0 ${n(t.width_mm)} ${n(t.height_mm)}" overflow="hidden">${inner}</svg>`;
    });
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n(plan.page_w)} ${n(plan.page_h)}"><rect width="100%" height="100%" fill="#fff"/>${cells.join('')}${labels.join('')}</svg>`;
  });

  async function exportPdf() {
    if (!t) return;
    busy = true;
    error = '';
    try {
      const bytes = await buildPdf($state.snapshot(t) as typeof t, records, $state.snapshot(studio.print));
      download(`etiquettes-${slug(t.name)}.pdf`, bytes as BlobPart, 'application/pdf');
      if (check) studio.markPrinted(check.valid.map((r) => r[idField] ?? r.ref));
      studio.announce(`PDF généré : ${total} étiquette${total > 1 ? 's' : ''}`);
    } catch (e) {
      error = `Échec de la génération : ${(e as Error).message}`;
    } finally {
      busy = false;
    }
  }

  function exportSvg() {
    if (!t) return;
    download(`etiquette-${slug(t.name)}.svg`, renderLabelSvg(t, records[0] ?? {}), 'image/svg+xml');
  }

  function exportCsvTemplate() {
    if (!t) return;
    const columns = usedFields.length ? usedFields : ['ref'];
    download(`modele-${slug(t.name)}.csv`, '﻿' + toCsv({ columns, rows: [Object.fromEntries(columns.map((c) => [c, t.sample[c] ?? '']))] }), 'text/csv');
  }

  function numInput(e: Event, apply: (v: number) => void) {
    const v = Number.parseFloat((e.currentTarget as HTMLInputElement).value.replace(',', '.'));
    if (Number.isFinite(v)) apply(v);
  }
</script>

{#if t}
  <div class="print">
    <div class="side">
      <section class="card pad" aria-labelledby="data-title">
        <h2 id="data-title">1. Données</h2>
        <div class="segmented" role="group" aria-label="Source des données">
          <button aria-pressed={source === 'server'} onclick={() => (source = 'server')}>Base de l'entreprise</button>
          <button aria-pressed={source === 'csv'} onclick={() => (source = 'csv')}>Fichier CSV</button>
          <button aria-pressed={source === 'list'} onclick={() => (source = 'list')}>Liste de références</button>
        </div>

        {#if source === 'server'}
          <ServerSource {onopenconfig} />
        {:else if source === 'list'}
          <label class="field">
            <span>Une référence par ligne (champ {'{ref}'})</span>
            <textarea bind:value={listText} oninput={applyList} rows="6" class="mono" placeholder="REF-0042&#10;REF-0043"></textarea>
          </label>
        {:else}
          <div class="buttons">
            <label class="btn">
              <Icon name="upload" /> Importer un CSV
              <input class="sr-only" type="file" accept=".csv,.txt,text/csv" onchange={importFile} />
            </label>
            <button class="btn ghost" onclick={exportCsvTemplate}><Icon name="download" /> Fichier exemple</button>
          </div>
          <p class="hint">Depuis Excel : « Enregistrer sous » → CSV (séparateur point-virgule). La première ligne contient les noms de colonnes.</p>
        {/if}

        {#if studio.table.rows.length}
          <p><strong>{studio.table.rows.length}</strong> ligne{studio.table.rows.length > 1 ? 's' : ''} · colonnes :
            {#each studio.table.columns as c, i (c)}<code>{c}</code>{i < studio.table.columns.length - 1 ? ' ' : ''}{/each}
          </p>
          <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
          <div class="table-wrap" tabindex="0" role="region" aria-label="Aperçu des données (défilement horizontal)">
            <table>
              <caption class="sr-only">Aperçu des données importées</caption>
              <thead><tr>{#each studio.table.columns as c (c)}<th scope="col">{c}</th>{/each}</tr></thead>
              <tbody>
                {#each studio.table.rows.slice(0, 8) as r, i (i)}
                  <tr>{#each studio.table.columns as c (c)}<td>{r[c]}</td>{/each}</tr>
                {/each}
              </tbody>
            </table>
          </div>
          {#if studio.table.rows.length > 8}<p class="hint">… et {studio.table.rows.length - 8} autres lignes.</p>{/if}
          <button class="btn ghost danger" onclick={() => { studio.table = { columns: [], rows: [] }; listText = ''; }}>Vider les données</button>
        {:else}
          <p class="hint">Sans données, l'aperçu utilise l'exemple du modèle.</p>
        {/if}

        {#if check?.rejected.length}
          <div class="notice error" role="alert">
            <p><strong>{check.rejected.length} étiquette{check.rejected.length > 1 ? 's' : ''} non imprimée{check.rejected.length > 1 ? 's' : ''}</strong> : champ obligatoire vide.</p>
            <ul>
              {#each check.rejected.slice(0, 8) as r (r.index)}
                <li>ligne {r.index}{r.record[idField] ? ` (${r.record[idField]})` : ''} : {r.missing.map((m) => `{${m}}`).join(', ')}</li>
              {/each}
            </ul>
            {#if check.rejected.length > 8}<p>… et {check.rejected.length - 8} autres.</p>{/if}
            <p class="small">Complétez la base puis relancez la recherche, ou rendez ces champs facultatifs dans le modèle.</p>
          </div>
        {/if}

        {#if unplaced.length}
          <div class="notice error unplaced" role="alert">
            <p><strong>Champs absents du modèle « {t.name} »</strong> : renseignés dans les données mais pas placés sur l'étiquette.</p>
            <ul>
              {#each unplaced as [field, count] (field)}
                <li>
                  <span><code>{'{'}{field}{'}'}</code> · {count} ligne{count > 1 ? 's' : ''}</span>
                  <span class="actions">
                    <button class="btn" onclick={() => studio.placeField(field)}><Icon name="plus" size={16} /> Ajouter au modèle</button>
                    <button class="btn ghost" onclick={() => studio.ignoreField(field)}>Ignorer pour ce modèle</button>
                  </span>
                </li>
              {/each}
            </ul>
          </div>
        {/if}
      </section>

      <section class="card pad" aria-labelledby="layout-title">
        <h2 id="layout-title">2. Mise en page</h2>
        <label class="field">
          <span>Modèle</span>
          <select value={studio.currentId} onchange={(e) => studio.open(e.currentTarget.value)}>
            {#each studio.data.templates as tt (tt.id)}<option value={tt.id}>{tt.name} · {tt.width_mm} × {tt.height_mm} mm</option>{/each}
          </select>
        </label>

        <div class="segmented" role="group" aria-label="Support">
          <button aria-pressed={layoutKind === 'roll'} onclick={setRoll}>Rouleau / imprimante d'étiquettes</button>
          <button aria-pressed={layoutKind === 'sheet'} onclick={() => setSheet('avery-l7160')}>Planche A4</button>
        </div>

        {#if studio.print.layout.kind === 'sheet'}
          {@const s = studio.print.layout as SheetLayout}
          <label class="field">
            <span>Planche</span>
            <select value={s.preset} onchange={(e) => setSheet(e.currentTarget.value)}>
              {#each Object.entries(SHEET_PRESETS) as [id, p] (id)}<option value={id}>{p.label}</option>{/each}
              <option value="custom">Personnalisée (A4, calculée depuis le modèle)</option>
            </select>
          </label>
          <details>
            <summary>Réglages fins de la planche (mm)</summary>
            <div class="row grid3">
              {#each [['label_w', 'Largeur case'], ['label_h', 'Hauteur case'], ['cols', 'Colonnes'], ['rows', 'Lignes'], ['margin_left', 'Marge gauche'], ['margin_top', 'Marge haut'], ['gap_x', 'Espace horiz.'], ['gap_y', 'Espace vert.']] as const as [k, l] (k)}
                <label class="field">
                  <span>{l}</span>
                  <input type="number" step={k === 'cols' || k === 'rows' ? 1 : 0.1} min="0" value={s[k]}
                    onchange={(e) => numInput(e, (v) => { s[k] = k === 'cols' || k === 'rows' ? Math.max(1, Math.round(v)) : v; s.preset = 'custom'; })} />
                </label>
              {/each}
            </div>
          </details>
          <label class="field">
            <span>Étiquettes déjà utilisées sur la première planche</span>
            <input type="number" min="0" step="1" value={studio.print.skip}
              onchange={(e) => numInput(e, (v) => (studio.print.skip = Math.max(0, Math.round(v))))} />
          </label>
          {#if plan?.oversize}
            <p class="notice error" role="alert">Le modèle ({t.width_mm} × {t.height_mm} mm) est plus grand que les étiquettes de cette planche ({s.label_w} × {s.label_h} mm).</p>
          {/if}
        {:else}
          <p class="hint">Une page PDF par étiquette, à la taille exacte du modèle ({t.width_mm} × {t.height_mm} mm). Dans le pilote de l'imprimante, choisir ce format et « taille réelle ».</p>
        {/if}

        <div class="row">
          <label class="field">
            <span>Exemplaires par ligne</span>
            <input type="number" min="1" max="999" step="1" value={studio.print.copies}
              onchange={(e) => numInput(e, (v) => (studio.print.copies = Math.max(1, Math.min(999, Math.round(v)))))} />
          </label>
        </div>
        <label class="check"><input type="checkbox" bind:checked={studio.print.outline} /> Tracer le contour (impression d'essai)</label>
      </section>
    </div>

    <section class="card preview" aria-labelledby="preview-title">
      <div class="preview-head">
        <h2 id="preview-title">3. Aperçu et export</h2>
        <p class="hint">{total} étiquette{total > 1 ? 's' : ''} · {plan?.pages ?? 0} page{(plan?.pages ?? 0) > 1 ? 's' : ''}</p>
      </div>
      <div class="sheet" class:roll={layoutKind === 'roll'} role="img" aria-label="Aperçu de la première page">
        {@html pagePreview}
      </div>
      {#if rowIssues.length}
        <div class="notice error" role="alert">
          <p>Codes impossibles à générer :</p>
          <ul>{#each rowIssues as i (i.row + i.message)}<li>ligne {i.row} : {i.message}</li>{/each}</ul>
        </div>
      {/if}
      {#if error}<p class="notice error" role="alert">{error}</p>{/if}
      <div class="buttons end">
        <button class="btn" onclick={exportSvg}><Icon name="image" /> SVG (1ʳᵉ étiquette)</button>
        <button class="btn primary" onclick={exportPdf} disabled={busy || total === 0 || plan?.oversize}>
          <Icon name="download" /> {busy ? 'Génération…' : 'Télécharger le PDF'}{check?.rejected.length ? ` (${check.rejected.length} exclue${check.rejected.length > 1 ? 's' : ''})` : ''}
        </button>
      </div>
    </section>
  </div>
{/if}

<style>
  .print {
    display: grid;
    grid-template-columns: minmax(320px, 440px) minmax(0, 1fr);
    gap: 1rem;
    align-items: start;
  }
  .side {
    display: grid;
    gap: 1rem;
    min-width: 0;
  }
  .pad {
    display: grid;
    gap: 0.8rem;
    padding: 1rem;
    min-width: 0;
  }
  .segmented {
    flex-wrap: wrap;
  }
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  .buttons.end {
    justify-content: flex-end;
  }
  label.btn {
    cursor: pointer;
  }
  label.btn:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  .mono {
    font-family: var(--mono);
  }
  code {
    font-family: var(--mono);
    font-size: 0.85em;
    padding: 0.05rem 0.35rem;
    border-radius: 4px;
    background: var(--surface-2);
  }
  .table-wrap {
    overflow-x: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  table {
    border-collapse: collapse;
    width: 100%;
    font-size: 0.85rem;
  }
  th,
  td {
    padding: 0.35rem 0.6rem;
    text-align: left;
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
  }
  th {
    background: var(--surface-2);
    font-weight: 600;
  }
  tr:last-child td {
    border-bottom: 0;
  }
  details summary {
    cursor: pointer;
    font-weight: 550;
    min-height: 32px;
  }
  .grid3 {
    grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
    margin-top: 0.6rem;
  }

  .preview {
    display: grid;
    gap: 0.9rem;
    padding: 1rem;
    position: sticky;
    top: 5rem;
    min-width: 0;
  }
  .preview-head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  .sheet {
    display: grid;
    place-items: center;
    padding: 1rem;
    background: var(--canvas);
    border-radius: var(--radius-sm);
    overflow: hidden;
  }
  .sheet > :global(svg) {
    width: 100%;
    max-height: 70vh;
    box-shadow: var(--shadow-lg);
  }
  .sheet.roll > :global(svg) {
    width: auto;
    max-width: 100%;
    height: min(40vh, 320px);
  }
  .small {
    font-size: 0.82rem;
    margin-top: 0.3rem;
  }
  .unplaced li {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.4rem;
    padding: 0.25rem 0;
  }
  .unplaced ul {
    list-style: none;
    padding: 0;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
  }
  .notice ul {
    margin: 0.3rem 0 0;
    padding-left: 1.2rem;
  }

  @media (max-width: 900px) {
    .print {
      grid-template-columns: minmax(0, 1fr);
    }
    .preview {
      position: static;
    }
  }
</style>
