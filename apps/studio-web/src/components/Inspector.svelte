<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { SYMBOLOGIES, type Align, type VAlign } from '../lib/model';
  import { columnKey } from '../lib/csv';

  const t = $derived(studio.current);
  const el = $derived(studio.selected);

  const SIZES: [number, number][] = [
    [50, 25], [40, 30], [57, 32], [60, 40], [70, 37], [100, 50], [100, 150], [63.5, 38.1], [38.1, 21.2],
  ];

  const fields = $derived.by(() => {
    const keys = new Set<string>(Object.keys(t?.sample ?? {}));
    for (const c of studio.table.columns) keys.add(c);
    return [...keys];
  });

  let contentInput: HTMLInputElement | HTMLTextAreaElement | undefined = $state();

  /** Insère {champ} à la position du curseur. */
  function insertField(name: string) {
    if (!el || (el.kind !== 'text' && el.kind !== 'code')) return;
    studio.checkpoint();
    const token = `{${name}}`;
    const input = contentInput;
    const start = input?.selectionStart ?? el.content.length;
    const end = input?.selectionEnd ?? el.content.length;
    el.content = el.content.slice(0, start) + token + el.content.slice(end);
    studio.touch();
    queueMicrotask(() => {
      input?.focus();
      input?.setSelectionRange(start + token.length, start + token.length);
    });
  }

  function num(v: string, fallback: number) {
    const n = Number.parseFloat(v.replace(',', '.'));
    return Number.isFinite(n) ? Math.round(n * 100) / 100 : fallback;
  }

  // Sauvegarde un point d'annulation au début de chaque saisie.
  function onFocusIn() {
    studio.checkpoint();
  }

  async function pickImage(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !el || el.kind !== 'image') return;
    if (!/^image\/(png|jpeg)$/.test(file.type)) {
      studio.announce('Format accepté : PNG ou JPEG');
      return;
    }
    const target = el;
    target.src = await downscale(file, 800);
    // Garde les proportions de l'image dans la largeur actuelle.
    const img = new Image();
    img.onload = () => {
      target.h = Math.max(1, Math.round((target.w * img.height) / img.width * 2) / 2);
      studio.touch();
    };
    img.src = target.src;
  }

  /** Réduit les grandes images pour que le modèle reste léger à partager. */
  function downscale(file: File, max: number): Promise<string> {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        const ratio = Math.min(1, max / Math.max(img.width, img.height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * ratio);
        canvas.height = Math.round(img.height * ratio);
        canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height);
        URL.revokeObjectURL(url);
        resolve(canvas.toDataURL(file.type === 'image/jpeg' ? 'image/jpeg' : 'image/png', 0.9));
      };
      img.onerror = reject;
      img.src = url;
    });
  }

  let newKey = $state('');
  function addSampleKey() {
    if (!t) return;
    const key = columnKey(newKey, Object.keys(t.sample).length);
    if (!newKey.trim() || key in t.sample) return;
    studio.checkpoint();
    t.sample[key] = '';
    newKey = '';
    studio.touch();
  }

  function removeSampleKey(key: string) {
    if (!t) return;
    studio.checkpoint();
    delete t.sample[key];
    studio.touch();
  }
</script>

<aside class="inspector card" aria-label="Propriétés" onfocusin={onFocusIn}>
  {#if t && el}
    <div class="head">
      <h2>
        {#if el.kind === 'text'}Texte{:else if el.kind === 'code'}Code{:else if el.kind === 'rect'}Cadre{:else}Image{/if}
      </h2>
      <div class="actions">
        <button class="btn ghost icon" onclick={() => studio.reorderSelected(-1)} aria-label="Envoyer vers l'arrière" title="Vers l'arrière"><Icon name="down" /></button>
        <button class="btn ghost icon" onclick={() => studio.reorderSelected(1)} aria-label="Mettre au premier plan" title="Vers l'avant"><Icon name="up" /></button>
        <button class="btn ghost icon" onclick={() => studio.duplicateSelected()} aria-label="Dupliquer l'élément" title="Dupliquer (Ctrl+D)"><Icon name="copy" /></button>
        <button class="btn ghost icon danger" onclick={() => studio.removeSelected()} aria-label="Supprimer l'élément" title="Supprimer"><Icon name="trash" /></button>
      </div>
    </div>

    {#if el.kind === 'text' || el.kind === 'code'}
      <div class="section">
        <label class="field">
          <span>{el.kind === 'text' ? 'Texte' : 'Contenu du code'}</span>
          {#if el.kind === 'text'}
            <textarea
              id="inspector-first"
              bind:this={contentInput}
              bind:value={el.content}
              oninput={() => studio.touch()}
              rows="3"
              placeholder="Ex. Réf. {'{ref}'} · {'{brand}'}"
            ></textarea>
          {:else}
            <input
              id="inspector-first"
              bind:this={contentInput}
              bind:value={el.content}
              oninput={() => studio.touch()}
              spellcheck="false"
              class="mono"
            />
          {/if}
        </label>
        {#if fields.length}
          <div class="chips" role="group" aria-label="Insérer un champ">
            {#each fields as f (f)}
              <button class="chip" onclick={() => insertField(f)} title="Insérer {'{'}{f}{'}'}">{f}</button>
            {/each}
          </div>
        {/if}
        <p class="hint">Texte libre et champs entre accolades, remplacés par les colonnes de votre fichier.</p>
      </div>
    {/if}

    {#if el.kind === 'code'}
      <div class="section row">
        <label class="field">
          <span>Type</span>
          <select bind:value={el.symbology} onchange={() => studio.touch()}>
            {#each SYMBOLOGIES as s (s.id)}<option value={s.id}>{s.label}</option>{/each}
          </select>
        </label>
        {#if el.symbology === 'qrcode'}
          <label class="field">
            <span>Correction d'erreur</span>
            <select bind:value={el.eclevel} onchange={() => studio.touch()}>
              <option value="L">Faible (7 %)</option>
              <option value="M">Moyenne (15 %)</option>
              <option value="Q">Forte (25 %)</option>
              <option value="H">Maximale (30 %)</option>
            </select>
          </label>
        {/if}
      </div>
    {/if}

    {#if el.kind === 'text'}
      <div class="section">
        <div class="row">
          <label class="field">
            <span>Taille (pt)</span>
            <input type="number" min="2" max="200" step="0.5" value={el.size_pt}
              onchange={(e) => { el.size_pt = num(e.currentTarget.value, el.size_pt); studio.touch(); }} />
          </label>
          <label class="field">
            <span>Couleur</span>
            <input type="color" bind:value={el.color} oninput={() => studio.touch()} />
          </label>
        </div>
        <div class="field">
          <span class="label" id="align-label">Alignement</span>
          <div class="segmented" role="group" aria-labelledby="align-label">
            {#each [['left', 'Gauche'], ['center', 'Centre'], ['right', 'Droite']] as [v, l] (v)}
              <button aria-pressed={el.align === v} onclick={() => { studio.checkpoint(); el.align = v as Align; studio.touch(); }}>{l}</button>
            {/each}
          </div>
        </div>
        <div class="field">
          <span class="label" id="valign-label">Position verticale</span>
          <div class="segmented" role="group" aria-labelledby="valign-label">
            {#each [['top', 'Haut'], ['middle', 'Milieu'], ['bottom', 'Bas']] as [v, l] (v)}
              <button aria-pressed={el.valign === v} onclick={() => { studio.checkpoint(); el.valign = v as VAlign; studio.touch(); }}>{l}</button>
            {/each}
          </div>
        </div>
        <label class="check"><input type="checkbox" bind:checked={el.bold} onchange={() => studio.touch()} /> Gras</label>
        <label class="check"><input type="checkbox" bind:checked={el.shrink} onchange={() => studio.touch()} /> Réduire pour tenir dans le cadre</label>
      </div>
    {/if}

    {#if el.kind === 'rect'}
      <div class="section">
        <div class="row">
          <label class="field">
            <span>Trait (mm)</span>
            <input type="number" min="0" max="20" step="0.1" value={el.stroke_mm}
              onchange={(e) => { el.stroke_mm = num(e.currentTarget.value, el.stroke_mm); studio.touch(); }} />
          </label>
          <label class="field">
            <span>Arrondi (mm)</span>
            <input type="number" min="0" max="100" step="0.5" value={el.radius_mm}
              onchange={(e) => { el.radius_mm = num(e.currentTarget.value, el.radius_mm); studio.touch(); }} />
          </label>
          <label class="field">
            <span>Couleur</span>
            <input type="color" bind:value={el.color} oninput={() => studio.touch()} />
          </label>
        </div>
        <label class="check"><input type="checkbox" bind:checked={el.filled} onchange={() => studio.touch()} /> Rempli</label>
      </div>
    {/if}

    {#if el.kind === 'image'}
      <div class="section">
        <label class="btn">
          <Icon name="upload" /> {el.src ? "Changer l'image" : 'Choisir une image'}
          <input id="inspector-first" class="sr-only" type="file" accept="image/png,image/jpeg" onchange={pickImage} />
        </label>
        <p class="hint">PNG ou JPEG (logo, pictogramme). L'image est intégrée au modèle.</p>
      </div>
    {/if}

    <div class="section">
      <h3>Position et taille (mm)</h3>
      <div class="row four">
        {#each [['x', 'X'], ['y', 'Y'], ['w', 'Largeur'], ['h', 'Hauteur']] as const as [k, l] (k)}
          <label class="field">
            <span>{l}</span>
            <input type="number" step="0.5" min={k === 'w' || k === 'h' ? 0.5 : undefined} value={el[k]}
              onchange={(e) => { el[k] = Math.max(k === 'w' || k === 'h' ? 0.5 : -1000, num(e.currentTarget.value, el[k])); studio.touch(); }} />
          </label>
        {/each}
      </div>
      <div class="quick">
        <button class="btn" onclick={() => { studio.checkpoint(); el.x = Math.round(((t.width_mm - el.w) / 2) * 100) / 100; studio.touch(); }}>Centrer horizontalement</button>
        <button class="btn" onclick={() => { studio.checkpoint(); el.y = Math.round(((t.height_mm - el.h) / 2) * 100) / 100; studio.touch(); }}>Centrer verticalement</button>
      </div>
    </div>
  {:else if t}
    <div class="head"><h2>Modèle</h2></div>
    <div class="section">
      <label class="field">
        <span>Nom</span>
        <input id="inspector-first" bind:value={t.name} oninput={() => studio.touch()} />
      </label>
      <div class="row">
        <label class="field">
          <span>Largeur (mm)</span>
          <input type="number" min="5" max="1000" step="0.5" value={t.width_mm}
            onchange={(e) => { t.width_mm = Math.max(5, num(e.currentTarget.value, t.width_mm)); studio.touch(); }} />
        </label>
        <label class="field">
          <span>Hauteur (mm)</span>
          <input type="number" min="5" max="1000" step="0.5" value={t.height_mm}
            onchange={(e) => { t.height_mm = Math.max(5, num(e.currentTarget.value, t.height_mm)); studio.touch(); }} />
        </label>
      </div>
      <div class="chips" role="group" aria-label="Formats courants">
        {#each SIZES as [w, h] (`${w}x${h}`)}
          <button class="chip" aria-pressed={t.width_mm === w && t.height_mm === h}
            onclick={() => { studio.checkpoint(); t.width_mm = w; t.height_mm = h; studio.touch(); }}>
            {String(w).replace('.', ',')} × {String(h).replace('.', ',')}
          </button>
        {/each}
      </div>
    </div>

    <div class="section">
      <h3>Données d'exemple</h3>
      <p class="hint">Valeurs utilisées pour l'aperçu. Les vraies données viennent de votre fichier à l'impression.</p>
      {#each Object.keys(t.sample) as key (key)}
        <div class="sample">
          <label class="field">
            <span class="mono">{'{'}{key}{'}'}</span>
            <input bind:value={t.sample[key]} oninput={() => studio.touch()} />
          </label>
          <button class="btn ghost icon" onclick={() => removeSampleKey(key)} aria-label="Retirer le champ {key}"><Icon name="x" /></button>
        </div>
      {/each}
      <form class="sample" onsubmit={(e) => { e.preventDefault(); addSampleKey(); }}>
        <label class="field">
          <span>Nouveau champ</span>
          <input bind:value={newKey} placeholder="ex. marque" />
        </label>
        <button class="btn icon" type="submit" aria-label="Ajouter le champ"><Icon name="plus" /></button>
      </form>
    </div>
    <p class="hint pad">Sélectionnez un élément de l'étiquette pour le modifier.</p>
  {/if}
</aside>

<style>
  .inspector {
    display: grid;
    align-content: start;
    gap: 0;
    min-width: 0;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.7rem 0.9rem;
    border-bottom: 1px solid var(--border);
  }
  .actions {
    display: flex;
  }
  .section {
    display: grid;
    gap: 0.65rem;
    padding: 0.9rem;
    border-bottom: 1px solid var(--border);
  }
  .section:last-child {
    border-bottom: 0;
  }
  .four {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .quick {
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
  }
  .quick .btn {
    flex: 1;
    font-size: 0.82rem;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
  }
  .chip {
    min-height: 32px;
    padding: 0 0.6rem;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--surface-2);
    color: var(--text);
    font: 0.82rem var(--mono);
    cursor: pointer;
  }
  .chip:hover,
  .chip[aria-pressed='true'] {
    border-color: var(--accent);
    background: var(--accent-soft);
  }
  .mono {
    font-family: var(--mono);
  }
  .sample {
    display: grid;
    grid-template-columns: 1fr auto;
    align-items: end;
    gap: 0.4rem;
  }
  .pad {
    padding: 0 0.9rem 0.9rem;
  }
  label.btn {
    cursor: pointer;
  }
  label.btn:focus-within {
    outline: 3px solid var(--focus);
    outline-offset: 2px;
  }

  @media (max-width: 380px) {
    .four {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
