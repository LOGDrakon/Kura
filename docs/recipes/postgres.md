# Recette : PostgreSQL

Exemple complet : [`apps/bridge/examples/postgres`](../../apps/bridge/examples/postgres) (`schema.sql` pour la base de démonstration, `kura-bridge.yaml` pour le Bridge).

## Utilisateur dédié

```sql
CREATE ROLE kura LOGIN PASSWORD 'mot-de-passe-long';
GRANT CONNECT ON DATABASE gmao TO kura;
GRANT USAGE ON SCHEMA public TO kura;

GRANT SELECT ON articles TO kura;
GRANT UPDATE (stock) ON articles TO kura;          -- seulement la colonne du stock
GRANT INSERT ON mouvements TO kura;                -- si after_apply écrit un historique
GRANT USAGE ON SEQUENCE mouvements_id_seq TO kura;

-- Journal du Bridge : soit le droit de le créer…
GRANT CREATE ON SCHEMA public TO kura;
-- …soit créez kura_operations vous-même (voir « Journal » ci-dessous) et donnez :
-- GRANT SELECT, INSERT ON kura_operations TO kura;
```

## Connexion

```yaml
database:
  driver: postgres
  dsn: "postgres://kura:${KURA_DB_PASSWORD}@srv-gmao:5432/gmao?sslmode=require"
```

- `sslmode` : `disable` (même machine), `require` (chiffré), `verify-full` (chiffré et certificat vérifié).
- Autre schéma que `public` : ajoutez `&search_path=stock` au DSN, ou préfixez les tables (`stock.articles`).
- Caractères spéciaux dans le mot de passe : encodez-les (`@` → `%40`, `/` → `%2F`), ou utilisez la forme `host=srv-gmao user=kura password=… dbname=gmao`.

## Requêtes

Voir [`kura-bridge.yaml`](../../apps/bridge/examples/postgres/kura-bridge.yaml). Points propres à PostgreSQL :

- `ILIKE` fonctionne aussi pour la recherche : `designation ILIKE :q_like`.
- Une colonne `NUMERIC` pour le stock convient : le Bridge la convertit en entier.
- Les conversions `::text`, `::int` peuvent être utilisées librement dans les requêtes.
- Stock négatif interdit : `UPDATE articles SET stock = stock + :value WHERE code = :ref AND stock + :value >= 0`. Aucune ligne n'est alors modifiée et l'opération est refusée comme « article inconnu » ; pour un refus avec un message clair (`forbidden`), passez par le [mode script](../guides/bridge.md#6-mode-script).

## Journal

Si l'utilisateur ne peut pas créer de table, créez-la avant le premier démarrage et mettez `journal.create: false` :

```sql
CREATE TABLE kura_operations (
  op_id VARCHAR(36) NOT NULL PRIMARY KEY,
  item_id VARCHAR(128) NOT NULL,
  op_type VARCHAR(8) NOT NULL,
  op_value BIGINT NOT NULL,
  client_ts_ms BIGINT NOT NULL,
  client_ts VARCHAR(40) NOT NULL,
  device_id VARCHAR(128),
  user_name VARCHAR(128),
  session_id VARCHAR(128),
  status VARCHAR(16) NOT NULL,
  received_at VARCHAR(40) NOT NULL
);
CREATE INDEX kura_operations_item ON kura_operations (item_id);
GRANT SELECT, INSERT ON kura_operations TO kura;
```

## Vérifier

```sh
kura-bridge check -c kura-bridge.yaml -ref REF-0042
```
