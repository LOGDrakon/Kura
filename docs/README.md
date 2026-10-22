# Documentation de Kura

Kura relie la base de stock d'une entreprise (GMAO, ERP, base maison) à des téléphones Android qui scannent les étiquettes des articles. Elle a trois composants :

- **Kura Scan** : l'appli Android ;
- **Kura Studio** : l'outil de bureau qui dessine et imprime les étiquettes, et configure l'appli ;
- **Kura Bridge** : le connecteur qui expose une base existante à Kura.

## Commencer

- [Démarrage rapide](quickstart.md) : base de démonstration, Studio, téléphone et premières étiquettes en 15 minutes.

## Guides

| Guide | Pour qui | Contenu |
|---|---|---|
| [Kura Scan](guides/scan.md) | Utilisateurs de l'appli, responsables du déploiement | Installation, configuration par QR, modes Fiche et Rafale, hors-ligne, réglages, dépannage |
| [Kura Studio](guides/studio.md) | Responsables du stock, magasiniers | Impression d'étiquettes, éditeur de modèles, configuration de Kura Scan, partage |
| [Kura Bridge](guides/bridge.md) | Informatique | Installer le connecteur et le brancher sur une base existante |
| [Intégration](guides/integration.md) | Développeurs, intégrateurs | Le contrat d'API, les requêtes, les réponses, et comment connecter un système existant |
| [Recettes](recipes/README.md) | Intégrateurs | Exemples de branchement sur des systèmes courants |

## Référence

- [Configuration de Kura Scan (`kura-config.json`)](reference/config.md) : toutes les options, valeurs par défaut et QR de configuration.
- [Contrat d'API](../spec/api-contract.yaml) (OpenAPI 3.1) : ce que le serveur doit proposer.
- [Schéma de configuration](../spec/config.schema.json) et [exemples](../spec/examples/).
- [Format du QR de configuration](../spec/config-qr.md) (`KURA1:` / `KURA1E:`), avec vecteurs de test.

## Pour les développeurs

- [Architecture](architecture.md) : vue d'ensemble, flux de données, synchronisation.
- [Décisions d'architecture (ADR)](adr/README.md) : pourquoi ZXing, pourquoi le scan sur appui, résolution des conflits…
- [Kura Scan : construire l'appli](../apps/scan-android/README.md).
- [Kura Studio : construire l'outil](../apps/studio-web/README.md).
- [Serveur de démonstration](../tools/mock-server/README.md) : implémentation de référence du contrat.
