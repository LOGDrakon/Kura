# Recette : stock dans un fichier CSV ou Excel

Exemple complet : [`apps/bridge/examples/script`](../../apps/bridge/examples/script). Le [mode script](../guides/bridge.md#6-mode-script) du Bridge appelle un petit programme Python qui lit et modifie le fichier.

```
examples/script/
├── kura-bridge.yaml     # config du Bridge (mode script)
├── adaptateur.py        # l'adaptateur : environ 100 lignes, sans dépendance
└── stock.csv            # le stock, séparateur « ; »
```

```sh
cd apps/bridge/examples/script
export KURA_TOKEN=$(openssl rand -hex 24)
kura-bridge check
kura-bridge
```

## Adapter à votre fichier

- **Colonnes** : modifiez `COLONNES` dans `adaptateur.py`. La colonne `ref` identifie l'article, `quantity` porte le stock ; les autres sont transmises telles quelles (affichage sur la fiche, étiquettes).
- **Séparateur** : `delimiter=";"` (format français d'Excel) ou `","`.
- **Fichier Excel (`.xlsx`)** : installez `openpyxl` (`pip install openpyxl`) et remplacez `lire()` / `ecrire()` :
  ```python
  from openpyxl import load_workbook

  def lire():
      ws = load_workbook(FICHIER).active
      entetes = [c.value for c in ws[1]]
      return [dict(zip(entetes, [("" if v is None else str(v)) for v in ligne]))
              for ligne in ws.iter_rows(min_row=2, values_only=True)]

  def ecrire(lignes):
      wb = load_workbook(FICHIER)
      ws = wb.active
      entetes = [c.value for c in ws[1]]
      col = entetes.index("quantity") + 1
      for i, ligne in enumerate(lignes, start=2):
          ws.cell(row=i, column=col, value=int(ligne["quantity"]))
      wb.save(FICHIER)
  ```

## Précautions

- **Fichier ouvert dans Excel** : Excel verrouille le fichier ; l'écriture échoue, le Bridge répond `503` et les téléphones réessaient plus tard. Personne ne doit garder le fichier ouvert en modification.
- **Une seule source de vérité** : si le fichier est aussi modifié à la main, faites-le quand le Bridge est arrêté, ou acceptez que les deux se croisent.
- Au-delà de quelques milliers d'articles ou de quelques utilisateurs, une vraie base (même [SQLite](sqlite.md)) sera plus sûre.
