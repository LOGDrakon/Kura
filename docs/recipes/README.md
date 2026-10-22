# Recettes

Exemples prêts à adapter pour brancher [Kura Bridge](../guides/bridge.md) sur un système courant. Chaque recette part d'un exemple testé automatiquement sur une vraie base ([`apps/bridge/examples`](../../apps/bridge/examples)).

| Recette | Système | Exemple |
|---|---|---|
| [PostgreSQL](postgres.md) | PostgreSQL 12 et plus | [`examples/postgres`](../../apps/bridge/examples/postgres) |
| [MySQL et MariaDB](mysql.md) | MySQL 8, MariaDB 10.5 et plus | [`examples/mysql`](../../apps/bridge/examples/mysql) |
| [SQL Server](sqlserver.md) | SQL Server 2016 et plus, Express compris | [`examples/sqlserver`](../../apps/bridge/examples/sqlserver) |
| [SQLite](sqlite.md) | Fichier SQLite (essais, petites installations) | [`examples/sqlite`](../../apps/bridge/examples/sqlite) |
| [Fichier CSV ou Excel](csv.md) | Stock tenu dans un fichier partagé | [`examples/script`](../../apps/bridge/examples/script) |
| [API REST d'un ERP ou d'une GMAO](rest-api.md) | Logiciel qui expose une API | script Python de la recette |

Toutes les recettes SQL reposent sur la même base de démonstration : une table `articles` (`code`, `designation`, `emplacement`, `service`, `unite`, `stock`, `modifie_le`) et une table `mouvements` remplie par `after_apply`. Dans votre cas, ces tables existent déjà : ne gardez que les requêtes, en remplaçant les noms.

Vous avez branché Kura sur un autre système (Odoo, Dolibarr, Sage, une GMAO du marché…) ? Proposez votre recette : voir [CONTRIBUTING.md](../../CONTRIBUTING.md).
