# Kura

**Transformez un smartphone Android en terminal de saisie de stock, branché sur votre GMAO existante.**

Kura remplace les terminaux de saisie coûteux (et leurs licences) par une appli de scan QR / codes-barres, un générateur d'étiquettes et un contrat d'échange simple. Votre système reste la source de vérité : Kura ne fait que scanner, afficher, envoyer et corriger.

- **Zéro lock-in** : Kura s'adapte à votre API, ou passe par un petit adaptateur (Kura Bridge).
- **Config, pas code** : un fichier `kura-config.json`, ou un QR de configuration à scanner.
- **Offline-first** : les mouvements sont mis en file et envoyés au retour du réseau, sans doublon ni perte.
- **Mode rafale** pour enchaîner les entrées/sorties.
- **Open source** (Apache 2.0), **sans télémétrie**, **sans dépendance Google**.

**Version 1.0** : appli Android, éditeur d'étiquettes et connecteur prêts à l'emploi. Voir la [feuille de route](docs/architecture.md#11-feuille-de-route) et le [journal des versions](CHANGELOG.md).

| | |
|---|---|
| **Kura Scan** | Appli Android 6.0+ : bouton Scanner (caméra, QR et codes-barres) ou douchette Bluetooth, fiche article, entrées/sorties, inventaire, mode rafale, file hors-ligne. |
| **Kura Studio** | Une page web autonome : imprimer les étiquettes depuis la base ou un CSV, dessiner les modèles, configurer l'appli (fichier ou QR de configuration). |
| **Kura Bridge** | Un programme (Linux, Windows, macOS, Docker) qui branche Kura sur PostgreSQL, MySQL/MariaDB, SQL Server, SQLite, ou sur un script (API REST, CSV, Excel). |

## Télécharger

Dans les [versions publiées](https://github.com/LOGDrakon/Kura/releases) :

- `kura-scan-<version>.apk` : l'appli, à installer sur les téléphones ;
- `kura-studio.html` : Kura Studio, à ouvrir dans Chrome, Edge ou Firefox (aucune installation) ;
- `kura-bridge_<version>_<système>.tar.gz` / `.zip` : le connecteur ;
- `kura-mock-server-<version>.zip` : le serveur de démonstration (Node.js).

## Contenu du dépôt

```
kura/
├── apps/
│   ├── scan-android/     # Kura Scan : appli Android (Kotlin, Compose, ZXing)
│   ├── studio-web/       # Kura Studio : étiquettes + config (Svelte, un seul fichier HTML)
│   └── bridge/           # Kura Bridge : adaptateur optionnel (Go)
├── spec/
│   ├── api-contract.yaml # Contrat d'API (OpenAPI 3.1)
│   ├── config.schema.json# JSON Schema de kura-config.json
│   └── examples/         # Configs d'exemple
├── tools/
│   └── mock-server/      # Serveur de démo et de test (Node, sans dépendance)
└── docs/
    ├── architecture.md   # Plan et architecture
    ├── adr/              # Décisions d'architecture
    ├── quickstart.md
    ├── guides/
    └── recipes/
```

## Essayer en 15 minutes

Base de démonstration, Kura Studio, un téléphone et vos premières étiquettes : [démarrage rapide](docs/quickstart.md).

```sh
cd tools/mock-server
npm start            # base de démonstration sur http://localhost:8787
```

## Documentation

Tout est dans [`docs/`](docs/README.md) :

- [Kura Scan](docs/guides/scan.md) et [Kura Studio](docs/guides/studio.md) : guides d'utilisation ;
- [Kura Bridge](docs/guides/bridge.md) : installer le connecteur et le brancher sur une base existante ;
- [Connecter un système existant](docs/guides/integration.md) : le contrat d'API, requêtes et réponses ;
- [Recettes](docs/recipes/README.md) : PostgreSQL, MySQL, SQL Server, SQLite, CSV, API REST ;
- [Référence de la configuration](docs/reference/config.md) ;
- [Architecture](docs/architecture.md) et [décisions (ADR)](docs/adr/README.md).

## Contribuer

Les retours d'usage, recettes pour d'autres logiciels et corrections sont les bienvenus : voir [CONTRIBUTING.md](CONTRIBUTING.md). Pour signaler une faille : [SECURITY.md](SECURITY.md).

## Licence

[Apache 2.0](LICENSE)
