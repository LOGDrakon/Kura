# Référence de la configuration (`kura-config.json`)

Le fichier `kura-config.json` décrit tout ce que Kura Scan doit savoir : où est le serveur, quels codes lire, quoi afficher, comment envoyer. Il est normalement produit par Kura Studio (onglet **Appli mobile**), puis transmis au téléphone par un [QR de configuration](#qr-de-configuration) ou par fichier.

- Schéma officiel : [`spec/config.schema.json`](../../spec/config.schema.json) (JSON Schema 2020-12).
- Exemples : [minimal](../../spec/examples/kura-config.minimal.json), [complet](../../spec/examples/kura-config.full.json).
- Validation : `python3 spec/validate.py` vérifie le schéma et les exemples. Kura Studio valide avant de générer le QR ; Kura Scan valide à l'import, avec des messages en français.

Toutes les options sont facultatives, sauf `kura_config_version`, `server.base_url` et `scan` (qui peut être vide : `"scan": {}`). Une option absente prend sa valeur par défaut. **Une option inconnue est une erreur** : la configuration est refusée, ce qui évite les fautes de frappe silencieuses.

## Exemple minimal

```json
{
  "kura_config_version": 1,
  "company": "Atelier Durand",
  "server": { "base_url": "https://stock.exemple.fr/kura" },
  "scan": {}
}
```

Avec ce fichier, Kura Scan lit les QR codes, EAN-13, EAN-8 et Code 128, considère que le code contient la référence, et utilise les routes standard du [contrat d'API Kura](../../spec/api-contract.yaml).

## Racine

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `$schema` | texte | — | Lien vers le schéma, pour l'autocomplétion des éditeurs. Ignoré par l'appli. |
| `kura_config_version` | `1` | **obligatoire** | Version du format. Seule la valeur `1` existe. |
| `company` | texte (≤ 120) | — | Nom de l'entreprise, affiché dans l'appli et à l'import. |
| `server` | objet | **obligatoire** | [Serveur](#server). |
| `scan` | objet | **obligatoire** | [Lecture des codes](#scan). |
| `endpoints` | objet | routes standard | [Routes de l'API](#endpoints). |
| `request_mapping` | objet | corps standard | [Corps des opérations](#request_mapping) pour un serveur hors contrat. |
| `response_mapping` | objet | réponse standard | [Lecture de la fiche](#response_mapping) pour un serveur hors contrat. |
| `display` | objet | — | [Fiche article](#display). |
| `burst` | objet | — | [Mode rafale](#burst). |
| `sync` | objet | — | [Synchronisation](#sync). |
| `security` | objet | — | [Sécurité](#security). |
| `remote_config` | objet | — | [Config distante](#remote_config) (réservé). |

## `server`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `base_url` | URL | **obligatoire** | Adresse de l'API, commençant par `https://` (ou `http://` pour un essai local). Elle peut contenir un chemin : `https://erp.exemple.fr/api/kura`. Les routes y sont ajoutées. |
| `auth` | objet | `{ "type": "none" }` | Authentification (ci-dessous). |
| `timeout_ms` | entier 1000–60000 | `8000` | Délai d'attente d'une requête, en millisecondes. Au-delà, le mouvement reste en file d'attente. |

Avec `http://`, l'import affiche un avertissement et l'appli demande une confirmation avant le premier échange ([détails](../guides/scan.md#connexion-non-chiffrée-http)).

### `server.auth`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `type` | `none` \| `bearer` \| `basic` \| `api_key` | **obligatoire** | `none` : aucun en-tête. `bearer` : `Authorization: Bearer <jeton>`. `basic` : `Authorization: Basic …` (identifiant + mot de passe). `api_key` : clé dans un en-tête dédié. |
| `token_ref` | `secure_store` \| `mdm` | `secure_store` | Où se trouve le secret. `secure_store` : saisi sur le téléphone, ou transmis par un QR chiffré, puis conservé dans le coffre-fort Android. `mdm` : **réservé**, pas encore pris en charge par Kura Scan 1.0 (l'appli affiche seulement « Le secret est fourni par la gestion de flotte (MDM). »). |
| `header` | texte | `X-Api-Key` | Nom de l'en-tête, pour `api_key`. |
| `username` | texte | — | Identifiant, pour `basic`. Absent : il faudra le saisir sur l'appareil (avertissement à l'import). |

**Le secret n'apparaît jamais dans le fichier.** Il est saisi dans **Réglages › Authentification**, ou inclus dans un QR `KURA1E:` protégé par PIN. Les en-têtes d'authentification ne sont envoyés qu'au serveur de `base_url` (même schéma, hôte et port), jamais à un autre hôte (images hébergées ailleurs, par exemple).

## `scan`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `symbologies` | liste (≥ 1) | `["qr_code", "ean_13", "ean_8", "code_128"]` | Formats lus : `qr_code`, `data_matrix`, `ean_13`, `ean_8`, `upc_a`, `upc_e`, `code_128`, `code_39`, `itf`. Restreindre la liste accélère la lecture et évite les fausses lectures. |
| `format` | `id` \| `delimited` \| `json` | `id` | Contenu du code. `id` (recommandé) : le code ne contient que la référence. `delimited` : plusieurs champs séparés par `delimiter`. `json` : un objet JSON. |
| `delimiter` | texte (≤ 4) | `;` | Séparateur, pour `delimited`. |
| `fields` | liste de noms de champ | — | **Obligatoire pour `delimited` et `json`.** `delimited` : noms des champs, dans l'ordre. `json` : clés attendues. Doit contenir `id_field`. |
| `id_field` | nom de champ | `ref` | Champ qui identifie l'article. |
| `prefix` | texte | — | Préfixe à retirer avant analyse (ex. `KURA:`). **Un code sans ce préfixe est refusé.** |
| `trigger` | objet | — | Déclenchement de la lecture (ci-dessous). |

Un **nom de champ** commence par une lettre minuscule, suivie de minuscules, chiffres ou `_` (40 caractères au plus) : `ref`, `lot`, `num_serie`.

Les champs lus dans le code sont disponibles comme variables `{nom}` dans les [routes](#endpoints) et le [`request_mapping`](#request_mapping). Avec `format: "id"`, la seule variable est `{ref}` (le contenu du code), et aussi `{<id_field>}` si `id_field` est différent.

Exemple : codes `REF-42;Vis M6;A12`.

```json
"scan": {
  "format": "delimited",
  "delimiter": ";",
  "fields": ["ref", "name", "location"],
  "id_field": "ref"
}
```

### `scan.trigger`

Par défaut, rien n'est lu tant que l'utilisateur n'appuie pas sur « Scanner » ([ADR 0006](../adr/0006-scan-sur-appui.md)).

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `mode` | `button` \| `continuous` | `button` | `button` : une lecture par appui, arrêt au premier code. `continuous` : lecture permanente, pour un poste fixe. |
| `timeout_ms` | entier 1000–30000 | `6000` | Durée maximale d'une tentative après appui. |
| `aim_area` | nombre 0,2–1 | `0.6` | Largeur de la zone de visée, en part de l'image. Seuls les codes dans cette zone sont lus. |
| `volume_keys` | booléen | `true` | Les touches de volume déclenchent aussi la lecture. |

Les scanners Bluetooth et gâchettes intégrées (clavier HID) fonctionnent toujours, quel que soit ce réglage.

## `endpoints`

Remplace les routes du contrat Kura. Chaque route est un objet `{ "method": "GET" | "POST" | "PUT" | "PATCH", "path": "/…" }` ; le chemin commence par `/` et s'ajoute à `base_url`.

| Clé | Défaut | Utilisée par | Variables du chemin |
|---|---|---|---|
| `get_item` | `GET /items/{ref}` | Kura Scan : fiche article, vérification en rafale | champs scannés |
| `send_op` | `POST /operations` | Kura Scan : envoi d'un mouvement | champs scannés + variables d'opération |
| `send_batch` | `POST /operations/batch` | Kura Scan : envoi groupé | champs scannés + variables d'opération |
| `get_config` | `GET /config` | réservé (config distante) | — |
| `list_items` | `GET /items` | Kura Studio : liste des articles à étiqueter (paramètres ajoutés : `q`, `updated_since`, `limit`, `cursor`) | — |

Les valeurs insérées dans un chemin sont encodées (`/` et espaces compris). Une variable inconnue donne un avertissement à l'import.

```json
"endpoints": {
  "get_item": { "method": "GET", "path": "/articles/{ref}" },
  "send_op": { "method": "POST", "path": "/stock/movements" }
}
```

## `request_mapping`

Pour un serveur qui ne parle pas le contrat Kura : décrit le corps JSON de chaque mouvement. Clé = nom attendu par le serveur ; valeur = gabarit avec des `{variables}`. Absent : corps standard Kura (voir le [contrat](../../spec/api-contract.yaml)).

Variables d'opération :

| Variable | Contenu |
|---|---|
| `op_id` | Identifiant unique du mouvement (UUID v4). Le même en cas de renvoi : il permet au serveur d'ignorer les doublons. |
| `item_id` | Référence de l'article. |
| `type` | `delta` (entrée/sortie, s'additionne) ou `set` (inventaire, remplace). |
| `value` | Quantité : signée pour `delta` (−3 = sortie de 3), quantité comptée pour `set`. |
| `client_ts` | Date et heure du mouvement sur le téléphone (UTC, ISO 8601). |
| `device_id` | Identifiant de l'appareil. |
| `user` | Identifiant de l'utilisateur, si saisi. |
| `session_id` | Identifiant de la session rafale, le cas échéant. |

S'y ajoutent les champs scannés (les variables d'opération priment en cas de conflit de nom).

Règles :

- **Gabarit réduit à une seule variable** (`"{value}"`) : la valeur garde son type JSON (un nombre reste un nombre). Si la variable est absente (ex. `user` non saisi), la clé est omise.
- **Gabarit composé** (`"Kura {op_id}"`) : le résultat est un texte ; une variable absente est remplacée par un texte vide.

```json
"request_mapping": {
  "article": "{item_id}",
  "quantite": "{value}",
  "mode": "{type}",
  "horodatage": "{client_ts}",
  "id_unique": "{op_id}"
}
```

Quand `request_mapping` est présent :

- toute réponse 2xx vaut acceptation du mouvement ;
- 401/403 arrêtent les envois (secret à vérifier) ;
- 408, 429 et 5xx déclenchent un nouvel essai ;
- les autres 4xx placent le mouvement en erreur ;
- l'envoi groupé n'est utilisé que si `endpoints.send_batch` est aussi défini. Le corps est alors `{ "operations": [ … ] }`, chaque élément suivant `request_mapping`.

Pour une intégration plus riche (réponses détaillées, doublons, conflits), préférez [Kura Bridge](../guides/bridge.md) ou une implémentation du contrat : voir le [guide d'intégration](../guides/integration.md).

## `response_mapping`

Pour lire la réponse de `get_item` quand elle ne suit pas le contrat. Clé = champ Kura ; valeur = chemin dans la réponse, en notation pointée.

```json
"response_mapping": {
  "name": "designation",
  "location": "emplacement",
  "quantity": "stock.disponible"
}
```

Avec la réponse `{ "designation": "Vis M6", "emplacement": "A12", "stock": { "disponible": 250 } }`, la fiche affiche `name = Vis M6`, `location = A12`, `quantity = 250`.

## `display`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `show_fields` | liste de noms de champ | — | Champs affichés sur la fiche, dans l'ordre. |
| `field_labels` | objet champ → libellé | — | Libellés à afficher : `{ "location": "Emplacement" }`. Sans libellé, le nom du champ est affiché. |
| `show_image` | booléen | `false` | Affiche la photo de l'article. |
| `image_url` | gabarit | — | Adresse de l'image si le serveur n'en fournit pas (`image_url` de la fiche) : `"/items/{ref}/image"`. Les chemins relatifs sont résolus sur `base_url`. |
| `image_cache` | booléen | `true` | Garde les images sur le téléphone pour l'affichage hors-ligne. |
| `step_buttons` | 1 à 3 entiers 1–10000 | `[1]` | Pas des boutons + / − de la fiche : `[1, 10]`. |
| `allow_set` | booléen | `true` | Affiche le bouton **Inventaire** (saisie de la quantité comptée, opération `set`). |

## `burst`

Mode rafale : chaque lecture crée immédiatement un mouvement `delta`, sans ouvrir la fiche ([ADR 0004](../adr/0004-mode-rafale-v1.md)).

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `enabled` | booléen | `true` | Rend le mode Rafale disponible. |
| `default_direction` | `in` \| `out` | `out` | Sens proposé à l'ouverture : entrée ou sortie. |
| `default_step` | entier ≥ 1 | `1` | Quantité par lecture. |
| `same_code_cooldown_ms` | entier 0–10000 | `1500` | Délai pendant lequel le même code relu est ignoré. |
| `verify_item` | booléen | `false` | Interroge `get_item` à chaque lecture pour signaler un article inconnu (demande du réseau). |

## `sync`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `mode` | `auto` \| `manual` | `auto` | `auto` : envoi dès que le réseau est disponible. `manual` : seulement sur « Envoyer maintenant ». |
| `batch` | booléen | `true` | Vide la file par lots (`send_batch`). |
| `max_batch_size` | entier 1–500 | `50` | Nombre maximal de mouvements par lot. |
| `retry_backoff_s` | liste d'entiers ≥ 1 | `[5, 30, 120]` | Délais successifs entre deux essais, en secondes ; le dernier est répété. |

## `security`

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `config_locked` | booléen | `false` | Verrouille les réglages de l'appli. |
| `config_password_hash` | texte (Argon2id, format PHC) | — | Empreinte du mot de passe de déverrouillage, par exemple `$argon2id$v=19$m=19456,t=2,p=1$…`. **Jamais de mot de passe en clair.** Kura Studio la calcule. Absente avec `config_locked: true` : le mot de passe sera défini sur l'appareil (avertissement à l'import). |
| `require_user` | booléen | `false` | Demande un identifiant utilisateur (« Qui scanne ? »), envoyé dans le champ `user` de chaque mouvement. |

Garder un coût Argon2id modéré (m = 19 456 Kio, t = 2, valeurs de Kura Studio) : sur un téléphone d'entrée de gamme, la vérification prend alors moins d'une seconde. Les empreintes au-delà de 256 Mio de mémoire sont refusées.

## `remote_config`

> **Réservé.** Ces options sont validées et la vérification de signature existe, mais **Kura Scan 1.0 ne télécharge pas encore de configuration distante**. Elles sont sans effet pour l'instant.

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `enabled` | booléen | `false` | Active la récupération de la config sur le serveur. |
| `url` | texte | `endpoints.get_config` | Adresse de la config distante. |
| `auto_update` | booléen | `false` | Applique automatiquement une nouvelle version. |
| `signing_public_key` | texte | — | Clé publique Ed25519 (SPKI DER en base64) qui doit avoir signé toute config distante. Sans elle, toute config distante est refusée. |

## Erreurs et avertissements à l'import

Kura Scan refuse une configuration (« Configuration refusée ») en cas de :

- clé inconnue, type incorrect, valeur hors limites ;
- `base_url` absente ou ne commençant pas par `http://` ou `https://` ;
- `delimited` ou `json` sans `fields`, ou `id_field` absent de `fields`.

Les messages indiquent le chemin de l'option : `scan.trigger.timeout_ms : valeur 500 hors limites (entre 1000 et 30000).`

Il accepte la configuration avec un avertissement (**Réglages › Avertissements de la configuration**) pour :

- un serveur en HTTP ;
- `basic` sans `username` ;
- une variable inconnue dans une route ou un `request_mapping` ;
- `config_locked` sans empreinte ;
- `remote_config` activée sans clé de signature.

## QR de configuration

Le QR de configuration transporte la configuration (et éventuellement le secret) en une lecture. Kura Studio le génère ; le format complet, avec vecteurs de test, est dans [`spec/config-qr.md`](../../spec/config-qr.md). En résumé :

| Préfixe | Contenu | Secret |
|---|---|---|
| `KURA1:` | `base64url` (sans `=`) du JSON `{ "config": { … } }`, en UTF-8 | **Interdit** : un QR `KURA1:` contenant un secret est refusé. |
| `KURA1E:` | `base64url(sel[16] ‖ iv[12] ‖ AES-256-GCM)` du JSON `{ "config": { … }, "secret": "…" }`. Clé dérivée du **code PIN** (≥ 6 chiffres) par PBKDF2-SHA-256. | Autorisé, chiffré. |

- Le PIN doit être communiqué séparément du QR.
- Le déchiffrement prend 0,5 à 2 s sur un téléphone modeste.
- Au-delà d'environ 1 200 caractères, le QR devient dense et difficile à lire : préférez alors le fichier `kura-config.json`.
