<script lang="ts">
  import Icon from './components/Icon.svelte';
  import Editor from './components/Editor.svelte';
  import PrintView from './components/PrintView.svelte';
  import ShareView from './components/ShareView.svelte';
  import ConfigView from './components/ConfigView.svelte';
  import { studio } from './lib/state.svelte';
  import { loadPrefs, savePrefs } from './lib/storage';

  type Tab = 'templates' | 'print' | 'config' | 'share';
  // Ordre = fréquence d'usage : imprimer est la tâche de tous les jours, le reste se règle une fois.
  const tabs: { id: Tab; label: string; icon: string }[] = [
    { id: 'print', label: 'Imprimer', icon: 'printer' },
    { id: 'templates', label: 'Modèles', icon: 'tag' },
    { id: 'config', label: 'Appli mobile', icon: 'phone' },
    { id: 'share', label: 'Partager', icon: 'share' },
  ];

  const prefs = loadPrefs({ tab: 'print' as Tab, theme: 'auto' as 'auto' | 'light' | 'dark' });
  let tab = $state<Tab>(tabs.some((t) => t.id === prefs.tab) ? prefs.tab : 'print');
  let theme = $state(prefs.theme);

  $effect(() => {
    if (theme === 'auto') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.dataset.theme = theme;
    savePrefs({ tab, theme });
  });

  // Sauvegarde automatique (légèrement différée pendant les glisser-déposer).
  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => {
    JSON.stringify(studio.data);
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => studio.persist(), 400);
  });

  function cycleTheme() {
    const dark = theme === 'dark' || (theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
    theme = dark ? 'light' : 'dark';
  }

  function onTabKey(e: KeyboardEvent, index: number) {
    const delta = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!delta) return;
    e.preventDefault();
    const next = tabs[(index + delta + tabs.length) % tabs.length];
    tab = next.id;
    document.getElementById(`tab-${next.id}`)?.focus();
  }
</script>

<a class="skip" href="#main">Aller au contenu</a>

<header class="top">
  <div class="brand">
    <span class="logo" aria-hidden="true"><Icon name="qr" size={20} /></span>
    <div>
      <h1>Kura Studio</h1>
      {#if studio.data.company}<p class="company">{studio.data.company}</p>{/if}
    </div>
  </div>

  <nav aria-label="Sections">
    <div class="tabs" role="tablist">
      {#each tabs as t, i (t.id)}
        <button
          id="tab-{t.id}"
          role="tab"
          aria-selected={tab === t.id}
          aria-controls="main"
          tabindex={tab === t.id ? 0 : -1}
          onclick={() => (tab = t.id)}
          onkeydown={(e) => onTabKey(e, i)}
        >
          <Icon name={t.icon} />
          <span>{t.label}</span>
        </button>
      {/each}
    </div>
  </nav>

  <button class="btn ghost icon" onclick={cycleTheme} aria-label="Changer le thème clair / sombre" title="Thème">
    <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
  </button>
</header>

{#if studio.saveFailed}
  <p class="notice warn banner" role="alert">
    Ce navigateur n'enregistre pas les modifications (navigation privée ou stockage bloqué). Pensez à exporter vos modèles.
  </p>
{/if}

<main id="main" tabindex="-1">
  <div role="tabpanel" aria-labelledby="tab-{tab}">
  {#if tab === 'templates'}
    <Editor onprint={() => (tab = 'print')} />
  {:else if tab === 'print'}
    <PrintView onopenconfig={() => (tab = 'config')} oneditTemplate={() => (tab = 'templates')} />
  {:else if tab === 'config'}
    <ConfigView />
  {:else}
    <ShareView />
  {/if}
  </div>
</main>

<div class="toast" role="status" aria-live="polite">
  {#if studio.status}<span>{studio.status}</span>{/if}
</div>

<style>
  .skip {
    position: absolute;
    left: 0.5rem;
    top: -3rem;
    z-index: 100;
    padding: 0.5rem 0.8rem;
    background: var(--surface);
    border-radius: var(--radius-sm);
  }
  .skip:focus {
    top: 0.5rem;
  }

  .top {
    position: sticky;
    top: 0;
    z-index: 20;
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 0.6rem clamp(0.75rem, 2vw, 1.5rem);
    background: color-mix(in srgb, var(--surface) 88%, transparent);
    backdrop-filter: blur(10px);
    border-bottom: 1px solid var(--border);
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    min-width: 0;
  }
  .brand h1 {
    font-size: 1rem;
    font-weight: 700;
    letter-spacing: -0.01em;
  }
  .company {
    font-size: 0.78rem;
    color: var(--muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .logo {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border-radius: 9px;
    background: var(--accent);
    color: var(--accent-text);
    flex: none;
  }

  nav {
    flex: 1;
    display: flex;
    justify-content: center;
    min-width: 0;
  }
  .tabs {
    display: flex;
    gap: 2px;
    padding: 3px;
    background: var(--surface-2);
    border-radius: 10px;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .tabs button {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    min-height: 38px;
    padding: 0 0.85rem;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--muted);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    white-space: nowrap;
  }
  .tabs button[aria-selected='true'] {
    background: var(--surface);
    color: var(--text);
    box-shadow: var(--shadow);
  }

  main {
    padding: clamp(0.75rem, 2vw, 1.5rem);
    max-width: 1600px;
    margin: 0 auto;
  }
  main:focus {
    outline: none;
  }

  .banner {
    margin: 0.75rem clamp(0.75rem, 2vw, 1.5rem) 0;
  }

  .toast {
    position: fixed;
    left: 50%;
    bottom: calc(1rem + env(safe-area-inset-bottom));
    transform: translateX(-50%);
    z-index: 50;
    pointer-events: none;
  }
  .toast span {
    display: block;
    padding: 0.55rem 1rem;
    border-radius: 999px;
    background: var(--text);
    color: var(--bg);
    font-weight: 550;
    box-shadow: var(--shadow-lg);
  }

  /* Petits écrans : onglets en barre fixe en bas, à portée du pouce. */
  @media (max-width: 720px) {
    /* backdrop-filter créerait un bloc conteneur pour la barre fixe. */
    .top {
      gap: 0.5rem;
      backdrop-filter: none;
      background: var(--surface);
    }
    nav {
      position: fixed;
      left: 0;
      right: 0;
      bottom: 0;
      padding: 0.4rem 0.5rem calc(0.4rem + env(safe-area-inset-bottom));
      background: var(--surface);
      border-top: 1px solid var(--border);
      z-index: 30;
    }
    .brand {
      flex: 1;
    }
    .tabs {
      width: 100%;
      background: transparent;
      justify-content: space-around;
    }
    .tabs button {
      flex: 1;
      flex-direction: column;
      gap: 0.1rem;
      min-height: 52px;
      padding: 0.2rem;
      font-size: 0.72rem;
    }
    main {
      padding-bottom: calc(5.5rem + env(safe-area-inset-bottom));
    }
    .toast {
      bottom: calc(5rem + env(safe-area-inset-bottom));
    }
  }
</style>
