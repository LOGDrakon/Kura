# Quickstart

> Version provisoire : seul le Mock Server est disponible pour l'instant. Ce guide sera complété avec Kura Scan et Kura Studio (objectif : opérationnel en 15 minutes).

## 1. Lancer le serveur de démonstration

Prérequis : Node.js 20 ou plus. Aucune dépendance à installer.

```sh
cd tools/mock-server
npm start
```

Variables d'environnement :

| Variable | Rôle | Défaut |
|---|---|---|
| `PORT` | Port d'écoute | `8787` |
| `KURA_MOCK_TOKEN` | Active l'authentification Bearer avec ce jeton | désactivée |
| `KURA_MOCK_ITEMS` | Fichier JSON des articles | `data/items.json` |
| `KURA_MOCK_CONFIG` | Fichier servi par `GET /config` | aucun |
| `KURA_MOCK_LATENCY_MS` | Latence simulée (tester l'appli sur réseau lent) | `0` |

Les données sont en mémoire : elles repartent de zéro à chaque redémarrage.

## 2. Essayer le contrat

```sh
# Fiche d'un article
curl localhost:8787/items/REF-0042

# Sortie d'une unité
curl -X POST localhost:8787/operations -H 'Content-Type: application/json' -d '{
  "op_id": "4b2f8d4e-0a7c-4a53-9d55-2f1e9b0c6a11",
  "item_id": "REF-0042", "type": "delta", "value": -1,
  "client_ts": "2026-10-22T13:55:12Z"
}'
# → {"op_id":"…","status":"applied","quantity":249,…}

# Même commande une seconde fois → "status":"duplicate", le stock ne bouge pas.

# Simuler un article créé dans la GMAO (hors contrat), puis le retrouver comme le fait Kura Studio
curl -X POST localhost:8787/_debug/items -H 'Content-Type: application/json' \
  -d '{"ref":"REF-0500","name":"Joint torique 20x2","brand":"Parker"}'
curl 'localhost:8787/items?q=parker'

# Journal de l'article (outil de débogage, hors contrat)
curl localhost:8787/_debug/journal/REF-0042
```

## 3. Depuis un téléphone

- Émulateur Android : le serveur est joignable sur `http://10.0.2.2:8787` (voir [`kura-config.minimal.json`](../spec/examples/kura-config.minimal.json)).
- Téléphone réel sur le même Wi-Fi : utiliser l'adresse IP du PC, ex. `http://192.168.1.20:8787`.
