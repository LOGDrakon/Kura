# Recette : SQLite

Exemple complet : [`apps/bridge/examples/sqlite`](../../apps/bridge/examples/sqlite). Le plus simple pour un essai : rien à installer, la base est un fichier.

```sh
cd apps/bridge/examples/sqlite
sqlite3 gmao.db < schema.sql
kura-bridge
```

```yaml
database:
  driver: sqlite
  dsn: "file:gmao.db"          # chemin relatif au dossier du fichier de config
```

Points propres à SQLite :

- **Dates en texte** : SQLite n'a pas de type date. Si les dates sont stockées en texte ISO 8601 (`2026-10-01T08:00:00Z`), comparez avec `:updated_since_iso` et enregistrez `:client_ts_iso`.
- **Un seul écrivain à la fois** : le Bridge attend jusqu'à 5 secondes qu'un autre programme libère le fichier. Pour une base partagée par plusieurs logiciels très actifs, préférez un serveur de base.
- **Fichier sur un partage réseau** (SMB, NFS) : à éviter, le verrouillage n'y est pas fiable. Installez le Bridge sur la machine qui héberge le fichier.
- Sauvegarde : copiez le fichier quand le Bridge est arrêté, ou utilisez `sqlite3 gmao.db ".backup sauvegarde.db"`.
