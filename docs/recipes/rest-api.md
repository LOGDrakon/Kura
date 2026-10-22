# Recette : API REST d'un ERP ou d'une GMAO

Quand le logiciel de stock propose une API (REST, JSON), le [mode script](../guides/bridge.md#6-mode-script) de Kura Bridge appelle cette API. Le Bridge se charge des doublons, de la règle des inventaires, du journal et de la sécurité ; le script ne fait que traduire.

Adaptateur d'exemple : [`examples/script/adaptateur_erp.py`](../../apps/bridge/examples/script/adaptateur_erp.py), en Python, sans dépendance (bibliothèque standard).

## L'API supposée

| Besoin | Appel de l'ERP |
|---|---|
| Lire un article | `GET /articles/{code}` → `{"code", "libelle", "emplacement", "stock", …}`, `404` si inconnu |
| Lister les articles | `GET /articles?recherche=…&page=1&taille=50` → `{"articles": [ … ], "total": 1234}` |
| Mouvement | `POST /articles/{code}/mouvements` `{"quantite": -2, "motif": "…", "reference": "<op_id>"}` → `{"stock": 10}` |
| Inventaire | `PUT /articles/{code}/stock` `{"quantite": 20, …}` → `{"stock": 20}` |

Votre API sera différente : seules les fonctions `article()`, `get_item()`, `list_items()` et `appliquer()` sont à adapter.

## Configuration

```yaml
listen: ":8787"
auth:
  type: bearer
  tokens: ["${KURA_TOKEN}"]

script:
  command: ["python3", "adaptateur_erp.py"]      # Windows : ["py", "adaptateur_erp.py"]
  env:
    ERP_URL: https://erp.exemple.fr/api/v2
    ERP_TOKEN: ${ERP_TOKEN}
  timeout_s: 15

journal:
  file: kura-journal.db
```

## Ce que fait l'adaptateur

- **Traduction des champs** (`article()`) : `libelle` → `name`, `emplacement` → `location`, `stock` → `quantity`. Les autres champs de l'ERP (`famille`, `fournisseur`…) sont transmis tels quels : on peut les afficher sur la fiche et les imprimer sur les étiquettes.
- **Références avec des caractères spéciaux** : elles sont encodées dans l'adresse (`REF/7` → `/articles/REF%2F7`).
- **Pagination** : le curseur Kura est le numéro de page de l'ERP.
- **Refus métier** : un `409` ou `422` de l'ERP (« stock insuffisant ») devient `rejected` / `forbidden` avec le message de l'ERP ; l'opération reste en erreur sur le téléphone, l'utilisateur voit le motif.
- **Panne** : erreur réseau ou `5xx` de l'ERP → le script échoue, le Bridge répond `503`, le téléphone garde l'opération et la renverra. Seule la dernière ligne de l'erreur est renvoyée ; la trace complète est dans le journal du Bridge.
- **Traçabilité** : `op_id` est transmis comme `reference` et l'utilisateur dans `motif`. Si l'ERP sait refuser une référence déjà reçue, c'est une protection supplémentaire contre les doublons (le Bridge les filtre déjà).

## Tester sans l'ERP

Lancez le script à la main, avec une requête sur l'entrée standard :

```sh
export ERP_URL=https://erp.exemple.fr/api/v2 ERP_TOKEN=…
echo '{"action":"get_item","ref":"REF-0042"}' | python3 adaptateur_erp.py
echo '{"action":"list_items","q":"","limit":5,"cursor":""}' | python3 adaptateur_erp.py
```

Puis vérifiez l'ensemble :

```sh
kura-bridge check -c kura-bridge.yaml -ref REF-0042
```

## Variantes

- **API SOAP** : même principe, avec `zeep` (Python) ou `New-WebServiceProxy` (PowerShell).
- **PowerShell** (serveurs Windows) : lisez l'entrée avec `$req = [Console]::In.ReadToEnd() | ConvertFrom-Json`, appelez l'API avec `Invoke-RestMethod`, répondez avec `ConvertTo-Json -Compress`. Commande : `["powershell", "-NoProfile", "-File", "adaptateur.ps1"]`.
- **Pas d'appel « inventaire » dans l'API** : calculez l'écart (`stock voulu − stock actuel`) dans `appliquer()` et envoyez-le comme mouvement.
- **Une API qui suit déjà presque le contrat Kura** : la [correspondance directe](../guides/integration.md#10-adapter-une-api-rest-existante-sans-code) dans la config de Kura Scan peut suffire, sans Bridge.
