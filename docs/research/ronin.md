# RoNIN sur nos marches (recherche uniquement)

RoNIN (Herath, Yan, Furukawa, ICRA 2020) prédit la vitesse horizontale à partir de 1 s de gyroscope et
d'accéléromètre (gravité comprise) remis dans le repère du monde par le game rotation vector, à 200 Hz.

- **Licences :** code GPL-3.0 ; données et poids pré-entraînés « non-commercial and scientific research
  purposes ». Rien de RoNIN n'entre dans l'app : c'est une référence de comparaison pour l'article.
- **Hors dépôt :** `~/Documents/Hackdecouverte-marches/ronin-work/` (environnement Python 3.12 + PyTorch,
  code RoNIN cloné) et `~/Documents/Hackdecouverte-marches/ronin/` (poids téléchargés depuis FRDR).
- **Lancer :** `tools/research/ronin.sh <checkpoint.pt> [resnet18|resnet50]` → convertit chaque marche
  tracée, intègre la vitesse prédite, puis `ExternalTrajectoryTest` compare au PDR avec les mêmes mesures
  (écart au tracé, murs, fermeture, distance) dans `app/build/replay/external-trajectories.txt`.
- **Vérifié :** entrée au bon format (gravité moyenne 9,79 m/s² sur l'axe vertical, 200 Hz) sur les deux
  marches ; chaîne testée avec un réseau aléatoire en attendant les poids.

## Premiers résultats (19/11, modèle `ronin_resnet`, sans aucun réglage)

| Marche | Source | Écart au tracé (m) | Fermeture % | Distance / tracé |
|---|---|---|---|---|
| 150346 | PDR (défaut) | 1,54 | 3,7 | 1,22 |
| 150346 | RoNIN | 3,66 | 11,6 | 1,08 |
| 150346 | RoNIN + boussole (`--compass`) | 0,81 | 1,9 | 1,08 |
| 193742 | PDR (défaut) | 3,57 | 16,6 | 1,03 |
| 193742 | RoNIN | 4,56 | 19,1 | 0,94 |
| 193742 | RoNIN + boussole | 8,12 | 29,2 | 0,94 |

- La distance de RoNIN est juste sans calibration du pas.
- Corriger la dérive du gyroscope par la boussole est décisif sur la 1ʳᵉ marche, nuisible sur la 2ᵉ : la
  correction du script n'a pas encore la coupure sur champ anormal du PDR (B6).
