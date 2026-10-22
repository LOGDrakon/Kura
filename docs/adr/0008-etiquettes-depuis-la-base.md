# 0008 : Les étiquettes partent de la base de l'entreprise

**Statut :** acceptée (2026-10-22)

## Contexte
Quand un nouvel article arrive, il faut à la fois l'enregistrer dans la base de stock et lui imprimer une étiquette. Deux sens étaient possibles :

1. **Base → Studio** : l'article est créé dans la GMAO, Kura Studio le retrouve et génère l'étiquette (à l'unité ou par lot).
2. **Studio → base** : l'article est saisi dans Studio (ou un fichier Excel), Studio crée l'identifiant et l'envoie à la base.

## Décision
**Solution 1.** Kura ne crée jamais d'article : la base de l'entreprise reste la seule source de vérité (principe « Kura n'est pas une GMAO »).

- Contrat : nouvel endpoint optionnel `GET /items?q=&updated_since=&limit=&cursor=` (config : `endpoints.list_items`).
- Studio, onglet Impression › « Base de l'entreprise » : recherche, filtre « créés ou modifiés depuis », masquage des articles **déjà imprimés sur ce poste**, sélection multiple → PDF du lot.
- L'import CSV reste disponible (export de la base, ou serveur sans `GET /items`).

### Champs du modèle
- Les champs `{nom}` reprennent les **noms de colonnes** de la base (normalisés comme pour le CSV : minuscules, sans accents ; `stock.disponible` → `stock_disponible`).
- Chaque champ du modèle est **obligatoire** ou **facultatif** (par défaut seul `{ref}` est obligatoire). Une étiquette dont un champ obligatoire est vide **n'est pas imprimée** : elle est listée en erreur avec sa ligne.
- Champ facultatif vide : laissé blanc ; une **ligne de texte dont tous les champs sont vides disparaît** (« Marque : {brand} » n'apparaît pas pour un article sans marque) ; un code vide n'est pas dessiné. Un même modèle sert ainsi à plusieurs services.
- Un champ **renseigné dans les données mais absent du modèle** est signalé avec deux actions : **Ajouter au modèle** (ligne de texte `{champ}` placée dans une zone libre, à ajuster) ou **Ignorer pour ce modèle** (mémorisé dans le modèle). `quantity`, `updated_at` et `image_url` ne sont jamais signalés (données vivantes ou techniques).

## Conséquences
- Studio est une page web : pour lire `GET /items`, le serveur doit autoriser les appels du navigateur (**CORS**). Le Mock Server le fait ; Kura Bridge le fera ; pour une API existante, l'intégrateur doit l'activer (sinon : import CSV).
- Le suivi « déjà imprimé » est local au poste (navigateur). Il ne remplace pas un statut côté base.
- La solution 2 reste possible plus tard (envoi d'articles vers `POST /items`), mais n'est pas prévue : elle ferait de Kura un outil de saisie de catalogue.
