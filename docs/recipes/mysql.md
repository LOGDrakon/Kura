# Recette : MySQL et MariaDB

Exemple complet : [`apps/bridge/examples/mysql`](../../apps/bridge/examples/mysql). Le même pilote sert pour MySQL et MariaDB.

## Utilisateur dédié

```sql
CREATE USER 'kura'@'%' IDENTIFIED BY 'mot-de-passe-long';   -- '%' : depuis n'importe quel hôte ; précisez l'IP du Bridge si possible
GRANT SELECT, UPDATE (stock) ON gmao.articles TO 'kura'@'%';
GRANT INSERT ON gmao.mouvements TO 'kura'@'%';               -- si after_apply écrit un historique
GRANT CREATE, SELECT, INSERT, INDEX ON gmao.kura_operations TO 'kura'@'%';
```

## Connexion

```yaml
database:
  driver: mysql
  dsn: "kura:${KURA_DB_PASSWORD}@tcp(srv-gmao:3306)/gmao"
```

- Format : `utilisateur:motdepasse@tcp(hôte:port)/base?options`.
- Le Bridge ajoute de lui-même `parseTime=true` (dates lues comme des dates) et `clientFoundRows=true`. Sans ce dernier, MySQL compte 0 ligne modifiée quand un inventaire ne change pas la valeur, et l'article passerait pour inconnu.
- Les dates sont lues et écrites en UTC. Si vos colonnes `DATETIME` sont en heure locale, ajoutez `&loc=Europe%2FParis`.
- Connexion chiffrée : `&tls=true` (ou `skip-verify` pour un certificat autosigné).

## Requêtes

Voir [`kura-bridge.yaml`](../../apps/bridge/examples/mysql/kura-bridge.yaml). Points propres à MySQL :

- `LIMIT :limit OFFSET :offset` fonctionne tel quel.
- Avec une collation insensible à la casse (`utf8mb4_general_ci`, le cas le plus courant), `lower()` est inutile : `designation LIKE :q_like` suffit.
- Tables en `MyISAM` : pas de transactions, donc pas d'atomicité entre stock et journal. Préférez `InnoDB` ou un [journal à part](../guides/bridge.md#5-le-journal-des-opérations).

## Vérifier

```sh
kura-bridge check -c kura-bridge.yaml -ref REF-0042
```
