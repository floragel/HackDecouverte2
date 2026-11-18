# Suivi intérieur v1 — Plan 1 : carte précise et filtre de localisation (Android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** construire, depuis les SVG du MB et le graphe existant, une carte précise (murs, portes,
façade fermée, mobilier franchissable) avec sa carte des directions, et un filtre à particules
qui trouve la position en marchant ; le tout visible dans l'écran développeur, étage connu,
premier plan seulement.

**Architecture:** tout le calcul est en Kotlin pur dans `com.hackdecouverte.map` (carte) et le
nouveau paquet `com.hackdecouverte.loc` (filtre), testé sur JVM. La carte est construite au
chargement depuis le SVG (`FloorVectorPlan`), le graphe (`FloorPlan`) et un fichier de
retouches facultatif par étage (`assets/maps/MB-<n>-overlay.json`) produit par l'éditeur. Le
backend nourrit le filtre avec les déplacements du PDR et coupe alors les corrections du PDR
liées au plan.

**Tech Stack:** Kotlin, kotlinx.serialization (déjà présent), JUnit 4, Jetpack Compose (écran
développeur), HTML/JS + Python (`floor-plans/MB/editor.html`, `editor_server.py`).

**Spec:** `docs/superpowers/specs/2026-11-17-indoor-tracking-design.md` (§6.1–6.3, §6.2 bis,
§7, §7.1, §10 étapes 1–2).

**Découpage en plans** (le spec couvre plusieurs sous-systèmes indépendants) :
- **Plan 1 (celui-ci)** : carte précise, carte des directions, filtre, écran développeur.
- Plan 2 : enregistreur + rejeu + réglage (spec §9, étape 0), zone de départ, cercles et
  itinéraire dans l'écran utilisateur (étape 3).
- Plan 3 : étage (étape 4), téléphone en poche (étape 5).
- Plan 4 : arrière-plan Android (étape 6), puis portage Swift et arrière-plan iOS (étape 7).

## Global Constraints

- JDK 17 : `JAVA_HOME=/opt/homebrew/opt/openjdk@17`. Tests : `./gradlew :app:testDebugUnitTest`.
- Repère unique : mètres est/nord vrai, centré sur le noyau d'ascenseurs du MB (même repère
  que `FloorPlan.posM` et `FloorVectorPlan`). Cap : radians, 0 = nord, sens horaire,
  direction `(sin h, cos h)` en (est, nord).
- `pdr/`, `map/`, `loc/` restent sans dépendance Android ; horloge = temps depuis le
  démarrage (jamais `System.nanoTime`).
- Le tracé brut du PDR n'est jamais réécrit (AGENTS.md).
- Grille de la carte : 0,1 m par case.
- Aléatoire injecté (`kotlin.random.Random`) pour que les tests soient reproductibles.
- Après chaque tâche : tests au vert, puis commit **et push** sur `lync-algorythme` (consigne
  de l'utilisateur). Ne jamais ajouter `promo/` ni les docs d'autres sessions.
- Le filtre reste désactivé par défaut dans ce plan (interrupteur de l'écran développeur) : le
  comportement utilisateur actuel ne change pas avant le plan 2.

## Review Focus

1. **Retouches absentes ou JSON invalide** : la carte se construit quand même, avec les portes
   par défaut aux nœuds du graphe ; jamais de plantage → test dans la tâche 3.
2. **Toutes les particules deviennent improbables** (porte oubliée dans la carte) : le filtre
   réinjecte des particules, ne produit jamais `NaN` ni un ensemble vide, et repasse en
   `SEARCHING` → test dans la tâche 6.
3. **Déplacement PDR anormal** entre deux états (plusieurs pas fusionnés par le `StateFlow`,
   remise à zéro du tracé, longueur nulle) : un déplacement de plus de 3 m est traité comme une
   remise à zéro (aucun mouvement), un déplacement nul est ignoré → test dans la tâche 9.
4. **Tourner sur place ou rester immobile** : aucune particule ne bouge et les poids ne
   s'effondrent pas → test dans la tâche 6.
5. **Coût** : un pas avec 2 000 particules et la construction d'un étage restent dans le budget
   (≤ 2 ms par pas visé sur téléphone ; garde-fou JVM ≤ 20 ms par pas et ≤ 3 s par étage)
   → tests dans les tâches 3 et 6.

---

### Task 1: Grille d'occupation

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/map/OccupancyGrid.kt`
- Test: `app/src/test/java/com/hackdecouverte/map/OccupancyGridTest.kt`

**Interfaces:**
- Produces:
  - `class OccupancyGrid(val minEastM: Double, val minNorthM: Double, val resM: Double, val width: Int, val height: Int, val cells: ByteArray)`
    — case `(row, col)` à l'indice `row * width + col`, **ligne 0 = sud** (pas d'inversion).
  - Constantes `OccupancyGrid.FREE = 0`, `WALL = 1`, `PASSABLE = 2` (mobilier franchissable),
    `OUTSIDE = 3` (type `Byte`).
  - `fun indexOf(p: PointM): Int` (−1 hors grille), `fun centerOf(index: Int): PointM`,
    `fun cellAt(p: PointM): Byte` (`OUTSIDE` hors grille).
  - `fun isBlocked(p: PointM): Boolean` = `WALL` ou `OUTSIDE`.
  - `fun segmentBlocked(a: PointM, b: PointM): Boolean` : échantillonne tous les `resM / 2`.
  - `companion fun rasterize(polygons: List<List<PointM>>, minEastM: Double, minNorthM: Double, resM: Double, width: Int, height: Int): BooleanArray`
    — remplissage « nonzero winding » au centre de chaque case, tous les polygones dans un
    seul remplissage (les trous orientés à l'envers restent vides).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun rasterizeFillsSquareAndKeepsReversedHoleEmpty() {
    // carré 4×4 m (sens trigo) avec un trou 2×2 m (sens horaire), res 0,1 m, grille 60×60 depuis (-1,-1)
    val outer = listOf(PointM(0.0,0.0), PointM(4.0,0.0), PointM(4.0,4.0), PointM(0.0,4.0))
    val hole = listOf(PointM(1.0,1.0), PointM(1.0,3.0), PointM(3.0,3.0), PointM(3.0,1.0))
    val filled = OccupancyGrid.rasterize(listOf(outer, hole), -1.0, -1.0, 0.1, 60, 60)
    val g = OccupancyGrid(-1.0, -1.0, 0.1, 60, 60, ByteArray(3600) { if (filled[it]) OccupancyGrid.WALL else OccupancyGrid.FREE })
    assertTrue(g.isBlocked(PointM(0.5, 0.5)))
    assertFalse(g.isBlocked(PointM(2.0, 2.0)))      // trou
    assertFalse(g.isBlocked(PointM(4.5, 4.5)))      // dehors du carré, dans la grille
    assertTrue(g.isBlocked(PointM(10.0, 10.0)))     // hors grille = OUTSIDE
}
@Test fun segmentBlockedDetectsThinWall() {
    // mur vertical de 0,2 m d'épaisseur en x ∈ [2,0 ; 2,2]
    // segment (1,0;1,0)->(3,0;1,0) bloqué ; (1,0;1,0)->(1,9;1,0) libre
}
@Test fun indexAndCenterRoundTrip() { /* centerOf(indexOf(p)) à moins de resM/2 de p */ }
```

- [ ] **Step 2: Run tests, expect FAIL** — `./gradlew :app:testDebugUnitTest --tests 'com.hackdecouverte.map.OccupancyGridTest'` → compilation error (classe absente).

- [ ] **Step 3: Implement `OccupancyGrid`** — remplissage par balayage de lignes : pour chaque
  ligne de cases, intersecter le centre `y` avec toutes les arêtes de tous les polygones, trier
  les intersections par `x` avec leur sens (+1 montante, −1 descendante), et remplir les cases
  dont le centre est dans un intervalle où la somme des sens ≠ 0.

- [ ] **Step 4: Run tests, expect PASS** (même commande).

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/com/hackdecouverte/map/OccupancyGrid.kt app/src/test/java/com/hackdecouverte/map/OccupancyGridTest.kt
git commit -m "map: occupancy grid with nonzero-winding rasterizer"
git push origin lync-algorythme
```

---

### Task 2: Distance aux murs et carte des directions

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/map/DistanceField.kt`
- Create: `app/src/main/java/com/hackdecouverte/map/DirectionField.kt`
- Test: `app/src/test/java/com/hackdecouverte/map/DirectionFieldTest.kt`

**Interfaces:**
- Consumes: `OccupancyGrid` (tâche 1).
- Produces:
  - `class DistanceField(val grid: OccupancyGrid, val clearanceM: FloatArray)` avec
    `fun at(p: PointM): Double` (0 si bloqué ou hors grille) et
    `companion fun compute(grid: OccupancyGrid, blocked: (Byte) -> Boolean = { it == OccupancyGrid.WALL || it == OccupancyGrid.OUTSIDE }): DistanceField`
    — transformée de distance euclidienne exacte (Felzenszwalb–Huttenlocher, colonnes puis
    lignes), en mètres.
  - `class DirectionField(val grid: OccupancyGrid, val axisRad: FloatArray, val coherence: FloatArray)`
    avec `fun axisAt(p: PointM): Double` (cap modulo π, dans [0, π)) et
    `fun coherenceAt(p: PointM): Double` (0 si bloqué) ;
    `companion fun compute(grid: OccupancyGrid, clearance: DistanceField, doorCentersM: List<PointM>, windowCells: Int = 8, doorFadeM: Double = 1.5): DirectionField`.
  - `fun axialDiff(a: Double, b: Double): Double` (écart d'axes dans [0, π/2]), au niveau du
    fichier.

Algorithme de `DirectionField.compute` (repris de l'essai du spec §7.1) :

```text
pour chaque case libre : (gx, gy) = gradient centré de clearance (gx vers l'est, gy vers le nord)
Jxx = gx², Jxy = gx·gy, Jyy = gy²  (0 hors cases libres)
lisser Jxx, Jxy, Jyy par une moyenne sur une fenêtre carrée ±windowCells (tables cumulées)
cohérence = sqrt((Jxx−Jyy)² + 4·Jxy²) / (Jxx + Jyy)          (0 si somme < 1e-9)
φ = ½·atan2(2·Jxy, Jxx − Jyy)   — direction dominante du gradient, angle mathématique (x est, y nord)
axe = direction perpendiculaire à φ, convertie en cap : cap = atan2(cos(φ+π/2), sin(φ+π/2)), ramené dans [0, π)
cohérence ×= min(1, distance à la porte la plus proche / doorFadeM)
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun clearanceInTwoMetreCorridorIsOneMetreOnCentreline() { /* couloir est-ouest de 2 m, murs de 0,3 m : at(centre) ∈ [0,95 ; 1,05] */ }
@Test fun axisFollowsStraightCorridor() {
    // couloir est-ouest : axisAt(centre) à moins de 3° de π/2, cohérence ≥ 0,8
    // même couloir tourné de 40,5° (axe MB) : axe à moins de 3° de 40,5°+90° modulo 180°
}
@Test fun coherenceIsLowInOpenHallAndNearDoor() {
    // salle carrée de 12×12 m : cohérence au centre < 0,3
    // couloir avec porte en (5, 0) : cohérence à 0,3 m de la porte < 0,3
}
@Test fun mbFloor2AxesMatchGraphCorridors() {
    // construit grille brute (murs du SVG MB-2, formes < 0,75 m retirées) + DirectionField ;
    // pour les arêtes couloir↔couloir du graphe MB-2 de ≥ 3 m, points tous les 0,5 m hors 1,5 m des bouts :
    // part des points avec cohérence ≥ 0,5 ≥ 0,9 ; écart d'axe médian ≤ 3° (essai : 99 %, 0,98°)
}
```

- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.map.DirectionFieldTest'`.
- [ ] **Step 3: Implement `DistanceField.compute`, `DirectionField.compute`, `axialDiff`** (le test MB-2 peut utiliser une petite aide de test qui fait le tri des formes ; la vraie construction arrive en tâche 3).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit and push** — `git commit -m "map: clearance field and wall-derived corridor direction field"` puis `git push origin lync-algorythme`.

---

### Task 3: Carte précise d'un étage (construction + retouches)

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/map/IndoorMap.kt` (carte + constructeur)
- Create: `app/src/main/java/com/hackdecouverte/map/MapOverlay.kt` (format des retouches)
- Test: `app/src/test/java/com/hackdecouverte/map/IndoorMapBuilderTest.kt`

**Interfaces:**
- Consumes: `FloorVectorPlan` (contours en mètres est/nord : `packedEastNorthM`,
  `contourStartPairIndex`, `contourPointCounts`), `FloorPlan` (`nodes`, `edges`, `type`,
  `posM`), tâches 1–2.
- Produces:
  - `@Serializable data class MapOverlay(val floorId: String, val doors: List<OverlayDoor> = emptyList(), val removeDoorNodeIds: List<String> = emptyList(), val extraWalls: List<List<List<Double>>> = emptyList(), val passableAreas: List<List<List<Double>>> = emptyList())`
    et `@Serializable data class OverlayDoor(val east: Double, val north: Double, val widthM: Double = 0.9, val nodeId: String? = null)`.
    Les polygones sont des listes de `[est, nord]`. `companion fun parse(text: String): MapOverlay?`
    (renvoie `null` si le JSON est invalide).
  - `data class DoorOpening(val centerM: PointM, val widthM: Double, val nodeId: String?)`
  - `class IndoorMap(val floorId: String, val grid: OccupancyGrid, val clearance: DistanceField, val directions: DirectionField, val doors: List<DoorOpening>, val dominantAxisRad: Double)`
    avec `fun freeCellIndices(): IntArray` (cases `FREE` ou `PASSABLE`).
  - `object IndoorMapBuilder { fun build(vector: FloorVectorPlan, plan: FloorPlan, overlay: MapOverlay?, resM: Double = 0.1): IndoorMap }`
  - `fun loadIndoorMap(floorNumber: Int, open: (String) -> InputStream?): IndoorMap?` — lit
    `floor-plans/MB/MB-floor-0<n>.svg`, `floors/floorplan-MB-<n>.json` et, s'il existe,
    `maps/MB-<n>-overlay.json` (même fonction pour les assets Android et le classpath de test).

Valeurs du spec (§7, §7.1) à utiliser telles quelles : formes retirées si leur plus grande
dimension < **0,75 m** ; façade fermée en bouchant les ouvertures ≤ **1,6 m** (extérieur =
cases atteintes depuis le bord avec une marge de **0,8 m** aux murs, puis élargi de **0,95 m**) ;
porte par défaut à chaque nœud `room` du graphe, ouverture de **1,0 m** de diamètre (rayon
0,5 m) sauf si le nœud est dans `removeDoorNodeIds` ; `dominantAxisRad` =
`plan.dominantCorridorAxisRad`.

Ordre de construction : murs (contours gardés + `extraWalls`) → extérieur (`OUTSIDE`) calculé
sur les murs **avant** d'ouvrir les portes → ouverture des portes (graphe puis `overlay.doors`)
→ `passableAreas` : cases `WALL` → `PASSABLE` → distance aux murs → carte des directions
(centres des portes en paramètre).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun buildsMbFloor2FromBundledAssetsWithoutOverlay() {
    val map = loadIndoorMap(2) { javaClass.classLoader!!.getResourceAsStream(it) }!!
    // au moins 90 % des nœuds couloir du graphe MB-2 sur une case libre
    // aucune case libre accessible depuis les nœuds couloir ne touche le bord de la grille (façade fermée)
    // map.doors.size == nombre de nœuds room du graphe MB-2 (42)
}
@Test fun overlayDoorOpensSealedRoomAndRemovalClosesOne() { /* carte synthétique : couloir + salle fermée ; une OverlayDoor relie la salle (chemin libre via segmentBlocked/flood) ; removeDoorNodeIds referme */ }
@Test fun passableAreaTurnsWallIntoPassable() { /* rangée de sièges dessinée en mur dans passableAreas → cellAt == PASSABLE, segment non bloqué */ }
@Test fun invalidOverlayJsonFallsBackToGraphDoors() { assertNull(MapOverlay.parse("{not json")); /* build(..., overlay = null) réussit */ }
@Test fun buildingOneFloorTakesUnderThreeSecondsOnJvm() { /* chronométrer loadIndoorMap(2) après un premier appel d'échauffement */ }
```

- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.map.IndoorMapBuilderTest'`.
- [ ] **Step 3: Implement `MapOverlay`, `IndoorMap`, `IndoorMapBuilder`, `loadIndoorMap`.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit and push** — `git commit -m "map: build precise floor map from SVG, graph and optional overlay"` puis push.

---

### Task 4: Contrôles automatiques de la carte

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/map/MapValidator.kt`
- Test: `app/src/test/java/com/hackdecouverte/map/MapValidatorTest.kt`

**Interfaces:**
- Consumes: `IndoorMap`, `FloorPlan`.
- Produces:
  - `data class MapReport(val edgesCrossingWalls: List<Pair<String, String>>, val doorNodesBlocked: List<String>, val unreachableDoorNodes: List<String>, val facadeClosed: Boolean)`
    avec `val ok: Boolean` (les trois listes vides et `facadeClosed`) et `fun summary(): String`.
  - `object MapValidator { fun validate(map: IndoorMap, plan: FloorPlan): MapReport }` :
    arête du graphe qui traverse un mur (`segmentBlocked`) ; nœud `room` sur une case bloquée ;
    nœud `room` dont la case n'est pas atteignable depuis un nœud couloir ; façade fermée = aucune
    case atteignable depuis les couloirs sur le bord de la grille.

- [ ] **Step 1: Write the failing tests** — carte synthétique saine → `ok` ; arête qui traverse
  un mur → listée ; porte murée → `doorNodesBlocked` et `unreachableDoorNodes` ; test
  `reportsAllSevenMbFloors()` qui construit les 7 étages et imprime `summary()` sans rien
  affirmer d'autre que « la construction réussit » (les 7 étages ne passeront qu'après la passe
  manuelle de la tâche 5).
- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.map.MapValidatorTest'`.
- [ ] **Step 3: Implement `MapValidator`.**
- [ ] **Step 4: Run, expect PASS** ; noter dans le message de commit les chiffres imprimés pour
  le MB-2 (point de départ de la passe manuelle).
- [ ] **Step 5: Commit and push** — `git commit -m "map: automatic map checks (walls, doors, reachability, facade)"` puis push.

---

### Task 5: Retouches dans l'éditeur

**Files:**
- Modify: `floor-plans/MB/editor.html`
- Modify: `floor-plans/MB/editor_server.py`
- Modify: `floor-plans/MB/README.md` (section « Retouches de carte »)
- Create (produit par l'éditeur, puis commité) : `app/src/main/assets/maps/MB-2-overlay.json`

**Interfaces:**
- Consumes: le format `MapOverlay` de la tâche 3 (mêmes noms de champs, coordonnées
  `[est, nord]` en mètres).
- Produces: un mode « Retouches » dans l'éditeur avec 4 outils : ajouter une porte (clic sur le
  mur, largeur 0,9 m par défaut, rattachée au nœud `room` le plus proche à moins de 3 m) ;
  supprimer la porte par défaut d'un nœud ; dessiner un mur en plus (polygone) ; dessiner une
  zone de mobilier franchissable (polygone). `editor_server.py` expose
  `GET/POST /overlay/<floor>` qui lit/écrit `app/src/main/assets/maps/MB-<n>-overlay.json`.

- [ ] **Step 1: Ajouter le mode « Retouches » et les deux routes serveur** (même style que le code existant de l'éditeur).
- [ ] **Step 2: Vérifier à la main** : lancer l'éditeur (`floor-plans/MB/start-editor.bat` ou
  `python3 floor-plans/MB/editor_server.py`), ajouter une porte et une zone franchissable sur le
  MB-2, enregistrer, et vérifier que `maps/MB-2-overlay.json` se relit avec
  `MapOverlay.parse` (relancer `MapValidatorTest.reportsAllSevenMbFloors`).
- [ ] **Step 3: Passe manuelle du MB-2** jusqu'à ce que `MapValidator.validate` renvoie `ok`
  pour le MB-2 ; **noter le temps passé** (risque du spec §13) dans le README.
- [ ] **Step 4: Ajouter au test de la tâche 4** l'assertion `report.ok` pour le MB-2 et la faire passer.
- [ ] **Step 5: Commit and push** — `git commit -m "editor: map overlay mode; MB-2 overlay passes all map checks"` puis push.

(Les 6 autres étages suivent la même passe ; c'est du travail de saisie, pas de code. Commencer
par les étages 1 et 7, les moins bien alignés.)

---

### Task 6: Filtre à particules — mouvement, murs, carte des directions

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/loc/LocModels.kt`
- Create: `app/src/main/java/com/hackdecouverte/loc/LocalizationFilter.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/LocalizationFilterTest.kt`
- Test helper: `app/src/test/java/com/hackdecouverte/loc/SyntheticMaps.kt` (couloir en L
  A(0,0)→B(0,20)→C(25,20), 2 m de large ; couloir droit symétrique ; salle ouverte 12×12 m)

**Interfaces:**
- Consumes: `IndoorMap` (tâche 3).
- Produces (dans `LocModels.kt`) :
  - `data class LocParams(val maxParticles: Int = 2000, val minParticles: Int = 300, val headingNoiseRad: Double = Math.toRadians(3.0), val lengthNoise: Double = 0.05, val offsetDriftRad: Double = Math.toRadians(0.5), val scaleDrift: Double = 0.005, val wallFactor: Double = 0.02, val beta: Double = 0.2, val resampleEssRatio: Double = 0.5, val recoveryFraction: Double = 0.02, val recoveryRadiusM: Double = 15.0)`
  - `enum class LocState { SEARCHING, APPROXIMATE, FOUND }`
  - `data class LocZone(val centerM: PointM, val radiusM: Double, val weight: Double, val headingRad: Double)`
  - `data class LocEstimate(val zones: List<LocZone>, val state: LocState)` — `zones` triées par poids décroissant.
- Produces (dans `LocalizationFilter.kt`) :
  - `class LocalizationFilter(val map: IndoorMap, val params: LocParams = LocParams(), private val random: Random = Random.Default)`
  - `fun initUniform(count: Int = params.maxParticles)` — positions tirées uniformément sur
    `map.freeCellIndices()`, échelle 1,0, angle « inconnu » (géré en tâche 7 ; ici angle
    uniforme sur [0, 2π)).
  - `fun initAt(points: List<PointM>, offsetsRad: List<Double>)` — pour les tests.
  - `fun onStep(lengthM: Double, headingRad: Double)` — `headingRad` = cap relatif du PDR.
  - `fun size(): Int`, `fun samplePositions(max: Int): List<PointM>` (pour l'affichage).
  - `fun estimate(): LocEstimate` — implémentée en tâche 8 ; ici une version minimale qui renvoie
    une seule zone (moyenne pondérée) suffit aux tests.

Comportement de `onStep` (spec §6.3) :
1. Chaque particule `i` : `h = heading + offset_i + N(0, headingNoiseRad)`,
   `L = length · scale_i · (1 + N(0, lengthNoise))`, nouvelle position `p + L·(sin h, cos h)`.
   `offset_i += N(0, offsetDriftRad)`, `scale_i = clamp(scale_i + N(0, scaleDrift), 0.8, 1.25)`.
2. Si `map.grid.segmentBlocked(p, p')` : poids × `wallFactor` (la particule avance quand même).
3. Facteur d'alignement : `c = map.directions.coherenceAt(p')`,
   `d = axialDiff(h, map.directions.axisAt(p'))`, poids × `1 − beta · c · sin²(d)`.
4. Normaliser ; si la somme des poids est 0 ou non finie : réinitialiser comme en cas de
   secours (étape 6) sur toute la carte et marquer `SEARCHING`.
5. Rééchantillonnage systématique si `ESS < resampleEssRatio · N` ; nouvelle taille
   `minParticles` si l'état est `FOUND`, sinon `maxParticles`.
6. À chaque rééchantillonnage, remplacer `recoveryFraction` des particules par des particules
   tirées uniformément parmi les cases libres à moins de `recoveryRadiusM` du centre de la zone
   principale, avec l'angle et l'échelle d'une particule existante tirée au hasard.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun walkingIntoWallLosesWeightToCorridorFollowers() {
    // 2 particules au centre du couloir est-ouest, offsets 0 et π/2 (π/2 = va vers un mur) ; 3 pas de 0,7 m cap relatif 0
    // poids normalisé de la particule qui suit le couloir > 0,9
}
@Test fun lCorridorConvergesFromUniformStartWithKnownOffset() {
    // filtre seedé (Random(42)), initUniform puis offsets forcés à 0 ; marche 29 pas à 0,7 m vers le nord puis 30 vers l'est
    // estimate().zones[0].centerM à moins de 2 m de (21, 20) ; aucun NaN
}
@Test fun allParticlesBlockedTriggersRecoveryNotEmptySet() {
    // toutes les particules dans une salle sans porte, 10 pas qui traversent les murs
    // size() > 0, aucun poids NaN, estimate().state == SEARCHING
}
@Test fun standingStillOrTurningInPlaceDoesNotMoveOrCollapse() {
    // 0 appel à onStep pendant 20 « secondes » simulées puis 1 pas de 0 m : positions identiques, ESS inchangé
}
@Test fun stepWith2000ParticlesUnder20msOnJvm() { /* moyenne sur 200 pas après échauffement, carte MB-2 */ }
```

- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.loc.LocalizationFilterTest'`.
- [ ] **Step 3: Implement `LocModels.kt` and `LocalizationFilter`** — stockage en tableaux
  parallèles (`DoubleArray` est/nord/offset/échelle/poids) pour tenir le budget.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit and push** — `git commit -m "loc: particle filter with wall elimination and corridor-direction weighting"` puis push.

---

### Task 7: Angle de départ par la première ligne droite

**Files:**
- Modify: `app/src/main/java/com/hackdecouverte/loc/LocalizationFilter.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/StartHeadingTest.kt`

**Interfaces:**
- Consumes: tâche 6, `IndoorMap.dominantAxisRad`.
- Produces: `fun initUniform(...)` met désormais les particules en angle **inconnu** ;
  `val offsetKnown: Boolean`.

Comportement (spec §6.2 bis, usage 2) :
- Tant que l'angle est inconnu, `onStep` ne déplace pas les particules : il garde les pas
  `(length, heading)` dans un tampon.
- Dès que les **5** derniers pas ont des caps relatifs à moins de **8°** de leur moyenne
  circulaire `m` : pour chaque particule, si `coherenceAt(p) ≥ 0.5`, offset = `axe(p) − m` ou
  `axe(p) + π − m` (une chance sur deux) ; sinon offset = `dominantAxisRad + k·π/2 − m`, `k`
  tiré dans {0, 1, 2, 3}. **10 %** des particules reçoivent un offset uniforme sur [0, 2π).
  Puis on rejoue tout le tampon avec les étapes 1–6 de la tâche 6, et `offsetKnown = true`.
- Si **20** pas passent sans ligne droite : offsets uniformes, rejouer le tampon,
  `offsetKnown = true`.
- `initAt(...)` (offsets fournis) met directement `offsetKnown = true`.

- [ ] **Step 1: Write the failing tests** — `lCorridorConvergesWithUnknownOffset()` : départ
  uniforme seedé, angle inconnu, même marche en L que la tâche 6 mais avec un cap relatif
  décalé de 73° (le PDR n'est pas aligné sur le plan) ; zone principale à moins de 2 m de
  (21, 20) à la fin. `noStraightLineFallsBackAfter20Steps()` : 20 pas en zigzag → `offsetKnown`.
- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.loc.StartHeadingTest'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS** (et la tâche 6 reste verte).
- [ ] **Step 5: Commit and push** — `git commit -m "loc: learn start heading offset from the first straight line"` puis push.

---

### Task 8: Zones, états et signal d'alarme

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/loc/ZoneClustering.kt`
- Modify: `app/src/main/java/com/hackdecouverte/loc/LocalizationFilter.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/ZoneClusteringTest.kt`

**Interfaces:**
- Produces:
  - `object ZoneClustering { fun zones(east: DoubleArray, north: DoubleArray, headings: DoubleArray, weights: DoubleArray, n: Int, cellM: Double = 3.0): List<LocZone> }`
    — regroupe les particules par cases de 3 m connectées (8 voisins) ; centre = moyenne
    pondérée ; rayon = `2.45 · sqrt((varEst + varNord) / 2)` (environ 95 %) ; cap = moyenne
    circulaire pondérée de `heading + offset` au dernier pas.
  - `LocalizationFilter.estimate()` utilise ces zones. État (spec §5, §6.3) : `FOUND` si
    zone principale > **0,9** du poids et rayon < **3 m** ; `APPROXIMATE` si > 0,6 et rayon
    ≤ **8 m** ; sinon `SEARCHING`.
  - Signal d'alarme (spec §6.3, ton plan) : si, sur les **5** derniers pas, la moyenne du
    facteur d'alignement de la zone principale est < `1 − beta/2` ou si plus de la moitié de ses
    particules ont touché un mur, réinjecter **10 %** de particules de secours et ne pas
    renvoyer `FOUND` au pas suivant.

- [ ] **Step 1: Write the failing tests** — deux nuages séparés de 20 m (poids 0,7/0,3) → 2
  zones dans l'ordre ; nuage compact (σ 0,5 m, 100 % du poids) → `FOUND` ; zone principale qui
  marche 5 pas en travers d'un couloir net → pas `FOUND` et taille augmentée de 10 %.
- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.loc.ZoneClusteringTest'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS** (toute la suite `loc` verte).
- [ ] **Step 5: Commit and push** — `git commit -m "loc: zones, SEARCHING/APPROXIMATE/FOUND states and alarm signal"` puis push.

---

### Task 9: Branchement au backend (interrupteur développeur)

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/backend/LocalizationController.kt`
- Modify: `app/src/main/java/com/hackdecouverte/backend/IndoorNavigationBackend.kt`
- Test: `app/src/test/java/com/hackdecouverte/backend/LocalizationControllerTest.kt`

**Interfaces:**
- Consumes: `loadIndoorMap` (tâche 3), `LocalizationFilter` (tâches 6–8), `PdrState`.
- Produces:
  - `class LocalizationController(private val mapProvider: (floorId: String) -> IndoorMap?, private val random: Random = Random.Default)`
    - `fun start(floorId: String): Boolean` — construit (ou reprend du cache) la carte de
      l'étage et appelle `initUniform()` ; `false` si la carte est introuvable.
    - `fun onPdrState(s: PdrState)` — déplacement = `s.position − dernière position` dans le
      repère local du PDR ; longueur = norme, cap = `atan2(dx, dy)`. Ignore un déplacement nul ;
      un déplacement > **3 m** (pas fusionnés en masse, remise à zéro) ne bouge pas les
      particules et remet la position de référence ; ignore tout ce qui n'est pas `TRACKING`.
    - `fun stop()`, `val estimate: LocEstimate?`, `fun samplePositions(max: Int): List<PointM>`.
  - Dans `NavigationSnapshot` : `val localization: LocEstimate? = null` et
    `val localizationParticles: List<PointM> = emptyList()` (au plus 400 points).
  - Dans `IndoorNavigationBackend` : `fun setLocalizationEnabled(enabled: Boolean)`. Quand c'est
    activé : construction des cartes sur `Dispatchers.Default` ; si la phase PDR est `IDLE` et
    `canStart`, `startWithSavedOrDefaultStepLength()` (le PDR démarre tout seul, spec §6.1) ;
    `tracker.corridorConstraint` renvoie `null`, `matchJunction` et `tracker.onExternalFix` ne
    sont plus appelés, `tracker.mapAxisBaseLocal = null` (la stabilisation sur les axes appris
    reste) ; chaque `PdrState` reçu est passé à `onPdrState`. Désactivé : tout revient comme
    avant (`recomputeAnchor()`).

- [ ] **Step 1: Write the failing tests** (sur le contrôleur, avec une carte synthétique en L) :
  déplacements successifs → les particules bougent ; déplacement de 5 m → aucune particule ne
  bouge ; état `IDLE` → ignoré ; `start` sur un étage inconnu → `false`.
- [ ] **Step 2: Run, expect FAIL** — `--tests 'com.hackdecouverte.backend.LocalizationControllerTest'`.
- [ ] **Step 3: Implement the controller and the backend wiring.**
- [ ] **Step 4: Run all unit tests and build** — `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit and push** — `git commit -m "backend: localization controller behind a developer switch"` puis push.

---

### Task 10: Écran développeur — particules, zones, état

**Files:**
- Modify: `app/src/main/java/com/lync/app/ui/indoor/IndoorDevScreen.kt`
- Modify: `app/src/main/java/com/hackdecouverte/ui/MapLayers.kt` (nouvelle couche)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: `NavigationSnapshot.localization`, `localizationParticles`,
  `IndoorNavigationBackend.setLocalizationEnabled` (tâche 9).
- Produces: un interrupteur « Localisation par la carte » dans l'écran développeur ; une couche
  qui dessine les particules (points de 2 dp), chaque zone (cercle translucide de rayon
  `radiusM`, la principale plus opaque) et une ligne d'état (`SEARCHING` / `APPROXIMATE` /
  `FOUND`, nombre de particules, coût moyen d'un pas en ms).

- [ ] **Step 1: Ajouter l'interrupteur, la couche et les textes** (FR : « Localisation par la carte », « Recherche », « Approximatif », « Trouvé » ; EN : « Map localization », « Searching », « Approximate », « Found »).
- [ ] **Step 2: Build and lint** — `./gradlew :app:assembleDebug :app:lintDebug` → aucune nouvelle erreur.
- [ ] **Step 3: Vérifier sur téléphone** (MB-2) : activer l'interrupteur, marcher ~30 m avec au
  moins un virage ; attendu : les particules couvrent l'étage, se regroupent, l'état passe à
  `FOUND` à l'endroit réel. Noter la distance marchée avant `FOUND` (cible du spec : médiane
  ≤ 50 m sans position système).
- [ ] **Step 4: Commit and push** — `git commit -m "dev screen: map localization switch with particles and zones"` puis push.

---

## Hors de ce plan (plans suivants)

- Vraisemblance tolérante de la position du système (spec §6.3, « Mesures »), zone GPS et
  propagation au retour (spec §6.4), cercles et itinéraire dans l'écran
  utilisateur (§6.9) ; enregistreur et rejeu, réglage de `beta` (§9) → plan 2.
- Étage, baromètre, particules sur deux étages (§6.5) ; poche (§6.6) → plan 3.
- Arrière-plan (§6.7), foulée système (§6.8), portage Swift (§6.10) → plan 4.
