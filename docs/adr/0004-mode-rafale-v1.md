# 0004 : Mode rafale inclus dans la v1

**Statut :** acceptée (2026-10-22)

## Contexte
Sur le terrain, on enchaîne souvent les mouvements : sortir 20 articles pour une intervention, ranger une livraison. Passer par la fiche (scan → fiche → +1 → Valider) à chaque article est trop lent.

## Décision
Un **mode rafale** fait partie de la v1, sur l'écran Scan :

1. Bascule **Fiche / Rafale** en haut de l'écran Scan, avec le sens **Entrée (+) / Sortie (−)**.
2. En rafale, chaque lecture crée **immédiatement** une opération `delta` de `±burst.default_step`, sans ouvrir la fiche.
3. Retour immédiat : vibration + bip + bandeau « Vis M6 · −1 » (ou la référence seule si l'article n'est pas connu localement).
4. Un **même code relu** pendant `same_code_cooldown_ms` (1,5 s par défaut) est ignoré, pour que la caméra ne compte pas deux fois l'étiquette qu'elle a encore dans le champ. Pour compter le même article deux fois, on écarte puis on revise, ou on tape le compteur.
5. Un **panneau de session** en bas liste les lignes regroupées par article (« REF-0042 × 3 »), avec **annuler** (supprime la dernière opération si elle n'est pas encore partie, sinon crée l'opération inverse) et un champ pour corriger la quantité d'une ligne.
6. Toutes les opérations d'une rafale portent le même `session_id`, ce qui permet au serveur de les regrouper (bon de sortie, intervention).

## Raisons
- Pas d'écran supplémentaire : c'est un mode de l'écran Scan.
- Réutilise entièrement la file offline et les opérations `delta` : aucune logique serveur nouvelle.

## Conséquences
- Ajout du bloc `burst` dans la config et du champ `session_id` (optionnel) dans le contrat.
- `burst.verify_item` (désactivé par défaut) interroge le serveur à chaque scan pour signaler tout de suite un article inconnu ; sinon l'erreur `unknown_item` remonte à la synchronisation.
- Les scanners Bluetooth/HID sont particulièrement adaptés à ce mode (lecture en rafale très rapide).
