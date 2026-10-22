<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { renderLabelSvg } from '../lib/render-svg';
</script>

<section class="list card" aria-labelledby="templates-title">
  <div class="head">
    <h2 id="templates-title">Modèles</h2>
    <button class="btn primary" onclick={() => studio.create()}>
      <Icon name="plus" /> <span class="new-lbl">Nouveau</span>
    </button>
  </div>

  <!-- Petits écrans : liste déroulante compacte. -->
  <label class="compact field">
    <span class="sr-only">Modèle affiché</span>
    <select value={studio.currentId} onchange={(e) => studio.open(e.currentTarget.value)}>
      {#each studio.data.templates as t (t.id)}
        <option value={t.id}>{t.name} · {t.width_mm} × {t.height_mm} mm</option>
      {/each}
    </select>
  </label>

  <ul class="full">
    {#each studio.data.templates as t (t.id)}
      <li class:active={t.id === studio.currentId}>
        <button class="open" onclick={() => studio.open(t.id)} aria-current={t.id === studio.currentId ? 'true' : undefined}>
          <span class="thumb" style="aspect-ratio: {t.width_mm} / {t.height_mm}" aria-hidden="true">
            {@html renderLabelSvg(t, t.sample)}
          </span>
          <span class="meta">
            <span class="name">{t.name}</span>
            <span class="size">{t.width_mm} × {t.height_mm} mm</span>
          </span>
        </button>
      </li>
    {/each}
  </ul>
</section>

<style>
  .list {
    padding: 0.9rem;
    display: grid;
    gap: 0.75rem;
    align-content: start;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.4rem;
  }
  li {
    display: flex;
    align-items: center;
    gap: 0.25rem;
    border-radius: var(--radius-sm);
    border: 1px solid transparent;
  }
  li.active {
    background: var(--accent-soft);
    border-color: color-mix(in srgb, var(--accent) 35%, transparent);
  }
  .open {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.45rem;
    border: 0;
    background: transparent;
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
    border-radius: var(--radius-sm);
  }
  .thumb {
    width: 56px;
    max-height: 44px;
    flex: none;
    display: grid;
    place-items: center;
    border-radius: 4px;
    overflow: hidden;
    box-shadow: 0 0 0 1px var(--border);
    background: #fff;
  }
  .thumb :global(svg) {
    width: 100%;
    height: auto;
    display: block;
  }
  .meta {
    display: grid;
    min-width: 0;
  }
  .name {
    font-weight: 600;
    line-height: 1.3;
    overflow-wrap: anywhere;
  }
  .size {
    font-size: 0.78rem;
    color: var(--muted);
  }
  li:not(.active):hover {
    background: var(--surface-2);
  }
  .compact {
    display: none;
  }

  @media (max-width: 1100px) {
    .full {
      display: none;
    }
    .compact {
      display: grid;
      flex: 1;
    }
    .list {
      grid-template-columns: 1fr auto;
      align-items: center;
    }
    .head {
      display: contents;
    }
    .head h2 {
      display: none;
    }
    .head .btn {
      order: 2;
    }
    .new-lbl {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  }
</style>
