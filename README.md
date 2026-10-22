# Kura

**Transformez un smartphone Android en terminal de saisie de stock, branché sur votre GMAO existante.**

Kura remplace les terminaux de saisie coûteux (et leurs licences) par une appli de scan QR / codes-barres, un générateur d'étiquettes et un contrat d'échange simple. Votre système reste la source de vérité : Kura ne fait que scanner, afficher, envoyer et corriger.

- **Zéro lock-in** : Kura s'adapte à votre API, ou passe par un petit adaptateur (Kura Bridge).
- **Config, pas code** : un fichier `kura-config.json`, ou un QR de configuration à scanner.
- **Offline-first** : les mouvements sont mis en file et envoyés au retour du réseau, sans doublon ni perte.
- **Mode rafale** pour enchaîner les entrées/sorties.
- **Open source** (Apache 2.0), **sans télémétrie**, **sans dépendance Google**.

> 🚧 Projet en démarrage (v0.1). Voir la [feuille de route](docs/architecture.md#11-feuille-de-route).

## Contenu du dépôt

```
kura/
├── apps/
│   ├── scan-android/     # Kura Scan : appli Android (Kotlin, Compose, ZXing)
│   ├── studio-web/       # Kura Studio : config + étiquettes (PWA)
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

## Essayer en 2 minutes

```sh
cd tools/mock-server
npm start            # http://localhost:8787
curl localhost:8787/items/REF-0042
```

Détails : [docs/quickstart.md](docs/quickstart.md).

## Documentation

- [Architecture et plan](docs/architecture.md)
- [Décisions (ADR)](docs/adr/README.md)
- [Contrat d'API](spec/api-contract.yaml)
- [Schéma de configuration](spec/config.schema.json)

## Licence

[Apache 2.0](LICENSE)
