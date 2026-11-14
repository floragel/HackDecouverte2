# Lync — Comment ça marche (stack & backend)

> Doc pour le hackathon : la pile technologique, les capteurs, les équations
> et la structure de l'app. L'app = réseau social étudiant Lync + navigation
> intérieure/outdoor dans le pavillon MB (Concordia).

## 1. La pile technique

| Couche | Technologie |
|---|---|
| Langage / UI | **Kotlin + Jetpack Compose** (Material 3), une seule Activity, navigation par `NavController` |
| Carte outdoor | **MapLibre** (rendu vectoriel tuiles **OpenFreeMap**, données **OpenStreetMap**) |
| Plans intérieurs | **SVG vectoriels** des 7 étages MB + graphes JSON (nœuds/salles/escaliers/ascenseurs) parsés en Kotlin pur |
| Capteurs | `TYPE_ROTATION_VECTOR`, `TYPE_GAME_ROTATION_VECTOR`, `TYPE_STEP_DETECTOR`, `TYPE_PRESSURE` (baromètre), accéléromètre, **GNSS** (GPS L1/L5 + Galileo E1/E5a via `GnssStatus`) |
| Backend / données | **Supabase** (Postgres + Auth + Realtime + Storage), partage de position temps réel, calendrier **ICS** |
| Architecture | Une **façade unique** `IndoorNavigationBackend` exposant un `StateFlow<NavigationSnapshot>` — l'UI ne touche jamais les capteurs directement |

## 2. Le principe : Pedestrian Dead Reckoning (PDR)

Le problème : le GPS ne marche pas à l'intérieur (±20–40 m à travers le béton).
La solution : **le téléphone devient sa propre balise**.

Chaque pas accepté fait avancer une position 2D :

```
  θ = cap_boussole(t) − cap_référence          (cap relatif, en radians)
  x += L · sin(θ)          (x = Est)
  y += L · cos(θ)          (y = Nord)
```

- **`L` = longueur de pas** : calibrée en marchant 5 m, puis **adaptée en continu** au rythme de marche (cadence sur les 4 derniers pas vs cadence de référence, facteur empirique ~0,9, borné à −25 %/+30 %).
- **Cap** : issu du vecteur de rotation (quaternion). On en extrait le yaw par **décomposition Swing-Twist** (`atan2(R₁₀−R₀₁, R₀₀+R₁₁)`), ce qui rend le cap insensible à l'inclinaison du téléphone. Corrigé de la **déclinaison magnétique** (≈ −14° à Montréal, recalculée par `GeomagneticField` au fix GPS) → **nord vrai**, donc aucun angle à calibrer à la main.
- **Anti-faux-pas** : seuils sur l'accélération + temps de réarmement ; les secousses (≥ 11,5 m/s² ou rotation brutale) sont rejetées et il faut 2 pas rythmiques pour repartir — secouer le téléphone ne déplace plus le point.
- Timestamps capteurs en **nanosecondes** = référence de temps unique (les événements retardés sont appariés au buffer d'orientation par leur timestamp, pas leur heure d'arrivée).

## 3. Le passage extérieur → intérieur (le cœur du hack)

1. **Hors campus** : MapLibre + position GPS classique, itinéraire piéton.
2. **Arrivée sur le campus** : la position GPS devient trop grossière → on bascule sur le PDR ancré sur le plan.
3. **Choix de la salle de départ** : le GPS ne sait pas dans quelle pièce tu es → l'utilisateur choisit sa salle (ex. `MB-2.255`, recherche tolérante `M.2.255`, `2.255`…), l'**orientation reste automatique** (nord plan = nord téléphone).
4. **Changement d'étage** : le **baromètre** mesure la variation de pression. Avec la loi hypsométrique simplifiée :

```
   Δh ≈ 44330 · ln(P₀ / P)        (P en hPa)
   un étage MB ≈ 4 m  →  ΔP ≈ 0,5 hPa
```

   `FloorTransitionTracker` filtre (médiane + EMA), estime la vitesse verticale, **gèle le déplacement horizontal des pas** pendant la montée (sinon 25 marches = traversée de murs), puis **re-ancre** sur le nœud du même `vertical_group_id` côté palier d'arrivée (gère les décalages horizontaux entre cages d'escalier, ex. 12,8 m pour l'escalier 295).
   `AltitudeFloorEstimator` ajoute une estimation **absolue** : référence de pression capturée au niveau de la rue (fix GPS ≤ 15 m à 45–160 m du bâtiment) → l'étage courant est connu même sans avoir marché dans l'escalier.

## 4. Routage multi-étages

Le bâtiment est un **graphe** : nœuds = couloirs/salles/escaliers/ascenseurs, arêtes = distances en mètres.

- **Dijkstra** depuis la position PDR projetée sur le couloir le plus proche → n'importe quelle salle, **tous étages confondus**.
- Les groupes verticaux (`stairs_294`, `elevator_central`…) relient les mêmes positions sur plusieurs étages avec des **coûts** : escalier 15 m/étage, ascenseur 20 m + 3 m/étage.
- **Mode PMR** : les arêtes d'escalier sont supprimées → ascenseur obligatoire.
- Les instructions sont **re-jouées** localement : la prochaine étape est recalculée à chaque pas ("Continue 23 m puis tourne à droite", "Prends l'ascenseur jusqu'à l'étage 4", ou `RejoinHint` demi-tour/quart de tour si tu t'éloignes du trait).

## 5. Rendu de la carte

- **Géométrie** : les SVG d'évacuation (grille de calques, transform `walls-and-text`) sont parsés en contours métriques (`FloorVectorPlan`) → bitmap mis en cache par étage (`FloorVectorRenderer`, `LruCache`) → dessiné par `setPolyToPoly` (une seule matrure GPU par plan).
- **Repère écran** : `MapTransform` = translation + zoom + rotation. En mode **head-up** (défaut), `rotation = −cap_lissé` : **le haut de l'écran = la direction du téléphone**, comme Google Maps. Le cap est **déroulé en continu** (`rememberSmoothHeadingRad`) pour éviter le flip 360° au sud et lisser le tremblement.
- **Couches** (ordre) : fond OSM → rues/bois/bâtiments voisins → plan MB (murs, badges escaliers/ascenseurs, numéros de salles toujours droits) → trace bleue parcourue → itinéraire bleu à chevrons blancs + épingle destination → badges de transfert d'étage → point bleu pulsé avec faisceau de direction.
- **Lissage visuel uniquement** : la trace affichée est un `Path` quadratique lissé ; les métriques (distance, milestones) restent sur les points bruts.

## 6. Structure du code

```
com.hackdecouverte            ← backend réutilisable (indépendant de l'UI)
├── pdr/        moteur PDR pur Kotlin, 100 % unit-testé JVM
├── sensor/     adaptateurs capteurs (timestamps ns, buffering)
├── map/        FloorPlan, FloorVectorPlan, BuildingGraph, GeoReference, OSM
└── backend/    IndoorNavigationBackend = LA façade (StateFlow + commandes)

com.lync.app                 ← produit Lync
├── ui/           Compose : feed, réseau, calendrier, carte, indoor, profil…
│   ├── indoor/   IndoorScreen + IndoorCanvas (head-up, plans MB)
│   └── map/      MapScreen (MapLibre outdoor)
├── data/         Supabase, Calendrier (ICS), LocationTracker, Buildings/Rooms
└── ui/theme/     Tokens + CampusColors (couleur = université)
```

Le `PdrViewModel` fait 13 lignes : il expose `backend.state` et `backend.commandes`. Toute la logique est testable hors Android.

## 7. Les chiffres qui comptent

- **105 tests unitaires** (JVM pur) : parsing SVG/JSON réel, Dijkstra multi-étages, PDR, anti-shake, transitions barométriques, head-up.
- **7 étages, 761 nœuds, ~300 salles** du MB routables en une seule recherche.
- Précision : pas 2–3 cm près en ligne droite, dérive ~1–2 % corrigée par les couloirs et le baromètre ; le GPS reprend le relais dehors (bearing vrai-nord + recalage doux ≤ 8 m de précision).
