# Journal des versions

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les numéros suivent le [versionnage sémantique](https://semver.org/lang/fr/).

## [1.0.0] - 2026-10-22

Première version complète : un téléphone Android qui scanne, une page pour les étiquettes et la configuration, un connecteur vers la base de l'entreprise.

### Kura Scan (Android 6.0 et plus)

- Scan sur appui (caméra, ZXing) : QR, Data Matrix, EAN-13/8, UPC, Code 128/39, ITF ; douchettes Bluetooth et gâchettes intégrées (clavier HID).
- Fiche article : informations de la base, photo, stock, boutons de pas, inventaire en un appui (clavier numérique ouvert directement), validation toujours visible.
- Mode rafale : chaque lecture crée un mouvement, avec vérification facultative de l'article.
- File hors-ligne : mouvements enregistrés sur le téléphone, envoyés seuls au retour du réseau, par lots, sans doublon ni perte ; erreurs à corriger dans l'onglet File.
- Configuration par fichier ou par QR (`KURA1:`, ou `KURA1E:` chiffré par PIN pour inclure le secret), verrouillage par mot de passe.
- Connexion à une API existante sans code : `endpoints`, `request_mapping`, `response_mapping`.

### Kura Studio (une page HTML)

- Impression d'étiquettes depuis la base de l'entreprise (recherche, nouveautés, tri par service ou autre critère) ou depuis un CSV ; planches A4 et rouleaux ; suivi des étiquettes déjà imprimées.
- Éditeur de modèles : QR et codes-barres, textes avec champs `{nom}`, cadres, logos ; champs obligatoires et facultatifs.
- Configuration de Kura Scan sans écrire de JSON : connexion, test, affichage, rafale, synchronisation, sécurité ; QR de configuration.
- Partage de l'éditeur avec ses modèles et sa configuration.

### Kura Bridge (nouveau)

- Connecteur en un seul programme (Linux, Windows, macOS, Docker) qui expose le contrat Kura sur une base existante.
- PostgreSQL, MySQL/MariaDB, SQL Server, SQLite : quelques requêtes SQL dans `kura-bridge.yaml`, avec paramètres nommés.
- Mode script : un programme de l'entreprise (Python, PowerShell…) fait le lien avec une API REST, un fichier CSV ou Excel. Exemples fournis.
- Doublons, règle des inventaires ([ADR 0005](docs/adr/0005-resolution-conflits.md)) et journal `kura_operations` dans la même transaction que le stock.
- Jetons d'accès, HTTPS, CORS pour Kura Studio, config distante signée (Ed25519), service Windows, commande `check`.

### Serveur de démonstration

- Base de démonstration persistante, page de suivi des articles et du journal en direct.

### Documentation

- Démarrage rapide, guides Kura Scan, Kura Studio et Kura Bridge, guide d'intégration (contrat d'API), recettes par moteur, référence complète de la configuration.

### Limites connues

- Kura Scan ne télécharge pas encore la config distante (`remote_config`), ni le secret fourni par une gestion de flotte (`token_ref: "mdm"`).
- Interface en français uniquement.
- Pas encore sur F-Droid ni le Play Store : APK sur GitHub.

[1.0.0]: https://github.com/LOGDrakon/Kura/releases/tag/v1.0.0
