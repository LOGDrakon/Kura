# Guide de Kura Studio

Kura Studio est l'outil de bureau de Kura. Il sert à :

- **dessiner des modèles d'étiquettes** (QR code ou code-barres, textes, cadres, logo) ;
- **imprimer des étiquettes** pour les articles de votre base, d'un fichier CSV ou d'une simple liste de références, en PDF, sur imprimante d'étiquettes ou planches A4 ;
- **configurer Kura Scan** et produire le QR de configuration que l'on scanne avec chaque téléphone ;
- **partager** l'éditeur et ses modèles avec les autres postes.

Kura Studio est **un seul fichier HTML** (`kura-studio.html`) qui s'ouvre dans un navigateur récent (Chrome, Edge, Firefox, Safari). Rien à installer, aucun serveur : on peut le déposer sur un partage réseau ou l'envoyer par mail.

- [Ouvrir Kura Studio](#ouvrir-kura-studio)
- [Imprimer des étiquettes](#imprimer-des-étiquettes)
- [Modèles : dessiner une étiquette](#modèles--dessiner-une-étiquette)
- [Appli mobile : configurer Kura Scan](#appli-mobile--configurer-kura-scan)
- [Partager : diffuser l'éditeur et les modèles](#partager--diffuser-léditeur-et-les-modèles)
- [Où sont enregistrées les données ?](#où-sont-enregistrées-les-données-)
- [Dépannage](#dépannage)

## Ouvrir Kura Studio

Double-cliquez sur `kura-studio.html` (ou faites-le glisser dans le navigateur). L'outil a quatre onglets : **Imprimer** (ouvert par défaut), **Modèles**, **Appli mobile** et **Partager**.

La mise en page s'adapte du grand écran au téléphone (onglets en bas sur mobile). Tout se fait aussi au clavier, et les thèmes clair et sombre suivent le réglage du système.

## Imprimer des étiquettes

L'impression se fait en trois étapes.

### Étape 1 : choisir les articles

Trois sources possibles :

#### Votre base

Les articles sont lus directement dans la base de l'entreprise (GMAO, ERP, base de stock), à condition que la connexion soit réglée dans l'onglet **Appli mobile** : c'est la même que celle des téléphones.

- **Rechercher (référence, désignation…)** puis **Rechercher**.
- **Filtres** :
  - **Créés ou modifiés depuis** : pour imprimer les étiquettes des articles ajoutés récemment ;
  - **Masquer les articles déjà imprimés sur ce poste** (coché par défaut) : chaque article apparaît une fois, jusqu'à ce que vous l'imprimiez.
- **Étiquettes de regroupement** : Studio détecte les colonnes qui regroupent les articles (service, catégorie, marque…) et les affiche en boutons, le service en premier, les autres sous **Autres critères**. Un clic sur « Maintenance » sélectionne **tous** les articles de ce groupe, y compris ceux déjà imprimés (pratique pour réétiqueter un service entier). **Voir tous les articles** revient à la liste complète.
- Cochez les articles, ou **Tout sélectionner**. Le compteur indique « 12 / 40 sélectionnés ». **Charger plus** affiche la suite de la liste.

Si la base demande un secret (jeton, clé…), un champ **Jeton d'accès (non enregistré)** apparaît : il sert pour la session en cours et n'est jamais enregistré dans le navigateur.

Côté serveur, il faut que la base expose la liste des articles (`GET /items`) et autorise les appels depuis un navigateur (CORS). C'est le cas du serveur de démonstration et de [Kura Bridge](bridge.md). Sinon, exportez la base en CSV.

#### Fichier CSV

- **Importer un CSV** : la première ligne donne les noms des colonnes (`ref`, `name`, `brand`…). Le séparateur (point-virgule, virgule ou tabulation) est détecté automatiquement.
- **Fichier exemple** : télécharge un CSV avec les colonnes attendues par le modèle choisi, à remplir.
- Depuis Excel : **Enregistrer sous › CSV (séparateur point-virgule)**. Les accents sont lus correctement, que le fichier soit en UTF-8 ou dans l'ancien encodage Windows.

Les premières lignes sont affichées pour vérification. **Tout effacer** vide la liste.

#### Saisie libre

Une référence par ligne. Pratique pour réimprimer quelques étiquettes abîmées. Seule la colonne `{ref}` est alors remplie.

#### Contrôle des données

Avant l'impression, Studio vérifie les données :

- **Champ obligatoire vide** : l'étiquette de cet article **n'est pas imprimée** (« 2 étiquettes non imprimées : champ obligatoire vide »). On choisit les champs obligatoires dans le modèle (voir [Informations imprimées](#informations-imprimées)).
- **Informations absentes de l'étiquette** : vos données contiennent une colonne que le modèle n'imprime pas (par exemple `emplacement`). Pour chacune :
  - **Ajouter** : la place sur le modèle ;
  - **Ignorer** : ne plus la signaler pour ce modèle.

### Étape 2 : modèle et support

- **Modèle** : choisissez le modèle d'étiquette ; le bouton crayon l'ouvre dans l'éditeur.
- **Support** :
  - **Imprimante d'étiquettes** (rouleau) : une page par étiquette, à la taille du modèle. À l'impression du PDF, choisissez **taille réelle** (100 %), pas « ajuster à la page ».
  - **Planche A4** : planches prédécoupées. Formats proposés :

    | Planche | Taille d'une étiquette | Par page |
    |---|---|---|
    | Avery L7160 | 63,5 × 38,1 mm | 21 |
    | Avery L7163 | 99,1 × 38,1 mm | 14 |
    | Avery L7173 | 99,1 × 57 mm | 10 |
    | Avery L7651 | 38,1 × 21,2 mm | 65 |
    | Avery 3474 | 70 × 37 mm | 24 |
    | Avery 3475 | 70 × 36 mm | 24 |
    | Personnalisée | calculée depuis la taille du modèle | — |

- **Étiquettes déjà utilisées sur la première planche** : pour reprendre une planche entamée, indiquez combien d'emplacements sont déjà pris ; l'impression commence au suivant.
- **Réglages fins de la planche (mm)** : marges et espacements, si l'impression est décalée.
- **Exemplaires par article** : de 1 à 999.
- **Tracer le contour (impression d'essai)** : imprime le bord de chaque étiquette, pour vérifier l'alignement sur une feuille blanche avant d'utiliser une vraie planche.

Si le modèle est plus grand qu'une case de la planche, Studio le signale : choisissez une autre planche ou réduisez le modèle.

### Étape 3 : vérifier et imprimer

L'aperçu montre les premières étiquettes (ou la première planche) avec les vraies données. Les codes impossibles à générer sont listés (par exemple une référence de 12 chiffres dans un modèle EAN-13 qui en attend 13).

- **Télécharger le PDF** : un PDF vectoriel, net à toutes les tailles. Le bouton indique combien d'étiquettes sont exclues. Ouvrez-le et imprimez-le (taille réelle).
- **Exporter la 1ʳᵉ étiquette en SVG** : pour un graphiste ou un autre logiciel.

Après le téléchargement, les articles sont **marqués « imprimé » sur ce poste** : ils disparaissent de la liste « Votre base » tant que le filtre « Masquer les articles déjà imprimés » est coché.

## Modèles : dessiner une étiquette

L'onglet **Modèles** liste les modèles à gauche (**Nouveau** pour en créer un) et ouvre l'éditeur.

### Ajouter des éléments

Barre **Ajouter :**

- **Texte** : texte libre. Les noms entre accolades sont remplacés par la valeur de chaque article : `Emplacement {location}` donne « Emplacement A12 ». Les boutons « Insérer une information de la base » ajoutent ces noms sans les taper.
- **QR / code-barres** : le contenu du code, en général `{ref}`.
- **Cadre** : rectangle, avec épaisseur du trait et arrondi.
- **Image** : logo ou pictogramme en PNG ou JPEG. L'image est intégrée au modèle.

### Positionner au millimètre

| Action | Souris / tactile | Clavier |
|---|---|---|
| Sélectionner | clic / toucher | Tab (élément suivant) |
| Déplacer | glisser (aimanté à la grille, Alt pour libérer) | flèches (Maj : 5 mm) |
| Redimensionner | poignées d'angle | Alt + flèches |
| Supprimer | — | Suppr ou Retour arrière |
| Dupliquer | — | Ctrl+D |
| Annuler / Rétablir | boutons | Ctrl+Z / Ctrl+Y (ou Ctrl+Maj+Z) |
| Désélectionner | clic dans le vide | Échap |
| Aller aux propriétés | — | Entrée |

Le zoom se règle avec **−** et **+** ; un clic sur le pourcentage ajuste l'étiquette à l'écran. La section **Position et taille (mm)** du panneau de droite permet de saisir les valeurs exactes.

Le bouton **Imprimer avec ce modèle**, en bas, ouvre l'onglet Imprimer avec ce modèle déjà choisi.

### Propriétés des éléments

- **Texte** : taille (pt), couleur, alignement (Gauche / Centre / Droite), position verticale (Haut / Milieu / Bas), **Gras**, **Réduire pour tenir dans le cadre** (le texte rétrécit si une désignation est longue).
- **Code** : **Type** (QR Code, Data Matrix, Code 128, Code 39, EAN-13, EAN-8, UPC-A, ITF). Dans **Options avancées**, la **résistance aux salissures** des codes 2D : Faible, Moyenne, Forte, Maximale. Plus elle est forte, plus le code est dense ; « Moyenne » convient presque toujours.
- **Cadre** : trait (mm), arrondi (mm), couleur.

Quel code choisir ?

- **QR Code** (par défaut) : lisible sous tous les angles, tolérant aux salissures, contient n'importe quelle référence. Le meilleur choix pour Kura Scan ([ADR 0003](../adr/0003-qr-id-par-defaut.md)).
- **Data Matrix** : plus compact pour les très petites étiquettes.
- **Code 128 / Code 39** : si d'autres lecteurs de l'entreprise ne lisent que les codes-barres classiques.
- **EAN-13, EAN-8, UPC-A** : uniquement pour des références qui sont de vrais codes produits (chiffres, avec clé de contrôle).

Studio signale en direct les problèmes : code invalide, **code trop petit pour être lu** (traits de moins de 0,25 mm), élément qui dépasse du bord, texte qui ne tient pas. La marge blanche nécessaire autour des codes est incluse dans leur cadre.

### Le panneau « Modèle »

Quand aucun élément n'est sélectionné, le panneau de droite règle le modèle lui-même :

- **Nom**, **Largeur** et **Hauteur** (mm), avec des tailles courantes en un clic (50×25, 40×30, 57×32, 60×40, 70×37, 100×50, 100×150, 63,5×38,1, 38,1×21,2) ;
- dupliquer ou supprimer le modèle ;
- **Informations imprimées** et **Exemple utilisé pour l'aperçu** (ci-dessous).

#### Informations imprimées

La liste des informations (`{ref}`, `{name}`…) utilisées par le modèle. Cochez **Obligatoire** pour celles sans lesquelles l'étiquette n'a pas de sens (la référence, en général) : un article dont ce champ est vide ne sera pas imprimé.

Les autres sont **facultatives** : si elles sont vides, la ligne de texte qui les contient disparaît simplement de l'étiquette.

« Obligatoire mais absent de l'étiquette » signale un champ obligatoire qui n'est placé nulle part sur le modèle.

#### Exemple utilisé pour l'aperçu

Des valeurs d'exemple pour voir le rendu pendant la conception. **Nouveau champ** ajoute une information (par exemple `fournisseur`) que vous pourrez ensuite placer sur l'étiquette.

## Appli mobile : configurer Kura Scan

Cet onglet prépare la configuration des téléphones, en trois temps :

1. indiquer l'adresse de votre base et tester la connexion ;
2. ajuster si besoin le comportement de l'appli (les valeurs par défaut conviennent dans la plupart des cas) ;
3. générer le **QR de configuration**, puis le scanner avec Kura Scan sur chaque téléphone.

Chaque réglage correspond à une option du fichier `kura-config.json`, décrite dans la [référence de la configuration](../reference/config.md).

### 1. Connexion à votre base

- **Entreprise** : nom affiché dans l'appli.
- **Adresse de l'API de votre base** : par exemple `https://stock.exemple.fr/kura` (votre [Kura Bridge](bridge.md)) ou `http://192.168.1.20:8787` (serveur de démonstration).
- **Authentification** :
  - **Aucune** ;
  - **Jeton Bearer** ;
  - **Identifiant + mot de passe (Basic)** ;
  - **Clé d'API (en-tête)**, avec le nom de l'en-tête.

  Le secret n'est **jamais** écrit dans le fichier de configuration : il est saisi sur le téléphone, ou transmis par le QR protégé par un code PIN.
- **Référence d'essai** et **Tester la connexion** : Studio interroge la base pour cet article et affiche le résultat (**Détail de la réponse** pour les curieux). Le champ **Secret (non enregistré)** sert uniquement à ce test.
- **Réglages avancés** : délai maximal d'attente du serveur.

Une base dont l'API ne suit pas le contrat Kura demande des adresses et correspondances de requêtes (`endpoints`, `request_mapping`, `response_mapping`). Studio ne les propose pas encore : téléchargez le fichier technique et ajoutez-les à la main (voir le [guide d'intégration](integration.md) et la [référence](../reference/config.md#endpoints)), ou, plus simple, placez [Kura Bridge](bridge.md) devant la base.

### 2. Codes à lire

- **Formats acceptés** : cochez les types de codes présents sur vos étiquettes (QR Code, EAN-13, EAN-8 et Code 128 par défaut). Moins il y en a, plus la lecture est fiable.
- **Contenu du code** :
  - **Référence seule** (recommandé) : le code contient juste la référence, et la fiche vient de la base ;
  - **Champs séparés** : par exemple `REF-42;Vis M6;A12`. Indiquez les **champs, dans l'ordre**, le **champ identifiant** et le **séparateur** ;
  - **JSON** : le code contient un objet JSON.
- **Réglages avancés › Préfixe obligatoire** : si renseigné, les codes qui ne commencent pas par ce texte sont refusés (utile pour ignorer les codes des fournisseurs).

### 3. Lecture et mode rafale

- **Déclenchement** :
  - **Bouton « Scanner »** : rien n'est lu tant que l'utilisateur n'appuie pas, les codes voisins sont ignorés ;
  - **Continu** : convient à un poste fixe.
- **Les touches de volume déclenchent aussi la lecture**.
- **Réglages avancés** :
  - durée d'une tentative (secondes) ;
  - taille du cadre de visée (% de la largeur).
- **Mode rafale disponible** (chaque lecture = un mouvement, sans ouvrir la fiche), avec :
  - le **sens par défaut** (Sortie ou Entrée) ;
  - la **quantité par lecture** ;
  - **Vérifier chaque article auprès du serveur** (signale tout de suite un code inconnu, mais demande du réseau).

### 4. Fiche article

- **Informations affichées** : colonnes de la base à montrer sur la fiche, séparées par des virgules (`name, location, quantity`).
- **Boutons + / −** : jusqu'à 3 pas, par exemple `1, 10`.
- **Autoriser la saisie du stock compté (inventaire)**.
- **Afficher une image**, avec son **adresse** relative à la base (par exemple `/items/{ref}/image`).

### 5. Synchronisation et sécurité

- **Envoi** :
  - **Automatique** : dès que le réseau est là ;
  - **Manuel** : l'utilisateur appuie sur « Envoyer maintenant ».
- **Regrouper les envois** : plusieurs mouvements en une requête (plus rapide sur un réseau lent).
- **Demander l'identifiant de l'utilisateur** : l'appli demande « Qui scanne ? » et joint ce nom à chaque mouvement.
- **Verrouiller les réglages de l'appli** : saisissez un **mot de passe** et sa **confirmation** (6 caractères minimum). Il sera demandé sur le téléphone pour modifier les réglages ou charger une autre configuration. Seule son empreinte (Argon2id) est mise dans la configuration. **Retirer** supprime le verrouillage.

### QR de configuration

La colonne de droite indique si la configuration est complète (**✓ Configuration valide**) ou liste ce qui manque. Puis :

1. **Générer le QR**.
2. Optionnel : **Inclure le secret**. Le QR est alors chiffré (AES-256) par un **code PIN de 6 chiffres ou plus**. Communiquez le PIN séparément, jamais sur la même feuille que le QR.
3. **Imprimer** le QR (ou l'exporter en **SVG**), ou affichez-le à l'écran.
4. Sur chaque téléphone : ouvrez Kura Scan, appuyez sur **Scanner** et visez ce code. L'appli est prête en quelques secondes (voir le [guide de Kura Scan](scan.md#premier-lancement--relier-lappli-au-serveur)).

**Fichier technique (kura-config.json)** : alternative au QR, à copier sur le téléphone (**Réglages › Charger un fichier**) ou à déployer par la gestion de flotte. **Télécharger le fichier** l'enregistre.

Le format du QR est décrit dans [spec/config-qr.md](../../spec/config-qr.md).

## Partager : diffuser l'éditeur et les modèles

### Partager l'éditeur avec vos modèles

1. Renseignez le **nom de l'entreprise** (affiché en en-tête).
2. Cliquez sur **Télécharger l'éditeur (3 modèles)**.

Vous obtenez un nouveau fichier `kura-studio.html` qui **contient vos modèles et votre configuration** (sans aucun secret). Déposez-le sur un partage réseau ou envoyez-le : il s'ouvre sur n'importe quel poste avec les mêmes modèles.

Les données d'impression (fichiers CSV) ne sont jamais incluses. Les modifications faites ensuite sur un poste restent sur ce poste jusqu'au prochain partage.

Quand un poste ouvre un éditeur partagé, Studio **fusionne** : pour chaque modèle, c'est la version la plus récente (partagée ou locale) qui est affichée. Un modèle supprimé sur le poste qui partage disparaît aussi chez les autres, sauf s'il y a été modifié depuis. **Revenir aux modèles partagés** abandonne les modifications locales et reprend ceux du fichier.

### Sauvegarde et échange de fichiers

**Exporter les modèles (.json)** enregistre une sauvegarde ; **Importer des modèles** ajoute ceux d'un fichier `.json`. Un modèle importé remplace celui qui a le même identifiant.

## Où sont enregistrées les données ?

Tout reste **dans le navigateur du poste** (stockage local), rien n'est envoyé ailleurs :

- les modèles et la configuration ;
- les préférences (dernière planche choisie…) ;
- la liste des articles **déjà imprimés** : elle est propre à chaque poste et à chaque navigateur, et n'est pas partagée.

Conséquences :

- Vider les données du navigateur efface les modèles locaux : exportez-les régulièrement (ou partagez l'éditeur).
- Deux navigateurs sur le même poste ne partagent pas leurs données.
- Le jeton d'accès à la base et le secret de test ne sont jamais enregistrés.

## Dépannage

**« Votre base » ne charge rien, ou affiche une erreur réseau.**
Testez la connexion dans **Appli mobile**. Si le test échoue alors que l'adresse s'ouvre dans le navigateur, la base n'autorise probablement pas les appels depuis une page web (CORS) : voir [Kura Bridge](bridge.md), ou passez par un export CSV.

**L'impression est décalée sur la planche.**
Imprimez à **taille réelle** (100 %), sans « ajuster à la page ». Faites un essai avec **Tracer le contour** sur papier blanc, superposez à une planche à contre-jour, puis corrigez dans **Réglages fins de la planche**.

**Le téléphone ne lit pas les codes imprimés.**
Studio a peut-être signalé « code trop petit » : agrandissez le code, ou passez de QR Code à Data Matrix pour les petites étiquettes. Évitez les imprimantes à jet d'encre sur papier glacé. Vérifiez que le type de code fait partie des **formats acceptés** de la configuration.

**Des étiquettes manquent dans le PDF.**
Elles ont un champ obligatoire vide (voir le message de l'étape 1), ou leur code est impossible à générer (étape 3).

**Les accents sont mal affichés après l'import CSV.**
Réenregistrez le fichier en « CSV UTF-8 » depuis Excel ou LibreOffice.
