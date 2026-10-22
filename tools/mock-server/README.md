# Kura Mock Server

Serveur de démonstration qui implémente le [contrat d'API Kura](../../spec/api-contract.yaml). Il sert à :

- essayer Kura Scan et Kura Studio sans toucher à la vraie base de l'entreprise ;
- développer et tester les applis (les tests de `:core` et de Studio s'en servent) ;
- servir de **référence** à qui implémente le contrat pour son propre système (voir le [guide d'intégration](../../docs/guides/integration.md)).

**Ne pas utiliser en production** : pas de HTTPS, un seul jeton facultatif, aucune limite d'accès.

Node.js 20 ou plus, **aucune dépendance** à installer.

## Lancer

```sh
cd tools/mock-server
npm start                     # ou : node server.js
```

Le serveur écoute sur `http://0.0.0.0:8787` (toutes les interfaces, donc joignable depuis un téléphone du même réseau). Il charge 6 articles de test et garde tout **en mémoire** : chaque redémarrage repart de zéro.

Pour une démonstration plus parlante (14 articles répartis en services, catégories et marques) **et persistante** :

```sh
KURA_MOCK_DB=.data/db.json KURA_MOCK_ITEMS=demo/items.demo.json npm start
```


Ouvrez ensuite <http://localhost:8787/> : la **Base de démo Kura** simule la base de stock de l'entreprise (GMAO).

- Le tableau **Articles** se met à jour toutes les 2 s : chaque mouvement envoyé par un téléphone fait clignoter la ligne concernée.
- Cliquez sur une ligne pour voir son **Journal** : date, type (mouvement ou inventaire), valeur, utilisateur, appareil.
- **Créer ou modifier une entrée** : simule la création d'un article dans la GMAO. Il apparaît alors dans Kura Studio (« Votre base ») pour l'impression de son étiquette. Le stock initial n'est pris en compte qu'à la création.

## Variables d'environnement

| Variable | Rôle | Défaut |
|---|---|---|
| `PORT` | Port d'écoute | `8787` |
| `HOST` | Interface d'écoute (`127.0.0.1` pour n'accepter que le poste local) | `0.0.0.0` |
| `KURA_MOCK_ITEMS` | Fichier JSON des articles de départ (tableau d'objets avec au moins `ref`) | `data/items.json` |
| `KURA_MOCK_DB` | Fichier où enregistrer articles et journal après chaque modification (écriture atomique). S'il existe au démarrage, il est rechargé et `KURA_MOCK_ITEMS` est ignoré. | aucun (mémoire) |
| `KURA_MOCK_TOKEN` | Exige `Authorization: Bearer <jeton>` sur toutes les routes sauf `/`, `/_admin` et `/health` | désactivé |
| `KURA_MOCK_CONFIG` | Fichier `kura-config.json` servi par `GET /config` | aucun (404) |
| `KURA_MOCK_LATENCY_MS` | Latence ajoutée à chaque requête, pour simuler un réseau lent | `0` |

Pour **repartir de zéro** avec `KURA_MOCK_DB`, arrêtez le serveur et supprimez le fichier (`rm .data/db.json`).

Avec `KURA_MOCK_TOKEN`, ouvrez la page de démo avec le jeton dans l'adresse : `http://localhost:8787/?token=<jeton>`.

## Fichiers de démo

| Fichier | Contenu |
|---|---|
| `data/items.json` | 6 articles, utilisés par les tests automatiques. Ne pas modifier sans adapter les tests. |
| `demo/items.demo.json` | 14 articles (services Mécanique, Maintenance, Électricité, EPI ; catégories ; marques), pour les démonstrations et le [démarrage rapide](../../docs/quickstart.md). |
| `demo/kura-config.demo.json` | Configuration Kura Scan correspondante (`http://127.0.0.1:8787`, images, rafale avec vérification, utilisateur demandé). À adapter : `127.0.0.1` ne fonctionne sur un téléphone qu'avec `adb reverse`. |

## Routes

Contrat Kura (détails et schémas dans [`spec/api-contract.yaml`](../../spec/api-contract.yaml)) :

| Route | Rôle |
|---|---|
| `GET /health` | `{"status":"ok"}`, sans authentification. |
| `GET /items` | Liste paginée : `q` (recherche dans toutes les colonnes), `updated_since` (ISO 8601), `limit` (≤ 500, défaut 50), `cursor`. Tri du plus récemment modifié au plus ancien. Réponse `{ "items": […], "next_cursor": "…" }` (`next_cursor` absent sur la dernière page). |
| `GET /items/{ref}` | Fiche d'un article (avec `image_url`), 404 si inconnu. |
| `GET /items/{ref}/image` | Image SVG générée (initiales de l'article), pour essayer `display.show_image`. |
| `POST /operations` | Applique une opération (`delta` ou `set`). Statuts : `applied`, `duplicate` (même `op_id` déjà reçu, le stock ne bouge pas), `superseded` (inventaire plus ancien que le dernier enregistré), `rejected`. |
| `POST /operations/batch` | `{ "operations": [ … ] }`, 500 au plus ; réponse `{ "results": [ … ] }` dans le même ordre. |
| `GET /config` | Config distante (`KURA_MOCK_CONFIG`), 404 sinon. |

Hors contrat (démo et débogage) :

| Route | Rôle |
|---|---|
| `GET /`, `GET /_admin` | Page « Base de démo Kura ». |
| `POST /_debug/items` | Crée ou modifie un article : `{"ref":"REF-0500","name":"Joint torique 20x2"}`. `quantity` n'est pris en compte qu'à la création. |
| `GET /_debug/journal/{ref}` | Opérations reçues pour un article. |

Toutes les réponses portent les en-têtes CORS (`Access-Control-Allow-Origin: *`), pour que Kura Studio puisse interroger le serveur depuis le navigateur.

## Exemples

```sh
# Fiche d'un article
curl localhost:8787/items/REF-0042

# Sortie d'une unité
curl -X POST localhost:8787/operations -H 'Content-Type: application/json' -d '{
  "op_id": "4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11",
  "item_id": "REF-0042", "type": "delta", "value": -1,
  "client_ts": "2026-10-22T13:55:12Z"
}'
# → {"op_id":"…","status":"applied","quantity":249,…}
# Même commande une seconde fois → "status":"duplicate", le stock ne bouge pas.

# Inventaire : 240 pièces comptées
curl -X POST localhost:8787/operations -H 'Content-Type: application/json' -d '{
  "op_id": "9a1c3e5f-1111-4222-8333-944455556666",
  "item_id": "REF-0042", "type": "set", "value": 240,
  "client_ts": "2026-10-22T14:00:00Z"
}'

# Nouvel article « créé dans la GMAO », puis recherche comme le fait Kura Studio
curl -X POST localhost:8787/_debug/items -H 'Content-Type: application/json' \
  -d '{"ref":"REF-0500","name":"Joint torique 20x2","brand":"Parker"}'
curl 'localhost:8787/items?q=parker'

# Journal de l'article
curl localhost:8787/_debug/journal/REF-0042
```

## Tests

```sh
npm test        # node --test : magasin (règles d'application des opérations) et serveur HTTP
```

Le serveur est aussi utilisé par le test de bout en bout de Kura Scan (`./gradlew :core:test`, ignoré si `node` est absent) et par le parcours Chrome de Kura Studio.

## Organisation

| Fichier | Rôle |
|---|---|
| `server.js` | Routes HTTP, authentification, CORS, persistance. Exporte `createServer()` pour les tests. |
| `lib/store.js` | Articles, journal et règles du contrat : idempotence par `op_id`, `delta` additifs, `set` « le plus récent gagne » ([ADR 0005](../../docs/adr/0005-resolution-conflits.md)). |
| `admin.html` | Page « Base de démo Kura ». |
| `test/` | Tests (`node:test`). |
