# 0006 : Lecture déclenchée par un bouton « Scanner »

**Statut :** acceptée (2026-10-22)

## Contexte
Dans un magasin, des codes sont partout : étiquettes voisines, cartons, affiches. Une caméra qui lit en continu enregistre le premier code qui passe, pas forcément celui visé.

## Décision
- Sur l'écran Scan, l'aperçu caméra sert **seulement à viser**. Rien n'est décodé tant que l'utilisateur n'appuie pas sur le gros bouton **Scanner** (en bas, à portée du pouce).
- Un appui arme la lecture pendant `scan.trigger.timeout_ms` (6 s par défaut). La lecture s'arrête **au premier code lu** ; un nouvel appui pendant la tentative l'annule.
- Seule la **zone de visée centrale** (`scan.trigger.aim_area`, 60 % de la largeur par défaut) est analysée : l'image est recadrée avant ZXing, les codes voisins sont ignorés. Le cadre est dessiné à l'écran.
- Les **touches de volume** déclenchent aussi la lecture (`scan.trigger.volume_keys`), utile avec des gants.
- Retour : vibration courte + son + le cadre passe au vert. En cas d'échec à la fin du délai : vibration double et message « Aucun code lu ».
- Le mode `continuous` reste disponible par config pour les postes fixes (table de réception).
- Les scanners **Bluetooth/HID** ont leur propre gâchette : leurs lectures sont toujours acceptées.

## Conséquences
- En mode rafale, chaque appui = une lecture = un mouvement. Le délai `same_code_cooldown_ms` ne sert plus qu'en mode `continuous` et pour les scanners HID.
- Ajout de `scan.trigger` dans le schéma de config.
