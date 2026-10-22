# Kura : plan et architecture

> **Kura = un scanner de codes QR/barres sur smartphone Android, qui remplace les terminaux de saisie coûteux (type Zebra/Honeywell + licences), et qui se branche sur n'importe quel système de GMAO/stock existant.**

**Ce que Kura est :** un *client de scan* + un *générateur d'étiquettes* + un *contrat d'échange* simple.
**Ce que Kura n'est pas (v1) :** une GMAO, une base de stock, un ERP. Kura ne stocke aucune donnée métier de façon durable : le système de l'entreprise reste la source de vérité.

## 1. Principes directeurs

1. **Le strict nécessaire** : scanner, lire, envoyer, corriger.
2. **Zéro lock-in** : l'entreprise garde son système, Kura s'adapte à lui.
3. **Config, pas code** : tout passe par un fichier de config (et l'éditeur no-code).
4. **Installation en moins de 15 min** avec un tuto clair.
5. **Offline-first** : le terrain n'a pas toujours de réseau.

## 2. Décisions prises

| Sujet | Décision | ADR |
|---|---|---|
| Licence | Apache 2.0 | [0001](adr/0001-licence-apache-2.md) |
| Lecture | ZXing (sans Google), QR **et** 1D (EAN, UPC, Code 128, Code 39, ITF, Data Matrix) | [0002](adr/0002-zxing-qr-et-1d.md) |
| Contenu du code | Identifiant seul par défaut ; données embarquées en option | [0003](adr/0003-qr-id-par-defaut.md) |
| Saisie en série | Mode rafale dès la v1 | [0004](adr/0004-mode-rafale-v1.md) |
| Conflits | `delta` additionnés, `set` : le plus récent gagne | [0005](adr/0005-resolution-conflits.md) |
| Lecture | Bouton « Scanner », zone de visée | [0006](adr/0006-scan-sur-appui.md) |
| Appareils | Android 6+, entrée de gamme | [0007](adr/0007-compatibilite-android.md) |
| Étiquettes | Générées depuis la base de l'entreprise, champs obligatoires / facultatifs | [0008](adr/0008-etiquettes-depuis-la-base.md) |

## 3. Vue d'ensemble

```
┌─────────────────────┐        ┌──────────────────────────┐
│  Kura Studio        │        │  Kura Scan (Android)     │
│  (web statique/PWA) │        │  Kotlin + Compose        │
│                     │        │                          │
│  • Éditeur no-code  │        │  • Scan QR/1D (ZXing)    │
│  • Étiquettes       │        │  • Fiche +1 / −1 / qté   │
│  • Config + QR      │        │  • Mode rafale           │
│  • Test connexion   │        │  • File offline          │
└─────────┬───────────┘        └────────────┬─────────────┘
          │ produit                         │ HTTPS (REST/JSON)
          ▼                                 ▼
   kura-config.json  ───────────►   ┌──────────────────────┐
   (+ QR de config)                 │  Serveur entreprise  │
                                    │  A : API compatible  │
                                    │  B : Kura Bridge     │
                                    └──────────────────────┘
```

| Brique | Rôle | Obligatoire | Emplacement |
|---|---|---|---|
| Kura Scan | Scanner et envoyer | ✅ | `apps/scan-android` |
| Kura Studio | Config + étiquettes | ✅ | `apps/studio-web` |
| Contrat d'API | Spécification de l'échange | ✅ | `spec/` |
| Kura Bridge | Adaptateur pour les systèmes sans API compatible | ⚪ | `apps/bridge` |
| Mock Server | Serveur de démo et de test | outil | `tools/mock-server` |

## 4. Kura Scan (Android)

### 4.1 Stack
- **Kotlin** + **Jetpack Compose**
- **CameraX** + **ZXing core** (analyseur d'images maison, pas de dépendance Google)
- **Room** pour la file offline et le cache des fiches
- **WorkManager** pour la synchro en arrière-plan
- **OkHttp** (+ kotlinx.serialization) pour le réseau
- **Coil** pour les images avec cache disque
- **Android Keystore** (via EncryptedSharedPreferences / DataStore chiffré) pour les secrets
- Scanners **Bluetooth/HID** : capture des frappes clavier terminées par Entrée sur l'écran Scan

### 4.2 Écrans

1. **Scan** : caméra plein écran, lampe, bascule **Fiche / Rafale** et sens **Entrée / Sortie**.
2. **Fiche** (après un scan en mode Fiche) : infos paramétrées, image optionnelle, quantité éditable (`set`), boutons de pas (`display.step_buttons`), **Valider**.
3. **File d'attente** : opérations non envoyées ou en erreur, relancer / supprimer.
4. **Réglages** : serveur, authentification, mise à jour de config (protégé par mot de passe).

Pas de tableau de bord, pas de recherche avancée.

### 4.3 Flux

```
Scan ─► analyse selon scan.format (id par défaut)
     ─► mode Fiche : GET /items/{ref} (ou cache si hors-ligne) ─► fiche ─► +/− / quantité ─► Valider
     ─► mode Rafale : opération delta ±step immédiate + retour sonore/vibration
     ─► opération locale (op_id UUID, client_ts) écrite dans Room
     ─► envoi immédiat si réseau, sinon file ; WorkManager vide la file à la reconnexion
```

Plusieurs appuis sur +1 avant **Valider** produisent **une seule** opération (`delta` = somme), pour limiter le trafic.

### 4.4 Opération

```json
{
  "op_id": "4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11",
  "item_id": "REF-0042",
  "type": "delta",
  "value": -1,
  "client_ts": "2026-10-22T13:55:12Z",
  "device_id": "tablette-atelier-2",
  "user": "jdupont",
  "session_id": "rafale-2026-10-22-1355"
}
```

Règles de résolution : voir [ADR 0005](adr/0005-resolution-conflits.md).

### 4.5 Traitement des réponses

| Statut | Action de l'appli |
|---|---|
| `applied` | retirée de la file, quantité affichée mise à jour |
| `duplicate` | retirée de la file (déjà reçue) |
| `superseded` | retirée de la file, notification discrète « correction plus récente déjà enregistrée » |
| `rejected` | gardée **en erreur** dans la file, motif affiché ; l'utilisateur corrige ou supprime |
| erreur réseau / 5xx | gardée, nouvel essai selon `sync.retry_backoff_s` |

## 5. Kura Studio (web statique)

- 100 % côté navigateur, hébergeable n'importe où ou utilisable en local, **PWA** hors-ligne.
- Stack : **Svelte + Vite**, livré en **un seul fichier HTML** ; `bwip-js` pour tous les codes (rendu vectoriel), `pdf-lib` pour le PDF.
- **Modèles d'étiquettes** : éléments placés librement (code, texte libre avec champs `{colonne}`, cadre, image), dimensions au mm. L'éditeur exporté embarque les modèles et la config pour être partagé entre postes sans les recréer.
- Si un jour une version desktop est nécessaire : emballage **Tauri**, sans code séparé.

Fonctions :

1. **Schéma du code** : format `id` (défaut) / `delimited` / `json`, champs, aperçu en direct.
2. **Étiquettes** : import CSV/Excel, planches A4 (Avery) ou rouleau (ex. 50 × 25 mm), QR ou code-barres 1D, texte, logo. Export PDF à la taille exacte, PNG/SVG en zip. ZPL/TSPL plus tard.
3. **Config** : assistant en 5 étapes (*Serveur → Authentification → Format du code → Affichage → Test*), export `kura-config.json` validé contre [`spec/config.schema.json`](../spec/config.schema.json), **QR de configuration** imprimable. Le jeton éventuel n'est inclus dans ce QR que **chiffré** par un code PIN communiqué à part.
4. **Testeur de connexion** : appelle `GET /items/{ref}` sur une référence d'exemple et affiche la réponse brute et la réponse interprétée via `response_mapping`.

## 6. Configuration

Référence : [`spec/config.schema.json`](../spec/config.schema.json). Exemples : [minimal](../spec/examples/kura-config.minimal.json), [complet](../spec/examples/kura-config.full.json).

Points clés :
- **`request_mapping`** décrit le corps attendu par le serveur quand il ne parle pas le contrat Kura. Variables disponibles : `{op_id}`, `{item_id}`, `{type}`, `{value}`, `{client_ts}`, `{device_id}`, `{user}`, `{session_id}`, plus les champs scannés.
- **`response_mapping`** fait l'inverse pour lire la fiche.
- **`burst`** règle le mode rafale.
- **Sécurité** : mot de passe de verrouillage **hashé** (argon2id), aucun secret dans le JSON exporté, config distante **signée** (Ed25519) et vérifiée avec `remote_config.signing_public_key`.

## 7. Compatibilité « n'importe quelle GMAO » : 3 niveaux

1. **Mapping direct** : la GMAO a une API REST/JSON, `endpoints` + `request_mapping` suffisent. Aucun code serveur.
2. **Kura Bridge** : binaire unique (**Go**) ou conteneur Docker, qui expose le contrat Kura et le traduit vers SQL (PostgreSQL, SQL Server, MySQL), CSV/Excel partagé ou SOAP. Configuration YAML :
   ```yaml
   get_item:    "SELECT nom AS name, emplacement AS location, stock AS quantity FROM articles WHERE ref = :ref"
   apply_delta: "UPDATE articles SET stock = stock + :value WHERE ref = :ref"
   apply_set:   "UPDATE articles SET stock = :value WHERE ref = :ref"
   ```
   Le Bridge tient lui-même le journal des `op_id` (idempotence) et des derniers `set` (conflits) dans une table `kura_operations`.
3. **Script** : le Bridge appelle un script de l'intégrateur (Python, PowerShell…).

Le **Kura Mock Server** permet de tester toute la chaîne en 2 minutes avant de toucher au système réel.

## 8. Contrat d'API

Référence : [`spec/api-contract.yaml`](../spec/api-contract.yaml) (OpenAPI 3.1).

| Endpoint | Rôle |
|---|---|
| `GET /items` | Liste / recherche des articles, pour générer les étiquettes dans Studio (optionnel) |
| `GET /items/{ref}` | Infos d'un article (optionnel si tout est dans le code) |
| `GET /items/{ref}/image` | Image (optionnel) |
| `POST /operations` | Une opération |
| `POST /operations/batch` | Lot d'opérations, résultat par `op_id` : `applied` / `duplicate` / `superseded` / `rejected` |
| `GET /config` | Config signée (optionnel) |

## 9. Sécurité

- HTTPS obligatoire hors réseau local de test (avertissement bloquant à confirmer sinon).
- Bearer token en v1, puis Basic, clé API, OAuth2/OIDC plus tard.
- Secrets dans l'Android Keystore.
- Rôles et droits : gérés par le serveur de l'entreprise ; Kura envoie l'identité (`user`, `device_id`).
- Journal local des opérations (traçabilité).
- **Aucune télémétrie.**

## 10. Distribution et installation

**Objectif : opérationnel en 15 minutes.**

Admin : ouvrir Kura Studio → assistant 5 étapes → télécharger la config + imprimer le QR de config → générer les étiquettes depuis un CSV.
Employé : installer l'appli → scanner le QR de config → prêt.

Canaux : **F-Droid** (possible grâce à ZXing), **Play Store**, **APK** sur GitHub Releases, **MDM / Android Enterprise** (config poussée via *managed configurations*).

## 11. Feuille de route

### 🟢 v0.1 : « ça scanne et ça envoie »
- [x] Contrat d'API (OpenAPI) et JSON Schema de la config
- [x] Mock Server + tests de la règle de conflit
- [ ] Kura Scan : bouton « Scanner » ([ADR 0006](adr/0006-scan-sur-appui.md)), scan QR/1D (ZXing), fiche, +/−/set, envoi HTTP direct
- [ ] Config chargée par fichier et par QR de config
- [x] Kura Studio : éditeur de modèles (QR/1D, textes, cadres, logos), export PDF, partage de l'éditeur avec ses modèles
- [ ] Quickstart

### 🟡 v0.5 : « ça tient en conditions réelles »
- [ ] File offline complète (Room + WorkManager, idempotence, backoff, envoi par lot)
- [ ] **Mode rafale** (exigé pour la v1, livré ici pour être éprouvé avant)
- [ ] Verrouillage de config par mot de passe
- [ ] Cache d'images et de fiches
- [x] Studio : éditeur de config no-code, QR de configuration, import CSV, planches A4 et rouleau
- [ ] Scanners Bluetooth/HID

### 🟠 v1.0 : « adoptable en entreprise »
- [ ] Kura Bridge (SQL + CSV)
- [ ] Config distante signée
- [ ] Guides complets + recettes de mapping
- [ ] F-Droid + Play Store
- [ ] Tests sur plusieurs vraies GMAO

### 🔵 Plus tard
NFC, impression ZPL/TSPL directe, mini-GMAO Kura optionnelle, iOS (si demande).

## 12. Hors périmètre

- Base de données propre dans Kura Scan au-delà de la file offline et du cache
- Gestion d'utilisateurs/rôles
- Tableaux de bord et rapports
- Multi-entreprises dans une même appli
- Application desktop séparée du web
- Catalogue produit : le serveur de l'entreprise s'en charge
