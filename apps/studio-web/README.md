# Kura Studio (web)

Générateur no-code, 100 % dans le navigateur : configuration de Kura Scan, QR de configuration et étiquettes.

> 🚧 Pas encore de code.

## Stack

- Svelte + Vite, PWA (fonctionne hors-ligne, hébergeable sur n'importe quel serveur statique ou ouvert en local)
- `qrcode` (QR), `bwip-js` (codes 1D), `pdf-lib` (PDF), `papaparse` / `xlsx` (import CSV/Excel)
- Validation de la config avec [`spec/config.schema.json`](../../spec/config.schema.json) (Ajv)

## v0.1 : critères d'acceptation

- [ ] Saisir une liste de références (ou importer un CSV) et générer un PDF d'étiquettes QR.
- [ ] Choisir le format : planche A4 (grille paramétrable) ou rouleau (largeur × hauteur en mm).
- [ ] Générer un `kura-config.json` minimal et son QR de configuration.
