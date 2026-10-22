# Kura Scan (Android)

Appli de scan : lit un code QR ou 1D, affiche la fiche, envoie des mouvements au serveur de l'entreprise, même hors réseau.

README développeur. Guide d'utilisation : [docs/guides/scan.md](../../docs/guides/scan.md). Options de configuration : [docs/reference/config.md](../../docs/reference/config.md).

## Construire

Prérequis :

- **JDK 17 à 21** : le wrapper Gradle (8.14.3) ne fonctionne pas avec un JDK plus récent. Si le JDK par défaut est trop récent, pointez `JAVA_HOME` (ou `org.gradle.java.home` dans `~/.gradle/gradle.properties`) vers un JDK 17 ou 21 ;
- pour `:app` : SDK Android avec la plateforme **35** et les build-tools correspondants (AGP 8.7.3).

```bash
# Logique pure Kotlin, sans SDK Android (CI, poste sans Android Studio)
./gradlew :core:test

# Appli Android : nécessite un SDK (Android Studio, ou ANDROID_HOME / local.properties sdk.dir)
./gradlew :app:assembleDebug        # APK de debug : app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # R8 + réduction des ressources (signature à configurer)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Le module `:app` n'est inclus que si un SDK Android est détecté (`local.properties` → `sdk.dir=/chemin/vers/Android/Sdk`, ou `ANDROID_HOME` / `ANDROID_SDK_ROOT`). Sans SDK, Gradle l'ignore (message « module :app ignoré ») et le plugin Android n'est même pas téléchargé : `:core` se construit partout.

Android Studio : ouvrir le dossier `apps/scan-android/` (il crée `local.properties` automatiquement).

Essai de bout en bout avec le serveur de démo (voir aussi le [démarrage rapide](../../docs/quickstart.md)) :

```bash
cd ../../tools/mock-server
KURA_MOCK_DB=.data/db.json KURA_MOCK_ITEMS=demo/items.demo.json node server.js   # http://0.0.0.0:8787
adb reverse tcp:8787 tcp:8787        # le téléphone (ou l'émulateur) joint le PC sur 127.0.0.1:8787
# Charger tools/mock-server/demo/kura-config.demo.json (ou le QR généré par Studio),
# accepter l'avertissement « connexion non chiffrée », scanner REF-0042.
```

Sans `adb reverse` : `http://10.0.2.2:8787` depuis l'émulateur ([`kura-config.minimal.json`](../../spec/examples/kura-config.minimal.json)), ou l'IP du PC depuis un téléphone sur le même Wi-Fi.

## Organisation

```
scan-android/
├── settings.gradle.kts      # :core toujours, :app seulement si SDK Android
├── gradle/libs.versions.toml
├── core/                    # Kotlin/JVM pur — testé sur JVM (JUnit 5)
│   └── src/main/kotlin/dev/kura/core/
│       ├── config/          # KuraConfig (miroir du schéma), validation FR, QR de config, signature Ed25519
│       ├── scan/            # Symbologies, ScanParser (id/delimited/json), ScanTrigger (ADR 0006), HID, filtre « même code »
│       ├── template/        # Gabarits {var} (chemins + request_mapping), JsonPath, fiche (response_mapping)
│       ├── ops/             # Operation, validation (= mock server), fabrique UUID v4, PendingStepper
│       ├── sync/            # Statuts → actions de file, backoff, lots, SyncEngine, OperationStore
│       ├── burst/           # Session rafale : lignes, annuler, corriger (ADR 0004)
│       ├── net/             # Construction des requêtes (auth, URL, corps), KuraClient, interface HttpTransport
│       ├── security/        # Argon2id PHC (Bouncy Castle), enveloppe de secret AES-GCM
│       └── util/            # Base64 (java.util.Base64 absent avant Android 8)
└── app/                     # Android : Compose, CameraX + ZXing, Room, WorkManager, OkHttp, Coil
    └── src/main/java/dev/kura/scan/
        ├── MainActivity.kt  # activité unique ; touches de volume et frappes HID
        ├── AppContainer.kt  # injection manuelle, paresseuse
        ├── data/            # Réglages, SecretStore (AndroidKeyStore), verrou, Room (file + cache fiches)
        ├── net/             # OkHttpTransport (implémente HttpTransport de :core)
        ├── sync/            # SyncWorker + SyncScheduler (WorkManager, contrainte réseau)
        ├── scan/            # ZxingAnalyzer (recadrage, rotation), CameraPreview, retour vibration/son
        └── ui/              # Compose Material 3 : Scan, Fiche, Rafale, File d'attente, Réglages, import de config
```

Le format du QR de configuration est spécifié dans [`spec/config-qr.md`](../../spec/config-qr.md) (avec vecteurs de test partagés avec Kura Studio).

## Choix techniques

| Besoin | Choix |
|---|---|
| Langage / UI | Kotlin 2.0, Jetpack Compose (BOM 2024.12), Material 3, couleurs dynamiques (Android 12+) sinon palette Kura |
| Caméra | CameraX 1.4 (`ImageAnalysis` YUV, 1280×720 visé, repli vers plus bas) |
| Décodage | `com.google.zxing:core` 3.5 ([ADR 0002](../../docs/adr/0002-zxing-qr-et-1d.md)) |
| File offline / cache | Room 2.6 (KSP) |
| Synchro | WorkManager 2.10, contrainte réseau uniquement |
| Réseau | OkHttp 4.12 + kotlinx.serialization |
| Images | Coil 2.7 (+ SVG pour le serveur de démo), seulement si `display.show_image` |
| Secrets | Clé AES-GCM dans l'AndroidKeyStore + SharedPreferences (pas d'EncryptedSharedPreferences, déprécié) |
| Mot de passe de verrouillage | Argon2id au format PHC, vérifié avec Bouncy Castle (pur Java) |
| Config distante | Signature Ed25519 vérifiée avec Bouncy Castle (`RemoteConfigVerifier`) |

Aucune dépendance aux Google Play Services ni à ML Kit, aucune bibliothèque native, aucune télémétrie : publiable sur F-Droid.

## Compatibilité

- **minSdk 23 (Android 6.0)**, targetSdk/compileSdk 35. Toutes les bibliothèques retenues acceptent minSdk 21.
- `java.time` (horodatages) via le désucrage (`coreLibraryDesugaring`). `java.util.Base64` (API 26) n'est pas utilisé : `:core` a son propre Base64.
- API récentes protégées par `Build.VERSION.SDK_INT` : vibreur (`VibrationEffect` 26+, `VibratorManager` 31+), couleurs dynamiques (31+).
- Caméra : arrière, sinon avant ; sans caméra, l'appli reste utilisable avec un scanner HID. Lampe affichée seulement si `hasFlashUnit`. Mise au point au toucher seulement si l'optique la gère (les caméras à focale fixe ne plantent pas).
- Analyse : `STRATEGY_KEEP_ONLY_LATEST`, un seul fil de décodage, rien n'est décodé hors tentative, recadrage sur la zone de visée avant ZXing, tampons réutilisés, `TRY_HARDER` limité à la zone recadrée, `POSSIBLE_FORMATS` = `scan.symbologies`.
- Légèreté : pas d'écran de démarrage, rien d'initialisé au lancement (WorkManager et Coil à la demande), pas de `largeHeap`, cache mémoire d'images à 10 %, cache disque 32 Mo, cache des fiches borné à 2 000 articles, pas d'animations décoratives.
- Petits écrans : mises en page prévues pour 320 dp de large et une police à 130 % (boutons de pas sur deux rangées, bouton « Scanner » de 72 dp toujours en bas). Tablette / paysage (largeur ≥ 600 dp) : fiche ou session rafale à côté de la caméra, rail de navigation.
- Accessibilité : descriptions TalkBack, résultats annoncés (`liveRegion`), cibles ≥ 48 dp, titres sémantiques.
- Taille estimée de l'APK release (R8, pas encore mesurée ; l'APK de debug, sans réduction, fait ≈ 18 Mo) : **5 à 7 Mo**. Ordre de grandeur après réduction : Compose + Material 3 ≈ 3 Mo, CameraX ≈ 0,6 Mo, OkHttp/Okio ≈ 0,5 Mo, WorkManager ≈ 0,4 Mo, Coil + SVG ≈ 0,4 Mo, ZXing ≈ 0,3 Mo, Room ≈ 0,3 Mo, kotlinx (serialization, coroutines) ≈ 0,4 Mo, Bouncy Castle (seulement Argon2/Ed25519 gardés) ≈ 0,1 Mo.

Limites connues sur l'entrée de gamme :
- ZXing est plus lent que ML Kit sur les codes abîmés ; sur caméra à focale fixe, les petits codes 1D doivent être tenus à 15–25 cm. Le délai `scan.trigger.timeout_ms` peut être allongé.
- La vérification Argon2id (m = 19 456 Kio) prend ≈ 0,2 à 1 s et 19 Mo de RAM ; un hash plus lourd (ex. m = 65 536 de l'exemple complet) peut prendre plusieurs secondes. Les hashs au-delà de 256 Mio sont refusés.
- Le déchiffrement d'un QR `KURA1E:` (PBKDF2, 120 000 itérations) prend ≈ 0,5 à 2 s.
- Certains keystores constructeur sont défaillants : le secret n'est alors **pas** enregistré (message explicite), jamais stocké en clair.
- Le cadre de visée est proportionnel à la largeur de l'image analysée ; avec le recadrage `FILL_CENTER` de l'aperçu, il peut être légèrement décalé par rapport à la zone réellement lue sur des capteurs au format inhabituel.

## Comportements clés

- **Lecture à l'appui** (ADR 0006) : rien n'est décodé avant « Scanner » ou une touche de volume ; arrêt au premier code ; nouvel appui = annulation ; délai → « Aucun code lu » + double vibration. Mode `continuous` par config.
- **HID** : frappes rapides (≤ 50 ms entre touches) terminées par Entrée/Tab = une lecture ; une frappe humaine est ignorée. Fonctionne avec les gâchettes intégrées des téléphones durcis.
- **Fiche** : `GET /items/{ref}` (ou `endpoints.get_item`), hors-ligne → dernière fiche en cache ; plusieurs +/− = **un seul** delta ; quantité saisie = `set`.
- **Rafale** : un delta `±step` par lecture, même `session_id`, lignes regroupées, annuler (retrait si non envoyé, sinon inverse), correction d'une ligne.
- **File** : Room + WorkManager ; `applied`/`duplicate`/`superseded` retirées, `rejected` gardées en erreur, réseau/5xx → nouvel essai selon `sync.retry_backoff_s` ; 401/403 → arrêt et message.
- **HTTP en clair** : bloqué par l'appli tant que l'utilisateur n'a pas confirmé l'avertissement pour ce serveur (y compris pour les images).
- **Import de config** : fichier JSON ou QR (`KURA1:` / `KURA1E:` + PIN) ; validation avec messages en français ; confirmation (adresse du serveur affichée) ; mot de passe exigé si la config courante est verrouillée.

## État

v1.0 : `:core` testé (`./gradlew :core:test`, dont un test de bout en bout contre `tools/mock-server` lancé avec `node`, ignoré si `node` est absent) ; `:app` compilé et essayé sur appareil contre le serveur de démo (import par QR, fiche, inventaire, rafale, file d'attente hors-ligne).

- [x] Lecture + validation de `kura-config.json` (messages clairs), QR de config clair et chiffré
- [x] Parsing des codes `id` / `delimited` / `json`, préfixe, symbologies
- [x] Machine à états du déclenchement, saisie HID, filtre « même code »
- [x] Gabarits d'URL et de corps, `response_mapping`, fiche affichable
- [x] Opérations, validation identique au serveur, accumulation +/− avant Valider
- [x] Politique de synchro, backoff, lots, moteur de synchro ; session rafale
- [x] Argon2id (vecteurs de libargon2), enveloppe de secret, signature Ed25519

Non implémenté (options acceptées par la config mais sans effet, signalées dans la [référence](../../docs/reference/config.md)) :

- Secret fourni par MDM (`token_ref: "mdm"`, *managed configurations*) : message affiché seulement.
- Config distante (`remote_config`) : vérification de signature prête dans `:core`, récupération non branchée dans l'appli.

À faire ensuite :

- Tests instrumentés Android (Compose UI, Room, analyseur ZXing sur images de référence).
- Mesurer la taille réelle de l'APK release.
- Traductions (seul le français est livré).
