# 0002 : Lecture par ZXing, QR et codes-barres 1D

**Statut :** acceptée (2026-10-22)

## Contexte
Deux moteurs possibles sur Android : ML Kit (Google, très rapide, mais dépend des Google Play Services) et ZXing (open source, autonome). Beaucoup d'articles portent déjà un code-barres fabricant (EAN-13) ou un Code 128 interne.

## Décision
- Moteur : **ZXing** (`com.google.zxing:core`) branché sur un analyseur **CameraX**.
- Formats acceptés : QR Code, Data Matrix, EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, ITF.
- La liste active est réglable par config (`scan.symbologies`). Par défaut : QR, EAN-13, EAN-8, Code 128.

## Raisons
- **Aucune dépendance Google** : publication possible sur F-Droid, fonctionne sur les terminaux durcis/sans GMS (fréquents en industrie).
- Un seul moteur à maintenir pour toutes les distributions (F-Droid, Play Store, APK direct).
- Lire les codes 1D permet d'utiliser les étiquettes **déjà présentes** sans en réimprimer.

## Conséquences
- ZXing est un peu moins rapide que ML Kit sur les codes abîmés ou en angle : on compense par un cadrage visuel, la lampe, l'autofocus continu et une résolution d'analyse de 1280×720.
- Restreindre `symbologies` réduit les faux positifs (un EAN lu par erreur sur un emballage voisin).
- Les **scanners Bluetooth/HID** restent supportés en parallèle (saisie clavier), indépendamment du moteur caméra.
