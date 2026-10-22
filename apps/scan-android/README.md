# Kura Scan (Android)

Appli de scan : lit un code QR ou 1D, affiche la fiche, envoie des mouvements au serveur de l'entreprise.

> 🚧 Pas encore de code. Ce document fixe le périmètre du premier jalon (v0.1).

## Stack

| Besoin | Choix |
|---|---|
| Langage / UI | Kotlin, Jetpack Compose (Material 3) |
| Caméra | CameraX (`ImageAnalysis`, 1280×720) |
| Décodage | `com.google.zxing:core` ([ADR 0002](../../docs/adr/0002-zxing-qr-et-1d.md)) |
| File offline / cache | Room |
| Synchro | WorkManager |
| Réseau | OkHttp + kotlinx.serialization |
| Images | Coil (cache disque) |
| Secrets | Android Keystore |
| minSdk / targetSdk | 23 / dernière stable ([ADR 0007](../../docs/adr/0007-compatibilite-android.md)) |

Aucune dépendance aux Google Play Services : l'appli doit pouvoir être publiée sur F-Droid.

## Organisation prévue

```
scan-android/
├── app/                 # UI Compose, navigation, injection
├── core/config/         # Lecture + validation de kura-config.json, QR de config
├── core/scan/           # Analyseur CameraX → ZXing, parsing id/delimited/json, saisie HID
├── core/sync/           # Opérations, file Room, WorkManager, mapping requête/réponse
└── core/net/            # Client HTTP, authentification
```

`core/config`, le parsing des codes et le moteur de mapping sont du Kotlin pur, testables sur JVM sans émulateur.

## v0.1 : critères d'acceptation

- [ ] Charger une config depuis un fichier ou un QR de config ; refuser une config invalide avec un message clair.
- [ ] Scanner QR, EAN-13, EAN-8, Code 128 (liste réglable par `scan.symbologies`).
- [ ] Mode `id` : `GET /items/{ref}` et affichage des champs `display.show_fields`.
- [ ] Boutons de pas, champ quantité, Valider → une opération `delta` (somme des appuis) ou `set`.
- [ ] Envoi `POST /operations` (ou endpoint + `request_mapping` de la config) et affichage du résultat.
- [ ] Fonctionne de bout en bout contre `tools/mock-server`.
