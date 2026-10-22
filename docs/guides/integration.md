# Connecter un système existant

Ce guide s'adresse à l'informaticien ou à l'intégrateur qui relie Kura à la base de stock de l'entreprise. Il décrit les possibilités, puis le [contrat d'API](../../spec/api-contract.yaml) dans le détail : les requêtes envoyées par Kura Scan et Kura Studio, les réponses attendues, et la façon de traiter doublons et inventaires.

Sommaire :

1. [Choisir sa méthode](#1-choisir-sa-méthode)
2. [Le contrat en bref](#2-le-contrat-en-bref)
3. [Lire un article](#3-lire-un-article--get-itemsref)
4. [Recevoir les opérations](#4-recevoir-les-opérations--post-operations)
5. [Envoi groupé](#5-envoi-groupé--post-operationsbatch)
6. [Doublons et inventaires : la règle de stock](#6-doublons-et-inventaires--la-règle-de-stock)
7. [Codes HTTP et réaction de l'appli](#7-codes-http-et-réaction-de-lappli)
8. [Liste des articles pour Kura Studio](#8-liste-des-articles-pour-kura-studio--get-items)
9. [Images et config distante](#9-images-et-config-distante)
10. [Adapter une API REST existante sans code](#10-adapter-une-api-rest-existante-sans-code)
11. [Tester son intégration](#11-tester-son-intégration)

---

## 1. Choisir sa méthode

| Votre situation | Méthode | Effort |
|---|---|---|
| Le stock est dans une base **SQL** (PostgreSQL, MySQL/MariaDB, SQL Server, SQLite) accessible depuis un serveur | **[Kura Bridge](bridge.md)** : 3 requêtes SQL dans un fichier YAML | Une heure |
| Le logiciel a une **API** (REST, SOAP) ou le stock est dans un **fichier** (CSV, Excel) | **Kura Bridge en mode script** : un petit programme (Python, PowerShell…) qui lit et écrit le stock | Une demi-journée |
| Vous développez le logiciel de stock vous-même, ou il est extensible | **Implémenter le contrat** directement : 2 routes obligatoires, décrites ci-dessous | Une à deux journées |
| Le logiciel a une API REST qui sait déjà ajouter un mouvement, et vous ne pouvez rien installer | **Correspondance directe** dans la config de Kura Scan (`endpoints`, `request_mapping`, `response_mapping`) | Une heure, mais sans gestion des doublons ni des inventaires |
| Pas de serveur du tout, ou un essai | **[Serveur de démonstration](../../tools/mock-server/README.md)** ; étiquettes depuis un fichier CSV | Quelques minutes |

Le Bridge est recommandé dans la plupart des cas : il implémente tout le contrat (doublons, règle des inventaires, journal, CORS, sécurité) et vous n'écrivez que ce qui est propre à votre système.

---

## 2. Le contrat en bref

Référence complète : [`spec/api-contract.yaml`](../../spec/api-contract.yaml) (OpenAPI 3.1, lisible dans tout éditeur Swagger).

| Route | Appelée par | Obligatoire |
|---|---|---|
| `GET /items/{ref}` | Kura Scan : fiche de l'article scanné, vérification en mode rafale | Oui, sauf si toutes les infos sont dans le code scanné |
| `POST /operations` | Kura Scan : un mouvement ou un inventaire | **Oui** |
| `POST /operations/batch` | Kura Scan : vidage de la file d'attente hors-ligne | Recommandée (`sync.batch`) |
| `GET /items` | Kura Studio : liste des articles à étiqueter | Non (Studio sait lire un CSV) |
| `GET /items/{ref}/image` | Kura Scan : photo de l'article | Non |
| `GET /config` | Config distante signée | Non (pas encore utilisée par Kura Scan 1.0) |
| `GET /health` | Supervision | Non |

Conventions :

- **JSON** en UTF-8, dans les deux sens.
- Les routes s'ajoutent à l'adresse configurée : avec `base_url = https://erp.exemple.fr/api/kura`, la fiche est `https://erp.exemple.fr/api/kura/items/REF-0042`.
- **Authentification** : `Authorization: Bearer <jeton>` (ou `Basic`, ou une clé dans un en-tête, selon `server.auth`). Le secret n'est envoyé qu'à l'hôte de `base_url`.
- Les références sont **encodées** dans le chemin : `REF 12/B` devient `/items/REF%2012%2FB`.
- Les erreurs suivent la forme `{"error": "code", "message": "texte lisible"}`.
- Les dates sont en **ISO 8601 avec fuseau** : `2026-10-22T13:55:12.345Z`.

---

## 3. Lire un article : `GET /items/{ref}`

```http
GET /items/REF-0042 HTTP/1.1
Authorization: Bearer 9f2c…
Accept: application/json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "ref": "REF-0042",
  "name": "Roulement 6204-2RS",
  "location": "A-03-2",
  "quantity": 12,
  "unit": "pcs",
  "image_url": "/items/REF-0042/image",
  "updated_at": "2026-10-01T08:00:00Z",
  "service": "Maintenance",
  "fournisseur": "SKF"
}
```

- Seul `ref` est obligatoire. `name`, `location`, `quantity` (entier), `unit`, `image_url` (absolue ou relative à `base_url`) et `updated_at` ont un sens connu.
- **Tous les autres champs sont libres** : la fiche de Kura Scan les affiche si on les liste dans `display.show_fields`, et Kura Studio peut les imprimer sur les étiquettes (`{fournisseur}`).
- Article inconnu : **`404`**, avec `{"error":"not_found","message":"article inconnu : REF-0042"}`.

---

## 4. Recevoir les opérations : `POST /operations`

Chaque action de l'utilisateur produit **une opération**, enregistrée d'abord sur le téléphone, puis envoyée dès que possible (immédiatement en ligne, plus tard hors-ligne).

```http
POST /operations HTTP/1.1
Authorization: Bearer 9f2c…
Content-Type: application/json

{
  "op_id": "6b1f8a52-3c1e-4c2a-9b1e-2f0e4d5a7c11",
  "item_id": "REF-0042",
  "type": "delta",
  "value": -2,
  "client_ts": "2026-10-22T13:55:12.345Z",
  "device_id": "kura-7f3a2c",
  "user": "marie.dupont",
  "session_id": "b0c2…"
}
```

| Champ | Description |
|---|---|
| `op_id` | **UUID v4** créé par le téléphone. Identique à chaque renvoi : c'est la clé d'idempotence. |
| `item_id` | Référence de l'article (1 à 128 caractères). |
| `type` | `delta` : mouvement, à **additionner** (positif = entrée, négatif = sortie, jamais 0). `set` : inventaire, quantité comptée (≥ 0). |
| `value` | Entier. |
| `client_ts` | Moment de l'action sur le téléphone. |
| `device_id`, `user`, `session_id` | Facultatifs : appareil, utilisateur saisi dans l'appli, session du mode rafale (regroupe les lectures d'une même tournée). |

Réponse : **toujours `200`** dès que l'opération a été traitée, avec son statut :

```json
{
  "op_id": "6b1f8a52-3c1e-4c2a-9b1e-2f0e4d5a7c11",
  "status": "applied",
  "quantity": 10,
  "server_received_at": "2026-10-22T13:55:13.020Z"
}
```

| `status` | Sens | L'appli… |
|---|---|---|
| `applied` | Prise en compte. | la retire de sa file et affiche le stock `quantity` s'il est donné. |
| `duplicate` | Ce `op_id` a déjà été reçu ; rien n'est refait. | la retire de sa file. |
| `superseded` | Inventaire plus ancien qu'un inventaire déjà reçu : enregistré, sans effet sur le stock. | la retire et prévient discrètement l'utilisateur. |
| `rejected` | Refusée ; précisez `reason` et un `message` lisible. | la garde **en erreur** : l'utilisateur corrige, relance ou supprime. |

Motifs de rejet (`reason`) :

| `reason` | Quand |
|---|---|
| `invalid` | Champ manquant ou incorrect (`delta` nul, `set` négatif, date illisible…). |
| `unknown_item` | L'article n'existe pas. Ne mémorisez pas l'`op_id` : l'article peut être créé, puis l'opération renvoyée. |
| `forbidden` | Refus métier : droits de l'utilisateur, article bloqué, stock négatif interdit… |
| `conflict` | Conflit propre à votre système. |
| `server_error` | Erreur définitive côté serveur. Pour une erreur **passagère**, répondez plutôt `503` (voir [§ 7](#7-codes-http-et-réaction-de-lappli)). |

`quantity` (stock après traitement) est facultatif mais très utile : l'utilisateur voit tout de suite le stock réel, y compris les mouvements faits par d'autres.

---

## 5. Envoi groupé : `POST /operations/batch`

Quand le téléphone retrouve le réseau, il vide sa file par lots (jusqu'à 500 opérations, réglage `sync.max_batch_size`) :

```json
{ "operations": [ { "op_id": "…", "item_id": "REF-0042", "type": "delta", "value": -1, "client_ts": "…" },
                  { "op_id": "…", "item_id": "REF-0100", "type": "set",   "value": 31, "client_ts": "…" } ] }
```

```json
{ "results": [ { "op_id": "…", "status": "applied", "quantity": 9 },
               { "op_id": "…", "status": "rejected", "reason": "unknown_item", "message": "article inconnu : REF-0100" } ] }
```

- Chaque opération est traitée **indépendamment** : un rejet n'annule pas les autres.
- Les résultats sont renvoyés **dans le même ordre** que les opérations, chacun avec son `op_id`.
- Liste vide ou de plus de 500 éléments : `400`.
- Si le système devient indisponible au milieu du lot, répondez `503` : le téléphone renverra tout le lot, et les opérations déjà traitées reviendront en `duplicate`. C'est pourquoi l'idempotence est indispensable.

---

## 6. Doublons et inventaires : la règle de stock

C'est la seule partie délicate. Un téléphone peut rester hors-ligne des heures, puis tout envoyer d'un coup ; une réponse peut se perdre et l'opération être renvoyée. Le stock doit pourtant rester juste.

### Idempotence

Mémorisez chaque `op_id` traité (contrainte d'unicité en base). Un `op_id` déjà connu répond `duplicate`, sans rien refaire. Le plus simple est d'enregistrer l'opération dans **la même transaction** que la mise à jour du stock :

```sql
CREATE TABLE kura_operations (
  op_id      VARCHAR(36) PRIMARY KEY,
  item_id    VARCHAR(128) NOT NULL,
  op_type    VARCHAR(8) NOT NULL,
  op_value   BIGINT NOT NULL,
  client_ts  TIMESTAMP NOT NULL,
  user_name  VARCHAR(128),
  status     VARCHAR(16) NOT NULL
);
```

### Mouvements et inventaires

Les **mouvements** (`delta`) s'additionnent : deux sorties de 1 faites en même temps sur deux téléphones donnent bien −2, sans conflit.

Un **inventaire** (`set`) fixe le stock à la quantité comptée. Mais il arrive parfois après des mouvements faits **plus tard** que lui, ou avant un autre inventaire plus récent. La règle de Kura ([ADR 0005](../adr/0005-resolution-conflits.md)) :

> **stock = quantité du dernier inventaire (par `client_ts`) + somme des mouvements faits après lui.**
> À `client_ts` égal, l'inventaire dont l'`op_id` est le plus grand l'emporte.

Le résultat ne dépend pas de l'ordre d'arrivée. Exemple, stock initial 10 :

| Opération | Faite à | Arrivée |
|---|---|---|
| inventaire = 15 | 08:00 | 4ᵉ |
| sortie −1 | 09:00 | 1ʳᵉ |
| inventaire = 40 | 10:00 | 2ᵉ |
| entrée +3 | 11:00 | 3ᵉ |
| sortie −4 | 12:00 | 5ᵉ |

Stock final : 40 (dernier inventaire, 10:00) + 3 − 4 = **39**. La sortie de 09:00 et l'inventaire de 08:00 sont antérieurs à l'inventaire de 10:00, qui les a déjà comptés ; l'inventaire de 08:00 répond `superseded`.

### L'appliquer sur une base qui ne garde que le stock

Si votre base ne garde que le stock courant (pas l'historique), appliquez la règle **au fil de l'eau**, à l'aide du journal des opérations déjà reçues pour l'article. C'est ce que fait Kura Bridge ([`internal/journal/rule.go`](../../apps/bridge/internal/journal/rule.go)) :

```
recevoir(op) :
  si op.op_id est déjà dans le journal        → duplicate (renvoyer le stock actuel)
  si l'article n'existe pas                    → rejected / unknown_item (sans journaliser)
  dernier = inventaire le plus récent du journal pour cet article

  si op est un delta :
     si dernier existe et op.client_ts <= dernier.client_ts → journaliser, stock inchangé   (déjà compté)
     sinon                                                  → stock = stock + op.value

  si op est un set :
     si dernier existe et dernier est plus récent que op    → journaliser en superseded, stock inchangé
     sinon → stock = op.value + somme des delta du journal faits après op.client_ts

  journaliser op, puis répondre applied (ou superseded) avec le stock
```

Exécutez le tout dans une transaction, et traitez les opérations **une à une** pour un même article (verrou de ligne `SELECT … FOR UPDATE`, ou une seule file de traitement).

Si votre système sait déjà recalculer le stock à partir de l'historique des mouvements, appliquez directement la formule.

Vérification : le [serveur de démonstration](../../tools/mock-server/lib/store.js) et Kura Bridge testent les **120 ordres d'arrivée** de l'exemple ci-dessus ; réutilisez ce scénario dans vos tests.

---

## 7. Codes HTTP et réaction de l'appli

| Réponse | Kura Scan |
|---|---|
| `200` + résultat | Selon `status` (voir [§ 4](#4-recevoir-les-opérations--post-operations)). |
| `401`, `403` | Arrête les envois et signale un problème d'authentification ; les opérations restent dans la file. |
| `408`, `429`, `5xx`, réseau coupé, délai dépassé | Garde les opérations et **réessaie** plus tard (`sync.retry_backoff_s`). |
| Autre `4xx` | Place l'opération en erreur (`404` → `unknown_item`, `409` → `conflict`, sinon `invalid`). |
| `2xx` illisible | Place l'opération en erreur (`server_error`). |

**Règle d'or : une erreur passagère (base injoignable, verrou, maintenance) doit répondre `503`, jamais `rejected`.** Un `rejected` demande une décision humaine sur chaque téléphone ; un `503` est réessayé automatiquement.

---

## 8. Liste des articles pour Kura Studio : `GET /items`

Kura Studio liste les articles de la base pour en imprimer les étiquettes (tous, une recherche, les nouveautés depuis une date, ou un service entier).

```http
GET /items?q=roulement&updated_since=2026-10-01T00:00:00Z&limit=200&cursor=200 HTTP/1.1
```

| Paramètre | Sens |
|---|---|
| `q` | Recherche texte (référence, désignation…), insensible à la casse. |
| `updated_since` | Articles créés ou modifiés depuis cette date. |
| `limit` | Taille de page, 1 à 500 (50 par défaut). |
| `cursor` | Valeur `next_cursor` de la page précédente (format libre : un décalage, un identifiant…). |

```json
{
  "items": [ { "ref": "REF-0042", "name": "Roulement 6204-2RS", "service": "Maintenance", "updated_at": "2026-10-01T08:00:00Z" } ],
  "next_cursor": "400"
}
```

Triez du plus récent au plus ancien, avec un ordre stable. Omettez `next_cursor` sur la dernière page.

**Kura Studio est une page web** : le navigateur n'autorise l'appel que si le serveur renvoie les en-têtes **CORS**. Répondez à la requête préalable `OPTIONS` (`204`) et ajoutez à toutes les réponses :

```http
Access-Control-Allow-Origin: *                  (ou l'origine exacte de Studio)
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Authorization, Content-Type, X-Api-Key
Access-Control-Allow-Private-Network: true      (si la requête OPTIONS contient Access-Control-Request-Private-Network)
```

Studio ouvert depuis un fichier sur le poste envoie l'origine `null`.

---

## 9. Images et config distante

**`GET /items/{ref}/image`** renvoie l'image (`image/jpeg`, `image/png`, `image/webp`…), ou `404`. Ajoutez `ETag` et `Cache-Control` : l'appli garde les images pour l'affichage hors-ligne. Une redirection (`302`) vers un autre serveur convient aussi. Vous pouvez aussi indiquer `image_url` dans l'article, vers n'importe quelle adresse.

**`GET /config`** renvoie `{"config": "<contenu de kura-config.json, en texte>", "signature": "<Ed25519 en base64>"}`. La signature porte sur les octets UTF-8 exacts de la chaîne `config` ; la clé publique correspondante (SPKI DER en base64) figure dans `remote_config.signing_public_key`. Kura Bridge sait le faire (`kura-bridge keygen`), mais **Kura Scan 1.0 ne télécharge pas encore** de config distante.

---

## 10. Adapter une API REST existante sans code

Si votre logiciel a déjà une API REST capable de lire un article et d'enregistrer un mouvement, la config de Kura Scan peut s'y adapter directement, sans serveur intermédiaire. Exemple : une GMAO qui expose `GET /api/v2/articles/{code}` et `POST /api/v2/mouvements`.

```json
{
  "server": { "base_url": "https://gmao.exemple.fr/api/v2", "auth": { "type": "api_key", "header": "X-GMAO-Key" } },
  "endpoints": {
    "get_item": { "method": "GET",  "path": "/articles/{ref}" },
    "send_op":  { "method": "POST", "path": "/mouvements" }
  },
  "response_mapping": {
    "name": "libelle",
    "location": "magasin.emplacement",
    "quantity": "stock.disponible"
  },
  "request_mapping": {
    "code_article": "{item_id}",
    "quantite": "{value}",
    "nature": "{type}",
    "date": "{client_ts}",
    "operateur": "{user}",
    "reference_externe": "{op_id}"
  }
}
```

La réponse `{"libelle": "Vis M6", "magasin": {"emplacement": "A12"}, "stock": {"disponible": 250}}` s'affiche alors comme `name = Vis M6`, `location = A12`, `quantity = 250`. Chaque mouvement est envoyé comme `{"code_article": "REF-0042", "quantite": -2, "nature": "delta", …}`. Toutes les variables et règles sont dans la [référence de la configuration](../reference/config.md#endpoints).

Limites de cette méthode :

- **toute réponse 2xx vaut acceptation** : l'appli ne peut pas savoir si l'opération était un doublon. Faites en sorte que l'API refuse ou ignore un `reference_externe` déjà reçu (contrainte d'unicité), sinon un renvoi après coupure réseau serait compté deux fois ;
- **pas de règle des inventaires** : un inventaire envoyé tard écrase le stock tel quel. Réservez les inventaires (`display.allow_set`) aux téléphones qui restent en ligne, ou passez par le Bridge ;
- l'API doit savoir distinguer un mouvement (`nature = delta`, à additionner) d'un inventaire (`nature = set`, à écrire). Sinon, désactivez les inventaires ;
- Kura Studio n'utilise pas ces correspondances (sauf `endpoints.list_items`, qui doit renvoyer le format du contrat) : imprimez les étiquettes depuis un export CSV.

Dès que l'une de ces limites gêne, le [mode script du Bridge](bridge.md#6-mode-script) appelle la même API en ajoutant doublons, inventaires et journal.

---

## 11. Tester son intégration

1. **Comparez avec la référence.** Le [serveur de démonstration](../../tools/mock-server/README.md) implémente tout le contrat en quelques centaines de lignes de JavaScript lisibles ([`lib/store.js`](../../tools/mock-server/lib/store.js)) : envoyez-lui les mêmes requêtes qu'à votre serveur et comparez les réponses.
2. **Vérifiez à la main** avec `curl` :
   ```sh
   BASE=https://erp.exemple.fr/api/kura; AUTH="Authorization: Bearer $KURA_TOKEN"
   curl -s -H "$AUTH" $BASE/items/REF-0042
   OP='{"op_id":"6b1f8a52-3c1e-4c2a-9b1e-2f0e4d5a7c11","item_id":"REF-0042","type":"delta","value":-1,"client_ts":"2026-10-22T10:00:00Z"}'
   curl -s -H "$AUTH" -H 'Content-Type: application/json' -d "$OP" $BASE/operations    # applied
   curl -s -H "$AUTH" -H 'Content-Type: application/json' -d "$OP" $BASE/operations    # duplicate, même stock
   curl -s -i -X OPTIONS -H 'Origin: null' -H 'Access-Control-Request-Method: GET' $BASE/items   # en-têtes CORS
   ```
3. **Liste de contrôle** :
   - [ ] un article inconnu répond `404` ; une opération sur un article inconnu, `rejected` / `unknown_item` ;
   - [ ] un `op_id` renvoyé répond `duplicate`, le stock ne bouge pas ;
   - [ ] `delta` de valeur 0, `set` négatif, `client_ts` sans fuseau : `rejected` / `invalid` ;
   - [ ] un inventaire plus ancien qu'un autre répond `superseded` ;
   - [ ] le scénario du [§ 6](#6-doublons-et-inventaires--la-règle-de-stock) donne 39 quel que soit l'ordre d'envoi ;
   - [ ] un lot renvoie autant de résultats que d'opérations, dans l'ordre ;
   - [ ] base arrêtée : `503`, puis tout passe au redémarrage, sans doublon ;
   - [ ] jeton absent ou faux : `401` ;
   - [ ] Kura Studio liste les articles (CORS) ;
   - [ ] de bout en bout : un téléphone en mode avion fait quelques mouvements et un inventaire, puis se reconnecte.
