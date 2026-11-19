# Réseau léger entraîné sur IDOL (19/11)

IDOL (Sun et al., AAAI 2021) : 19,9 h de marche, 15 personnes, iPhone 8 tenu en main sur un support
rigide, données **CC BY 4.0** (utilisables commercialement en citant les auteurs). Code d'entraînement :
le nôtre (`tools/research/idol_train.py`), réseau à convolutions séparables de 97 000 paramètres, entrée
gyroscope + accéléromètre dans le repère du monde, 100 Hz, fenêtres de 2 s. Entraînement : 12 passes,
≈ 3 min sur le GPU du Mac par version. Données et modèles hors dépôt (`~/Documents/Hackdecouverte-marches/`).

## Sur les utilisateurs inconnus d'IDOL (même téléphone tenu en main, orientation parfaite)

| Version | Erreur de vitesse (RMS) | Dérive en fin de trajet |
|---|---|---|
| avec simulation de poche | 0,187 m/s | 1,3 % de la distance |
| sans simulation | 0,179 m/s | 1,7 % de la distance |

## Sur nos deux marches (Pixel 9, surtout en poche)

| Marche | Source | Écart au tracé (m) | Fermeture % | Distance / tracé | Suivi départ connu méd. / p90 (m) | « Trouvé » juste à froid | « Trouvé » faux |
|---|---|---|---|---|---|---|---|
| 150346 | PDR | 1,54 | 3,7 | 1,22 | 6,0 / 9,7 | 0/5 | 0 % |
| 150346 | IDOL sans poche | 2,28 | 27,7 | 0,46 | 6,2 / 12,7 | 2/5 (28 m) | 80 % |
| 150346 | IDOL + poche simulée | 3,44 | 23,2 | 0,62 | 6,8 / 13,6 | 4/5 (43 m) | 68 % |
| 150346 | RoNIN | 3,66 | 11,6 | 1,08 | 4,7 / 9,0 | 2/5 (56 m) | 70 % |
| 193742 | PDR | 3,57 | 16,6 | 1,03 | 6,9 / 8,3 | 0/5 | 0 % |
| 193742 | IDOL sans poche | 3,99 | 31,6 | 0,49 | 12,0 / 19,9 | 3/5 (64 m) | 72 % |
| 193742 | IDOL + poche simulée | 8,62 | 52,5 | 0,50 | 13,2 / 21,2 | 2/5 (44 m) | 95 % |
| 193742 | RoNIN | 4,56 | 19,1 | 0,94 | 8,0 / 17,0 | 2/5 (68 m) | 79 % |

## Conclusion

- Le réseau IDOL est excellent sur des téléphones tenus en main (1,3 % de dérive), mais **ne se transfère
  pas à la poche** : il voit environ la moitié de la distance réelle (0,46–0,62) et ferme mal les boucles.
- La poche simulée n'aide qu'un peu sur la distance (0,46 → 0,62 sur la 1ʳᵉ marche) ; le balancement de la
  cuisse est plus complexe qu'un pendule.
- RoNIN, entraîné avec de vraies données en poche, fait mieux : c'est la donnée réelle en poche qui manque,
  pas l'architecture.
- Pistes : (1) garder la longueur de pas du PDR et ne prendre au réseau que la direction ; (2) réajuster
  le réseau sur nos marches en poche, étiquetées par le trajet que retrace le filtre (pseudo-vérité) ;
  (3) demander SIMD (190 h, 7 tenues) aux auteurs.
