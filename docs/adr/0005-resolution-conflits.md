# 0005 : Résolution des conflits hors-ligne

**Statut :** acceptée (2026-10-22)

## Contexte
Plusieurs appareils travaillent hors-ligne puis se synchronisent dans le désordre. Une règle « le dernier timestamp gagne » appliquée à tout ferait perdre des mouvements (deux sorties simultanées de −1 ne doivent pas donner −1 au total).

## Décision
Deux types d'opérations :

| Type | Sens | Résolution |
|---|---|---|
| `delta` | mouvement (−1, +10) | **additionné**, jamais perdu |
| `set` | correction d'inventaire (= 12) | **le `client_ts` le plus récent gagne** |

Le stock se calcule toujours ainsi, à partir du journal :

```
quantité = valeur du set le plus récent (ou stock initial)
         + somme des delta dont client_ts est postérieur à ce set
```

- Égalité de `client_ts` entre deux `set` : l'`op_id` le plus grand gagne (déterministe).
- Un `set` plus ancien qu'un `set` déjà connu est enregistré mais répond `superseded`.
- `op_id` (UUID v4) rend chaque envoi **idempotent** : un renvoi répond `duplicate`.

## Raisons
- Le résultat ne dépend **pas de l'ordre d'arrivée** (vérifié par un test sur toutes les permutations dans `tools/mock-server/test/store.test.js`).
- Un inventaire (`set`) annule logiquement les mouvements qu'il a déjà « vus », pas ceux faits après.

## Conséquences
- Le serveur doit garder un journal des opérations (ou au moins le dernier `set` par article). Kura Bridge le fera dans une table dédiée quand la base cible ne le permet pas.
- Pour une GMAO qui n'applique qu'un simple `UPDATE stock = stock + x`, les `set` tardifs peuvent écraser des mouvements récents : c'est signalé dans les guides d'intégration.
- `server_received_at` permet de repérer un appareil dont l'horloge dérive.
