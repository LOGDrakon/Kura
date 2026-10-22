# Guide de Kura Scan (Android)

Kura Scan transforme un téléphone Android en terminal de stock : on vise le code d'un article, on voit sa fiche, on indique une entrée, une sortie ou la quantité comptée, et le mouvement part vers la base de l'entreprise. Hors réseau, les mouvements attendent dans le téléphone et partent dès que la connexion revient.

Ce guide s'adresse aux personnes qui utilisent l'appli au quotidien et à celles qui la déploient. Pour préparer la configuration, voir le [guide de Kura Studio](studio.md#appli-mobile--configurer-kura-scan). Pour un premier essai complet, voir le [démarrage rapide](../quickstart.md).

- [Installer l'appli](#installer-lappli)
- [Premier lancement : relier l'appli au serveur](#premier-lancement--relier-lappli-au-serveur)
- [L'écran Scan](#lécran-scan)
- [Mode Fiche : un article à la fois](#mode-fiche--un-article-à-la-fois)
- [Mode Rafale : une lecture = un mouvement](#mode-rafale--une-lecture--un-mouvement)
- [Hors-ligne et file d'attente](#hors-ligne-et-file-dattente)
- [Réglages](#réglages)
- [Connexion non chiffrée (HTTP)](#connexion-non-chiffrée-http)
- [Dépannage](#dépannage)
- [Conseils pour les téléphones d'entrée de gamme](#conseils-pour-les-téléphones-dentrée-de-gamme)

## Installer l'appli

Kura Scan fonctionne sur **Android 6.0 ou plus récent**, sans les services Google. Il n'est pas (encore) sur le Play Store : on installe directement le fichier APK.

1. Copiez le fichier `kura-scan-….apk` sur le téléphone (câble USB, mail, partage réseau), ou ouvrez son lien de téléchargement depuis le téléphone.
2. Ouvrez le fichier. Android demande d'autoriser l'installation d'applis depuis cette source (« Sources inconnues » ou « Installer des applis inconnues », selon la version) : autorisez-la pour l'appli qui ouvre le fichier (navigateur, gestionnaire de fichiers…).
3. Appuyez sur **Installer**. Vous pouvez ensuite retirer l'autorisation donnée à l'étape 2.

Pour un parc de téléphones, la gestion de flotte (MDM) peut installer l'APK et déposer le fichier de configuration sans intervention.

Développeurs : `adb install app-debug.apk` depuis un PC (voir [apps/scan-android](../../apps/scan-android/README.md) pour la construire).

Kura Scan n'envoie **aucune télémétrie** : il ne communique qu'avec le serveur indiqué dans sa configuration.

## Premier lancement : relier l'appli au serveur

Au premier lancement, l'écran **Bienvenue dans Kura Scan** demande une configuration. Elle indique l'adresse du serveur, les types de codes à lire, ce qu'affiche la fiche, etc. Elle est préparée une fois pour toutes dans Kura Studio (onglet **Appli mobile**) par la personne qui gère l'outil.

Trois façons de la charger :

- **Scanner le QR de configuration** (le plus simple) : appuyez sur **Scanner** et visez le QR affiché ou imprimé par Kura Studio. L'appli demande d'abord l'**accès à la caméra** : elle ne l'utilise que pour lire les codes visés, aucune image n'est enregistrée ni envoyée.
- **Charger un fichier de configuration** : choisissez le fichier `kura-config.json` copié sur le téléphone.
- **Scanner Bluetooth ou gâchette intégrée** : sur un téléphone durci, la gâchette peut lire le QR directement.

L'appli affiche alors **Nouvelle configuration**, avec le nom de l'entreprise et l'adresse du serveur. Vérifiez-les, puis appuyez sur **Appliquer**.

### QR protégé par un code PIN

Le QR peut contenir le secret d'accès au serveur (jeton, mot de passe ou clé d'API). Dans ce cas il est chiffré, et l'appli affiche **QR protégé** : saisissez le **code PIN** (6 chiffres ou plus) que votre responsable vous a communiqué séparément. Le message « Le secret d'authentification est inclus et sera stocké chiffré » confirme que vous n'aurez rien d'autre à saisir.

Un QR sans secret n'a pas de PIN. Si le serveur demande un secret, saisissez-le ensuite dans **Réglages › Authentification**.

### Configuration refusée

Si le fichier ou le QR est invalide, l'appli affiche **Configuration refusée** avec la liste des problèmes, en français (champ manquant, valeur hors limites…). Rien n'est modifié : l'ancienne configuration reste active. Corrigez la configuration dans Kura Studio et générez un nouveau QR.

Certaines remarques ne bloquent pas l'import (serveur en HTTP, variable inconnue dans une adresse…) : elles sont consultables dans **Réglages › Avertissements de la configuration**.

### Qui scanne ?

Si la configuration l'exige, l'appli demande **Qui scanne ?** : saisissez votre identifiant (nom, matricule…). Il est envoyé avec chaque mouvement, pour savoir qui a fait quoi. On le change dans **Réglages › Identité › Utilisateur**.

## L'écran Scan

L'appli a trois onglets : **Scan**, **File d'attente** et **Réglages**.

En haut de l'écran Scan :

- le choix du mode, **Fiche** ou **Rafale** ;
- en mode Rafale, le sens : **Entrée** ou **Sortie** ;
- l'état de l'envoi : **À jour** quand tout est parti, ou **3 en attente** quand des mouvements n'ont pas encore été envoyés (appuyez dessus pour ouvrir la file d'attente).

Au centre, l'aperçu de la caméra avec le **cadre de visée**, et un bouton pour **allumer la lampe** si le téléphone en a une.

En bas, le grand bouton **Scanner**.

### Pourquoi faut-il appuyer sur « Scanner » ?

Dans un magasin, les étiquettes sont serrées les unes contre les autres. Si l'appli lisait en permanence, elle attraperait le code voisin. C'est pourquoi **rien n'est lu tant que vous n'appuyez pas** ([décision ADR 0006](../adr/0006-scan-sur-appui.md)) :

1. Appuyez sur **Scanner** : le bouton devient **Lecture… (appuyer pour annuler)**.
2. Visez le code dans le cadre. Dès qu'un code est lu, l'appli vibre et s'arrête.
3. Si aucun code n'est lu au bout de quelques secondes (6 s par défaut), elle affiche **Aucun code lu** et vibre deux fois. Réappuyez.

Appuyer de nouveau pendant la lecture l'annule.

- **Touches de volume** : par défaut, les touches de volume déclenchent aussi la lecture. Pratique avec des gants ou d'une seule main.
- **Lecture continue** : pour un poste fixe (articles présentés un par un sous le téléphone), la configuration peut activer la lecture continue. Le bouton affiche alors **Lecture continue** et l'appli lit sans appui.
- **Mise au point** : touchez l'aperçu pour faire la mise au point, si l'objectif le permet.

### Scanner Bluetooth et gâchette intégrée

Un scanner Bluetooth (ou la gâchette d'un téléphone durci) se comporte comme un clavier qui tape très vite le code puis « Entrée ». Kura Scan le reconnaît automatiquement, sur l'écran Scan, sans réglage : une lecture du scanner vaut un appui sur Scanner suivi d'une lecture réussie. Ce que vous tapez vous-même au clavier est ignoré.

Sans caméra (ou si vous l'avez refusée), l'appli reste utilisable avec un scanner : elle l'indique à l'écran.

## Mode Fiche : un article à la fois

> « Un article à la fois : vous voyez sa fiche et ajustez le stock. »

C'est le mode par défaut. Après chaque lecture, la **fiche de l'article** s'ouvre : désignation, emplacement, stock… (les informations affichées dépendent de la configuration), et éventuellement une photo.

### Enregistrer une entrée ou une sortie

1. Utilisez les boutons **+** et **−** (par 1, ou par d'autres pas comme 10 selon la configuration). Le mouvement en préparation s'affiche, par exemple « −3 ».
2. Appuyez sur **Valider −3**.

Plusieurs appuis sur + ou − ne donnent **qu'un seul mouvement** : appuyer trois fois sur −1 envoie « −3 ». Le bouton **✕** (Effacer le mouvement) remet le compteur à zéro sans rien envoyer.

### Faire un inventaire

Le bouton **Inventaire** ouvre le champ **Quantité comptée** : saisissez la quantité réellement présente sur l'étagère, puis **Valider**. Elle **remplace** le stock enregistré (au lieu de s'y ajouter).

Si deux personnes comptent le même article, c'est le comptage **le plus récent** (heure du téléphone) qui l'emporte. Si un comptage plus récent est déjà enregistré, l'appli affiche « Une correction plus récente est déjà enregistrée » ([ADR 0005](../adr/0005-resolution-conflits.md)). Les entrées et sorties, elles, s'additionnent toujours.

L'inventaire peut être désactivé par la configuration ; le bouton n'apparaît alors pas.

### Résultat

Après **Valider**, la fiche affiche l'un de ces messages :

| Message | Signification |
|---|---|
| Enregistré · stock 247 | Le serveur a appliqué le mouvement ; nouveau stock. |
| En file d'attente : sera envoyé dès que possible | Pas de réseau (ou serveur injoignable) : le mouvement est gardé et sera envoyé plus tard. |
| Refusé par le serveur : … | Le serveur a refusé le mouvement ; il reste visible dans la file d'attente, en erreur. |
| Une correction plus récente est déjà enregistrée | Inventaire ignoré, un comptage plus récent existe déjà. |

Appuyez ensuite sur **Scanner l'article suivant** (ou directement sur Scanner).

### Hors-ligne

Sans réseau, la fiche affiche **Hors-ligne : dernières infos connues** si l'article a déjà été consulté sur ce téléphone. Sinon : « Hors-ligne : aucune info en cache pour cet article. Le mouvement sera envoyé plus tard. » Vous pouvez quand même saisir le mouvement.

### Article inconnu

« Article inconnu sur le serveur : REF-… » signifie que le serveur ne connaît pas cette référence. Vérifiez l'étiquette, ou faites créer l'article dans la base.

## Mode Rafale : une lecture = un mouvement

> « Chaque code lu compte directement −1, sans ouvrir de fiche. »

Le mode Rafale sert à enchaîner les lectures sans toucher l'écran : sortie d'un lot de pièces, réception d'une livraison, préparation de commande.

1. Choisissez **Rafale**, puis **Sortie** ou **Entrée**.
2. Scannez les articles les uns après les autres. Chaque lecture enregistre immédiatement un mouvement (−1 ou +1 par défaut ; la quantité par lecture se règle dans la configuration).
3. La **Session rafale** liste les articles lus, regroupés : « REF-0042 · −5 ».

Le même code relu dans la seconde et demie qui suit est ignoré, pour éviter les doubles lectures involontaires.

Corriger une erreur :

- **Annuler la dernière lecture** : retire la dernière lecture. Si elle était déjà partie vers le serveur, l'appli envoie un mouvement inverse (« Dernière lecture déjà envoyée : mouvement inverse ajouté »).
- **Corriger la quantité** d'une ligne : touchez-la et saisissez le total voulu pour cet article (négatif = sortie). L'appli envoie la différence.
- **Nouvelle session** : repart d'une liste vide (les mouvements déjà enregistrés ne sont pas annulés).

Selon la configuration, chaque article peut être vérifié auprès du serveur ; un code inconnu affiche alors « Article inconnu : … ».

## Hors-ligne et file d'attente

Kura Scan est conçu pour les zones mal couvertes (sous-sol, chambre froide, entrepôt métallique). Chaque mouvement est d'abord **enregistré dans le téléphone**, puis envoyé. Rien n'est perdu si le réseau coupe, si l'appli est fermée ou si le téléphone redémarre.

L'onglet **File d'attente** montre les mouvements pas encore envoyés :

- **En attente** : sera envoyé automatiquement dès que le réseau revient (nouvel essai après 5 s, 30 s, puis toutes les 2 min par défaut).
- **En erreur** : le serveur a refusé le mouvement (opération invalide, article inconnu, action interdite, conflit, erreur serveur). Il ne sera pas renvoyé tout seul.

Actions :

- **Envoyer maintenant** : force l'envoi sans attendre.
- **Relancer** : renvoie un mouvement en erreur (après correction côté serveur, par exemple).
- **Supprimer** : abandonne le mouvement. L'appli demande confirmation : « Le mouvement ne sera jamais envoyé. »

Quand tout est parti, l'écran affiche **Tout est envoyé**.

Si la configuration est en **envoi manuel**, rien ne part tout seul : l'appli rappelle « Envoi manuel (config) : appuyez sur « Envoyer maintenant » ».

Un même mouvement n'est **jamais compté deux fois**, même s'il est renvoyé après une coupure : chaque mouvement porte un identifiant unique que le serveur reconnaît.

Si le serveur refuse l'accès (« Authentification refusée par le serveur. Vérifiez le secret dans les Réglages. »), les envois s'arrêtent jusqu'à ce que le secret soit corrigé.

## Réglages

| Section | Contenu |
|---|---|
| **Serveur** | Entreprise et adresse du serveur (issues de la configuration). |
| **Authentification** | Type d'accès : Aucune, Jeton d'accès, Mot de passe (utilisateur …) ou Clé API (en-tête …). Champ pour saisir le secret. « Secret enregistré de façon chiffrée » confirme qu'il est en place ; **Effacer le secret** le retire. |
| **Identité** | **Identifiant de l'appareil** (créé automatiquement, envoyé avec chaque mouvement) et **Utilisateur**. |
| **Configuration** | **Charger un fichier**, **Scanner un QR de configuration**, **Avertissements de la configuration**. |
| **Verrouillage** | Protéger les réglages par un mot de passe (voir ci-dessous). |

Le bas de l'écran rappelle la version : « Kura Scan 1.0.0 · aucune télémétrie ».

Le secret (jeton, mot de passe, clé) est stocké chiffré par le coffre-fort matériel du téléphone. Il n'est envoyé qu'au serveur de la configuration, jamais aux autres adresses (images hébergées ailleurs, par exemple). Sur les rares téléphones dont le coffre-fort est défaillant, l'appli refuse de l'enregistrer plutôt que de le garder en clair.

### Verrouiller les réglages

Sur un téléphone partagé, on peut empêcher les changements de configuration :

- **Depuis la configuration** : Kura Studio peut définir un mot de passe (« Mot de passe défini par la configuration. »). Le QR de configuration ne contient que son empreinte, jamais le mot de passe lui-même.
- **Sur le téléphone** : **Définir un mot de passe de verrouillage** (8 caractères minimum).

Les réglages s'affichent alors comme **Réglages verrouillés** : appuyez sur **Déverrouiller** et saisissez le mot de passe. **Verrouiller maintenant** les referme ; **Retirer le verrouillage** supprime le mot de passe local.

Charger une nouvelle configuration demande aussi le mot de passe : « La configuration est verrouillée : saisissez le mot de passe pour la remplacer. »

## Connexion non chiffrée (HTTP)

Quand l'adresse du serveur commence par `http://` (et non `https://`), les données et le secret circulent en clair sur le réseau. Au premier échange avec ce serveur, l'appli affiche **Connexion non chiffrée** et ne contacte pas le serveur tant que vous n'avez pas appuyé sur **Je comprends, continuer**.

C'est normal pour un essai sur un réseau local (serveur de démonstration, poste de test). En production, demandez une adresse `https://` : voir [Kura Bridge](bridge.md) pour exposer une base existante en HTTPS.

## Dépannage

**L'appli dit « Aucun code lu » à chaque fois.**
Rapprochez ou éloignez le téléphone (15 à 25 cm pour un petit code-barres), allumez la lampe, nettoyez l'objectif. Vérifiez que le type de code fait partie des **formats acceptés** de la configuration : un code d'un autre type est ignoré. Si un préfixe obligatoire est configuré, les codes sans ce préfixe sont refusés.

**Le serveur est injoignable (tout part en file d'attente).**
- Le téléphone et le serveur sont-ils sur le même réseau ? Ouvrez l'adresse du serveur dans le navigateur du téléphone.
- `http://localhost` ou `127.0.0.1` désigne le téléphone lui-même, pas le PC. Utilisez l'adresse IP du PC (ex. `http://192.168.1.20:8787`), ou `adb reverse` (voir le [démarrage rapide](../quickstart.md#4-relier-le-téléphone-au-pc)).
- Un pare-feu sur le PC peut bloquer le port.

**« Authentification refusée par le serveur ».**
Le secret est absent ou faux : **Réglages › Authentification**, saisissez-le de nouveau (ou rescannez un QR protégé par PIN).

**« Code PIN incorrect ».**
Le PIN ne correspond pas à ce QR. Chaque génération de QR peut avoir son propre PIN : demandez celui qui va avec ce QR.

**« Article inconnu sur le serveur ».**
La référence lue n'existe pas dans la base. Si l'étiquette contient plusieurs informations (référence, désignation…), vérifiez que le **contenu du code** de la configuration correspond à celui des étiquettes.

**Les photos ne s'affichent pas.**
Elles ne sont affichées que si la configuration l'active. Hors-ligne, seules les photos déjà vues sont disponibles.

**Un mouvement reste « En erreur ».**
Le serveur l'a refusé et donne la raison. Corrigez la cause (article créé, droits accordés…), puis **Relancer** ; sinon **Supprimer**.

**J'ai changé de téléphone : mes mouvements en attente ?**
Ils sont dans l'ancien téléphone. Rallumez-le avec du réseau et attendez « À jour » (ou **Envoyer maintenant**) avant de le réinitialiser.

## Conseils pour les téléphones d'entrée de gamme

Kura Scan est prévu pour tourner sur des téléphones modestes (Android 6, 2 Go de mémoire, caméra sans mise au point automatique) :

- **Caméra à focale fixe** : tenez les petits codes 1D à 15–25 cm ; préférez des QR codes un peu plus grands sur les étiquettes (voir [Kura Studio](studio.md)).
- **Délai de lecture** : si la lecture est lente, allongez la durée d'une tentative dans la configuration (Studio › Appli mobile › Lecture et mode rafale › Réglages avancés).
- **Photos** : désactivez l'affichage des images si le réseau est lent.
- **Moins de formats** : limitez les formats acceptés à ceux réellement présents sur les étiquettes ; la lecture est plus rapide et plus fiable.
- **QR protégé** et **mot de passe de verrouillage** : leur vérification peut prendre une à deux secondes, c'est normal.
- **Petits écrans et grands textes** : l'appli reste utilisable sur 320 dp de large avec une police agrandie ; sur tablette ou en paysage, la fiche s'affiche à côté de la caméra.
