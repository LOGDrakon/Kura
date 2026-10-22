# Kura Bridge

Kura Bridge branche Kura Scan et Kura Studio sur la base de stock de l'entreprise (GMAO, ERP, base maison) **sans modifier ce système** : il expose le [contrat d'API Kura](../../spec/api-contract.yaml) et le traduit en requêtes SQL, ou en appels à un script.

```
 Kura Scan (téléphones) ─┐                       ┌─ PostgreSQL, MySQL/MariaDB,
                         ├── HTTPS ─ Kura Bridge ─┤   SQL Server, SQLite
 Kura Studio (navigateur)┘   contrat Kura        └─ ou un script (API REST, CSV, SOAP…)
```

Le Bridge est un **programme unique** (Linux, Windows, macOS), sans dépendance à installer. Il se règle par un fichier `kura-bridge.yaml` : vous y écrivez les quelques requêtes SQL qui lisent un article et modifient son stock, avec les noms de **vos** tables et colonnes.

Ce qu'il prend en charge pour vous :

- **les doublons** : une opération renvoyée par un téléphone (réseau coupé au mauvais moment) n'est jamais comptée deux fois ;
- **les inventaires** : un inventaire fait hors-ligne et envoyé tard ne doit pas écraser un inventaire plus récent, ni effacer les sorties faites après lui. Le Bridge applique la règle de Kura ([ADR 0005](../adr/0005-resolution-conflits.md)) alors que votre base ne sait faire que « stock + x » et « stock = x » ;
- **la traçabilité** : chaque opération (qui, quand, quel appareil) est enregistrée dans un journal ;
- **la sécurité** : jetons d'accès, HTTPS, CORS pour Kura Studio.

Sommaire :

1. [Installer](#1-installer)
2. [Premier essai en 5 minutes](#2-premier-essai-en-5-minutes)
3. [Brancher votre base](#3-brancher-votre-base)
4. [Écrire les requêtes](#4-écrire-les-requêtes)
5. [Le journal des opérations](#5-le-journal-des-opérations)
6. [Mode script](#6-mode-script)
7. [Relier Kura Scan et Kura Studio](#7-relier-kura-scan-et-kura-studio)
8. [Sécurité](#8-sécurité)
9. [Exploitation](#9-exploitation)
10. [Référence de `kura-bridge.yaml`](#10-référence-de-kura-bridgeyaml)
11. [Dépannage](#11-dépannage)

---

## 1. Installer

Le Bridge s'installe sur un serveur qui voit la base de données, et que les téléphones peuvent joindre (réseau de l'entreprise, Wi-Fi, VPN). Une petite machine virtuelle suffit : il consomme quelques dizaines de Mo de mémoire.

### Binaire

Téléchargez l'archive de votre système dans les [versions publiées](https://github.com/LOGDrakon/Kura/releases) :

| Système | Fichier |
|---|---|
| Linux (x86-64) | `kura-bridge_<version>_linux_amd64.tar.gz` |
| Linux (ARM 64 bits, Raspberry Pi 4/5) | `kura-bridge_<version>_linux_arm64.tar.gz` |
| Windows | `kura-bridge_<version>_windows_amd64.zip` |
| macOS (Apple Silicon) | `kura-bridge_<version>_darwin_arm64.tar.gz` |

Chaque archive contient le programme, les exemples de configuration et cette documentation. La somme de contrôle de chaque fichier est publiée dans `SHA256SUMS`.

```sh
tar xzf kura-bridge_1.0.0_linux_amd64.tar.gz
sudo install kura-bridge /usr/local/bin/
kura-bridge version
```

### Service Linux (systemd)

```sh
sudo useradd --system --home /etc/kura --shell /usr/sbin/nologin kura
sudo mkdir -p /etc/kura && sudo cp kura-bridge.yaml /etc/kura/
sudo chown -R kura: /etc/kura && sudo chmod 600 /etc/kura/kura-bridge.yaml
```

`/etc/systemd/system/kura-bridge.service` :

```ini
[Unit]
Description=Kura Bridge
After=network-online.target
Wants=network-online.target

[Service]
User=kura
WorkingDirectory=/etc/kura
ExecStart=/usr/local/bin/kura-bridge run -c /etc/kura/kura-bridge.yaml
# Secrets hors du fichier de config : KURA_TOKEN=…, KURA_DB_PASSWORD=…
EnvironmentFile=/etc/kura/secrets.env
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/etc/kura

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now kura-bridge
journalctl -u kura-bridge -f        # journal du service
```

### Service Windows

Le programme sait fonctionner comme service Windows. Décompressez l'archive dans `C:\Kura`, préparez `C:\Kura\kura-bridge.yaml`, puis dans une invite de commandes **administrateur** :

```bat
sc.exe create KuraBridge binPath= "C:\Kura\kura-bridge.exe -c C:\Kura\kura-bridge.yaml" start= delayed-auto DisplayName= "Kura Bridge"
sc.exe failure KuraBridge reset= 86400 actions= restart/5000
sc.exe start KuraBridge
```

Les variables `${…}` du fichier de config se règlent alors comme variables d'environnement **système** (Paramètres › Système › Informations système › Paramètres système avancés › Variables d'environnement), suivies d'un redémarrage du service. Pensez à ouvrir le port dans le pare-feu :

```bat
netsh advfirewall firewall add rule name="Kura Bridge" dir=in action=allow protocol=TCP localport=8787
```

Le journal du service s'affiche avec `kura-bridge.exe run -c …` lancé à la main dans une console (arrêtez le service d'abord).

### Docker

L'image se construit depuis le dossier [`apps/bridge`](../../apps/bridge) :

```sh
docker build -t kura-bridge apps/bridge
docker run -d --name kura-bridge --restart unless-stopped \
  -p 8787:8787 \
  -v /etc/kura:/etc/kura \
  -e KURA_TOKEN -e KURA_DB_PASSWORD \
  kura-bridge
```

L'image lit `/etc/kura/kura-bridge.yaml`. Depuis un conteneur, la base de l'hôte ne s'appelle pas `localhost` : utilisez son nom ou son adresse IP. L'image ne contient que le Bridge : pour le [mode script](#6-mode-script), construisez une image à partir de celle de votre interpréteur (`python:3-slim`…) et copiez-y le binaire.

### Depuis les sources

Go 1.26 ou plus récent :

```sh
cd apps/bridge
CGO_ENABLED=0 go build -ldflags "-s -w -X main.version=1.0.0" -o kura-bridge ./cmd/kura-bridge
```

---

## 2. Premier essai en 5 minutes

L'exemple [`examples/sqlite`](../../apps/bridge/examples/sqlite) contient une petite base d'articles et une config complète, sans authentification.

```sh
cd apps/bridge/examples/sqlite
sqlite3 gmao.db < schema.sql          # ou : python3 -c "import sqlite3; sqlite3.connect('gmao.db').executescript(open('schema.sql').read())"
kura-bridge check                     # vérifie la config, la base et les requêtes
kura-bridge                           # démarre sur le port 8787
```

`kura-bridge check` affiche :

```
✓ config kura-bridge.yaml lue
✓ connexion à la base sqlite
✓ requêtes SQL préparées
✓ journal : table kura_operations
✓ list_items : 3 article(s) lus (3 au plus)
✓ get_item "3760123456789" :
  { "location": "B-01-4", "name": "Cartouche filtrante", "quantity": 5, "ref": "3760123456789", … }
✓ stock lu dans la colonne "quantity"
```

Dans un autre terminal :

```sh
curl http://localhost:8787/items/REF-0042
curl -X POST http://localhost:8787/operations -H 'Content-Type: application/json' -d '{
  "op_id": "6b1f8a52-3c1e-4c2a-9b1e-2f0e4d5a7c11", "item_id": "REF-0042",
  "type": "delta", "value": -1, "client_ts": "2026-10-22T19:00:00+02:00", "user": "Marie" }'
```

```json
{"op_id":"6b1f8a52-3c1e-4c2a-9b1e-2f0e4d5a7c11","status":"applied","quantity":11,"server_received_at":"2026-10-22T17:00:01.412Z"}
```

Relancez la même commande : la réponse devient `"status":"duplicate"` et le stock reste à 11.

Pour essayer avec le téléphone, suivez le [démarrage rapide](../quickstart.md) en remplaçant l'adresse du serveur de démonstration par celle du Bridge.

---

## 3. Brancher votre base

1. **Partez de l'exemple de votre moteur** dans [`apps/bridge/examples`](../../apps/bridge/examples) : `postgres`, `mysql` (MySQL et MariaDB), `sqlserver`, `sqlite`, ou `script`. Les [recettes](../recipes/README.md) donnent les détails propres à chaque moteur (droits, chaîne de connexion, pièges).
2. **Créez un utilisateur de base dédié** au Bridge, avec le moins de droits possible : lecture des articles, mise à jour de la seule colonne du stock, et création de la table du journal (ou fournissez-la, voir [§ 5](#5-le-journal-des-opérations)).
3. **Adaptez les requêtes** (`queries`) à vos tables : voir [§ 4](#4-écrire-les-requêtes).
4. **Générez un jeton d'accès** et placez les secrets hors du fichier :
   ```sh
   openssl rand -hex 24        # ou : python3 -c "import secrets; print(secrets.token_hex(24))"
   ```
   ```yaml
   auth:
     type: bearer
     tokens: ["${KURA_TOKEN}"]
   database:
     dsn: "postgres://kura:${KURA_DB_PASSWORD}@srv-gmao:5432/gmao"
   ```
   Les `${NOM}` sont remplacés au démarrage par les variables d'environnement ; une variable absente arrête le démarrage avec un message clair.
5. **Vérifiez** avec un article réel :
   ```sh
   kura-bridge check -c kura-bridge.yaml -ref REF-0042
   ```
   `check` ne modifie aucun stock (il crée seulement la table du journal si elle n'existe pas).
6. **Testez un mouvement** sur un article de test avec `curl` (voir [§ 2](#2-premier-essai-en-5-minutes)), puis vérifiez le stock dans votre logiciel.
7. **Installez le service** ([§ 1](#1-installer)), **activez HTTPS** ([§ 8](#8-sécurité)), puis [reliez les téléphones et Studio](#7-relier-kura-scan-et-kura-studio).

---

## 4. Écrire les requêtes

Les requêtes sont du SQL ordinaire, dans le dialecte de votre base, avec des **paramètres nommés** `:nom`. Le Bridge les convertit dans le format du pilote (`$1`, `?`, `@p1`) et les passe toujours **séparément de la requête** : aucune valeur venue d'un téléphone n'est jamais collée dans le texte SQL.

Un nom de paramètre inconnu est refusé dès le démarrage (`queries.get_item : paramètre :reference inconnu…`). Les `:` à l'intérieur des chaînes `'…'`, des commentaires et des conversions PostgreSQL `::type` ne sont pas des paramètres.

### `get_item` (obligatoire)

Lit **un** article. Paramètre : `:ref` (la référence scannée).

```sql
SELECT code AS ref, designation AS name, emplacement AS location,
       stock AS quantity, unite AS unit, modifie_le AS updated_at
FROM articles
WHERE code = :ref
```

- **Chaque colonne du résultat devient un champ** de l'article. Les noms `ref`, `name`, `location`, `quantity`, `unit`, `image_url`, `updated_at` sont ceux du [contrat](../../spec/api-contract.yaml) et sont compris partout. Toute autre colonne (`service`, `marque`, `fournisseur`…) est transmise telle quelle : on peut l'afficher sur la fiche de Kura Scan (`display.show_fields`), l'imprimer sur une étiquette (`{service}` dans Kura Studio) ou s'en servir pour trier les articles dans Studio.
- **Le stock doit figurer dans le résultat** : par défaut dans une colonne `quantity` (sinon, réglez `items.quantity_column`). Le Bridge s'en sert pour renvoyer le stock après chaque opération.
- Aucune ligne : l'article est inconnu (404 pour la fiche, `unknown_item` pour une opération). Plusieurs lignes : seule la première compte.
- Les valeurs `NULL` sont omises, les dates sont converties en ISO 8601 (UTC), les décimaux entiers en nombres.

### `apply_delta` et `apply_set` (obligatoires)

Modifient le stock. Le Bridge a déjà vérifié que l'article existe, que l'opération n'est pas un doublon, et calculé la valeur à écrire.

```sql
-- Mouvement (entrée ou sortie) : :value est signé, -3 pour une sortie de 3.
UPDATE articles SET stock = stock + :value WHERE code = :ref

-- Inventaire : :value est la quantité à écrire.
UPDATE articles SET stock = :value WHERE code = :ref
```

Paramètres disponibles dans `apply_delta`, `apply_set` et `after_apply` :

| Paramètre | Contenu |
|---|---|
| `:ref` | Référence de l'article. |
| `:value` | Valeur à appliquer : l'écart pour `apply_delta` ; pour `apply_set`, la quantité comptée **corrigée** des mouvements faits après l'inventaire et arrivés avant lui (voir [§ 5](#5-le-journal-des-opérations)). |
| `:sent_value` | Valeur envoyée par le téléphone, sans correction (écart signé ou quantité comptée). Utile pour un historique. |
| `:type` | `delta` ou `set` : ce que fait la requête exécutée. |
| `:op_id` | Identifiant unique de l'opération (UUID). |
| `:client_ts` | Date et heure de l'opération sur le téléphone (date/heure, avec fuseau). |
| `:client_ts_iso` | La même, en texte ISO 8601 UTC (`2026-10-22T13:55:12.345Z`), pour une colonne texte. |
| `:user` | Identifiant de l'utilisateur saisi dans l'appli (`NULL` s'il n'y en a pas). |
| `:device_id` | Identifiant de l'appareil (`NULL` si absent). |
| `:session_id` | Session du mode rafale (`NULL` en mode fiche). |
| `:received_at` | Date et heure de réception par le Bridge. |

La requête doit modifier **une ligne**. Si elle n'en modifie aucune, l'article est considéré comme inconnu et rien n'est enregistré.

Vous pouvez y mettre la logique de votre système : mettre à jour une date de modification, interdire un stock négatif (`… WHERE code = :ref AND stock + :value >= 0` : l'opération est alors refusée comme `unknown_item`, ou préférez un [script](#6-mode-script) pour un refus explicite), écrire dans une autre table…

### `after_apply` (facultative)

Exécutée **dans la même transaction**, juste après `apply_delta` ou `apply_set`. Typiquement : tracer le mouvement dans l'historique de la GMAO.

```sql
INSERT INTO mouvements (code, sens, quantite, utilisateur, appareil, date_mouvement, op_id)
VALUES (:ref, :type, :sent_value, :user, :device_id, :client_ts, :op_id)
```

Si elle échoue, toute l'opération est annulée et le téléphone réessaiera plus tard. Elle n'est exécutée que lorsque le stock change réellement : un inventaire dépassé (`superseded`) ou un mouvement déjà compté par un inventaire plus récent n'appellent ni `apply_*` ni `after_apply`, mais restent visibles dans le journal du Bridge.

### `list_items` (facultative, pour Kura Studio)

Liste les articles, pour imprimer leurs étiquettes depuis Kura Studio. Sans elle, Studio peut toujours imprimer à partir d'un fichier CSV.

```sql
SELECT code AS ref, designation AS name, emplacement AS location, service,
       stock AS quantity, modifie_le AS updated_at
FROM articles
WHERE (:q = '' OR lower(code) LIKE :q_like OR lower(designation) LIKE :q_like)
  AND modifie_le >= :updated_since
ORDER BY modifie_le DESC, code
LIMIT :limit OFFSET :offset
```

| Paramètre | Contenu |
|---|---|
| `:q` | Texte recherché (vide : tous les articles). |
| `:q_like` | Le même, en minuscules, entre `%` : `%gants%`. |
| `:updated_since` | Date/heure : articles créés ou modifiés depuis (1970 si non demandé). |
| `:updated_since_iso` | La même en texte ISO 8601, pour une colonne texte (SQLite). |
| `:limit` | Nombre de lignes à renvoyer. Le Bridge demande une ligne de plus que la page pour savoir s'il en reste. |
| `:offset` | Nombre de lignes à sauter (pagination). |

Renvoyez les mêmes colonnes que `get_item`, triées du plus récent au plus ancien, avec un ordre **stable** (ajoutez la référence au `ORDER BY`). SQL Server : `ORDER BY … OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY`.

### Images (facultatif)

Deux possibilités :

- les photos sont déjà accessibles par une adresse web : `image.url: "https://intranet.exemple.fr/photos/{ref}.jpg"`. Le Bridge redirige vers cette adresse ;
- elles sont dans la base : `queries.get_image` renvoie une ligne avec une colonne binaire (`SELECT photo FROM articles WHERE code = :ref`). Le type (JPEG, PNG, WebP…) est détecté automatiquement.

Dans les deux cas, le Bridge ajoute `image_url` aux articles et sert `GET /items/{ref}/image`. Activez ensuite `display.show_image` dans la config de Kura Scan.

### Article identifié par une autre colonne

Si votre requête ne renvoie pas de colonne `ref`, indiquez laquelle identifie l'article : `items.id_column: code`. Le Bridge ajoute alors `ref` à partir de cette colonne.

---

## 5. Le journal des opérations

Le Bridge enregistre chaque opération acceptée dans une table **`kura_operations`**, créée au premier démarrage :

| Colonne | Contenu |
|---|---|
| `op_id` | Identifiant unique (clé primaire) : c'est lui qui empêche les doublons. |
| `item_id` | Référence de l'article. |
| `op_type`, `op_value` | `delta` ou `set`, valeur envoyée par le téléphone. |
| `client_ts`, `client_ts_ms` | Date de l'opération sur le téléphone (texte d'origine et millisecondes). |
| `user_name`, `device_id`, `session_id` | Qui, avec quel appareil, dans quelle session rafale. |
| `status` | `applied` ou `superseded`. |
| `received_at` | Réception par le Bridge. |

C'est à la fois un **historique** consultable (qui a sorti quoi, quand) et la mémoire qui permet au Bridge d'appliquer la règle des inventaires :

- un **mouvement** s'additionne au stock, sauf s'il a été fait **avant** le dernier inventaire connu de l'article : cet inventaire l'a déjà compté. Il est alors journalisé sans modifier le stock ;
- un **inventaire** plus ancien qu'un inventaire déjà reçu est `superseded` : journalisé, sans effet ;
- un **inventaire** plus récent écrit la quantité comptée **plus** les mouvements faits après lui mais arrivés avant (téléphone resté hors-ligne).

Ainsi, quel que soit l'ordre dans lequel les téléphones se reconnectent, le stock final est le même (les tests du Bridge vérifient les 120 ordres d'arrivée possibles d'un même scénario).

**Où se trouve le journal ?**

- **Par défaut, dans votre base**, à côté des articles. La mise à jour du stock et l'écriture du journal se font alors dans **la même transaction** : soit les deux réussissent, soit aucune. C'est le réglage recommandé. Le nom de la table se change avec `journal.table` ; avec `journal.create: false`, le Bridge ne la crée pas (créez-la vous-même : la requête exacte pour votre moteur est dans [`internal/journal/store.go`](../../apps/bridge/internal/journal/store.go)).
- **Dans un fichier SQLite à part** (`journal.file: kura-journal.db`), si vous ne pouvez pas ajouter de table à la base. C'est aussi le cas du mode script. Les deux écritures ne sont alors plus atomiques : dans le cas rarissime où le Bridge s'arrête entre la mise à jour du stock et celle du journal, un renvoi de l'opération serait compté une seconde fois. Le Bridge le signale dans son journal d'erreurs. **Sauvegardez ce fichier** avec le reste.

Le journal ne remplace pas votre logiciel : les opérations antérieures à la mise en service du Bridge, ou faites dans la GMAO elle-même, n'y figurent pas. Ce n'est pas un problème : le Bridge ne recalcule jamais le stock depuis le journal, il le lit dans votre base.

**Un seul Bridge par base.** Les opérations sont traitées une par une dans le Bridge ; deux Bridge branchés sur la même base pourraient se croiser. Pour la haute disponibilité, préférez un redémarrage automatique (systemd, service Windows, Docker) à plusieurs instances.

---

## 6. Mode script

Quand le stock n'est pas dans une base SQL accessible (API REST d'un ERP, service SOAP, fichier CSV ou Excel, logiciel ancien), le Bridge peut appeler **votre programme** : Python, PowerShell, PHP, Node.js, un exécutable… Le Bridge garde la sécurité, le journal, les doublons et la règle des inventaires ; le script n'a que trois choses à faire : lire un article, ajouter une quantité, écrire une quantité.

```yaml
script:
  command: ["python3", "adaptateur.py"]     # Windows : ["py", "adaptateur.py"] ou ["powershell", "-File", "adaptateur.ps1"]
  env:
    ERP_URL: https://erp.exemple.fr/api
    ERP_TOKEN: ${ERP_TOKEN}
  timeout_s: 15
journal:
  file: kura-journal.db
```

Le Bridge lance la commande **à chaque demande**, depuis le dossier du fichier de config. Il écrit la requête en JSON sur l'**entrée standard** ; le script répond en JSON sur la **sortie standard**, puis se termine. Ce qu'il écrit sur la sortie d'erreur apparaît dans les messages d'erreur du Bridge.

| Requête (`action`) | Champs reçus | Réponse attendue |
|---|---|---|
| `get_item` | `ref` | `{"item": {"ref": "…", "name": "…", "quantity": 12, …}}` ou `{"item": null}` si inconnu |
| `list_items` | `q`, `limit`, `cursor` (texte, vide pour la première page), `updated_since` (si demandé) | `{"items": [ … ], "next_cursor": "…"}` (sans `next_cursor` sur la dernière page), ou `{"error": "not_supported"}` |
| `apply_delta` | `ref`, `value` (écart signé), et les champs ci-dessous | `{"ok": true, "quantity": 9}` |
| `apply_set` | `ref`, `value` (quantité à écrire), et les champs ci-dessous | `{"ok": true, "quantity": 20}` |

Champs supplémentaires de `apply_delta` et `apply_set` : `sent_value`, `type`, `op_id`, `client_ts`, `user`, `device_id`, `session_id`, `received_at` (mêmes sens qu'au [§ 4](#apply_delta-et-apply_set-obligatoires) ; les dates sont en texte ISO 8601).

Exemple d'échange :

```sh
$ echo '{"action":"apply_delta","ref":"REF-0042","value":-2,"op_id":"…","user":"Marie",…}' | python3 adaptateur.py
{"ok": true, "quantity": 10}
```

**Refuser une opération.** Répondez `{"ok": false, "reason": "…", "message": "…"}` :

| `reason` | Effet dans Kura Scan |
|---|---|
| `unknown_item` | « Article inconnu » ; rien n'est journalisé, l'opération pourra être renvoyée si l'article est créé ensuite. |
| `forbidden` | Refus métier (droits, article bloqué, stock négatif interdit…). L'opération reste **en erreur** sur le téléphone avec votre `message` ; l'utilisateur décide. |
| `invalid`, `conflict` | Idem, avec ce motif. |

**Panne.** Si le script se termine avec un code d'erreur, dépasse `timeout_s` ou répond un JSON illisible, le Bridge répond `503` : le téléphone garde l'opération et la renverra plus tard (rien n'est journalisé). C'est le bon comportement quand l'ERP est momentanément injoignable : **sortez en erreur** plutôt que de répondre `ok: false`.

L'exemple complet [`examples/script/adaptateur.py`](../../apps/bridge/examples/script/adaptateur.py) gère un stock dans un fichier CSV, en une centaine de lignes. Pour une API REST, remplacez les fonctions `lire()` et `ecrire()` par des appels HTTP : voir la [recette](../recipes/rest-api.md).

---

## 7. Relier Kura Scan et Kura Studio

**Kura Scan.** Dans Kura Studio, onglet **Appli mobile** :

- **Adresse de l'API de votre base** : l'adresse du Bridge, par exemple `https://stock.exemple.fr` ou `http://192.168.1.20:8787` pour un essai ;
- **Authentification** : *Jeton Bearer*. Le jeton se saisit sur chaque téléphone (**Réglages › Authentification**), ou s'inclut dans un QR de configuration protégé par PIN ([guide Kura Scan](scan.md)).

Aucune autre option n'est nécessaire : le Bridge parle le contrat Kura, ni `endpoints` ni `request_mapping`. Le Bridge accepte le jeton dans `Authorization: Bearer …` ou dans l'en-tête `X-Api-Key` (authentification *Clé d'API (en-tête)*, avec le nom d'en-tête par défaut).

Pour que la fiche affiche vos colonnes supplémentaires, listez-les dans **Informations affichées** (`display.show_fields`).

**Kura Studio** appelle le Bridge depuis le navigateur (`GET /items`) pour lister les articles et imprimer leurs étiquettes. Il faut :

- une requête `list_items` ;
- que l'adresse de Studio soit autorisée dans `cors_origins` : `"*"` (par défaut) convient pour un Bridge protégé par jeton ; sinon, indiquez l'origine exacte (`https://kura.exemple.fr`), ou `"null"` pour Studio ouvert depuis un fichier sur le poste ;
- qu'un Studio servi en `https://` appelle un Bridge en `https://` (le navigateur bloque l'inverse).

Le Bridge répond aussi aux vérifications « Private Network Access » de Chrome, qui permettent à une page web d'appeler un serveur du réseau local.

---

## 8. Sécurité

**Jetons.** `auth.tokens` accepte plusieurs jetons (16 caractères au minimum, 48 caractères aléatoires recommandés). Donnez-en un par équipe ou par site ; pour en révoquer un, retirez-le et redémarrez. `auth.type: none` n'est à utiliser que pour un essai : le Bridge l'indique au démarrage.

**HTTPS.** Hors d'un réseau de test, les téléphones doivent joindre le Bridge en `https://` (Kura Scan demande une confirmation pour toute adresse `http://`). Deux façons de faire :

- **certificat sur le Bridge** :
  ```yaml
  tls:
    cert: /etc/kura/certificat.pem      # chaîne complète
    key: /etc/kura/cle.pem
  ```
  Le certificat doit être reconnu par les téléphones (autorité publique comme Let's Encrypt, ou autorité interne installée sur les appareils) ;
- **derrière un proxy** existant (nginx, Caddy, Traefik, IIS) qui gère le certificat et transmet au Bridge en local. Exemple Caddy, certificat automatique :
  ```
  stock.exemple.fr {
      reverse_proxy 127.0.0.1:8787
  }
  ```
  Avec `listen: "127.0.0.1:8787"`, le Bridge n'est joignable que par le proxy.

**Base de données.** Utilisateur dédié, droits limités au strict nécessaire (exemples dans les [recettes](../recipes/README.md)). Les requêtes sont toujours paramétrées.

**Réseau.** N'exposez pas le Bridge sur Internet sans nécessité : réseau interne, Wi-Fi d'entreprise ou VPN suffisent souvent.

**Secrets.** Gardez les mots de passe et jetons dans des variables d'environnement (`${…}`) et protégez le fichier de config (`chmod 600`). Le Bridge n'écrit jamais les jetons dans ses journaux.

**Config distante signée.** `GET /config` sert un fichier `kura-config.json` signé par une clé Ed25519 :

```sh
kura-bridge keygen -o /etc/kura/kura-signing.key    # affiche la clé publique à placer dans kura-config.json
```

```yaml
remote_config:
  file: kura-config.json
  signing_key_file: kura-signing.key
```

> Le Bridge est prêt, mais **Kura Scan 1.0 ne télécharge pas encore de configuration distante** (voir [`remote_config`](../reference/config.md#remote_config)). Diffusez la config par QR en attendant.

---

## 9. Exploitation

**Journal d'activité.** Une ligne par opération, sur la sortie d'erreur (récupérée par systemd, Docker ou la console) :

```
level=INFO msg=opération op_id=6b1f8a52-… item=REF-0042 type=delta value=-1 status=applied quantity=11 user=Marie
```

`log: debug` ajoute chaque requête HTTP et sa durée.

**Supervision.** `GET /health` (sans jeton) répond `200 {"status":"ok"}`, ou `503 {"status":"degraded"}` quand la base ne répond pas.

**Base indisponible.** Le Bridge répond `503` : les téléphones gardent les opérations dans leur file d'attente et les renvoient automatiquement. Aucune perte, aucun doublon.

**Mise à jour.** Remplacez le binaire et redémarrez le service. Les téléphones qui envoient pendant le redémarrage réessaieront.

**Sauvegarde.** Le journal suit les sauvegardes de votre base. Avec `journal.file`, ajoutez ce fichier aux sauvegardes.

**Purge.** Le journal grossit d'environ 200 octets par opération (soit 20 Mo pour 100 000 opérations). Vous pouvez supprimer les lignes anciennes, en gardant au moins quelques mois : un téléphone resté longtemps hors-ligne doit pouvoir être reconnu.

```sql
DELETE FROM kura_operations WHERE received_at < '2026-01-01';
```

---

## 10. Référence de `kura-bridge.yaml`

Les chemins relatifs (fichiers, base SQLite, script) se rapportent au dossier du fichier de config. `${NOM}` est remplacé par la variable d'environnement `NOM`. Une clé inconnue est une erreur (faute de frappe repérée au démarrage).

| Clé | Défaut | Description |
|---|---|---|
| `listen` | `:8787` | Adresse et port d'écoute. `127.0.0.1:8787` : local uniquement (derrière un proxy). |
| `tls.cert`, `tls.key` | — | Certificat et clé PEM : active HTTPS. |
| `auth.type` | `bearer` | `bearer` ou `none` (essai uniquement). |
| `auth.tokens` | — | Jetons acceptés (au moins un avec `bearer`, 16 caractères minimum). |
| `cors_origins` | `["*"]` | Origines web autorisées (Kura Studio). |
| `log` | `info` | `info` ou `debug`. |
| `database.driver` | — | `postgres`, `mysql` (MySQL et MariaDB), `sqlserver` ou `sqlite`. |
| `database.dsn` | — | Chaîne de connexion (format du pilote, voir les [recettes](../recipes/README.md)). |
| `database.max_open_conns` | illimité | Connexions simultanées au maximum. |
| `database.timeout_s` | `10` | Délai maximal d'une requête de lecture. |
| `script.command` | — | Commande et arguments du [mode script](#6-mode-script). `database` et `script` sont exclusifs. |
| `script.env` | — | Variables d'environnement ajoutées pour le script. |
| `script.timeout_s` | `15` | Délai maximal d'un appel au script. |
| `queries.get_item` | **obligatoire** (SQL) | Lecture d'un article. |
| `queries.apply_delta` | **obligatoire** (SQL) | Mouvement. |
| `queries.apply_set` | **obligatoire** (SQL) | Inventaire. |
| `queries.after_apply` | — | Requête complémentaire dans la même transaction. |
| `queries.list_items` | — | Liste pour Kura Studio. |
| `queries.get_image` | — | Image stockée dans la base. |
| `items.id_column` | `ref` | Colonne qui identifie l'article, si le résultat n'a pas de colonne `ref`. |
| `items.quantity_column` | `quantity` | Colonne du stock dans le résultat de `get_item`. |
| `image.url` | — | Adresse des images, avec `{ref}`. |
| `journal.table` | `kura_operations` | Table du journal dans la base. |
| `journal.file` | — (mode script : `kura-journal.db`) | Journal dans un fichier SQLite à part. |
| `journal.create` | `true` | Crée la table du journal si elle n'existe pas. |
| `remote_config.file` | — | `kura-config.json` servi par `GET /config`. |
| `remote_config.signing_key_file` | — | Clé privée Ed25519 (PEM) qui signe la config. |

Variable d'environnement `KURA_BRIDGE_CONFIG` : chemin du fichier de config si `-c` n'est pas donné (défaut : `kura-bridge.yaml` dans le dossier courant).

Commandes :

| Commande | Rôle |
|---|---|
| `kura-bridge [run] [-c fichier]` | Démarre le serveur. Ctrl+C ou l'arrêt du service le termine proprement. |
| `kura-bridge check [-c fichier] [-ref REF]` | Vérifie la config, la connexion, les requêtes et le journal, lit quelques articles. Ne modifie aucun stock. |
| `kura-bridge keygen [-o fichier]` | Crée une clé de signature pour la config distante. |
| `kura-bridge version` | Affiche la version. |

---

## 11. Dépannage

| Symptôme | Cause probable et solution |
|---|---|
| `variables d'environnement absentes : KURA_TOKEN` | La variable n'est pas définie pour le service (systemd : `EnvironmentFile` ; Windows : variable **système** puis redémarrage du service). |
| `field … not found in type config.Config` | Clé inconnue ou mal indentée dans le YAML (les espaces comptent, pas de tabulations). |
| `queries.get_item : paramètre :reference inconnu` | Nom de paramètre non prévu : voir la liste du [§ 4](#4-écrire-les-requêtes). |
| `connexion à la base : …` | DSN, pare-feu ou droits. Testez la connexion avec le client de la base depuis la même machine, avec le même utilisateur. |
| Tous les articles « inconnus » | `get_item` ne renvoie rien : vérifiez la colonne de la clause `WHERE` et le format des références (espaces, zéros en tête, majuscules). `kura-bridge check -ref …` montre ce que lit le Bridge. |
| `get_item doit renvoyer le stock dans la colonne "quantity"` | Ajoutez `stock AS quantity` à la requête, ou réglez `items.quantity_column`. |
| Mouvement accepté mais stock inchangé | Il était antérieur au dernier inventaire de l'article : c'est voulu (l'inventaire l'a compté). Vérifiez dans `kura_operations`. |
| Le téléphone affiche « Erreur d'authentification » | Jeton absent ou différent sur le téléphone ; espaces copiés en trop. |
| Kura Studio : « échec de connexion » alors que l'adresse s'ouvre dans le navigateur | CORS : ajoutez l'origine de Studio à `cors_origins` ; ou page `https://` qui appelle un Bridge `http://`. |
| `503` sur toutes les opérations | Base ou script indisponible : le message d'erreur et le journal du Bridge donnent la cause. Les téléphones réessaieront seuls. |
| MySQL : article « inconnu » lors d'un inventaire qui ne change rien | Ne retirez pas `clientFoundRows` du DSN (le Bridge l'ajoute automatiquement). |
| SQLite : `database is locked` | Un autre programme écrit dans le fichier. SQLite convient aux essais et aux petites installations ; au-delà, passez sur un serveur de base. |

Pour aller plus loin : le [guide d'intégration](integration.md) décrit le contrat d'API en détail (requêtes, réponses, codes d'erreur), pour qui préfère l'implémenter directement dans son propre système.
