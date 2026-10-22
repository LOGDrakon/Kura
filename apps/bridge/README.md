# Kura Bridge

Connecteur entre Kura (Scan, Studio) et la base de stock de l'entreprise. Il expose le [contrat d'API Kura](../../spec/api-contract.yaml) et le traduit en requêtes SQL (PostgreSQL, MySQL/MariaDB, SQL Server, SQLite) ou en appels à un script.

**Installation et configuration : [guide Kura Bridge](../../docs/guides/bridge.md).** Exemples par moteur : [`examples/`](examples/), et [recettes](../../docs/recipes/README.md).

## Construire

Go 1.26 ou plus récent, aucune dépendance C (`CGO_ENABLED=0`) :

```sh
go build -o kura-bridge ./cmd/kura-bridge
go test ./...
```

Les exemples de [`examples/`](examples/) sont testés sur de vraies bases ([`internal/app/examples_test.go`](internal/app/examples_test.go)) : SQLite toujours ; PostgreSQL, MySQL et SQL Server quand une chaîne de connexion est fournie (c'est le cas en CI) :

```sh
KURA_TEST_POSTGRES_DSN="postgres://kura:kura@localhost:5432/gmao?sslmode=disable" \
KURA_TEST_MYSQL_DSN="root:kura@tcp(127.0.0.1:3306)/gmao" \
KURA_TEST_SQLSERVER_DSN="sqlserver://sa:Motdepasse_1@localhost:1433?database=master&encrypt=disable" \
go test ./internal/app/ -run TestExamples -v
```

Les tables `articles`, `mouvements` et `kura_operations` de ces bases sont recréées à chaque passage.

## Organisation

| Paquet | Rôle |
|---|---|
| [`cmd/kura-bridge`](cmd/kura-bridge) | Commandes `run`, `check`, `keygen`, `version` ; service Windows. |
| [`internal/config`](internal/config) | Lecture et vérification de `kura-bridge.yaml` (`${VAR}`, valeurs par défaut). |
| [`internal/named`](internal/named) | Paramètres nommés `:ref` → `$1` / `?` / `@p1`, sans toucher aux chaînes ni aux commentaires. |
| [`internal/backend`](internal/backend) | Sources : SQL (`database/sql`) et script (JSON sur stdin/stdout). |
| [`internal/journal`](internal/journal) | Table `kura_operations` et règle de stock incrémentale ([ADR 0005](../../docs/adr/0005-resolution-conflits.md)). |
| [`internal/engine`](internal/engine) | Traitement d'une opération : validation, doublon, règle, écriture, journal, dans une transaction. |
| [`internal/server`](internal/server) | Routes HTTP du contrat, authentification, CORS, config signée. |
| [`internal/app`](internal/app) | Assemblage, et tests de bout en bout. |

Le comportement de référence est celui du serveur de démonstration ([`tools/mock-server/lib/store.js`](../../tools/mock-server/lib/store.js)) : les deux sont testés sur le même scénario (120 ordres d'arrivée, un seul stock final).
