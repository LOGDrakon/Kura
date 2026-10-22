<script lang="ts">
  import Icon from './Icon.svelte';
  import { studio } from '../lib/state.svelte';
  import { authHeaders, checkConfig, defaultConfig, withDefaults, expandPath, joinUrl, pruneConfig, stripDefaults, type KuraConfig } from '../lib/config';
  import { buildConfigQr, hashPassword, MAX_QR_BYTES } from '../lib/config-qr';
  import { codeGeometry } from '../lib/codes';
  import { download, slug } from '../lib/storage';
  import { now } from '../lib/model';

  if (!studio.data.config) {
    studio.data.config = defaultConfig(studio.data.company);
    studio.data.config_updated_at = now();
  } else {
    studio.data.config = withDefaults($state.snapshot(studio.data.config) as KuraConfig);
  }
  const cfg = $derived(studio.data.config as KuraConfig);
  let firstRun = true;
  $effect(() => {
    JSON.stringify(studio.data.config);
    if (firstRun) firstRun = false;
    else studio.data.config_updated_at = now();
  });

  const SYMS = [
    ['qr_code', 'QR Code'], ['data_matrix', 'Data Matrix'], ['ean_13', 'EAN-13'], ['ean_8', 'EAN-8'],
    ['upc_a', 'UPC-A'], ['upc_e', 'UPC-E'], ['code_128', 'Code 128'], ['code_39', 'Code 39'], ['itf', 'ITF'],
  ] as const;

  const output = $derived(pruneConfig($state.snapshot(cfg)));
  const issues = $derived(checkConfig(output));
  const errors = $derived(issues.filter((i) => i.level === 'error'));

  const seconds = (ms: unknown, d: number) => Math.round((Number(ms ?? d) / 1000) * 10) / 10;
  const toMs = (v: string, min: number, max: number) => Math.min(max, Math.max(min, Math.round(Number(v.replace(',', '.')) * 1000)));

  // Listes saisies en texte (« ref, name, location »).
  const listOf = (v: unknown) => (Array.isArray(v) ? v.join(', ') : '');
  const parseList = (s: string) => s.split(/[,;\s]+/).map((x) => x.trim()).filter(Boolean);
  const parseInts = (s: string) => parseList(s).map(Number).filter((n) => Number.isInteger(n) && n > 0);

  function toggleSym(id: string, on: boolean) {
    const list: string[] = cfg.scan.symbologies ?? [];
    cfg.scan.symbologies = on ? [...new Set([...list, id])] : list.filter((s) => s !== id);
  }

  function setFormat(format: string) {
    cfg.scan.format = format;
    if (format !== 'id') {
      cfg.scan.fields ??= ['ref', 'name', 'location'];
      cfg.scan.id_field ??= 'ref';
      if (format === 'delimited') cfg.scan.delimiter ??= ';';
    } else {
      delete cfg.scan.fields;
      delete cfg.scan.id_field;
      delete cfg.scan.delimiter;
    }
  }

  function setAuth(type: string) {
    cfg.server.auth = type === 'none' ? { type } : { type, token_ref: 'secure_store' };
    if (type === 'api_key') cfg.server.auth.header = 'X-Api-Key';
    if (type === 'basic') cfg.server.auth.username = '';
  }

  // --- Mot de passe de verrouillage ---
  let password = $state('');
  let password2 = $state('');
  let hashing = $state(false);
  async function setPassword() {
    if (password.length < 6 || password !== password2) return;
    hashing = true;
    cfg.security.config_password_hash = await hashPassword(password);
    cfg.security.config_locked = true;
    password = password2 = '';
    hashing = false;
    studio.announce('Mot de passe enregistré (sous forme hachée)');
  }

  // --- Test de connexion ---
  let secret = $state('');
  let testRef = $state('REF-0042');
  let testing = $state(false);
  let testResult = $state<{ ok: boolean; title: string; detail: string } | null>(null);

  async function testConnection() {
    testing = true;
    testResult = null;
    const endpoint = cfg.endpoints?.get_item ?? { method: 'GET', path: '/items/{ref}' };
    const idField = cfg.scan.id_field ?? 'ref';
    const url = joinUrl(cfg.server.base_url, expandPath(endpoint.path, { ref: testRef, item_id: testRef, [idField]: testRef }));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), cfg.server.timeout_ms ?? 8000);
    try {
      const res = await fetch(url, { method: endpoint.method, headers: { Accept: 'application/json', ...authHeaders(cfg, secret) }, signal: controller.signal });
      const text = await res.text();
      let detail = text.slice(0, 2000);
      try {
        detail = JSON.stringify(JSON.parse(text), null, 2).slice(0, 2000);
      } catch { /* réponse non JSON */ }
      const hints: Record<number, string> = {
        401: 'Authentification refusée : vérifiez le jeton.',
        403: 'Accès interdit pour ce compte.',
        404: 'Article introuvable (la connexion fonctionne) ou chemin incorrect.',
      };
      testResult = { ok: res.ok, title: `${res.status} ${res.statusText} · ${url}`, detail: (hints[res.status] ? hints[res.status] + '\n\n' : '') + detail };
    } catch (e) {
      const aborted = (e as Error).name === 'AbortError';
      testResult = {
        ok: false,
        title: aborted ? 'Délai dépassé' : 'Serveur injoignable depuis ce navigateur',
        detail: aborted
          ? `Pas de réponse en ${cfg.server.timeout_ms ?? 8000} ms.`
          : `${url}\n\nCauses possibles : adresse incorrecte, serveur arrêté, certificat HTTPS non reconnu, ou serveur qui n'autorise pas les appels depuis un navigateur (CORS). Ce dernier cas ne gêne pas l'application Android.`,
      };
    } finally {
      clearTimeout(timer);
      testing = false;
    }
  }

  // --- Export ---
  function downloadJson() {
    download('kura-config.json', JSON.stringify(output, null, 2) + '\n', 'application/json');
  }

  let qrSecret = $state('');
  let qrPin = $state('');
  let qrPayload = $state('');
  let qrError = $state('');
  let qrBusy = $state(false);

  async function makeQr() {
    qrError = '';
    qrBusy = true;
    try {
      if (qrSecret && !/^\d{6,}$/.test(qrPin)) throw new Error('Un code PIN d’au moins 6 chiffres est nécessaire pour protéger le secret.');
      qrPayload = await buildConfigQr(stripDefaults(output), qrSecret ? { secret: qrSecret, pin: qrPin } : undefined);
      if (new TextEncoder().encode(qrPayload).length > MAX_QR_BYTES) {
        throw new Error('Configuration trop volumineuse pour un QR code lisible : retirez les options avancées ou utilisez le fichier.');
      }
    } catch (e) {
      qrPayload = '';
      qrError = (e as Error).message;
    } finally {
      qrBusy = false;
    }
  }

  const qrSvg = $derived.by(() => {
    if (!qrPayload) return '';
    const size = 100;
    const { rects } = codeGeometry({ symbology: 'qrcode', w: size, h: size, eclevel: 'L' }, qrPayload);
    const d = rects.map((r) => `M${r.x.toFixed(3)} ${r.y.toFixed(3)}h${r.w.toFixed(3)}v${r.h.toFixed(3)}h${(-r.w).toFixed(3)}z`).join('');
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}"><rect width="100%" height="100%" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
  });

  function printQr() {
    const w = window.open('', '_blank');
    if (!w) return;
    const company = (cfg.company || 'Kura').replace(/[<>&]/g, '');
    w.document.write(`<!doctype html><html lang="fr"><meta charset="utf-8"><title>QR de configuration Kura</title>
      <style>body{font-family:system-ui,sans-serif;text-align:center;padding:2cm}svg{width:9cm;height:9cm}h1{font-size:20pt}p{font-size:12pt;color:#333}</style>
      <h1>Configuration Kura Scan · ${company}</h1>${qrSvg}
      <p>Ouvrez Kura Scan, puis scannez ce code.${qrSecret ? '<br>Le code PIN vous sera demandé : il est communiqué séparément.' : ''}</p>
      <script>window.onload=()=>window.print()<\/script></html>`);
    w.document.close();
  }

  function downloadQr() {
    download(`kura-config-${slug(cfg.company || 'qr')}.svg`, qrSvg, 'image/svg+xml');
  }

  function reset() {
    if (confirm('Repartir d’une configuration vierge ?')) studio.data.config = defaultConfig(studio.data.company);
  }
</script>

<div class="config">
  <div class="forms">
    <section class="card intro" aria-labelledby="c-intro">
      <h2 id="c-intro">Relier les téléphones à votre base</h2>
      <ol>
        <li>Indiquez l'adresse de votre base ci-dessous et testez la connexion.</li>
        <li>Ajustez si besoin le comportement de l'appli (les valeurs par défaut conviennent dans la plupart des cas).</li>
        <li>Générez le <strong>QR de configuration</strong>, puis scannez-le avec Kura Scan sur chaque téléphone.</li>
      </ol>
    </section>

    <section class="card pad" aria-labelledby="c-server">
      <h2 id="c-server"><span class="step">1</span> Connexion à votre base</h2>
      <label class="field">
        <span>Entreprise</span>
        <input bind:value={cfg.company} placeholder="Atelier Dupont" />
      </label>
      <label class="field">
        <span>Adresse de l'API de votre base</span>
        <input type="url" bind:value={cfg.server.base_url} placeholder="https://gmao.entreprise.fr/api" spellcheck="false" />
      </label>
      <div class="row">
        <label class="field">
          <span>Authentification</span>
          <select value={cfg.server.auth?.type ?? 'none'} onchange={(e) => setAuth(e.currentTarget.value)}>
            <option value="none">Aucune</option>
            <option value="bearer">Jeton Bearer</option>
            <option value="basic">Identifiant + mot de passe (Basic)</option>
            <option value="api_key">Clé d'API (en-tête)</option>
          </select>
        </label>
      </div>
      {#if cfg.server.auth?.type === 'basic'}
        <label class="field"><span>Identifiant</span><input bind:value={cfg.server.auth.username} /></label>
      {:else if cfg.server.auth?.type === 'api_key'}
        <label class="field"><span>Nom de l'en-tête</span><input bind:value={cfg.server.auth.header} /></label>
      {/if}
      {#if cfg.server.auth?.type && cfg.server.auth.type !== 'none'}
        <p class="hint">Le secret n'est jamais écrit dans le fichier de config : il est saisi sur le téléphone, ou transmis par le QR protégé par un code PIN.</p>
      {/if}
      <div class="test">
        <div class="row">
          <label class="field"><span>Référence d'essai</span><input bind:value={testRef} /></label>
          {#if cfg.server.auth?.type && cfg.server.auth.type !== 'none'}
            <label class="field"><span>Secret (non enregistré)</span><input type="password" autocomplete="off" bind:value={secret} /></label>
          {/if}
          <button class="btn" onclick={testConnection} disabled={testing || !/^https?:\/\/.+/.test(cfg.server.base_url ?? '')}><Icon name="wifi" /> {testing ? 'Test en cours…' : 'Tester la connexion'}</button>
        </div>
        {#if testResult}
          <div class="notice {testResult.ok ? 'info' : 'error'}" role="status">
            <strong>{testResult.ok ? '✓ Connexion réussie' : '✗'} {testResult.ok ? '' : testResult.title}</strong>
            <details><summary>Détail de la réponse</summary><p class="mono small">{testResult.title}</p><pre>{testResult.detail}</pre></details>
          </div>
        {/if}
      </div>
      <details class="adv">
        <summary>Réglages avancés</summary>
        <label class="field">
          <span>Délai maximal d'attente (secondes)</span>
          <input type="number" min="1" max="60" step="0.5" value={seconds(cfg.server.timeout_ms, 8000)}
            onchange={(e) => (cfg.server.timeout_ms = toMs(e.currentTarget.value, 1000, 60000))} />
        </label>
      </details>
    </section>

    <section class="card pad" aria-labelledby="c-codes">
      <h2 id="c-codes"><span class="step">2</span> Codes à lire</h2>
      <fieldset>
        <legend class="label">Formats acceptés</legend>
        <div class="checks">
          {#each SYMS as [id, label] (id)}
            <label class="check"><input type="checkbox" checked={cfg.scan.symbologies?.includes(id)} onchange={(e) => toggleSym(id, e.currentTarget.checked)} /> {label}</label>
          {/each}
        </div>
      </fieldset>
      <div class="field">
        <span class="label" id="fmt-label">Contenu du code</span>
        <div class="segmented" role="group" aria-labelledby="fmt-label">
          <button aria-pressed={cfg.scan.format === 'id'} onclick={() => setFormat('id')}>Référence seule</button>
          <button aria-pressed={cfg.scan.format === 'delimited'} onclick={() => setFormat('delimited')}>Champs séparés</button>
          <button aria-pressed={cfg.scan.format === 'json'} onclick={() => setFormat('json')}>JSON</button>
        </div>
        <p class="hint">
          {#if cfg.scan.format === 'id'}Recommandé : le code ne contient que la référence, les infos viennent du serveur.{:else}Les infos sont lues dans le code, même hors-ligne. Exemple : <code>REF-42;Vis M6;A12</code>{/if}
        </p>
      </div>
      {#if cfg.scan.format !== 'id'}
        <div class="row">
          <label class="field"><span>Champs, dans l'ordre</span><input value={listOf(cfg.scan.fields)} onchange={(e) => (cfg.scan.fields = parseList(e.currentTarget.value))} /></label>
          <label class="field"><span>Champ identifiant</span><input bind:value={cfg.scan.id_field} /></label>
          {#if cfg.scan.format === 'delimited'}
            <label class="field"><span>Séparateur</span><input bind:value={cfg.scan.delimiter} maxlength="4" /></label>
          {/if}
        </div>
      {/if}
      <details class="adv">
        <summary>Réglages avancés</summary>
        <label class="field"><span>Préfixe obligatoire (optionnel)</span><input bind:value={cfg.scan.prefix} placeholder="ex. KURA:" /></label>
        <p class="hint">Si renseigné, les codes qui ne commencent pas par ce texte sont refusés.</p>
      </details>
    </section>

    <section class="card pad" aria-labelledby="c-scan">
      <h2 id="c-scan"><span class="step">3</span> Lecture et mode rafale</h2>
      <div class="field">
        <span class="label" id="trig-label">Déclenchement</span>
        <div class="segmented" role="group" aria-labelledby="trig-label">
          <button aria-pressed={cfg.scan.trigger.mode === 'button'} onclick={() => (cfg.scan.trigger.mode = 'button')}>Bouton « Scanner »</button>
          <button aria-pressed={cfg.scan.trigger.mode === 'continuous'} onclick={() => (cfg.scan.trigger.mode = 'continuous')}>Continu</button>
        </div>
        <p class="hint">Avec le bouton, rien n'est lu tant que l'utilisateur n'appuie pas : les codes voisins sont ignorés. « Continu » convient à un poste fixe.</p>
      </div>
      <label class="check"><input type="checkbox" bind:checked={cfg.scan.trigger.volume_keys} /> Les touches de volume déclenchent aussi la lecture</label>
      <details class="adv">
        <summary>Réglages avancés</summary>
        <div class="row">
          <label class="field"><span>Durée d'une tentative (secondes)</span>
            <input type="number" min="1" max="30" step="0.5" value={seconds(cfg.scan.trigger.timeout_ms, 6000)}
              onchange={(e) => (cfg.scan.trigger.timeout_ms = toMs(e.currentTarget.value, 1000, 30000))} />
          </label>
          <label class="field"><span>Taille du cadre de visée (% de la largeur)</span>
            <input type="number" min="20" max="100" step="5" value={Math.round((cfg.scan.trigger.aim_area ?? 0.6) * 100)}
              onchange={(e) => (cfg.scan.trigger.aim_area = Math.min(1, Math.max(0.2, Number(e.currentTarget.value) / 100)))} />
          </label>
        </div>
      </details>
      <label class="check"><input type="checkbox" bind:checked={cfg.burst.enabled} /> Mode rafale disponible (chaque lecture = un mouvement, sans ouvrir la fiche)</label>
      {#if cfg.burst.enabled}
        <div class="row">
          <label class="field"><span>Sens par défaut</span>
            <select bind:value={cfg.burst.default_direction}><option value="out">Sortie (−)</option><option value="in">Entrée (+)</option></select>
          </label>
          <label class="field"><span>Quantité par lecture</span><input type="number" min="1" step="1" bind:value={cfg.burst.default_step} /></label>
        </div>
        <label class="check"><input type="checkbox" bind:checked={cfg.burst.verify_item} /> Vérifier chaque article auprès du serveur</label>
      {/if}
    </section>

    <section class="card pad" aria-labelledby="c-display">
      <h2 id="c-display"><span class="step">4</span> Fiche article</h2>
      <label class="field"><span>Informations affichées (colonnes de la base, séparées par des virgules)</span><input value={listOf(cfg.display.show_fields)} onchange={(e) => (cfg.display.show_fields = parseList(e.currentTarget.value))} /></label>
      <label class="field"><span>Boutons + / − (jusqu'à 3 valeurs, ex. 1, 10)</span><input value={listOf(cfg.display.step_buttons)} onchange={(e) => (cfg.display.step_buttons = parseInts(e.currentTarget.value).slice(0, 3))} /></label>
      <label class="check"><input type="checkbox" bind:checked={cfg.display.allow_set} /> Autoriser la saisie du stock compté (inventaire)</label>
      <label class="check"><input type="checkbox" bind:checked={cfg.display.show_image} /> Afficher une image</label>
      {#if cfg.display.show_image}
        <label class="field"><span>Adresse de l'image (relative à la base)</span><input bind:value={cfg.display.image_url} placeholder="/items/{'{ref}'}/image" /></label>
      {/if}
    </section>

    <section class="card pad" aria-labelledby="c-sec">
      <h2 id="c-sec"><span class="step">5</span> Synchronisation et sécurité</h2>
      <div class="row">
        <label class="field"><span>Envoi</span>
          <select bind:value={cfg.sync.mode}><option value="auto">Automatique</option><option value="manual">Manuel</option></select>
        </label>
        <label class="check"><input type="checkbox" bind:checked={cfg.sync.batch} /> Regrouper les envois</label>
      </div>
      <label class="check"><input type="checkbox" bind:checked={cfg.security.require_user} /> Demander l'identifiant de l'utilisateur</label>
      <div class="field">
        <span class="label">Verrouiller les réglages de l'appli</span>
        {#if cfg.security.config_password_hash}
          <p class="notice info">Mot de passe défini (stocké sous forme hachée).
            <button class="link" onclick={() => { delete cfg.security.config_password_hash; cfg.security.config_locked = false; }}>Retirer</button>
          </p>
        {:else}
          <form class="row" onsubmit={(e) => { e.preventDefault(); setPassword(); }}>
            <label class="field"><span>Mot de passe</span><input type="password" autocomplete="new-password" bind:value={password} minlength="6" /></label>
            <label class="field"><span>Confirmation</span><input type="password" autocomplete="new-password" bind:value={password2} /></label>
            <button class="btn" type="submit" disabled={hashing || password.length < 6 || password !== password2}>{hashing ? 'Calcul…' : 'Définir'}</button>
          </form>
          {#if password && password.length < 6}<p class="hint">6 caractères minimum.</p>{/if}
        {/if}
      </div>
    </section>

  </div>

  <aside class="out">
    <section class="card pad qr-card" aria-labelledby="c-qr">
      <h2 id="c-qr"><Icon name="phone" /> QR de configuration</h2>
      <p class="hint">Sur chaque téléphone : ouvrez Kura Scan, appuyez sur « Scanner » et visez ce code. L'appli est prête en quelques secondes.</p>
      {#if issues.length}
        <ul class="issues" aria-label="Problèmes de configuration">
          {#each issues as i (i.path + i.message)}<li class={i.level}><strong>{i.path}</strong> : {i.message}</li>{/each}
        </ul>
      {:else}
        <p class="notice info">✓ Configuration valide</p>
      {/if}
      {#if cfg.server.auth?.type && cfg.server.auth.type !== 'none'}
        <div class="row">
          <label class="field"><span>Inclure le secret (optionnel)</span><input type="password" autocomplete="off" bind:value={qrSecret} /></label>
          {#if qrSecret}<label class="field"><span>Code PIN (6 chiffres ou plus)</span><input type="password" inputmode="numeric" autocomplete="off" bind:value={qrPin} /></label>{/if}
        </div>
        {#if qrSecret}<p class="hint">Le secret est chiffré par le PIN (AES-256). Communiquez le PIN séparément, jamais sur la même feuille.</p>{/if}
      {/if}
      <button class="btn primary" onclick={makeQr} disabled={errors.length > 0 || qrBusy}><Icon name="qr" /> {qrSvg ? 'Mettre à jour le QR' : 'Générer le QR'}</button>
      {#if qrError}<p class="notice error" role="alert">{qrError}</p>{/if}
      {#if qrSvg}
        <div class="qr" role="img" aria-label="QR code de configuration">{@html qrSvg}</div>
        <div class="buttons">
          <button class="btn primary" onclick={printQr}><Icon name="printer" /> Imprimer</button>
          <button class="btn" onclick={downloadQr}><Icon name="download" /> SVG</button>
        </div>
      {/if}
    </section>

    <details class="card pad tech">
      <summary>Fichier technique (kura-config.json)</summary>
      <p class="hint">Alternative au QR : à copier sur le téléphone (Réglages › Charger un fichier) ou à déployer par la gestion de flotte.</p>
      <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
      <pre class="json" tabindex="0" role="region" aria-label="Contenu du fichier">{JSON.stringify(output, null, 2)}</pre>
      <div class="buttons">
        <button class="btn" onclick={downloadJson} disabled={errors.length > 0}><Icon name="download" /> Télécharger le fichier</button>
        <button class="btn ghost danger" onclick={reset}>Repartir de zéro</button>
      </div>
    </details>
  </aside>
</div>

<style>
  .config {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(320px, 440px);
    gap: 1rem;
    align-items: start;
  }
  .forms {
    display: grid;
    gap: 1rem;
    min-width: 0;
  }
  .out {
    display: grid;
    gap: 1rem;
    position: sticky;
    top: 5rem;
    min-width: 0;
  }
  .pad {
    display: grid;
    gap: 0.8rem;
    padding: 1.1rem;
    min-width: 0;
  }
  h2 {
    display: flex;
    align-items: center;
    gap: 0.55rem;
  }
  .step {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    border-radius: 50%;
    background: var(--accent-soft);
    color: var(--accent);
    font-size: 0.85rem;
    font-weight: 700;
  }
  fieldset {
    border: 0;
    padding: 0;
    margin: 0;
  }
  .checks {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
    gap: 0 0.5rem;
  }
  .segmented {
    flex-wrap: wrap;
    justify-self: start;
  }
  form.row {
    align-items: end;
  }
  .intro {
    padding: 1.1rem 1.2rem;
    background: var(--accent-soft);
    border-color: color-mix(in srgb, var(--accent) 30%, transparent);
  }
  .intro ol {
    margin: 0.5rem 0 0;
    padding-left: 1.3rem;
    display: grid;
    gap: 0.2rem;
  }
  .test {
    display: grid;
    gap: 0.6rem;
    padding-top: 0.8rem;
    border-top: 1px dashed var(--border);
  }
  .test .row {
    align-items: end;
  }
  .adv summary,
  .tech summary {
    cursor: pointer;
    font-size: 0.88rem;
    font-weight: 600;
    color: var(--muted);
  }
  .adv[open] {
    display: grid;
    gap: 0.6rem;
  }
  .tech summary {
    font-size: 0.95rem;
  }
  .qr-card h2 :global(svg) {
    color: var(--accent);
  }
  .small {
    font-size: 0.8rem;
  }
  .mono {
    font-family: var(--mono);
  }
  .out-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .json {
    margin: 0;
    max-height: 360px;
    overflow: auto;
    padding: 0.8rem;
    border-radius: var(--radius-sm);
    background: var(--surface-2);
    font: 0.78rem/1.45 var(--mono);
  }
  pre {
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0.4rem 0 0;
    font: 0.78rem/1.45 var(--mono);
  }
  .issues {
    margin: 0;
    padding-left: 1.1rem;
    font-size: 0.85rem;
  }
  .issues .error {
    color: var(--danger);
  }
  .issues .warning {
    color: var(--warn);
  }
  .qr {
    width: min(100%, 260px);
    margin: 0 auto;
    padding: 8px;
    background: #fff;
    border-radius: var(--radius-sm);
  }
  .qr :global(svg) {
    display: block;
    width: 100%;
    height: auto;
  }
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    justify-content: center;
  }
  code {
    font-family: var(--mono);
  }
  .link {
    border: 0;
    background: none;
    color: var(--accent);
    font: inherit;
    font-weight: 600;
    text-decoration: underline;
    cursor: pointer;
  }

  @media (max-width: 1000px) {
    .config {
      grid-template-columns: minmax(0, 1fr);
    }
    .out {
      position: static;
    }
  }
</style>
