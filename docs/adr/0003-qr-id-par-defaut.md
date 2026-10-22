# 0003 : Le code contient l'identifiant seul par défaut

**Statut :** acceptée (2026-10-22)

## Contexte
Le code peut contenir soit un simple identifiant (`REF-0042`), soit des données (`REF-0042;Vis M6;A12` ou du JSON).

## Décision
- `scan.format = "id"` est la **valeur par défaut**.
- `delimited` et `json` restent disponibles en option, pour les cas sans serveur consultable (fiche affichée hors-ligne à partir du seul code).

## Raisons
- **Étiquettes durables** : renommer un article ou le changer d'emplacement ne demande pas de réimprimer.
- Codes plus courts, donc plus petits et plus faciles à lire (moins de modules dans le QR).
- Compatible avec les codes-barres 1D existants, qui ne contiennent qu'un identifiant.
- Une seule source de vérité : le serveur de l'entreprise.

## Conséquences
- En mode `id`, la fiche affiche les infos via `GET /items/{ref}`. Hors-ligne, l'appli affiche le dernier état mis en cache (s'il existe) avec une mention « hors-ligne », et l'opération part quand même en file d'attente.
- En mode `delimited`/`json`, `fields` et `id_field` sont obligatoires (vérifié par le JSON Schema).
