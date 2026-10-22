# Sécurité

## Signaler une faille

Ne publiez pas de faille dans une issue publique. Utilisez le [signalement privé de GitHub](https://github.com/LOGDrakon/Kura/security/advisories/new) (onglet **Security › Report a vulnerability**).

Indiquez le composant, la version, la façon de reproduire et l'impact. Nous accusons réception sous une semaine et publions un correctif et un avis de sécurité dès que possible, en citant l'auteur du signalement s'il le souhaite.

## Versions maintenues

Les correctifs de sécurité sont publiés pour la dernière version.

## Bonnes pratiques de déploiement

- **HTTPS** entre les téléphones et le serveur en production ([guide Kura Bridge](docs/guides/bridge.md#8-sécurité)).
- **Jetons longs et aléatoires** (48 caractères), un par équipe ou par site, retirés dès qu'un appareil est perdu.
- **Utilisateur de base dédié**, limité à la lecture des articles et à la mise à jour du stock ([recettes](docs/recipes/README.md)).
- Les secrets ne figurent jamais dans `kura-config.json` : ils sont saisis sur le téléphone ou transmis par un QR chiffré par PIN, puis gardés dans le coffre-fort Android.
- Verrouillez les réglages de Kura Scan par mot de passe sur les appareils partagés.
