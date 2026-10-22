# Kura Bridge

Adaptateur optionnel pour les systèmes qui ne parlent pas le [contrat Kura](../../spec/api-contract.yaml) : il expose le contrat et le traduit vers une base SQL, un fichier CSV/Excel ou un script.

> 🚧 Prévu pour la v1.0. Pas encore de code.

- **Go** : un seul binaire multiplateforme, aucune dépendance à installer ; image Docker en complément.
- Configuration YAML avec requêtes SQL paramétrées (voir [architecture](../../docs/architecture.md#7-compatibilité--nimporte-quelle-gmao---3-niveaux)).
- Le Bridge tient sa propre table `kura_operations` pour l'idempotence (`op_id`) et la règle des `set` ([ADR 0005](../../docs/adr/0005-resolution-conflits.md)).
- La logique de [`tools/mock-server/lib/store.js`](../../tools/mock-server/lib/store.js) et ses tests servent de référence de comportement.
