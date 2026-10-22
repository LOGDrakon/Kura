// Lecture des articles dans la base de l'entreprise (GET /items, ou endpoints.list_items).
import { authHeaders, joinUrl, type KuraConfig } from './config';
import { flattenItem } from './records';

export interface ItemPage {
  items: Record<string, string>[];
  next?: string;
}

export interface ListQuery {
  q?: string;
  updatedSince?: string;
  cursor?: string;
  limit?: number;
}

export class ServerError extends Error {}

export async function listItems(config: KuraConfig, secret: string, query: ListQuery, fetchFn: typeof fetch = fetch): Promise<ItemPage> {
  const endpoint = config.endpoints?.list_items ?? { method: 'GET', path: '/items' };
  const url = new URL(joinUrl(config.server?.base_url ?? '', endpoint.path));
  if (query.q) url.searchParams.set('q', query.q);
  if (query.updatedSince) url.searchParams.set('updated_since', query.updatedSince);
  if (query.cursor) url.searchParams.set('cursor', query.cursor);
  url.searchParams.set('limit', String(query.limit ?? 100));

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), config.server?.timeout_ms ?? 8000);
  let res: Response;
  try {
    res = await fetchFn(url, {
      method: endpoint.method,
      headers: { Accept: 'application/json', ...authHeaders(config, secret) },
      signal: controller.signal,
    });
  } catch (e) {
    throw new ServerError(
      (e as Error).name === 'AbortError'
        ? 'Le serveur ne répond pas (délai dépassé).'
        : "Serveur injoignable depuis ce navigateur : adresse, certificat, ou autorisation des appels navigateur (CORS) à vérifier.",
    );
  } finally {
    clearTimeout(timer);
  }
  if (res.status === 401 || res.status === 403) throw new ServerError('Accès refusé : vérifiez le jeton.');
  if (res.status === 404) throw new ServerError("Ce serveur ne propose pas la liste des articles (GET /items). Utilisez l'import CSV.");
  if (!res.ok) throw new ServerError(`Erreur du serveur (${res.status}).`);

  let body: any;
  try {
    body = await res.json();
  } catch {
    throw new ServerError("Réponse illisible (JSON attendu).");
  }
  const raw = Array.isArray(body) ? body : body?.items;
  if (!Array.isArray(raw)) throw new ServerError("Réponse inattendue : liste « items » absente.");
  return {
    items: raw.filter((x) => x && typeof x === 'object').map((x) => flattenItem(x)),
    next: typeof body?.next_cursor === 'string' ? body.next_cursor : undefined,
  };
}
