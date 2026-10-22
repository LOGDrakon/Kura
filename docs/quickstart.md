# Démarrage rapide : Kura en 15 minutes

À la fin de ce guide, vous aurez :

- une base de stock de démonstration sur votre PC ;
- Kura Studio pour imprimer des étiquettes ;
- un téléphone Android qui scanne ces étiquettes et met le stock à jour.

Tout reste sur votre réseau local, sans toucher à la vraie base de l'entreprise.

Il vous faut :

- un PC (Windows, macOS ou Linux) avec **Node.js 20** ou plus ([nodejs.org](https://nodejs.org)) ;
- un navigateur récent (Chrome, Edge, Firefox) ;
- un téléphone **Android 6** ou plus récent (ou un émulateur Android) ;
- le dépôt Kura (`git clone`, ou l'archive ZIP de la version).

## 1. Lancer la base de démonstration (2 min)

Dans un terminal, à la racine du dépôt :

```sh
cd tools/mock-server
KURA_MOCK_DB=.data/db.json KURA_MOCK_ITEMS=demo/items.demo.json npm start
```

Sous Windows (PowerShell) :

```powershell
cd tools\mock-server
$env:KURA_MOCK_DB=".data/db.json"; $env:KURA_MOCK_ITEMS="demo/items.demo.json"; npm start
```

Ouvrez <http://localhost:8787/> : la page **Base de démo Kura** liste 14 articles (visserie, roulements, EPI…) avec leur stock. Laissez-la ouverte : vous y verrez arriver les mouvements du téléphone.

Les données sont enregistrées dans `.data/db.json` et survivent aux redémarrages. Pour repartir de zéro, arrêtez le serveur (Ctrl+C) et supprimez ce fichier. Détails : [tools/mock-server](../tools/mock-server/README.md).

## 2. Ouvrir Kura Studio (1 min)

Kura Studio est un seul fichier HTML, à ouvrir dans le navigateur. Deux façons de l'obtenir :

- **Fichier fourni** : téléchargez `kura-studio.html` dans les [versions publiées](https://github.com/LOGDrakon/Kura/releases), puis double-cliquez dessus.
- **Depuis les sources** (Node.js 20+) :

  ```sh
  cd apps/studio-web
  npm ci
  npm run build
  ```

  puis ouvrez `apps/studio-web/dist/index.html`.

## 3. Relier Studio à la base (2 min)

1. Onglet **Appli mobile**, section **Connexion à votre base**.
2. **Entreprise** : « Démo Kura ».
3. **Adresse de l'API de votre base** : l'adresse que le **téléphone** utilisera pour joindre le PC (choisissez-la à l'[étape 4](#4-relier-le-téléphone-au-pc)), par exemple `http://192.168.1.20:8787`.
4. **Authentification** : Aucune.
5. **Référence d'essai** : `REF-0042`, puis **Tester la connexion**. La fiche « Vis M6 x 20 inox » doit s'afficher.

Optionnel, pour une démo plus complète :

- section **Fiche article**, **Informations affichées** : `name, brand, location, quantity` ;
- **Afficher une image**, avec l'adresse `/items/{ref}/image` ;
- section **Synchronisation et sécurité** : **Demander l'identifiant de l'utilisateur**.

## 4. Relier le téléphone au PC

Le téléphone doit pouvoir joindre le port 8787 du PC. Choisissez l'une de ces solutions :

| Situation | Adresse à mettre dans Studio | Remarques |
|---|---|---|
| **Même Wi-Fi** que le PC | `http://<IP du PC>:8787`, ex. `http://192.168.1.20:8787` | IP du PC : `ipconfig` (Windows), `ip addr` (Linux), Réglages › Réseau (macOS). Autorisez le port 8787 dans le pare-feu du PC si besoin. Testez en ouvrant `http://192.168.1.20:8787/health` dans le navigateur du téléphone : `{"status":"ok"}`. |
| **Câble USB** (mode développeur et débogage USB activés) | `http://127.0.0.1:8787` | Sur le PC : `adb reverse tcp:8787 tcp:8787`. À refaire à chaque branchement. Fonctionne même sans Wi-Fi. |
| **Émulateur Android** | `http://10.0.2.2:8787` | `10.0.2.2` désigne le PC hôte depuis l'émulateur. Mais Studio, lui, ne joint pas cette adresse : « Tester la connexion » et « Votre base » échoueront. Préférez `adb reverse` avec `http://127.0.0.1:8787`, qui fonctionne aussi avec l'émulateur. |

> `localhost` ou `127.0.0.1` désignent le téléphone lui-même : sans `adb reverse`, ils ne mènent pas au PC.

## 5. Installer Kura Scan (3 min)

- **APK fourni** : téléchargez `kura-scan-….apk` dans les [versions publiées](https://github.com/LOGDrakon/Kura/releases), copiez-le sur le téléphone et ouvrez-le. Autorisez l'installation depuis cette source quand Android le demande.
- **Depuis les sources** : JDK 17 à 21 et SDK Android (Android Studio), puis :

  ```sh
  cd apps/scan-android
  ./gradlew :app:assembleDebug
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

  Détails : [apps/scan-android](../apps/scan-android/README.md).

## 6. Configurer le téléphone (1 min)

1. Dans Studio, colonne de droite de l'onglet **Appli mobile** : vérifiez **✓ Configuration valide**, puis **Générer le QR**.
2. Ouvrez Kura Scan. Sur l'écran **Bienvenue dans Kura Scan**, appuyez sur **Scanner**, autorisez la caméra et visez le QR affiché à l'écran du PC.
3. Vérifiez l'adresse du serveur, puis **Appliquer**.
4. Si demandé, indiquez votre nom à la question **Qui scanne ?**.

## 7. Scanner un article (2 min)

Il faut un code à scanner :

- **Pour aller vite** : dans Studio, onglet **Modèles**, le modèle affiche un QR d'exemple contenant `REF-0042`. Visez-le à l'écran.
- **Mieux** : imprimez de vraies étiquettes (étape 8).

Puis :

1. Appuyez sur **Scanner** et visez le QR. L'appli vibre et ouvre la fiche.
2. Au premier échange, l'appli affiche **Connexion non chiffrée** : c'est attendu, le serveur de démo est en HTTP. Appuyez sur **Je comprends, continuer**.
3. La fiche de l'article s'affiche avec son stock. Appuyez deux fois sur **−1**, puis **Valider −2**.
4. Le message **Enregistré · stock 248** s'affiche. Sur la page **Base de démo Kura**, la ligne `REF-0042` clignote ; cliquez dessus pour voir le mouvement dans le journal, avec votre nom et l'appareil.

Essayez aussi :

- **Inventaire** : saisissez la quantité comptée, elle remplace le stock.
- **Mode Rafale** : en haut de l'écran Scan, chaque lecture compte −1 sans ouvrir de fiche.
- **Hors-ligne** : coupez le Wi-Fi, faites un mouvement (il part en **File d'attente**), rallumez : il est envoyé tout seul.

## 8. Imprimer des étiquettes (3 min)

1. Studio, onglet **Imprimer**, source **Votre base** : la liste des articles se charge.
2. Cliquez sur l'étiquette de groupe **Maintenance** (ou cochez quelques articles).
3. Étape 2 : choisissez le support, **Imprimante d'étiquettes** ou **Planche A4** (Avery L7160, par exemple).
4. Étape 3 : **Télécharger le PDF**, ouvrez-le et imprimez-le en **taille réelle**.
5. Scannez les étiquettes imprimées avec le téléphone.

Pour essayer la détection des nouveautés : sur la page de démo, **Créer ou modifier une entrée** (par exemple `REF-0500`, « Joint torique 20x2 »), puis **Rechercher** dans Studio. Le nouvel article apparaît, prêt à être étiqueté. Les articles imprimés disparaissent de la liste.

## Et ensuite ?

- **Brancher votre vraie base** (GMAO, ERP, base SQL) : installez [Kura Bridge](guides/bridge.md), ou implémentez le contrat d'API vous-même avec le [guide d'intégration](guides/integration.md).
- **Utiliser l'appli au quotidien** : [guide de Kura Scan](guides/scan.md).
- **Dessiner vos étiquettes et partager l'éditeur** : [guide de Kura Studio](guides/studio.md).
- **Toutes les options de configuration** : [référence de `kura-config.json`](reference/config.md).
- **Toute la documentation** : [index](README.md).
