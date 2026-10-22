# Recette : Microsoft SQL Server

Exemple complet : [`apps/bridge/examples/sqlserver`](../../apps/bridge/examples/sqlserver). Fonctionne avec SQL Server 2016 et plus, éditions Express comprises, et Azure SQL.

## Login dédié

L'authentification Windows (intégrée) n'est pas prise en charge : créez un login SQL. Le serveur doit accepter l'authentification mixte (*Propriétés du serveur › Sécurité › Mode d'authentification SQL Server et Windows*).

```sql
CREATE LOGIN kura WITH PASSWORD = 'Mot-de-passe-long-1';
USE GMAO;
CREATE USER kura FOR LOGIN kura;
GRANT SELECT ON dbo.articles TO kura;
GRANT UPDATE ON dbo.articles (stock) TO kura;    -- seulement la colonne du stock
GRANT INSERT ON dbo.mouvements TO kura;          -- si after_apply écrit un historique
```

Créez le journal du Bridge vous-même (avec un compte administrateur), puis réglez `journal.create: false` :

```sql
CREATE TABLE dbo.kura_operations (
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
CREATE INDEX kura_operations_item ON dbo.kura_operations (item_id);
GRANT SELECT, INSERT ON dbo.kura_operations TO kura;
```

```yaml
journal:
  create: false
```

## Connexion

```yaml
database:
  driver: sqlserver
  dsn: "sqlserver://kura:${KURA_DB_PASSWORD}@srv-gmao:1433?database=GMAO"
```

- **Instance nommée** : `sqlserver://kura:…@srv-gmao/SQLEXPRESS?database=GMAO` (le service *SQL Server Browser* doit tourner), ou indiquez directement le port de l'instance.
- **Chiffrement** : par défaut, la connexion est chiffrée et le certificat vérifié. Certificat autosigné (cas fréquent en interne) : `&TrustServerCertificate=true`. Réseau local de test : `&encrypt=disable`.
- Le protocole **TCP/IP** doit être activé (*SQL Server Configuration Manager › Protocoles*), et le port ouvert dans le pare-feu.

## Requêtes

Voir [`kura-bridge.yaml`](../../apps/bridge/examples/sqlserver/kura-bridge.yaml). Points propres à SQL Server :

- Pagination : `ORDER BY … OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY` (le `ORDER BY` est obligatoire).
- Colonnes `NVARCHAR` : les accents passent sans conversion.
- Colonnes `DATETIME2` sans fuseau : le Bridge les considère en UTC. Si elles sont en heure locale, convertissez dans la requête : `modifie_le AT TIME ZONE 'Romance Standard Time' AS updated_at`.
- Un trigger existant sur la table des articles (fréquent dans les ERP) s'exécute normalement, dans la transaction du Bridge.

## Installer le Bridge comme service Windows

Voir [Service Windows](../guides/bridge.md#service-windows).

## Vérifier

```bat
kura-bridge.exe check -c kura-bridge.yaml -ref REF-0042
```
