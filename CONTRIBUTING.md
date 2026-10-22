# Contribuer à Kura

Merci de votre intérêt ! Kura est un projet libre (Apache 2.0) ; toutes les contributions sont bienvenues : retours d'usage, recettes pour d'autres logiciels, corrections, traductions, code.

## Signaler un problème ou proposer une idée

Ouvrez une [issue](https://github.com/LOGDrakon/Kura/issues) en précisant :

- le composant (Kura Scan, Kura Studio, Kura Bridge, serveur de démonstration) et sa version ;
- pour Kura Scan : le modèle du téléphone et la version d'Android ; la config utilisée (**sans les secrets**) ;
- ce que vous avez fait, ce que vous attendiez, ce qui s'est passé.

Pour une faille de sécurité, n'ouvrez pas d'issue publique : voir [SECURITY.md](SECURITY.md).

## Partager une recette

Vous avez branché Kura sur un logiciel du marché (Odoo, Dolibarr, Sage, une GMAO…) ? Une recette aide les suivants. Ajoutez un fichier dans [`docs/recipes/`](docs/recipes/README.md) sur le modèle des recettes existantes : versions concernées, droits nécessaires, `kura-bridge.yaml` ou script, pièges rencontrés. Retirez toute donnée de l'entreprise (noms, adresses, jetons).

## Proposer du code

1. Pour un changement important, ouvrez d'abord une issue pour en discuter : les grandes décisions sont tracées dans les [ADR](docs/adr/README.md).
2. Créez une branche, faites des commits courts et explicites (en français).
3. Ajoutez ou adaptez les tests, et vérifiez que tout passe :

| Composant | Dossier | Commandes |
|---|---|---|
| Contrat et schéma | `spec/` | `python spec/validate.py` |
| Serveur de démonstration | `tools/mock-server` | `npm test` |
| Kura Studio | `apps/studio-web` | `npm ci && npm run check && npm test && npm run build && npm run test:e2e` |
| Kura Scan | `apps/scan-android` | `./gradlew :core:test` (logique), `./gradlew :app:assembleDebug` (appli, SDK Android requis) |
| Kura Bridge | `apps/bridge` | `gofmt -l . && go vet ./... && go test ./...` |

4. Mettez à jour la documentation (`docs/`) et le [journal des versions](CHANGELOG.md) si le comportement change.
5. Ouvrez une pull request en décrivant le pourquoi du changement.

## Principes

- **La base de l'entreprise reste la source de vérité** : Kura ne crée pas d'article et ne garde pas de données métier au-delà de la file hors-ligne.
- **Config, pas code** : une nouvelle option vaut mieux qu'une variante de l'appli.
- **Appareils modestes** : Kura Scan doit rester fluide sur Android 6 et un téléphone d'entrée de gamme ([ADR 0007](docs/adr/0007-compatibilite-android.md)).
- **Pas de télémétrie, pas de service tiers obligatoire.**
- **Lisible par un non-informaticien** : messages et documentation en français clair.

## Publier une version (mainteneurs)

1. Mettez à jour les numéros de version : `apps/scan-android/app/build.gradle.kts` (`versionName`, et `versionCode` + 1), `apps/studio-web/package.json`, `tools/mock-server/package.json`, `spec/api-contract.yaml` si le contrat change.
2. Ajoutez la section de la version au [CHANGELOG](CHANGELOG.md) (`## [1.2.0] - AAAA-MM-JJ`).
3. Poussez une étiquette : `git tag v1.2.0 && git push origin v1.2.0`. Le workflow [`release.yml`](.github/workflows/release.yml) construit et publie l'APK, `kura-studio.html`, les archives du Bridge et du serveur de démonstration, avec leurs sommes de contrôle.

L'APK est signé avec la clé de publication, fournie par quatre secrets du dépôt (*Settings › Secrets and variables › Actions*) : `KURA_KEYSTORE_B64` (le fichier `.jks` en base64), `KURA_KEYSTORE_PASSWORD`, `KURA_KEY_ALIAS`, `KURA_KEY_PASSWORD`. Créer la clé (une seule fois, **à conserver précieusement** : sans elle, les téléphones ne pourront plus installer les mises à jour) :

```sh
keytool -genkeypair -keystore kura-release.jks -alias kura -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 kura-release.jks        # valeur de KURA_KEYSTORE_B64
```

## Licence

En contribuant, vous acceptez que votre contribution soit distribuée sous licence [Apache 2.0](LICENSE).
