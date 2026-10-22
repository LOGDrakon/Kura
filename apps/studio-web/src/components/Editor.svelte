<script lang="ts">
  import Icon from './Icon.svelte';
  import Inspector from './Inspector.svelte';
  import TemplateList from './TemplateList.svelte';
  import { studio } from '../lib/state.svelte';
  import { renderLabelSvg, type ElementIssue } from '../lib/render-svg';
  import { MIN_MODULE_MM, moduleSize } from '../lib/codes';
  import { fill } from '../lib/fields';
  import { SYMBOLOGIES, type ElementKind, type LabelElement } from '../lib/model';

  let { onprint }: { onprint?: () => void } = $props();

  const SNAP = 0.5;
  const kindNames: Record<ElementKind, string> = { code: 'Code', text: 'Texte', rect: 'Cadre', image: 'Image' };

  let stage: HTMLDivElement | undefined = $state();
  let stageWidth = $state(600);
  let stageHeight = $state(400);
  let zoom = $state(1);

  const t = $derived(studio.current);

  /** Pixels par millimètre : l'étiquette remplit la zone disponible, puis le zoom s'applique. */
  const scale = $derived.by(() => {
    if (!t) return 4;
    const fit = Math.min((stageWidth - 48) / t.width_mm, (Math.max(stageHeight, 220) - 48) / t.height_mm);
    return Math.max(1, Math.min(fit, 40)) * zoom;
  });

  const preview = $derived.by(() => {
    if (!t) return { svg: '', issues: [] as ElementIssue[] };
    const issues: ElementIssue[] = [];
    const svg = renderLabelSvg(t, t.sample, { issues });
    for (const el of t.elements) {
      if (el.x < -0.01 || el.y < -0.01 || el.x + el.w > t.width_mm + 0.01 || el.y + el.h > t.height_mm + 0.01) {
        issues.push({ id: el.id, message: 'dépasse du bord de l’étiquette' });
      }
      if (el.kind === 'code') {
        try {
          const m = moduleSize(el, fill(el.content, t.sample));
          if (m < MIN_MODULE_MM) {
            issues.push({ id: el.id, message: `code trop petit (module de ${m.toFixed(2)} mm, viser ≥ ${MIN_MODULE_MM} mm)` });
          }
        } catch {
          /* déjà signalé par le rendu */
        }
      }
    }
    return { svg, issues };
  });

  function describe(el: LabelElement): string {
    const pos = `x ${el.x} mm, y ${el.y} mm, ${el.w} × ${el.h} mm`;
    if (el.kind === 'text') return `Texte « ${el.content || 'vide'} », ${pos}`;
    if (el.kind === 'code') {
      const sym = SYMBOLOGIES.find((s) => s.id === el.symbology)?.label ?? el.symbology;
      return `${sym} « ${el.content} », ${pos}`;
    }
    return `${kindNames[el.kind]}, ${pos}`;
  }

  const snap = (v: number, free: boolean) => (free ? Math.round(v * 100) / 100 : Math.round(v / SNAP) * SNAP);

  // --- Glisser / redimensionner ---

  type Handle = 'move' | 'nw' | 'ne' | 'sw' | 'se';
  let drag: { id: string; handle: Handle; px: number; py: number; start: Pick<LabelElement, 'x' | 'y' | 'w' | 'h'> } | null =
    null;

  function pointerDown(e: PointerEvent, el: LabelElement, handle: Handle) {
    if (e.button !== 0) return;
    e.stopPropagation();
    studio.selectedId = el.id;
    studio.checkpoint();
    drag = { id: el.id, handle, px: e.clientX, py: e.clientY, start: { x: el.x, y: el.y, w: el.w, h: el.h } };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }

  function pointerMove(e: PointerEvent) {
    if (!drag || !t) return;
    const el = t.elements.find((x) => x.id === drag!.id);
    if (!el) return;
    const dx = (e.clientX - drag.px) / scale;
    const dy = (e.clientY - drag.py) / scale;
    const s = drag.start;
    const free = e.altKey;
    const min = 1;
    switch (drag.handle) {
      case 'move':
        el.x = snap(s.x + dx, free);
        el.y = snap(s.y + dy, free);
        break;
      case 'se':
        el.w = Math.max(min, snap(s.w + dx, free));
        el.h = Math.max(min, snap(s.h + dy, free));
        break;
      case 'sw': {
        const w = Math.max(min, snap(s.w - dx, free));
        el.x = s.x + s.w - w;
        el.w = w;
        el.h = Math.max(min, snap(s.h + dy, free));
        break;
      }
      case 'ne': {
        const h = Math.max(min, snap(s.h - dy, free));
        el.y = s.y + s.h - h;
        el.h = h;
        el.w = Math.max(min, snap(s.w + dx, free));
        break;
      }
      case 'nw': {
        const w = Math.max(min, snap(s.w - dx, free));
        const h = Math.max(min, snap(s.h - dy, free));
        el.x = s.x + s.w - w;
        el.y = s.y + s.h - h;
        el.w = w;
        el.h = h;
        break;
      }
    }
    studio.touch();
  }

  function pointerUp() {
    drag = null;
  }

  // --- Clavier ---

  function elementKey(e: KeyboardEvent, el: LabelElement) {
    const step = e.shiftKey ? 5 : SNAP;
    const arrows: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    const move = arrows[e.key];
    if (move) {
      e.preventDefault();
      studio.checkpoint();
      if (e.altKey) {
        el.w = Math.max(1, el.w + move[0]);
        el.h = Math.max(1, el.h + move[1]);
      } else {
        el.x = Math.round((el.x + move[0]) * 100) / 100;
        el.y = Math.round((el.y + move[1]) * 100) / 100;
      }
      studio.touch();
    } else if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      studio.removeSelected();
      stage?.focus();
    } else if (e.key === 'Escape') {
      studio.selectedId = null;
      stage?.focus();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      document.getElementById('inspector-first')?.focus();
    }
  }

  function globalKey(e: KeyboardEvent) {
    const target = e.target as HTMLElement;
    if (target.closest('input, textarea, select, [contenteditable]')) return;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'z' && !e.shiftKey) {
      e.preventDefault();
      studio.undo();
    } else if (mod && (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey))) {
      e.preventDefault();
      studio.redo();
    } else if (mod && e.key.toLowerCase() === 'd' && studio.selected) {
      e.preventDefault();
      studio.duplicateSelected();
    }
  }

  function add(kind: ElementKind) {
    studio.addElement(kind);
    // Le nouvel élément reçoit le focus pour être déplacé au clavier tout de suite.
    queueMicrotask(() => document.querySelector<HTMLElement>(`[data-el="${studio.selectedId}"]`)?.focus());
  }

  const zoomSteps = [0.5, 0.75, 1, 1.5, 2, 3];
  function zoomBy(dir: 1 | -1) {
    const i = zoomSteps.findIndex((z) => z >= zoom - 1e-6);
    zoom = zoomSteps[Math.max(0, Math.min(zoomSteps.length - 1, (i < 0 ? 2 : i) + dir))];
  }
</script>

<svelte:window onkeydown={globalKey} />

<div class="editor">
  <TemplateList />

  {#if t}
    <section class="workspace card" aria-label="Éditeur de l'étiquette « {t.name} »">
      <div class="toolbar" role="toolbar" aria-label="Outils">
        <div class="group">
          <span class="add-lbl" aria-hidden="true">Ajouter</span>
          <button class="btn" onclick={() => add('text')}><Icon name="text" /><span class="lbl">Texte</span></button>
          <button class="btn" onclick={() => add('code')}><Icon name="qr" /><span class="lbl">QR / code-barres</span></button>
          <button class="btn" onclick={() => add('rect')}><Icon name="square" /><span class="lbl">Cadre</span></button>
          <button class="btn" onclick={() => add('image')}><Icon name="image" /><span class="lbl">Image</span></button>
        </div>
        <div class="group">
          <button class="btn ghost icon" onclick={() => studio.undo()} disabled={!studio.canUndo(t.id)} aria-label="Annuler" title="Annuler (Ctrl+Z)">
            <Icon name="undo" />
          </button>
          <button class="btn ghost icon" onclick={() => studio.redo()} disabled={!studio.canRedo(t.id)} aria-label="Rétablir" title="Rétablir (Ctrl+Y)">
            <Icon name="redo" />
          </button>
          <span class="sep" aria-hidden="true"></span>
          <button class="btn ghost icon" onclick={() => zoomBy(-1)} aria-label="Dézoomer" title="Dézoomer"><Icon name="zoomOut" /></button>
          <button class="btn ghost zoom" onclick={() => (zoom = 1)} title="Zoom actuel : cliquer pour ajuster à la fenêtre">
            {Math.round(zoom * 100)} %
          </button>
          <button class="btn ghost icon" onclick={() => zoomBy(1)} aria-label="Zoomer" title="Zoomer"><Icon name="zoomIn" /></button>
        </div>
      </div>

      <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
      <div
        class="stage"
        bind:this={stage}
        bind:clientWidth={stageWidth}
        bind:clientHeight={stageHeight}
        tabindex="0"
        role="region"
        aria-label="Zone de dessin. Tabulation pour parcourir les éléments, flèches pour déplacer (Maj : 5 mm), Alt + flèches pour redimensionner, Suppr pour supprimer."
        onpointerdown={() => (studio.selectedId = null)}
      >
        <div class="label" style="width: {t.width_mm * scale}px; height: {t.height_mm * scale}px;">
          <div class="render" aria-hidden="true">{@html preview.svg}</div>

          {#each t.elements as el (el.id)}
            {@const issue = preview.issues.some((i) => i.id === el.id)}
            <div
              class="box"
              class:selected={el.id === studio.selectedId}
              class:issue
              data-el={el.id}
              role="button"
              tabindex="0"
              aria-pressed={el.id === studio.selectedId}
              aria-label={describe(el)}
              style="left: {el.x * scale}px; top: {el.y * scale}px; width: {el.w * scale}px; height: {el.h * scale}px;"
              onpointerdown={(e) => pointerDown(e, el, 'move')}
              onpointermove={pointerMove}
              onpointerup={pointerUp}
              onpointercancel={pointerUp}
              onfocus={() => (studio.selectedId = el.id)}
              onkeydown={(e) => elementKey(e, el)}
            >
              {#if el.id === studio.selectedId}
                {#each ['nw', 'ne', 'sw', 'se'] as const as h (h)}
                  <span
                    class="handle {h}"
                    aria-hidden="true"
                    onpointerdown={(e) => pointerDown(e, el, h)}
                    onpointermove={pointerMove}
                    onpointerup={pointerUp}
                    onpointercancel={pointerUp}
                  ></span>
                {/each}
              {/if}
            </div>
          {/each}
        </div>
      </div>

      <footer class="foot">
        <span class="dims">{t.width_mm} × {t.height_mm} mm · {t.elements.length} élément{t.elements.length > 1 ? 's' : ''}</span>
        <span class="tip">{studio.selected ? 'Glisser pour déplacer, poignées pour redimensionner · Suppr pour effacer' : 'Cliquez sur un élément de l’étiquette pour le modifier'}</span>
        {#if onprint}<button class="btn primary small" onclick={onprint}><Icon name="printer" size={16} /> Imprimer avec ce modèle</button>{/if}
      </footer>

      {#if preview.issues.length}
        <ul class="issues" aria-label="Points à vérifier">
          {#each preview.issues as issue, i (issue.id + i)}
            {@const el = t.elements.find((e) => e.id === issue.id)}
            <li>
              <button class="link" onclick={() => (studio.selectedId = issue.id)}>
                {el ? kindNames[el.kind] : 'Élément'}
              </button>
              : {issue.message}
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <Inspector />
  {/if}
</div>

<style>
  .editor {
    display: grid;
    grid-template-columns: 230px minmax(0, 1fr) 330px;
    gap: 1rem;
    align-items: start;
  }

  .workspace {
    display: grid;
    min-width: 0;
    overflow: hidden;
  }

  .toolbar {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.6rem;
    border-bottom: 1px solid var(--border);
  }
  .group {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    flex-wrap: wrap;
  }
  .sep {
    width: 1px;
    height: 24px;
    background: var(--border);
    margin: 0 0.2rem;
  }
  .zoom {
    min-width: 64px;
    font-variant-numeric: tabular-nums;
  }

  .stage {
    position: relative;
    display: grid;
    place-items: center;
    min-height: clamp(260px, 52vh, 640px);
    padding: 24px;
    overflow: auto;
    background-color: var(--canvas);
    background-image: radial-gradient(color-mix(in srgb, var(--muted) 25%, transparent) 1px, transparent 1px);
    background-size: 16px 16px;
    touch-action: none;
  }
  .stage:focus-visible {
    outline-offset: -3px;
  }

  .label {
    position: relative;
    flex: none;
    background: #fff;
    border-radius: 3px;
    box-shadow: 0 0 0 1px rgb(0 0 0 / 0.08), var(--shadow-lg);
  }
  .render,
  .render :global(svg) {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    display: block;
  }

  .box {
    position: absolute;
    border-radius: 2px;
    cursor: move;
    touch-action: none;
    outline: 1px dashed transparent;
  }
  .box:hover {
    outline-color: color-mix(in srgb, var(--focus) 55%, transparent);
  }
  .box.issue {
    outline: 1px dashed #d97706;
  }
  .box.selected {
    outline: 2px solid var(--focus);
    outline-offset: 0;
  }
  .box:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: 1px;
  }

  .handle {
    position: absolute;
    width: 14px;
    height: 14px;
    background: #fff;
    border: 2px solid var(--focus);
    border-radius: 50%;
    touch-action: none;
  }
  /* Zone tactile élargie autour de la poignée. */
  .handle::after {
    content: '';
    position: absolute;
    inset: -10px;
  }
  .nw { left: -8px; top: -8px; cursor: nwse-resize; }
  .ne { right: -8px; top: -8px; cursor: nesw-resize; }
  .sw { left: -8px; bottom: -8px; cursor: nesw-resize; }
  .se { right: -8px; bottom: -8px; cursor: nwse-resize; }

  .add-lbl {
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--muted);
    margin-right: 0.15rem;
  }
  .btn.small {
    min-height: 32px;
    padding: 0 0.7rem;
    font-size: 0.85rem;
  }
  .foot {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.5rem 0.8rem;
    font-size: 0.8rem;
    color: var(--muted);
    border-top: 1px solid var(--border);
  }

  .issues {
    margin: 0;
    padding: 0.6rem 0.8rem 0.8rem 2rem;
    font-size: 0.85rem;
    color: var(--warn);
    background: var(--warn-soft);
  }
  .link {
    border: 0;
    padding: 0;
    background: none;
    color: inherit;
    font: inherit;
    font-weight: 650;
    text-decoration: underline;
    cursor: pointer;
  }

  @media (max-width: 1100px) {
    .editor {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  @media (max-width: 560px) {
    .tip,
    .add-lbl {
      display: none;
    }
    /* Libellé masqué visuellement mais lu par les lecteurs d'écran. */
    .lbl {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    .toolbar .group:first-child .btn {
      width: 44px;
      padding: 0;
    }
  }
</style>
