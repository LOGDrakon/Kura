// Construction et validation de kura-config.json (spec/config.schema.json).
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import schema from '../../../../spec/config.schema.json';

export type KuraConfig = Record<string, any>;

export function defaultConfig(company = ''): KuraConfig {
  return {
    kura_config_version: 1,
    company,
    server: { base_url: 'https://', auth: { type: 'bearer', token_ref: 'secure_store' }, timeout_ms: 8000 },
    scan: {
      symbologies: ['qr_code', 'ean_13', 'ean_8', 'code_128'],
      format: 'id',
      trigger: { mode: 'button', timeout_ms: 6000, aim_area: 0.6, volume_keys: true },
    },
    display: { show_fields: ['name', 'location', 'quantity'], show_image: false, step_buttons: [1, 10], allow_set: true },
    burst: { enabled: true, default_direction: 'out', default_step: 1, same_code_cooldown_ms: 1500, verify_item: false },
    sync: { mode: 'auto', batch: true, max_batch_size: 50, retry_backoff_s: [5, 30, 120] },
    security: { config_locked: false, require_user: false },
  };
}

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
const validateFn = ajv.compile(schema);

const MESSAGES: Record<string, (p: any) => string> = {
  required: (p) => `champ obligatoire manquant : ${p.missingProperty}`,
  pattern: () => 'format invalide',
  format: (p) => (p.format === 'uri' ? 'adresse invalide' : `format ${p.format} invalide`),
  enum: (p) => `valeur attendue : ${p.allowedValues.join(', ')}`,
  minimum: (p) => `minimum ${p.limit}`,
  maximum: (p) => `maximum ${p.limit}`,
  minItems: (p) => `au moins ${p.limit} élément(s)`,
  maxItems: (p) => `au plus ${p.limit} élément(s)`,
  uniqueItems: () => 'valeurs en double',
  additionalProperties: (p) => `champ inconnu : ${p.additionalProperty}`,
  type: (p) => `type attendu : ${p.type}`,
  const: (p) => `valeur attendue : ${p.allowedValue}`,
};

export interface ConfigIssue {
  path: string;
  message: string;
  level: 'error' | 'warning';
}

/** Nettoie les valeurs vides avant export (champs optionnels laissés blancs dans le formulaire). */
export function pruneConfig(value: any): any {
  if (Array.isArray(value)) return value.map(pruneConfig);
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      if (v === '' || v === undefined || v === null) continue;
      const pruned = pruneConfig(v);
      if (pruned && typeof pruned === 'object' && !Array.isArray(pruned) && Object.keys(pruned).length === 0) continue;
      out[k] = pruned;
    }
    return out;
  }
  return value;
}

export function checkConfig(config: KuraConfig): ConfigIssue[] {
  const issues: ConfigIssue[] = [];
  if (!validateFn(config)) {
    for (const e of validateFn.errors ?? []) {
      if (e.keyword === 'if') continue;
      issues.push({
        path: e.instancePath.replace(/^\//, '').replace(/\//g, ' › ') || 'config',
        message: MESSAGES[e.keyword]?.(e.params) ?? e.message ?? 'invalide',
        level: 'error',
      });
    }
  }
  const url = String(config.server?.base_url ?? '');
  if (/^http:\/\//i.test(url) && !/^http:\/\/(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/i.test(url)) {
    issues.push({ path: 'server › base_url', message: 'connexion non chiffrée (HTTP) : utilisez HTTPS en production', level: 'warning' });
  }
  if (config.security?.config_locked && !config.security?.config_password_hash) {
    issues.push({ path: 'security', message: 'verrouillage activé sans mot de passe', level: 'error' });
  }
  return issues;
}

/** Remplace {var} dans un chemin d'endpoint, en encodant les valeurs. */
export function expandPath(path: string, vars: Record<string, string>): string {
  return path.replace(/\{([a-z_][a-z0-9_]*)\}/gi, (_, k: string) => encodeURIComponent(vars[k] ?? ''));
}

export function joinUrl(base: string, path: string): string {
  return base.replace(/\/+$/, '') + '/' + path.replace(/^\/+/, '');
}

export function authHeaders(config: KuraConfig, secret: string): Record<string, string> {
  const auth = config.server?.auth ?? { type: 'none' };
  if (!secret) return {};
  switch (auth.type) {
    case 'bearer':
      return { Authorization: `Bearer ${secret}` };
    case 'basic': {
      const bytes = new TextEncoder().encode(`${auth.username ?? ''}:${secret}`);
      return { Authorization: `Basic ${btoa(String.fromCharCode(...bytes))}` };
    }
    case 'api_key':
      return { [auth.header || 'X-Api-Key']: secret };
    default:
      return {};
  }
}

/** Retire les valeurs identiques aux défauts du schéma (l'appli les réapplique) : QR plus léger, donc plus lisible. */
export function stripDefaults(value: any, node: any = schema): any {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value)) {
    let sub = node?.properties?.[k];
    if (sub?.$ref) sub = (schema as any).$defs[sub.$ref.split('/').pop()];
    if (sub && 'default' in sub && JSON.stringify(sub.default) === JSON.stringify(v)) continue;
    const stripped = stripDefaults(v, sub);
    const empty = stripped && typeof stripped === 'object' && !Array.isArray(stripped) && Object.keys(stripped).length === 0;
    if (empty && !node?.required?.includes(k)) continue;
    out[k] = stripped;
  }
  return out;
}
