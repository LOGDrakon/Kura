# Kura Studio

Outil web 100 % navigateur pour :

- **créer des modèles d'étiquettes** : QR / Data Matrix / codes-barres 1D, textes libres avec champs `{ref}`, `{marque}`…, cadres, logos, placés au millimètre à la souris, au doigt ou au clavier ;
- **imprimer** des lots d'étiquettes en PDF vectoriel, sur rouleau (imprimante d'étiquettes) ou planches A4 (Avery…), à partir des **articles de la base de l'entreprise** (recherche, nouveautés, déjà imprimés masqués), d'un CSV ou d'une liste de références ;
- **contrôler les données** : champs obligatoires (étiquette non imprimée s'ils sont vides) ou facultatifs (une ligne de texte dont les champs sont vides disparaît), et alerte pour tout champ des données absent du modèle, avec « Ajouter au modèle » ou « Ignorer » ([ADR 0008](../../docs/adr/0008-etiquettes-depuis-la-base.md)) ;
- **configurer Kura Scan** : assistant qui produit `kura-config.json` (validé contre le schéma), son **QR de configuration** et un test de connexion ;
- **partager l'éditeur** : Studio est **un seul fichier HTML**. « Partage › Télécharger l'éditeur » produit une copie qui **embarque vos modèles et votre config** : déposée sur un partage réseau ou envoyée par mail, elle s'ouvre sur n'importe quel poste avec les mêmes modèles, sans installation ni serveur.

## Utilisation

Ouvrir `kura-studio.html` (produit par le build) dans un navigateur récent (Chrome, Edge, Firefox, Safari). Rien à installer.

Les modifications sont enregistrées dans le navigateur du poste. Quand un poste ouvre un éditeur partagé, il fusionne : pour chaque modèle, la version la plus récente gagne.

### Éditeur de modèles

| Action | Souris / tactile | Clavier |
|---|---|---|
| Sélectionner | clic / tap | Tab |
| Déplacer | glisser (aimanté à 0,5 mm, Alt pour libérer) | flèches (Maj : 5 mm) |
| Redimensionner | poignées d'angle | Alt + flèches |
| Supprimer / dupliquer | barre de propriétés | Suppr / Ctrl+D |
| Annuler / rétablir | boutons | Ctrl+Z / Ctrl+Y |

Les problèmes sont signalés en direct : code invalide (ex. EAN-13 mal formé), code trop petit pour être lu (module < 0,25 mm), élément qui dépasse, texte qui ne tient pas. La zone de silence des codes est incluse dans leur cadre.

### Depuis la base de l'entreprise

1. Créer l'article dans la GMAO, comme d'habitude.
2. Studio › Impression › **Base de l'entreprise** › Rechercher (ou filtrer sur la date). Les articles déjà imprimés sur ce poste sont masqués.
3. Cocher les articles, vérifier les alertes, télécharger le PDF.

Le serveur doit proposer `GET /items` ([contrat](../../spec/api-contract.yaml)) et autoriser les appels depuis un navigateur (CORS). Sinon, exporter la base en CSV.

### Accessibilité et écrans

- Mise en page adaptée du téléphone au grand écran (barre d'onglets en bas sur mobile, cibles tactiles de 44 px).
- Navigation complète au clavier, annonces pour lecteurs d'écran, thèmes clair/sombre, respect de « réduire les animations ».
- Vérifié automatiquement avec axe-core (aucune violation grave).

## Développement

```sh
npm install
npm run dev        # serveur de développement
npm test           # tests unitaires (vitest)
npm run check      # typage (svelte-check)
npm run build      # → dist/index.html (fichier unique autonome)
npm run test:e2e   # parcours complet dans Chromium sur dist/index.html (CHROME_PATH si besoin)
```

Stack : Svelte 5, Vite + vite-plugin-singlefile, bwip-js (tous les codes, rendus en vectoriel), pdf-lib, Ajv, hash-wasm (argon2id).

| Fichier | Rôle |
|---|---|
| `src/lib/model.ts` | Modèles, éléments, validation des données importées, fusion |
| `src/lib/codes.ts` | Géométrie des codes en rectangles (même rendu en SVG et PDF) |
| `src/lib/text.ts` | Mise en page du texte avec les métriques Helvetica du PDF |
| `src/lib/render-svg.ts` / `pdf.ts` | Aperçu et PDF d'impression |
| `src/lib/layout.ts` | Planches A4 et rouleau |
| `src/lib/records.ts` / `server.ts` | Contrôle des champs, lecture des articles du serveur |
| `src/lib/embed.ts` | Données embarquées dans le HTML partagé |
| `src/lib/config.ts` / `config-qr.ts` | Config Kura Scan, QR, hachage du mot de passe |
