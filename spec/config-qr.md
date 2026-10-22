# QR de configuration Kura (format `KURA1`)

Le QR de configuration permet d'installer Kura Scan en une lecture : l'employé scanne le QR imprimé par Kura Studio, l'appli charge la config (et, si prévu, le secret d'authentification).

- **Producteur** : Kura Studio (`apps/studio-web`).
- **Consommateur** : Kura Scan (`apps/scan-android`, `dev.kura.core.config.ConfigQr`).
- Version du format : `1` (préfixes `KURA1:` et `KURA1E:`). Toute évolution incompatible change de préfixe (`KURA2…`).

## 1. Charge utile (commune aux deux variantes)

Un objet JSON, encodé en **UTF-8** :

```json
{ "config": { …contenu de kura-config.json… }, "secret": "jeton-ou-mot-de-passe" }
```

| Clé | Type | Obligatoire | Description |
|---|---|---|---|
| `config` | objet | oui | La config complète, valide contre [`config.schema.json`](config.schema.json). C'est un **objet JSON**, pas une chaîne. |
| `secret` | chaîne non vide | non | Secret de `server.auth` : jeton (`bearer`), mot de passe (`basic`) ou clé (`api_key`). Stocké par l'appli dans l'Android Keystore. Absent, `null` ou `""` = pas de secret. |

Toute autre clé est ignorée par l'appli (réservée aux évolutions).

Recommandations pour le producteur (pour garder un QR lisible par les téléphones d'entrée de gamme) :
- JSON **minifié** (aucun espace hors des chaînes) ;
- omettre les valeurs égales aux valeurs par défaut du schéma ;
- au-delà d'environ 1 200 caractères de QR, prévenir l'utilisateur (QR dense, difficile à lire) et proposer plutôt le fichier.

## 2. Variante en clair : `KURA1:`

```
KURA1:<base64url(charge utile UTF-8)>
```

- `base64url` = alphabet RFC 4648 §5 (`A–Z a–z 0–9 - _`), **sans remplissage `=`**. L'appli tolère un `=` final, le producteur ne doit pas en mettre.
- **Interdit : la charge utile `KURA1:` ne doit JAMAIS contenir `secret`.** L'appli **refuse** un QR `KURA1:` qui en contient un (message « QR refusé : il contient un secret en clair »), même si la config est valide. Un secret ne voyage que dans un QR `KURA1E:`.

## 3. Variante chiffrée : `KURA1E:`

```
KURA1E:<base64url( sel[16] ‖ iv[12] ‖ chiffré_AES-GCM[n + 16] )>
```

| Élément | Taille | Valeur |
|---|---|---|
| `sel` | 16 octets | aléatoire (CSPRNG), nouveau à chaque génération |
| `iv` | 12 octets | aléatoire (CSPRNG), nouveau à chaque génération |
| `chiffré_AES-GCM` | longueur de la charge utile + 16 | sortie AES-256-GCM, **tag de 128 bits concaténé à la fin** (format WebCrypto / JCA / `cryptography` standard) |

Paramètres :

- **Clé** : `PBKDF2-HMAC-SHA256(mot de passe = octets UTF-8 du PIN, sel, 120 000 itérations, 32 octets)`.
  - Le PIN est pris **tel que saisi** : pas de normalisation Unicode, pas de suppression d'espaces. Kura Studio impose des PIN de **6 chiffres minimum** (chiffres ASCII uniquement, recommandé), l'appli accepte n'importe quel texte non vide.
- **Chiffrement** : AES-256-GCM, IV de 12 octets, tag de 16 octets, **pas de données associées (AAD vide)**.
- **Texte clair** : la charge utile du §1 en UTF-8 (elle peut contenir ou non `secret`).
- `base64url` sans remplissage, comme au §2.

Le PIN est communiqué **à part** (oral, SMS, MDM), jamais imprimé à côté du QR.

À la lecture, l'appli :
1. détecte le préfixe, demande le PIN ;
2. un échec d'authentification GCM = « code PIN incorrect » (indiscernable d'un QR altéré, c'est voulu) ;
3. valide `config` contre le schéma, puis l'enregistre ; le secret va dans le Keystore.

Exemple WebCrypto (Studio) :

```js
const key = await crypto.subtle.deriveKey(
  { name: 'PBKDF2', hash: 'SHA-256', salt, iterations: 120000 },
  await crypto.subtle.importKey('raw', new TextEncoder().encode(pin), 'PBKDF2', false, ['deriveKey']),
  { name: 'AES-GCM', length: 256 }, false, ['encrypt']);
const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128 }, key, plaintext));
// QR = 'KURA1E:' + base64url(concat(salt, iv, ct)) sans '='
```

## 4. Vecteurs de test

Les deux implémentations (Studio et Scan) doivent reproduire ces valeurs à l'identique.
Vérifiés dans `apps/scan-android/core/src/test/kotlin/dev/kura/core/config/ConfigQrTest.kt` et générés indépendamment avec Python (`hashlib.pbkdf2_hmac` + `cryptography` AESGCM).

### 4.1 `KURA1:` (en clair)

Charge utile (UTF-8, exactement ces octets) :

```
{"config":{"kura_config_version":1,"server":{"base_url":"http://10.0.2.2:8787"},"scan":{"format":"id"}}}
```

QR :

```
KURA1:eyJjb25maWciOnsia3VyYV9jb25maWdfdmVyc2lvbiI6MSwic2VydmVyIjp7ImJhc2VfdXJsIjoiaHR0cDovLzEwLjAuMi4yOjg3ODcifSwic2NhbiI6eyJmb3JtYXQiOiJpZCJ9fX0
```

### 4.2 `KURA1E:` (chiffré)

| Entrée | Valeur |
|---|---|
| PIN | `482913` |
| sel (hex) | `000102030405060708090a0b0c0d0e0f` |
| iv (hex) | `101112131415161718191a1b` |
| clé dérivée (hex) | `7f3e4e9a0fc86393633ab7b1b8b620722f005b943bcd74a639c5c29f5dd3e166` |

Charge utile (UTF-8, exactement ces octets ; noter le `é` encodé sur 2 octets) :

```
{"config":{"kura_config_version":1,"server":{"base_url":"https://gmao.example.fr/api","auth":{"type":"bearer"}},"scan":{"format":"id"}},"secret":"jeton-de-démo"}
```

QR :

```
KURA1E:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG03Z2DfWlrQsxWg1ahW1gxAi655SsboV_5KQ-U899HgrnDDmMaqtV16WA5IuZB4FTnRBvRlmTPOYXiEL9nQ4fJLU8VrixdrWEZSe_abTvSMbKYqK8ev5LgDUbx9Cf83qTeEHwZDZVVavO4J7SmMUzoMXKW4E_H5m90_8kjBon6oL8Zx7yhwhFtwhg4yI8aDNnWUejjgNr3e-X-mR9X57pPMeZac3cTkWiXkWPypjzoCEmbg
```

Contrôle négatif : le même QR avec le PIN `482914` doit être refusé (« code PIN incorrect »).

## 5. Sécurité : ce que le format garantit et ne garantit pas

- `KURA1:` n'offre **aucune** confidentialité ni authenticité : n'importe qui peut fabriquer un QR qui pointe l'appli vers un autre serveur. L'appli affiche donc l'adresse du serveur et demande confirmation avant d'appliquer une config scannée, et exige le mot de passe de verrouillage si la config en cours est verrouillée (`security.config_locked`).
- `KURA1E:` protège le secret par le PIN. 120 000 itérations PBKDF2 ralentissent une attaque par force brute, mais un PIN de 6 chiffres reste cassable hors-ligne par un attaquant qui photographie le QR : traiter le QR chiffré comme confidentiel et **changer le jeton** s'il a circulé.
- L'authenticité d'une config (signature Ed25519) relève de la config distante (`remote_config`), pas du QR.
