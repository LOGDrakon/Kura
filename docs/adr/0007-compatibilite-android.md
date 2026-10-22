# 0007 : Compatibilité maximale, y compris les appareils d'entrée de gamme

**Statut :** acceptée (2026-10-22)

## Contexte
Kura doit remplacer des terminaux coûteux : l'intérêt est de pouvoir utiliser **n'importe quel smartphone Android**, y compris des modèles bon marché (Android Go, 1 à 2 Go de RAM, caméra à mise au point fixe, versions d'Android anciennes) et des téléphones durcis d'entrée de gamme.

## Décision
- **minSdk 23** (Android 6.0, 2015) : couvre la quasi-totalité du parc encore en service. Les API plus récentes ne sont utilisées que derrière un test de version, avec un repli.
- **Appli simple et légère** : peu de dépendances, aucune bibliothèque native, pas de Google Play Services, R8 et réduction des ressources en version de production.
- **Caméra peu exigeante** : analyse en 1280×720 avec repli en 640×480, fonctionnement sans autofocus ni flash (bouton lampe masqué s'il n'y a pas de flash), décodage seulement après appui sur « Scanner » et sur la zone de visée recadrée, pour économiser processeur et batterie.
- **Secrets** : clé AES-GCM dans l'Android Keystore + préférences chiffrées par l'appli, au lieu d'EncryptedSharedPreferences (obsolète et instable sur certains appareils).
- **Arrière-plan** : synchro WorkManager avec la seule contrainte « réseau disponible » ; pas de service au premier plan ni d'alarme exacte.
- **Petits écrans** : interface utilisable à 320 dp de large et avec un texte agrandi à 130 %.
- **Scanners intégrés** : la touche de scan des téléphones durcis (qui émet des frappes clavier) est prise en charge comme un scanner HID.

## Conséquences
- Pas d'API Android récentes « confortables » sans repli : un peu plus de code de compatibilité.
- Les tests réels devront inclure au moins un appareil Android Go et un appareil Android 6/7.
