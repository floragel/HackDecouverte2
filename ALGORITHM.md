# Algorithme PDR — Positionnement relatif indoor

Ce document explique comment le moteur de suivi de position (`PdrTracker`)
transforme les capteurs du téléphone en un tracé en mètres, étape par étape.
Tous les fichiers mentionnés vivent dans `app/src/main/java/com/hackdecouverte/`.

## 1. Capteurs utilisés

| Capteur Android | Rôle | Fréquence demandée |
| --- | --- | --- |
| `TYPE_GAME_ROTATION_VECTOR` | Orientation 3D → cap relatif (yaw) | ~60 Hz (`16_667 µs`) |
| `TYPE_STEP_DETECTOR` | Un événement par pas (horodaté au contact pied/sol) | événementiel |
| `TYPE_LINEAR_ACCELERATION` / `TYPE_ACCELEROMETER` | Repli expérimental de détection de pas | ~60 Hz |

`GAME_ROTATION_VECTOR` est choisi parce qu'il est fusionné gyroscope +
accéléromètre **sans magnétomètre** : il ne saute pas à cause du métal des
bâtiments, au prix d'une légère dérive dans le temps. Le cap affiché est donc
**relatif** à l'orientation de départ, jamais un nord absolu.

## 2. Unités, horloges et repère

- **Horloge unique** : `SystemClock.elapsedRealtimeNanos()` (CLOCK_BOOTTIME).
  `SensorEvent.timestamp` utilise la même base : on peut donc comparer
  directement les timestamps des deux capteurs et de l'horloge injectée dans
  `PdrTracker`. Ne jamais mélanger avec `System.nanoTime()`.
- **Repère monde** : origine `(0, 0)` à la fin de la calibration, `x` à droite,
  `y` vers l'avant (l'axe de référence), le tout relatif.
- **Positions** en mètres (`PointM`), angles en radians sur `]-π, π]` avec
  `wrapAnglePi`.

## 3. Orientation — `pdr/OrientationProcessor.kt`

1. Le quaternion du rotation vector est converti en matrice 3×3 par
   `rotationMatrixFromVector` (portage Kotlin de
   `SensorManager.getRotationMatrixFromVector`).
2. La direction « haut du téléphone » projetée sur le plan horizontal donne
   `east = R[1]`, `north = R[4]` et `yaw = atan2(east, north)`.
3. **Garde d'inclinaison** : si `norm = hypot(east, north) < 0.25`, le
   téléphone est presque vertical, la projection s'effondre → l'échantillon est
   marqué **invalide** plutôt que de produire un cap absurde (inversion 180°).
   Si l'échantillon le plus récent ≤ t est invalide, `meanHeadingAt(t)` renvoie
   `null` : aucun pas n'est avancé pendant l'intervalle vertical.
4. Les échantillons `(timestampNs, yaw, valid)` sont conservés dans un
   **buffer circulaire de 2,5 s**. C'est ce qui permet à un pas retardé
   (jusqu'à ~2 s de latence sur `STEP_DETECTOR`) de récupérer le cap qu'il
   avait **au moment où le pied a touché le sol**, et non le cap actuel.
5. Pour chaque pas on calcule la **moyenne circulaire** des échantillons
   valides sur les 150 ms précédant `event.timestamp` :

   `θ̄ = atan2(Σ sin θᵢ, Σ cos θᵢ)`

   La moyenne arithmétique est interdite : moyenner +179° et −179° donnerait
   0° au lieu de ±180°. Si le vecteur résultant est quasi nul (angles qui
   s'annulent), le résultat est invalidé au lieu de tomber sur 0.

## 4. Détection de pas — `pdr/StepDetectorEngine.kt` + `sensor/PdrSensorAdapter.kt`

- **Source matérielle** : `TYPE_STEP_DETECTOR` émet `1.0` par pas, son
  timestamp étant l'instant du contact (jusqu'à ~2 s de retard de livraison
  toléré par le buffer de 2,5 s). Une seule source active à la fois, verrouillée
  pendant calibration et tracking (`onStep` ignore toute autre source).
- **Repli accéléromètre** (expérimental, sélection manuelle) :
  - le vecteur « haut » mondial `R[6..8]` est dérivé de la même matrice de
    rotation (aucun capteur `GRAVITY` — évite les courses de signe) ;
  - l'accélération linéaire est projetée sur cette verticale, lissée par EMA
    `τ = 40 ms` ;
  - un pas est émis au **front montant** franchissant `1,0 m/s²`, réarmement
    sous `0,3`, intervalle minimal `330 ms`, warm-up `500 ms` ;
  - si `LINEAR_ACCELERATION` est absent, `ACCELEROMETER` sert avec soustraction
    d'une gravité EMA `τ = 0,5 s`.
- Les événements dupliqués ou désordonnés (`timestamp <= lastSeen`) sont
  rejetés partout.

## 5. Calibration 5 m — `PdrTracker.startCalibration / finishCalibration / finalizeCalibration`

1. Préconditions : `IDLE`, cap valide et récent, source utilisable, aucun
   échec d'enregistrement.
2. `startCalibration()` mémorise `calibStartNs`; **seuls les pas dont le
   timestamp tombe dans `[start, finish]`** comptent (les événements retardés
   datés d'avant le début sont ignorés).
3. `finishCalibration()` fige `calibFinishNs` et passe en `SETTLING` : la vue
   attend **2 s** pour drainer les pas matériels encore en transit (le repli
   accéléromètre finalise immédiatement).
4. `finalizeCalibration(now)` :
   - `n = 0` → échec `CALIBRATION_FAILED_NO_STEPS` ;
   - `L = 5,0 / n` doit rester dans `[0,2 ; 1,5]` m, sinon
     `CALIBRATION_FAILED_IMPLAUSIBLE_LENGTH` ;
   - le cap de référence est la moyenne à `now` (fin de marche), sinon
     `CALIBRATION_FAILED_NO_HEADING` ;
   - l'origine `(0,0)` est posée **au point d'arrêt**, `sessionCutoffNs`
     devient `max(dernier timestamp de pas vu, now)` : les pas de calibration
     retardés ne peuvent plus jamais déplacer le tracé.

Option : `startWithManualStepLength(L)` saute la calibration pour les tests,
avec les mêmes préconditions de cap frais.

## 6. Intégration pas à pas

Pour chaque pas accepté en `TRACKING` :

```
θᵢ   = meanHeadingAt(event.timestamp) − referenceYaw   (wrap ±π)
pos ← pos + (L·sin θᵢ, L·cos θᵢ)
distance += L ; stepCount++
```

- La position ne bouge que sur un pas **accepté** : tourner sur place n'avance
  rien (le cône visuel continue de tourner via `headingRadians`, indépendant).
- Pas rejetés (comptés `droppedStepCount`) : timestamp ≤ `sessionCutoff`,
  doublon (`≤ lastTrackedStep`), cap moyen absent (téléphone vertical ou
  buffer vide), longueur non calibrée, source inactive ou échec
  d'enregistrement.
- **Bornes** : `MilestoneTracker` interpole un marqueur tous les `5,0 m` de
  distance cumulée, sur le segment qui le franchit (tolérance `1e-9` contre
  les arrondis, fraction clampée dans `[0,1]`).

## 7. Reprises, pauses et trames capteurs

`GAME_ROTATION_VECTOR` peut réinitialiser son repère à chaque
enregistrement : **on ne connecte jamais deux trames**. `onSensorFrameRestart()`
(appelé à chaque `onForeground`, changement de source, recalibration ou
`onStop` via `pause()`) :

- vide le buffer d'orientation et la référence ;
- annule une calibration en cours → `IDLE` ; un tracking actif → `PAUSED`
  (tracé conservé, avertissement `SESSION_INTERRUPTED`) ;
- `resumeWithNewTrace()` exige un **cap frais** : nouvelle origine, nouvelle
  référence, `L` conservée. Sans cap valide, la reprise refuse sans toucher
  l'état.

`resetTrace()` fait pareil à la demande pendant le suivi ; `recalibrate()`
ramène à `IDLE` en remettant tout à zéro (y compris la source).

## 8. Affichage — `ui/TelemetryScreen.kt` + `ui/MapTransform.kt`

- La géométrie lissée (`PathGeometry.smoothedSegments`) est **purement
  visuelle** : chaîne `ligne – quadBézier… – ligne` dont les points de contrôle
  sont les sommets bruts et les extrémités les milieux → continuité C¹ aux
  milieux, endpoints réels préservés. Distances et bornes restent calculées
  sur les points bruts.
- `MapTransform` (pur Kotlin testé) convertit monde mètres ↔ écran pixels :
  `focus` = position (ou focus figé au premier pan), `pan`, `zoom` borné
  `0,2–12`, `rotation` = `0` (Axe fixe) ou `−heading` (Cap en haut). Les bornes
  de la grille proviennent de l'**inversion des coins du viewport**, donc la
  grille couvre l'écran même panné loin ; pas adaptatif parmi
  `0,5 / 1 / 2 / 5 / 10 / 20 / 50 / 100 m`.
- Le cône se fige en gris quand le cap devient invalide ; `headingRadians`
  garde la dernière valeur affichée plutôt que de retomber à 0.

### Ancrage plan (optionnel)

`map/FloorPlan.kt` charge `assets/floorplan.json` : un graphe de nœuds
(`corridor` / `room` / `stairs` / `elevator` …) et d'arêtes (`accessible`
ou non) en coordonnées normalisées `[0,1]` (y image vers le bas). Au
chargement, l'axe y est **inversé** (`posM.y = (1 − y)·H`) pour obtenir un
repère plan **nord en haut** : +y plan = haut de l'image = haut de l'écran
(`MapTransform` rend déjà +y monde vers le haut). L'utilisateur choisit
un nœud de départ puis une orientation (`ψ` : voisin adjacent via
`facingPsi`, ou cardinal plan 0/90/180/270°) sur l'écran de calibration.

`map/MapAnchor.kt` projette le repère PDR local sur le plan :

```
f = (sin ψ, cos ψ)   avant ;   r = (cos ψ, −sin ψ)   droite
toMap(p) = originM + r·p.x + f·p.y
cap plan affiché = wrap(ψ + headingRadians)
```

ψ = 0 → face au nord du plan (+y local → +y plan, identité) ;
ψ = π/2 → face à l'est (+y local → +x plan, droite → −y plan).
Chaque point du tracé, borne et position est passé par `toMap` avant
`MapTransform` ; le plan (arêtes, nœuds, étiquettes) est dessiné dans le
même repère monde. `PdrTracker` n'est jamais modifié : l'ancrage est une
pure couche d'affichage, indépendante de la phase PDR — un Reset repart
naturellement du nœud ancré. Sans ancre, le rendu local est inchangé.

## 9. Stabilisation anti-dérive, anti-shake, pas dynamique et recalage

- **Cap Swing-Twist 3D** (`atan2(R[1] − R[3], R[0] + R[4])`) : rotation autour
  de la verticale monde, insensible au tangage/roulis du poignet.
- **Référence pré-tap (`stableHeadingAt`)** : cap stable dans
  `[t − 500 ms, t − 100 ms]` avant l'appui sur l'écran ; la calibration
  moyenne le cap de ses pas.
- **Anti-shake passif** : secousse (`|a_lin| ≥ 11,5 m/s²` ou rotation
  `> 2,6 rad/s`) → pas rejeté ; sortie d'immobilité seulement après **2 pas
  rythmiques** (`280–1300 ms`).
- **Longueur de pas dynamique** : `L = L₀ · clamp(1 + 0,9·(f/f₀ − 1), 0,75, 1,30)`
  où `f` = cadence (médiane des 4 derniers intervalles entre pas) et `f₀` =
  cadence de référence (mesurée pendant la calibration 5 m, sinon apprise sur
  les 4 premiers intervalles, sauvegardée avec `L₀`). Marcher plus vite
  allonge les pas, plus lentement les raccourcit.
- **Stabiliser axes (activé par défaut)** — corrections **bornées et
  progressives**, appliquées seulement en marche droite (3 pas consécutifs
  à ±8° et rotation `< 0,5 rad/s`) :
  - cap : rapproché de l'axe du couloir du plan (ancré) ou des axes à 90°
    (plan, ou axe appris sur le 1er segment droit de 5 pas), si l'écart est
    `≤ 18°` / `≤ 12°`, gain 0,35, **max 2,5° par pas** ;
  - position (ancré) : uniquement la composante **latérale** vers l'axe d'un
    couloir **aligné avec la marche** (arêtes couloir↔couloir, `≤ 2 m`
    latéral, extrémités ±2 m), gain 0,25, **max 12 cm par pas** — jamais de
    rappel le long du couloir ni vers un couloir perpendiculaire ou une salle.
- **Recalage GPS (optionnel)** : un seul fournisseur (`FUSED`, sinon `GPS`),
  utilisé seulement si précision `≤ 8 m` et en mouvement ; l'écart est réparti
  sur les pas suivants (**max 8 cm par pas**, 1,5 m au total), l'historique
  du tracé n'est jamais réécrit. En intérieur il est en pratique inactif.

Corrigé : les sauts de ~5 m venaient (1) du recalage GPS/Wi-Fi en intérieur
(précision 10–25 m, origine mal posée, correction jusqu'à 25 % de l'écart
d'un coup) et (2) de la projection sur l'arête la plus proche, même
perpendiculaire ou vers une salle, avec rappel le long du couloir — ce qui
tirait le tracé en arrière après un virage (aspect « demi-tour »).

## 10. Routage multi-étages & Façade Backend (`BuildingGraph` + `IndoorNavigationBackend`)

- **Graphe multi-étages (`map/BuildingGraph.kt`)** :
  - Construit le graphe pondéré en mètres pour une liste `List<FloorPlan>`.
  - Relie automatiquement les étages adjacents lorsque deux nœuds partagent le
    même `vertical_group_id` (ex. `stairs_north_central`, `elevator_central`).
  - Mode **PMR (`accessibleOnly = true`)** : exclut toutes les arêtes
    `accessible = false` et les escaliers, forçant le passage par les ascenseurs
    lors des changements d'étage.
  - **Projection dynamique (`findRouteFromPosition`)** : projette la position
    courante `(x, y)` sur le segment praticable le plus proche, calcule le plus
    court chemin (Dijkstra), fusionne les tronçons de couloir colinéaires
    (`≤ 28°`) et génère l'instruction temps réel (`Continuer tout droit…`,
    `Tourner à droite…`, `Prendre l'ascenseur…`, `Arrivé à…`).
- **Intégration dans l'application finale (`backend/IndoorNavigationBackend.kt`)** :
  - Instancier `IndoorNavigationBackend(context, scope)` et observer
    `backend.state: StateFlow<NavigationSnapshot>`.
  - Pour ajouter d'autres étages plus tard, déposer simplement leurs fichiers
    JSON dans `app/src/main/assets/floors/` (ou appeler `backend.loadFloorPlans(...)`).

## 11. Où modifier quoi

| Besoin | Fichier |
| --- | --- |
| Façade unique à brancher dans l'app finale | `backend/IndoorNavigationBackend.kt` |
| Routage Dijkstra, PMR & multi-étages | `map/BuildingGraph.kt` |
| Fenêtre de moyenne de cap, seuil d'inclinaison | `pdr/OrientationProcessor.kt` |
| Seuils anti-shake, pas dynamique & stabilisation | `pdr/PdrTracker.kt` |
| Échelle initiale, limites de zoom | `ui/TelemetryScreen.kt` |

Tests : `app/src/test/java/com/hackdecouverte/` (93 tests : `pdr/`,
`ui/MapTransformTest`, `map/FloorPlanTest`, `map/MapAnchorTest`,
`map/CorridorTurnRegressionTest`, `map/BuildingGraphTest`).
