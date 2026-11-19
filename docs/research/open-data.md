# Données ouvertes utilisables commercialement (vérifié le 19/11)

Licences lues à la source. Ce n'est pas un avis juridique : à faire valider avant un produit vendu.

| Données | Contenu utile | Licence (source) | Usage pour nous |
|---|---|---|---|
| **IDOL** (CMU, AAAI 2021) | 20 h, 15 personnes, iPhone 8 en main sur support rigide, position et orientation de référence, 100 Hz | CC BY 4.0 ([Zenodo](https://zenodo.org/record/4484093)) | entraînement en main (fait : `docs/research/idol.md`) |
| **CMU Motion Capture** | des centaines de marches, squelette complet (cuisses, bassin, mains) | « free for all uses », « may be copied, modified, or redistributed without permission » ([site](http://mocap.cs.cmu.edu/), [FAQ](http://mocap.cs.cmu.edu/faqs.php)) | **IMU virtuel posé sur la cuisse (poche) ou la main**, vitesse exacte : la vraie poche, simulée proprement |
| **MotionSense** | iPhone 6s dans la poche avant, 24 personnes, 50 Hz, marche, escaliers, jogging ; pas de position | MIT ([GitHub](https://github.com/mmalekzadeh/motion-sense)) | signaux réels en poche : vérifier que la poche virtuelle leur ressemble, pré-entraînement sans étiquette, cadence |
| **HHAR** (UCI) | 8 téléphones Android de 4 modèles + montres, 9 personnes, marche et escaliers ; pas de position | CC BY 4.0 ([UCI](https://archive.ics.uci.edu/dataset/344/heterogeneity+activity+recognition)) | différences entre téléphones (bruit, fréquences) |
| **Compétition IPIN, piste 3** (2022, 2023) | enregistrements Android complets (IMU, Wi-Fi…) en marchant dans des bâtiments, points de vérité | CC BY 4.0 ([2023](https://zenodo.org/records/8362205), [2022](https://zenodo.org/record/7612915)) | validation sur d'autres bâtiments et téléphones, comme nos marches |

À écarter ou à demander : RoNIN, TLIO, ADVIO, Aria/Nymeria (non commerciaux) ; OxIOD, RIDI, SIMD, IMUNet,
RuDaCoP, Google GSDC (pas de licence claire : écrire aux auteurs).

## Ce que ça permet

Recette entièrement commerciale pour le problème de la poche :
1. Poche et main **virtuelles** à partir de CMU Mocap : on place un IMU sur la cuisse ou la main du
   squelette, on calcule ses mesures (gravité et bruit compris) et la vitesse exacte du bassin.
2. IDOL pour le réel en main ; MotionSense pour vérifier et affiner le réalisme de la poche.
3. HHAR pour la variété des téléphones ; IPIN pour tester sur d'autres bâtiments.
4. Nos propres marches (étiquetées par le filtre) pour le dernier réajustement.
