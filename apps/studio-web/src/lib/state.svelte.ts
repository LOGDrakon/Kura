// État global de l'application (runes Svelte 5).
import { readEmbedded } from './embed';
import { customSheet } from './layout';
import {
  emptyData, mergeData, newElement, normalizeTemplate, now, starterTemplate, uid,
  type ElementKind, type LabelElement, type PrintOptions, type StudioData, type Template,
} from './model';
import type { Table } from './csv';
import { loadLocal, loadPrinted, saveLocal, savePrinted } from './storage';
import { placeFieldElement } from './records';

const HISTORY_LIMIT = 100;

class Studio {
  data = $state<StudioData>(emptyData());
  /** Données livrées dans le fichier HTML (null si éditeur « vierge »). */
  embedded = $state<StudioData | null>(null);
  currentId = $state<string | null>(null);
  selectedId = $state<string | null>(null);
  table = $state<Table>({ columns: [], rows: [] });
  print = $state<PrintOptions>({ layout: { kind: 'roll' }, copies: 1, skip: 0, outline: false });
  saveFailed = $state(false);
  /** Secret d'accès au serveur pour lire les articles : en mémoire seulement, jamais enregistré. */
  serverSecret = $state('');
  /** Références déjà imprimées sur ce poste. */
  printed = $state<Record<string, string>>(loadPrinted());
  /** Message annoncé aux lecteurs d'écran et affiché brièvement. */
  status = $state('');
  /** Incrémenté à chaque changement d'historique, pour rafraîchir les boutons annuler/rétablir. */
  historyVersion = $state(0);

  #past = new Map<string, string[]>();
  #future = new Map<string, string[]>();
  #statusTimer: ReturnType<typeof setTimeout> | undefined;

  current = $derived(this.data.templates.find((t) => t.id === this.currentId) ?? null);
  selected = $derived(this.current?.elements.find((e) => e.id === this.selectedId) ?? null);

  init() {
    const embedded = readEmbedded();
    this.embedded = embedded;
    const local = loadLocal();
    if (embedded) this.data = mergeData(embedded, local);
    else if (local) this.data = local;
    if (this.data.templates.length === 0) this.data.templates.push(starterTemplate());
    this.currentId = this.data.templates[0].id;
    this.print.layout = { kind: 'roll' };
  }

  persist() {
    this.saveFailed = !saveLocal($state.snapshot(this.data) as StudioData);
  }

  announce(message: string) {
    this.status = message;
    clearTimeout(this.#statusTimer);
    this.#statusTimer = setTimeout(() => (this.status = ''), 4000);
  }

  // --- Modèles ---

  open(id: string) {
    this.currentId = id;
    this.selectedId = null;
    const t = this.current;
    if (t && this.print.layout.kind === 'sheet' && this.print.layout.preset === 'custom') {
      this.print.layout = customSheet(t);
    }
  }

  create() {
    const t = starterTemplate(`Modèle ${this.data.templates.length + 1}`);
    this.data.templates.push(t);
    this.open(t.id);
    this.announce(`Modèle « ${t.name} » créé`);
  }

  duplicate(id: string) {
    const src = this.data.templates.find((t) => t.id === id);
    if (!src) return;
    const copy: Template = {
      ...structuredClone($state.snapshot(src) as Template),
      id: uid('t'),
      name: `${src.name} (copie)`,
      updated_at: now(),
    };
    copy.elements.forEach((e) => (e.id = uid()));
    this.data.templates.push(copy);
    this.open(copy.id);
    this.announce(`Modèle dupliqué`);
  }

  remove(id: string) {
    const index = this.data.templates.findIndex((t) => t.id === id);
    if (index < 0) return;
    const [t] = this.data.templates.splice(index, 1);
    this.data.deleted[id] = now();
    if (this.data.templates.length === 0) this.data.templates.push(starterTemplate());
    if (this.currentId === id) this.open(this.data.templates[Math.max(0, index - 1)].id);
    this.announce(`Modèle « ${t.name} » supprimé`);
  }

  importTemplates(raw: unknown[]): number {
    let count = 0;
    for (const r of raw) {
      const t = normalizeTemplate(r);
      if (!t) continue;
      const existing = this.data.templates.findIndex((x) => x.id === t.id);
      if (existing >= 0) this.data.templates[existing] = t;
      else this.data.templates.push(t);
      delete this.data.deleted[t.id];
      count++;
    }
    return count;
  }

  /** Marque le modèle courant comme modifié. */
  touch() {
    if (this.current) this.current.updated_at = now();
  }

  // --- Historique (annuler / rétablir) ---

  checkpoint() {
    const t = this.current;
    if (!t) return;
    const snap = JSON.stringify($state.snapshot(t));
    const past = this.#past.get(t.id) ?? [];
    if (past[past.length - 1] === snap) return;
    past.push(snap);
    if (past.length > HISTORY_LIMIT) past.shift();
    this.#past.set(t.id, past);
    this.#future.set(t.id, []);
    this.historyVersion++;
  }

  #restore(from: Map<string, string[]>, to: Map<string, string[]>, label: string) {
    const t = this.current;
    if (!t) return;
    const stack = from.get(t.id);
    let snap = stack?.pop();
    const currentSnap = JSON.stringify($state.snapshot(t));
    // Un point de sauvegarde identique à l'état courant ne compte pas.
    while (snap === currentSnap) snap = stack?.pop();
    if (!snap) return;
    to.set(t.id, [...(to.get(t.id) ?? []), currentSnap]);
    const restored = JSON.parse(snap) as Template;
    const index = this.data.templates.findIndex((x) => x.id === t.id);
    this.data.templates[index] = { ...restored, updated_at: now() };
    if (!restored.elements.some((e) => e.id === this.selectedId)) this.selectedId = null;
    this.historyVersion++;
    this.announce(label);
  }

  undo() {
    this.#restore(this.#past, this.#future, 'Modification annulée');
  }

  redo() {
    this.#restore(this.#future, this.#past, 'Modification rétablie');
  }

  canUndo(id: string | null) {
    void this.historyVersion;
    return !!id && (this.#past.get(id)?.length ?? 0) > 0;
  }

  canRedo(id: string | null) {
    void this.historyVersion;
    return !!id && (this.#future.get(id)?.length ?? 0) > 0;
  }

  markPrinted(refs: string[]) {
    const at = now();
    for (const r of refs) if (r) this.printed[r] = at;
    savePrinted($state.snapshot(this.printed));
  }

  // --- Champs du modèle ---

  /** Place un champ des données sur le modèle courant (ligne de texte, à ajuster ensuite). */
  placeField(field: string) {
    const t = this.current;
    if (!t) return;
    this.checkpoint();
    t.elements.push({ ...newElement('text', t), ...placeFieldElement(t, field) } as LabelElement);
    t.ignored = t.ignored.filter((f) => f !== field);
    if (!(field in t.sample)) t.sample[field] = '';
    this.touch();
    this.announce(`Champ {${field}} ajouté en bas du modèle « ${t.name} » : ajustez sa place dans l'onglet Modèles`);
  }

  ignoreField(field: string, ignore = true) {
    const t = this.current;
    if (!t) return;
    this.checkpoint();
    t.ignored = ignore ? [...new Set([...t.ignored, field])] : t.ignored.filter((f) => f !== field);
    this.touch();
  }

  setRequired(field: string, required: boolean) {
    const t = this.current;
    if (!t) return;
    this.checkpoint();
    t.required = required ? [...new Set([...t.required, field])] : t.required.filter((f) => f !== field);
    this.touch();
  }

  // --- Éléments ---

  addElement(kind: ElementKind, init: Partial<LabelElement> = {}) {
    const t = this.current;
    if (!t) return;
    this.checkpoint();
    const el = { ...newElement(kind, t), ...init } as LabelElement;
    t.elements.push(el);
    this.selectedId = el.id;
    this.touch();
    const names = { code: 'Code', text: 'Texte', rect: 'Cadre', image: 'Image' };
    this.announce(`${names[kind]} ajouté`);
  }

  removeSelected() {
    const t = this.current;
    if (!t || !this.selectedId) return;
    this.checkpoint();
    t.elements = t.elements.filter((e) => e.id !== this.selectedId);
    this.selectedId = null;
    this.touch();
    this.announce('Élément supprimé');
  }

  duplicateSelected() {
    const t = this.current;
    const el = this.selected;
    if (!t || !el) return;
    this.checkpoint();
    const copy = { ...structuredClone($state.snapshot(el) as LabelElement), id: uid(), x: el.x + 2, y: el.y + 2 };
    t.elements.push(copy);
    this.selectedId = copy.id;
    this.touch();
    this.announce('Élément dupliqué');
  }

  /** Change l'ordre d'empilement : +1 au premier plan, -1 vers l'arrière. */
  reorderSelected(direction: 1 | -1) {
    const t = this.current;
    if (!t || !this.selectedId) return;
    const i = t.elements.findIndex((e) => e.id === this.selectedId);
    const j = i + direction;
    if (j < 0 || j >= t.elements.length) return;
    this.checkpoint();
    [t.elements[i], t.elements[j]] = [t.elements[j], t.elements[i]];
    this.touch();
  }
}

export const studio = new Studio();
