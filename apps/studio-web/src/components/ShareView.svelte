<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { exportEditorHtml } from '../lib/embed';
  import { download, slug } from '../lib/storage';
  import type { StudioData } from '../lib/model';

  let error = $state('');

  const snapshot = () => $state.snapshot(studio.data) as StudioData;

  function shareEditor() {
    error = '';
    try {
      const html = exportEditorHtml(snapshot());
      const name = studio.data.company ? `kura-studio-${slug(studio.data.company)}.html` : 'kura-studio.html';
      download(name, html, 'text/html');
      studio.announce('Éditeur exporté avec vos modèles');
    } catch (e) {
      error = (e as Error).message;
    }
  }

  function exportJson() {
    const data = snapshot();
    download(`kura-modeles-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(data, null, 2), 'application/json');
  }

  async function importJson(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    error = '';
    try {
      const raw = JSON.parse(await file.text());
      const list = Array.isArray(raw) ? raw : Array.isArray(raw?.templates) ? raw.templates : [raw];
      const count = studio.importTemplates(list);
      if (!count) throw new Error('aucun modèle reconnu dans ce fichier');
      if (!studio.data.company && typeof raw?.company === 'string') studio.data.company = raw.company;
      studio.announce(`${count} modèle${count > 1 ? 's' : ''} importé${count > 1 ? 's' : ''}`);
    } catch (err) {
      error = `Import impossible : ${(err as Error).message}`;
    }
  }

  function resetToShared() {
    if (!studio.embedded) return;
    if (!confirm('Remplacer les modèles de ce poste par ceux livrés avec l’éditeur ? Les modifications locales seront perdues.')) return;
    studio.data = structuredClone($state.snapshot(studio.embedded) as StudioData);
    studio.open(studio.data.templates[0]?.id ?? '');
    studio.announce('Modèles partagés restaurés');
  }
</script>

<div class="share">
  <section class="card pad hero" aria-labelledby="share-title">
    <div class="icon" aria-hidden="true"><Icon name="share" size={26} /></div>
    <div class="grid">
      <h2 id="share-title">Partager l'éditeur avec vos modèles</h2>
      <p>
        Kura Studio tient dans un seul fichier HTML. Exportez-le avec vos modèles intégrés, puis déposez-le sur un partage réseau,
        un intranet ou envoyez-le par mail : chaque poste l'ouvre dans son navigateur et retrouve les mêmes modèles, sans installation.
      </p>
      <label class="field company">
        <span>Nom de l'entreprise (affiché en en-tête)</span>
        <input bind:value={studio.data.company} placeholder="Atelier Dupont" maxlength="120" />
      </label>
      <div class="buttons">
        <button class="btn primary" onclick={shareEditor}><Icon name="download" /> Télécharger l'éditeur ({studio.data.templates.length} modèle{studio.data.templates.length > 1 ? 's' : ''})</button>
      </div>
      <p class="hint">Les données d'impression (fichiers CSV) ne sont jamais incluses. Les modifications faites ensuite sur un poste restent sur ce poste jusqu'au prochain partage.</p>
    </div>
  </section>

  {#if studio.embedded}
    <section class="card pad" aria-labelledby="embedded-title">
      <h2 id="embedded-title">Modèles livrés avec cet éditeur</h2>
      <p>Ce fichier contient {studio.embedded.templates.length} modèle{studio.embedded.templates.length > 1 ? 's' : ''} partagé{studio.embedded.templates.length > 1 ? 's' : ''}{studio.embedded.company ? ` par ${studio.embedded.company}` : ''}. Les versions les plus récentes (partagées ou locales) sont affichées.</p>
      <div class="buttons"><button class="btn" onclick={resetToShared}>Revenir aux modèles partagés</button></div>
    </section>
  {/if}

  <section class="card pad" aria-labelledby="files-title">
    <h2 id="files-title">Sauvegarde et échange de fichiers</h2>
    <p class="hint">Format JSON, pour sauvegarder vos modèles ou les fusionner avec ceux d'un autre poste. Un modèle importé remplace celui qui a le même identifiant.</p>
    <div class="buttons">
      <button class="btn" onclick={exportJson}><Icon name="download" /> Exporter les modèles (.json)</button>
      <label class="btn">
        <Icon name="upload" /> Importer des modèles
        <input class="sr-only" type="file" accept=".json,application/json" onchange={importJson} />
      </label>
    </div>
  </section>

  {#if error}<p class="notice error" role="alert">{error}</p>{/if}
</div>

<style>
  .share {
    display: grid;
    gap: 1rem;
    max-width: 860px;
    margin: 0 auto;
  }
  .pad {
    display: grid;
    gap: 0.8rem;
    padding: 1.2rem;
  }
  .hero {
    grid-template-columns: auto 1fr;
    gap: 1rem;
    align-items: start;
  }
  .icon {
    display: grid;
    place-items: center;
    width: 52px;
    height: 52px;
    border-radius: 14px;
    background: var(--accent-soft);
    color: var(--accent);
  }
  .grid {
    display: grid;
    gap: 0.8rem;
  }
  .company {
    max-width: 420px;
  }
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
  label.btn {
    cursor: pointer;
  }
  label.btn:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }
  @media (max-width: 560px) {
    .hero {
      grid-template-columns: 1fr;
    }
  }
</style>
