<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { listItems, ServerError } from '../lib/server';
  import type { KuraConfig } from '../lib/config';

  let { onopenconfig }: { onopenconfig?: () => void } = $props();

  const config = $derived((studio.data.config ?? {}) as KuraConfig);
  const baseUrl = $derived(String(config.server?.base_url ?? ''));
  const configured = $derived(/^https?:\/\/.+/.test(baseUrl));
  const needsSecret = $derived(!!config.server?.auth?.type && config.server.auth.type !== 'none');
  const idField = $derived(String(config.scan?.id_field ?? 'ref'));

  let q = $state('');
  let since = $state('');
  let hidePrinted = $state(true);
  let items = $state<Record<string, string>[]>([]);
  let next = $state<string | undefined>();
  let loading = $state(false);
  let error = $state('');
  let searched = $state(false);
  let selected = $state<Set<string>>(new Set());

  const visible = $derived(hidePrinted ? items.filter((it) => !studio.printed[it[idField]]) : items);
  const hiddenCount = $derived(items.length - visible.length);

  async function search(more = false) {
    loading = true;
    error = '';
    try {
      const page = await listItems($state.snapshot(config), studio.serverSecret, {
        q: q.trim() || undefined,
        updatedSince: since ? new Date(since).toISOString() : undefined,
        cursor: more ? next : undefined,
      });
      items = more ? [...items, ...page.items] : page.items;
      next = page.next;
      if (!more) selected = new Set(visible.map((it) => it[idField]));
      searched = true;
      apply();
      studio.announce(`${page.items.length} article${page.items.length > 1 ? 's' : ''} trouvé${page.items.length > 1 ? 's' : ''}`);
    } catch (e) {
      error = e instanceof ServerError ? e.message : `Erreur : ${(e as Error).message}`;
    } finally {
      loading = false;
    }
  }

  /** La sélection devient les données d'impression. */
  function apply() {
    const rows = items.filter((it) => selected.has(it[idField]));
    const columns = [...new Set(rows.flatMap((r) => Object.keys(r)))];
    studio.table = { columns, rows };
  }

  function toggle(ref: string, on: boolean) {
    const s = new Set(selected);
    if (on) s.add(ref);
    else s.delete(ref);
    selected = s;
    apply();
  }

  // Les articles s'affichent dès l'ouverture : pas besoin de savoir qu'il faut « rechercher ».
  let autoLoaded = false;
  $effect(() => {
    if (configured && !needsSecret && !autoLoaded && !searched) {
      autoLoaded = true;
      search();
    }
  });

  function selectAll(on: boolean) {
    selected = on ? new Set(visible.map((it) => it[idField])) : new Set();
    apply();
  }
</script>

{#if !configured}
  <p class="notice info">
    Renseignez d'abord l'adresse du serveur dans
    <button class="link" onclick={onopenconfig}>Configuration</button>.
    Studio y lira les articles créés dans votre base pour en faire les étiquettes.
  </p>
{:else}
  <form class="search" onsubmit={(e) => { e.preventDefault(); search(); }}>
    <label class="field grow">
      <span>Rechercher (référence, désignation…)</span>
      <input type="search" bind:value={q} placeholder="Laisser vide pour tout afficher" />
    </label>
    {#if needsSecret}
      <label class="field">
        <span>Jeton d'accès (non enregistré)</span>
        <input type="password" autocomplete="off" bind:value={studio.serverSecret} />
      </label>
    {/if}
    <button class="btn primary" type="submit" disabled={loading}><Icon name="search" /> {loading ? 'Recherche…' : 'Rechercher'}</button>
  </form>
  <details class="filters" open={!!since}>
    <summary>Filtres</summary>
    <div class="filters-body">
      <label class="field">
        <span>Créés ou modifiés depuis</span>
        <input type="date" bind:value={since} onchange={() => search()} />
      </label>
      <label class="check"><input type="checkbox" bind:checked={hidePrinted} onchange={() => { selectAll(true); }} /> Masquer les articles déjà imprimés sur ce poste</label>
    </div>
  </details>

  {#if error}<p class="notice error" role="alert">{error}</p>{/if}

  {#if searched}
    {#if visible.length}
      <div class="list-head">
        <label class="check">
          <input type="checkbox" checked={visible.length > 0 && visible.every((it) => selected.has(it[idField]))}
            onchange={(e) => selectAll(e.currentTarget.checked)} />
          Tout sélectionner
        </label>
        <span class="count"><strong>{selected.size}</strong> / {visible.length} sélectionné{selected.size > 1 ? 's' : ''}</span>
      </div>
      <ul class="items" aria-label="Articles du serveur">
        {#each visible as it (it[idField])}
          <li>
            <label>
              <input type="checkbox" checked={selected.has(it[idField])} onchange={(e) => toggle(it[idField], e.currentTarget.checked)} />
              <span class="text">
                <span class="name">{it.name ?? it.designation ?? it[idField]}</span>
                <span class="sub"><span class="ref">{it[idField]}</span>{#if it.location}&nbsp;· {it.location}{/if}</span>
              </span>
              {#if studio.printed[it[idField]]}<span class="badge">imprimé</span>{/if}
            </label>
          </li>
        {/each}
      </ul>
      {#if hiddenCount}<p class="hint">{hiddenCount} déjà imprimé{hiddenCount > 1 ? 's' : ''} masqué{hiddenCount > 1 ? 's' : ''} (voir Filtres).</p>{/if}
    {:else}
      <p class="empty">Aucun article{hiddenCount ? ' non imprimé' : ''} ne correspond.{#if hiddenCount} {hiddenCount} déjà imprimé{hiddenCount > 1 ? 's' : ''} masqué{hiddenCount > 1 ? 's' : ''} (voir Filtres).{/if}</p>
    {/if}
    {#if next}
      <button class="btn ghost" onclick={() => search(true)} disabled={loading}>Charger plus</button>
    {/if}
  {:else if loading}
    <p class="hint">Chargement des articles…</p>
  {/if}
{/if}

<style>
  .search {
    display: flex;
    flex-wrap: wrap;
    gap: 0.6rem;
    align-items: end;
  }
  .grow {
    flex: 1 1 180px;
  }
  .search .field:not(.grow) {
    flex: 1 1 140px;
  }
  .filters summary {
    cursor: pointer;
    font-size: 0.85rem;
    font-weight: 550;
    color: var(--muted);
  }
  .filters-body {
    display: grid;
    gap: 0.4rem;
    margin-top: 0.5rem;
  }
  .count {
    font-size: 0.85rem;
    color: var(--muted);
  }
  .empty {
    padding: 1rem;
    text-align: center;
    color: var(--muted);
    border: 1px dashed var(--border);
    border-radius: var(--radius-sm);
  }
  .text {
    flex: 1;
    min-width: 0;
    display: grid;
  }
  .sub {
    font-size: 0.8rem;
    color: var(--muted);
  }
  .list-head {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 0.5rem;
  }
  .items {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 360px;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .items li + li {
    border-top: 1px solid var(--border);
  }
  .items label {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    min-height: 44px;
    padding: 0.3rem 0.7rem;
    cursor: pointer;
  }
  .items input {
    width: 18px;
    height: 18px;
    accent-color: var(--accent);
    flex: none;
  }
  .ref {
    font-family: var(--mono);
  }
  .name {
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .badge {
    font-size: 0.72rem;
    padding: 0.1rem 0.45rem;
    border-radius: 999px;
    background: var(--surface-2);
    color: var(--muted);
  }
  .link {
    border: 0;
    padding: 0;
    background: none;
    color: var(--accent);
    font: inherit;
    font-weight: 650;
    text-decoration: underline;
    cursor: pointer;
  }
</style>
