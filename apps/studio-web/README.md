# Kura Studio

Outil web 100 % navigateur, livré en **un seul fichier HTML**, pour :

- **créer des modèles d'étiquettes** : QR / Data Matrix / codes-barres 1D, textes libres avec champs `{ref}`, `{name}`…, cadres, logos, placés au millimètre à la souris, au doigt ou au clavier ;
- **imprimer** des lots d'étiquettes en PDF vectoriel, sur rouleau (imprimante d'étiquettes) ou planches A4 (Avery…), à partir des **articles de la base de l'entreprise** (recherche, nouveautés, regroupement par service, déjà imprimés masqués), d'un CSV ou d'une liste de références ;
- **contrôler les données** : champs obligatoires (étiquette non imprimée s'ils sont vides) ou facultatifs (une ligne de texte dont les champs sont vides disparaît), alerte pour tout champ des données absent du modèle, avec « Ajouter » ou « Ignorer » ([ADR 0008](../../docs/adr/0008-etiquettes-depuis-la-base.md)) ;
- **configurer Kura Scan** : assistant qui produit `kura-config.json` (validé contre le schéma), son **QR de configuration** (chiffré par PIN s'il contient le secret) et un test de connexion ;
- **partager l'éditeur** : « Partager › Télécharger l'éditeur » produit une copie du fichier HTML qui **embarque les modèles et la config** (jamais de secret).

README développeur. Guide d'utilisation : [docs/guides/studio.md](../../docs/guides/studio.md).

## Utilisation

Ouvrir `kura-studio.html` (le `dist/index.html` produit par le build, renommé) dans un navigateur récent (Chrome, Edge, Firefox, Safari). Rien à installer, aucun serveur.

Onglets : **Imprimer**, **Modèles**, **Appli mobile**, **Partager**.

Les données restent dans le navigateur du poste (`localStorage`) :

| Clé | Contenu |
|---|---|
| `kura-studio:data` | Modèles, modèles supprimés (horodatés), config Kura Scan, nom de l'entreprise |
| `kura-studio:prefs` | Préférences d'impression (support, planche…) |
| `kura-studio:printed` | Articles déjà imprimés **sur ce poste** (filtre « Masquer les articles déjà imprimés ») |

Les secrets (jeton de la base, secret de test, PIN) ne sont jamais enregistrés.

Un éditeur partagé contient ses données dans un bloc `<script id="kura-data">`. À l'ouverture, Studio les fusionne avec les données locales : pour chaque modèle, la version au `updated_at` le plus récent gagne ; une suppression l'emporte sur une version plus ancienne ; la config la plus récente gagne.

### Depuis la base de l'entreprise

Le serveur doit proposer `GET /items` (ou `endpoints.list_items`, voir le [contrat](../../spec/api-contract.yaml)) et autoriser les appels depuis un navigateur (CORS). C'est le cas de [`tools/mock-server`](../../tools/mock-server/README.md) et de [Kura Bridge](../../docs/guides/bridge.md). Sinon, passer par un export CSV.

La connexion (adresse, authentification) est celle de l'onglet **Appli mobile**.

### Accessibilité et écrans

- Mise en page adaptée du téléphone au grand écran (barre d'onglets en bas sur mobile, cibles tactiles de 44 px).
- Navigation complète au clavier, annonces pour lecteurs d'écran, thèmes clair/sombre, respect de « réduire les animations ».
- Vérifié automatiquement avec axe-core (aucune violation grave).

## Développement

Node.js 20 ou plus.

```sh
npm ci
npm run dev        # serveur de développement (Vite)
npm test           # tests unitaires (vitest)
npm run check      # typage (svelte-check)
npm run build      # → dist/index.html, fichier unique autonome (≈ 1,7 Mo)
npm run test:e2e   # parcours complet dans Chromium sur dist/index.html
```

`test:e2e` utilise `playwright-core` sans navigateur embarqué : indiquer un Chrome ou Chromium avec `CHROME_PATH` si besoin, par exemple :

```sh
CHROME_PATH=~/.cache/ms-playwright/chromium-1194/chrome-linux/chrome npm run test:e2e
SCREENSHOTS=/tmp/captures npm run test:e2e     # enregistre aussi des captures
node e2e/shots.mjs <dossier> <préfixe>         # captures de toutes les vues (revue UX)
```

Le parcours e2e lance `tools/mock-server` pour tester la source « Votre base ».

Fichiers de démonstration (essai grandeur nature) :

```sh
sh demo/build-demo.sh
# → dist/kura-studio-demo.html     Studio préconfiguré pour le serveur de démo, avec deux modèles
# → dist/etiquettes-existantes.pdf étiquettes « déjà imprimées » de quelques articles
```

Stack : Svelte 5, Vite + vite-plugin-singlefile, bwip-js (tous les codes, rendus en vectoriel), pdf-lib, Ajv (validation contre `spec/config.schema.json`), hash-wasm (Argon2id), WebCrypto (QR chiffré).

| Fichier | Rôle |
|---|---|
| `src/App.svelte` | Onglets, annonces, chargement des données |
| `src/components/PrintView.svelte` | Onglet Imprimer (sources, support, aperçu, PDF) |
| `src/components/ServerSource.svelte` | Source « Votre base » : recherche, filtres, étiquettes de regroupement |
| `src/components/Editor.svelte` / `Inspector.svelte` / `TemplateList.svelte` | Onglet Modèles |
| `src/components/ConfigView.svelte` | Onglet Appli mobile |
| `src/components/ShareView.svelte` | Onglet Partager |
| `src/lib/model.ts` | Modèles, éléments, validation des données importées, fusion |
| `src/lib/state.svelte.ts` / `storage.ts` | État de l'application, annuler/rétablir, persistance locale |
| `src/lib/codes.ts` | Géométrie des codes en rectangles (même rendu en SVG et PDF) |
| `src/lib/text.ts` | Mise en page du texte avec les métriques Helvetica du PDF |
| `src/lib/render-svg.ts` / `pdf.ts` | Aperçu et PDF d'impression |
| `src/lib/layout.ts` | Planches A4 et rouleau |
| `src/lib/csv.ts` | Lecture CSV tolérante (séparateur détecté, guillemets, BOM) ; repli Windows-1252 dans `PrintView.svelte` |
| `src/lib/records.ts` / `fields.ts` | Contrôle des champs (obligatoires, absents du modèle) |
| `src/lib/server.ts` / `tags.ts` | Lecture des articles du serveur, détection des colonnes de regroupement |
| `src/lib/embed.ts` | Données embarquées dans le HTML partagé |
| `src/lib/config.ts` / `config-qr.ts` | Config Kura Scan, QR (`KURA1:` / `KURA1E:`, [spec](../../spec/config-qr.md)), hachage du mot de passe |
