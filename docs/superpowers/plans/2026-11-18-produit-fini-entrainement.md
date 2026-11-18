# Produit fini : suivi intérieur entraîné sur de vraies marches — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finir l'app Android de localisation intérieure (collège Stanislas, MB en bonus) : un filtre qui *suit* vraiment l'utilisateur en main comme en poche, des réglages appris automatiquement sur des marches réelles dont le trajet a été tracé, un écran utilisateur final sans calibration, et des critères d'acceptation chiffrés vérifiés sur des marches jamais vues pendant l'entraînement.

**Architecture:**
- **Mesurer d'abord (phase A).** La vérité terrain est le trajet tracé à la main, recalé dans le temps sur les pas. Elle mesure le PDR et le filtre avec une seule fonction d'évaluation. Tous les réglages sont regroupés dans un fichier `params.json` livré avec l'app.
- **Réparer le suivi (phase B).** Murs souples aux portes, qualité des pas (pivots), seuils d'état réglables, reprise quand le filtre est perdu, boussole filtrée par l'intensité du champ.
- **Entraîner (phase C).** CMA-ES en deux étages (PDR puis filtre), régularisé vers les valeurs par défaut, validation croisée « une marche de côté », et une porte d'acceptation qui refuse tout réglage qui dégrade une marche tenue à l'écart.
- **Cartes, produit, données (phases D, E, F).**

**Tech Stack:**
- Kotlin, JDK 17, Android (minSdk 26, targetSdk 36), Jetpack Compose, kotlinx.serialization, JUnit 4.
- Aucune nouvelle dépendance : CMA-ES et DTW sont écrits en Kotlin, côté tests.

**Spec:** `docs/superpowers/specs/2026-11-17-indoor-tracking-design.md` (sections 5, 6.3–6.7, 9, 10, 13). Notes de recherche : `docs/research/2026-11-18-zero-effort-indoor-localization.md`.

## Ce que « produit fini » veut dire ici

- **Inclus :**
  - **Collège :** l'app Android sur le collège (STAN-1), avec l'écran utilisateur final comme écran d'accueil et la carte de débogage derrière « Réglages → Mode développeur ».
  - **Réglages entraînés :** des valeurs apprises et validées sur au moins 3 marches réelles tracées.
  - **Robustesse :** le suivi en main, en poche, main qui balance, écran éteint et en arrière-plan.
  - **MB :** les 7 étages passent les contrôles de carte (§7 de la spec) et le changement d'étage reste fonctionnel.
- **Exclus :**
  - **Le portage iOS** (spec §10, étape 7) : il fera l'objet d'un plan séparé.
  - **La recherche** (GNSS par fenêtres, plan d'évacuation par photo, odométrie apprise) : voir `docs/research/`.
  - **Le Wi-Fi RTT :** les bornes du collège n'y répondent pas, c'est mesuré.

## État de départ (mesuré le 18/11 sur la marche réelle `STAN-1_20261118-150346`)

Marche avec un Pixel 9, en boucle, 139 s, téléphone en poche et écran éteint. Le trajet a été tracé dans `recordings/STAN-1_20261118-150346.truth.json` (81 m).

**Le PDR** (commit `16671d3`) :
- distance moyenne (DTW) au trajet réel 1,78 m ;
- écart de fermeture de la boucle 6,4 % ;
- 91 m comptés pour 81 m réels.

**Le filtre ne te trouve jamais à froid,** et **il te perd même avec le départ connu.** Placé à 1 m de la vérité, il reste en RECHERCHE environ 100 % des pas et finit à 19–25 m (3 tirages au hasard, 3 à 5 alarmes). C'est le problème n° 1.

**Les causes déjà mesurées :**
- le gyroscope dérive d'environ 25° en 2 min ;
- la boussole oscille de ±35° à l'intérieur ;
- le détecteur de pas compte les pivots (entre 40 et 50 s : 11 pas contre 1 au compteur) ;
- le cap en poche varie de ±13–20° dans une même foulée.

**Les cartes** se construisent en 0,18 s (STAN-1) et 0,41 s (MB-2) sur la JVM, et en 39–49 s sur l'émulateur. Le temps sur un vrai téléphone n'est pas mesuré.

**La base de code :**
- branche `lync-algorythme`, 207 tests ;
- outils existants : `WalkReplay`, `WalkAnalysis`, `TruthEval`, `PdrLoopTuningTest`, `PathGuessTest`, `tools/trace-STAN-1.html`.

## Global Constraints

- **Outils :** JDK 17 (`/opt/homebrew/opt/openjdk@17`), avec `local.properties` qui contient `sdk.dir=/Users/Apple/Library/Android/sdk`.
- **Commandes :**
  - tests `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest` ;
  - lint `… :app:lintDebug` ;
  - APK `… :app:assembleDebug`.
- **Code pur Kotlin :** `pdr/`, `loc/`, `map/` et `rec/` restent sans Android, testés sur la JVM. Le code d'entraînement vit dans les tests (`app/src/test/java/com/hackdecouverte/rec/…`).
- **Corrections du PDR (AGENTS.md) :**
  - bornées à chaque pas : cap ≤ 2,5° par pas, latéral ≤ 12 cm par pas, GPS ≤ 8 cm par pas ;
  - aucun point passé de la trace n'est jamais réécrit ;
  - la position ne change qu'à un pas accepté.
- **Horloge :** les horodatages des capteurs (`elapsedRealtimeNanos`, CLOCK_BOOTTIME) font foi. Jamais `System.nanoTime` pour l'horloge du PDR.
- **Interface :** en français ; textes visibles dans `res/values-fr/strings.xml` et `res/values/strings.xml`.
- **Vie privée :**
  - `recordings/` reste ignoré par git (BSSID Wi-Fi, trajets) ;
  - `params.json` et les rapports d'entraînement ne contiennent que des nombres et des noms de marches ;
  - rien n'est envoyé à un service extérieur.
- **Git :**
  - commit et push après chaque tâche testée, sur `lync-algorythme` ;
  - jamais `promo/`, `.claude/` ni `docs/superpowers/specs/2026-11-16-promo-video-design.md` ;
  - jamais de push forcé ni de push sur `main`.
- **Cibles de la spec §9** (reprises dans la section « Critères d'acceptation ») : erreur une fois trouvé, en main, médiane ≤ 1,5 m et 90 % ≤ 3 m ; en poche, médiane ≤ 2,5 m et 90 % ≤ 5 m ; « Trouvé » faux (> 5 m) ≤ 5 % ; batterie écran éteint ≤ 5 % par heure.

## Review Focus

1. **Sur-apprentissage :** des réglages excellents sur les 3 marches d'entraînement et pires sur une nouvelle route. La porte d'acceptation doit les refuser. Test `gateRefusesParamsWorseOnAHeldOutWalk` (tâche C2).
2. **Perturbation magnétique longue :** 30 s le long de casiers en acier, que le recalage continu par la boussole (constante de 8 s) suivrait jusqu'à environ 45° d'erreur. Le champ anormal doit couper la boussole. Test `aLongMagneticDisturbanceDoesNotTurnTheHeading` (tâche B6).
3. **Départ à froid dans une salle :** c'était le cas de la vraie marche. Le filtre doit trouver la sortie et l'utilisateur. Test `coldStartInsideARoomIsFoundAfterLeavingIt` (tâche B4).
4. **Itinéraire qui saute entre deux portes** d'une salle à plusieurs portes pendant APPROXIMATIF. Il faut de l'hystérésis. Test `routeDoesNotFlapBetweenTwoDoors` (tâche E1).
5. **Pivots et esquives :** des pas comptés pendant qu'on tourne sur place, qui ne doivent pas faire perdre le suivi. Test `aPivotWithSpuriousStepsKeepsTheTrack` (tâche B3).

---

## Structure des fichiers

| Fichier | Rôle | Tâche |
|---|---|---|
| `app/src/main/java/com/hackdecouverte/tuning/TuningParams.kt` (nouveau, pur) | Tous les réglages entraînables, valeurs par défaut = comportement actuel, lecture de `tuning/params.json` | A2 |
| `app/src/main/assets/tuning/params.json` (nouveau) | Réglages livrés (écrit par l'entraînement) | A2, C2, F2 |
| `app/src/test/java/com/hackdecouverte/rec/truth/TruthTrack.kt` (nouveau) | Format de vérité v2, recalage temporel trajet ↔ pas | A1 |
| `app/src/test/java/com/hackdecouverte/rec/train/WalkEval.kt` (nouveau) | Évaluation unique du PDR et du filtre (métriques, support, exécution parallèle) | A3 |
| `app/src/test/java/com/hackdecouverte/rec/train/Datasets.kt` (nouveau) | Chargement des marches qui ont une vérité | A3 |
| `app/src/test/java/com/hackdecouverte/rec/train/Cmaes.kt` (nouveau) | Optimiseur CMA-ES | C1 |
| `app/src/test/java/com/hackdecouverte/rec/train/Trainer.kt`, `TrainModel.kt` (nouveaux) | Entraînement en deux étages, validation croisée, porte d'acceptation, rapport | C2 |
| `app/src/test/java/com/hackdecouverte/rec/TraceToolTest.kt` (modifié) | Génère `tools/trace-<étage>.html` v2 pour chaque étage | A4 |
| `app/src/main/java/com/hackdecouverte/rec/WalkReplay.kt` (modifié) | `tuning`, `startHint`, `stepObserver` | A3 |
| `app/src/main/java/com/hackdecouverte/loc/LocalizationFilter.kt`, `LocModels.kt` (modifiés) | Murs souples, qualité des pas, seuils réglables, reprise | B2–B5 |
| `app/src/main/java/com/hackdecouverte/pdr/PdrTracker.kt`, `CarryMonitor.kt` (modifiés) | Boutons de réglage, qualité des pas, boussole filtrée | A2, B3, B6 |
| `app/src/main/java/com/hackdecouverte/sensor/SensorRouter.kt`, `PdrSensorAdapter.kt` (modifiés) | Champ magnétique dans le flux du PDR | B6 |
| `app/src/main/java/com/hackdecouverte/map/IndoorMap.kt` (modifié) ; `map/BakedMap.kt` (nouveau, conditionnel) | Largeur des portes réglable, voisinage des portes, cartes précalculées | B2, D2 |
| `app/src/main/java/com/hackdecouverte/backend/LocalizedNavigation.kt` (nouveau, pur) | Position localisée, itinéraire depuis la zone, arrivée, raccourci « Je suis à… » | E1 |
| `app/src/main/java/com/hackdecouverte/backend/IndoorNavigationBackend.kt` (modifié) | Branchement des réglages, de la navigation localisée et des mesures | A2, E1, E3 |
| `app/src/main/java/com/lync/app/ui/indoor/IndoorScreen.kt`, `IndoorCanvas.kt`, `MainScreen.kt`, `settings/SettingsScreen.kt` (modifiés) | Écran utilisateur final | E2 |
| `floor-plans/STAN/measurements.json` (nouveau) | Distances mesurées sur place | D1 |
| `docs/data/protocole-marches.md` (nouveau) | Protocole des marches d'entraînement | F1 |
| `docs/training/<date>-<n>-report.md` (nouveaux) | Rapports d'entraînement | C3, F2 |
| `app/build.gradle.kts` (modifié) | Transmission de `-Dtrain/-Daccept/-Dsweep/-Dloop/-Dguess/-Dreplay` à la JVM des tests, mémoire de 4 Go | A3 |

---

## Phase A — Mesurer (vérité terrain, réglages, évaluation)

### Task A1 : Vérité terrain v2 et recalage dans le temps

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/truth/TruthTrack.kt`
- Modify: `app/src/test/java/com/hackdecouverte/rec/TruthEval.kt` (`load` délègue à `TruthTrack.load`)
- Test: `app/src/test/java/com/hackdecouverte/rec/truth/TruthTrackTest.kt`

**Interfaces:**
- Produces:
  - `data class Checkpoint(val pointIndex: Int, val tS: Double)` : le point `pointIndex` du trajet est atteint `tS` secondes après le début de l'enregistrement.
  - `data class RouteLabel(val pointIndex: Int, val label: String)`, avec les libellés `porte`, `demi-tour`, `salle`, `escalier`, `pivot`.
  - `data class TruthRoute(val floorId: String, val path: List<PointM>, val checkpoints: List<Checkpoint>, val labels: List<RouteLabel>)`
  - `data class TruthPoint(val tNs: Long, val posM: PointM, val arcM: Double)`
  - `object TruthTrack { fun load(walk: File): TruthRoute?; fun parse(json: String): TruthRoute; fun align(route: TruthRoute, steps: List<Pair<Long, PointM>>, startNs: Long): List<TruthPoint>; fun positionAt(track: List<TruthPoint>, tNs: Long): PointM? }`

**Format v2** (le v1 actuel `{"floor","path","note"}` reste lisible) :

```json
{"version":2,"walk":"STAN-1_20261118-150346","floor":"STAN-1",
 "path":[[-17.8,8.5],[-14.1,12.7]],
 "checkpoints":[{"i":5,"tS":61.0}],
 "labels":[{"i":2,"label":"porte"}]}
```

**Algorithme de `align`** (à suivre tel quel) :
1. **Poser les pas sur le trajet.** On fait pivoter `steps` (positions du PDR) autour de leur origine pour la poser sur `path[0]`, avec l'échelle fixée à longueur(path) / longueur(pas) et la meilleure rotation trouvée par DTW (même recherche que `TruthEval.score` : grille de 3°, puis ±2° par pas de 1°).
2. **Rééchantillonner le trajet** tous les 0,25 m → `R[j]`, avec son abscisse curviligne `s[j]`.
3. **Apparier dans l'ordre (DTW)** les pas posés `P[k]` avec `R[j]` : coût euclidien, chemin monotone. Une cellule `(k, j)` est interdite si un checkpoint (temps `tS`, point `i`) a `|t_k − tS| ≤ 2 s` et `|s[j] − s(point i)| > 1,5 m`.
4. **Choisir un point par pas :** pour chaque `k`, `j*(k)` = médiane des `j` appariés à `k`. On force ensuite `s[j*(k)]` à ne jamais décroître (maximum cumulé).
5. **Résultat :** `TruthPoint(t_k, R[j*(k)], s[j*(k)])`. Ensuite, `positionAt` interpole entre deux pas, et renvoie `null` hors de la plage ou à travers un trou de plus de 3 s.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun loadsV1AndV2Files() {
    val v1 = TruthTrack.parse("""{"floor":"STAN-1","path":[[0,0],[1,2]],"note":"x"}""")
    assertEquals(2, v1.path.size); assertTrue(v1.checkpoints.isEmpty())
    val v2 = TruthTrack.parse("""{"version":2,"floor":"STAN-1","path":[[0,0],[3,4]],"checkpoints":[{"i":1,"tS":12.5}],"labels":[{"i":1,"label":"porte"}]}""")
    assertEquals(Checkpoint(1, 12.5), v2.checkpoints.single()); assertEquals("porte", v2.labels.single().label)
}
@Test fun alignmentRecoversTheTruthOfASyntheticWalk() {
    // SyntheticWalk.stanislasBackAndForth: legs known exactly. Route = start + leg ends; steps = PDR-only replay.
    // median |aligned − true position at the same time| ≤ 0.7 m, max ≤ 2.0 m
}
@Test fun checkpointsPinAnOutAndBackWalk() {
    // Same corridor out then back (DTW alone could fold it); checkpoint at the far end at its true time:
    // the step at that time aligns within 1.0 m of the far end.
}
```

- [ ] **Step 2: Run them, check they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests '*TruthTrackTest*'`
Expected: compilation FAIL (`TruthTrack` unknown).

- [ ] **Step 3: Implement `TruthTrack` as above.** To get the true positions in the synthetic test, add `fun SyntheticWalk.truePositionAt(rec: Recording, tNs: Long): PointM` to `SyntheticWalk.kt`, computed from the legs and the step times the walk generated.
- [ ] **Step 4: Run the tests again** — Expected: PASS.
- [ ] **Step 5: Commit** — `git add app/src/test/java/com/hackdecouverte/rec/truth app/src/test/java/com/hackdecouverte/rec/TruthEval.kt app/src/test/java/com/hackdecouverte/rec/SyntheticWalk.kt && git commit -m "truth: route format v2 with checkpoints and labels, time alignment of steps on the route"` puis `git push`.

### Task A2 : Tous les réglages dans `TuningParams` (comportement inchangé)

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/tuning/TuningParams.kt`, `app/src/main/assets/tuning/params.json`
- Modify: `pdr/PdrTracker.kt` (constructeur + `strideWindowFactor`, `compassPullMaxErrDeg`, `typicalCadenceHz`), `pdr/CarryMonitor.kt` (fabrique depuis `CarryTuning`), `backend/IndoorNavigationBackend.kt` (chargement au démarrage), `rec/WalkReplay.kt` (paramètre `tuning`)
- Test: `app/src/test/java/com/hackdecouverte/tuning/TuningParamsTest.kt`

**Interfaces:**
- Produces (les phases B et C ajoutent des champs, toujours avec une valeur par défaut qui garde le comportement actuel) :

```kotlin
@Serializable data class TuningParams(
    val version: Int = 1, val provisional: Boolean = false, val trainedOn: List<String> = emptyList(),
    val pdr: PdrTuning = PdrTuning(), val carry: CarryTuning = CarryTuning(), val filter: FilterTuning = FilterTuning())
@Serializable data class PdrTuning(
    val compassPullTauS: Double = 8.0, val compassPullMaxErrDeg: Double = 60.0,
    val walkAxisCorrection: Boolean = true, val walkAxisTau: Double = 4.0, val walkAxisMinRatio: Double = 2.0,
    val strideWindowFactor: Double = 1.0, val typicalCadenceHz: Double = 1.8)
@Serializable data class CarryTuning(
    val startDeg: Double = 25.0, val commitDeg: Double = 35.0, val settledDeg: Double = 12.0,
    val swingOnDeg: Double = 9.0, val swingOffDeg: Double = 6.0)
@Serializable data class FilterTuning(
    val maxParticles: Int = 2000, val minParticles: Int = 300, val headingNoiseDeg: Double = 3.0,
    val bodyCarryNoiseScale: Double = 2.0, val lengthNoise: Double = 0.05, val offsetDriftDeg: Double = 0.5,
    val scaleDrift: Double = 0.005, val wallFactor: Double = 0.02, val beta: Double = 0.2, val recoveryFraction: Double = 0.02)
fun TuningParams.locParams(base: LocParams = LocParams()): LocParams   // copies filter fields (degrees → radians)
fun PdrTracker.applyTuning(t: PdrTuning)                                 // sets the tracker's public knobs
object Tuning { const val ASSET = "tuning/params.json"; fun parse(json: String): TuningParams; fun load(open: (String) -> InputStream?): TuningParams }
```

- **Constructeur :** `PdrTracker` reçoit `carry: CarryMonitor = CarryMonitor()`. Le champ `val carry` existant devient ce paramètre, et `CarryMonitor.from(t: CarryTuning): CarryMonitor` le construit.
- **`LocalizationController.BODY_CARRY_HEADING_NOISE_SCALE`** devient `LocParams.bodyCarryNoiseScale` (valeur par défaut 2.0), et le contrôleur lit `params.bodyCarryNoiseScale`.
- **`Tuning.load`** lit `ignoreUnknownKeys` ; fichier absent ou invalide → `TuningParams()`, avec un journal `Log.w` côté app seulement (le chargeur pur renvoie les valeurs par défaut).

- [ ] **Step 0: Capture today's reference values.** Before touching any code, run `WalkReplay(SyntheticWalk.stanislasNorthWestAndBack(site.origin), …, seed = 3)` once in a throwaway test. Write its `steps.size`, `steps.last().estimate` and `strideScale` as constants in `replayWithDefaultTuningIsUnchanged`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun defaultsEqualTheCurrentConstants() {
    val t = TuningParams()
    assertEquals(LocParams(), t.locParams())                       // same filter as today
    assertEquals(8.0, t.pdr.compassPullTauS, 0.0); assertEquals(25.0, t.carry.startDeg, 0.0)
}
@Test fun missingOrBrokenFileGivesDefaults() {
    assertEquals(TuningParams(), Tuning.load { null })
    assertEquals(TuningParams(), Tuning.load { "{not json".byteInputStream() })
}
@Test fun unknownKeysAndPartialFilesAreAccepted() {
    val t = Tuning.parse("""{"version":1,"pdr":{"compassPullTauS":5.0},"future":{"x":1}}""")
    assertEquals(5.0, t.pdr.compassPullTauS, 0.0); assertEquals(PdrTuning().walkAxisTau, t.pdr.walkAxisTau, 0.0)
}
@Test fun shippedParamsFileParses() {   // the asset committed in this task = defaults
    assertEquals(TuningParams(), Tuning.load { MapTestSupport.open(it) })
}
@Test fun replayWithDefaultTuningIsUnchanged() {
    // WalkReplay(SyntheticWalk.stanislasNorthWestAndBack, seed 3) with tuning = TuningParams():
    // same steps count and same final estimate (±1e-9) as before this task (values pinned in the test from the pre-change run).
}
```

- [ ] **Step 2: Run them, check they fail** — Run: `… --tests '*TuningParamsTest*'` · Expected: compilation FAIL.
- [ ] **Step 3: Implement.**
  - Write `params.json` with exactly `TuningParams()` serialized (`encodeDefaults = true`, indented).
  - In the backend, read it once with `Tuning.load { runCatching { appContext.assets.open(it) }.getOrNull() }`, then pass it to the `PdrTracker` (constructor `carry` + `applyTuning`) and to `LocalizationController.params = tuning.locParams(...)` in `beginSearch` (the stride memory overwrites only `strideScaleMin/Max`).
  - Add `tuning: TuningParams = TuningParams()` to `WalkReplay` right after `seed`, and make `params` default to `tuning.locParams()`.
  - Add the knobs to `PdrTracker`, each defaulting to today's value:
    - `strideWindowFactor` multiplies `strideWindowNs()`, bounded to [0.4 s, 3.2 s] ;
    - `compassPullMaxErrDeg` replaces the 60° constant ;
    - `typicalCadenceHz` replaces `TYPICAL_CADENCE_HZ`.
- [ ] **Step 4: Run every test** — Run: `… :app:testDebugUnitTest` · Expected: PASS, 207 + 5 tests.
- [ ] **Step 5: Commit + push** — `"tuning: every trainable setting in TuningParams, shipped as assets/tuning/params.json (defaults = current behaviour)"`.

### Task A3 : Une seule évaluation (PDR + filtre), jeux de données, exécution parallèle

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/train/WalkEval.kt`, `.../train/Datasets.kt`
- Modify:
  - `rec/WalkReplay.kt` (`startHint`, `stepObserver`, magnétomètre de compatibilité : voir B6) ;
  - `rec/WalkAnalysis.kt` (le rapport utilise `WalkEval` quand une vérité existe ; la carte en cache passe en `ConcurrentHashMap`) ;
  - `rec/PdrLoopTuningTest.kt` (utilise `WalkEval.pdr`) ;
  - `app/build.gradle.kts`.
- Test: `app/src/test/java/com/hackdecouverte/rec/train/WalkEvalTest.kt`

**Interfaces:**
- Consumes: `TruthTrack` (A1), `TuningParams` (A2), `WalkReplay(recording, origin, mapProvider, seed, tuning, params, useGps, windowStartNs, windowEndNs, startHint, stepObserver)`
- Produces:

```kotlin
data class Dataset(val name: String, val recording: Recording, val route: TruthRoute, val site: IndoorSite, val track: List<TruthPoint>)
object Datasets { fun loadAll(dir: File = File("../recordings")): List<Dataset> }   // only walks with a .truth.json; track aligned once with TuningParams()
enum class StartMode { KNOWN_START, COLD_GPS, COLD_NO_GPS }
data class PdrRun(val steps: List<Pair<Long, PointM>>, val distanceM: Double)
data class PdrMetrics(val routeDtwM: Double, val wallCrossingsPer100m: Double, val closurePct: Double?, val distanceRatio: Double)
data class FilterRun(val mode: StartMode, val seed: Int, val trackErrMedianM: Double?, val trackErrP90M: Double?,
                     val foundAfterM: Double?, val falseFoundShare: Double, val endErrM: Double?, val supportLostAtS: Double?)
object WalkEval {
    fun pdrRun(ds: Dataset, tuning: TuningParams): PdrRun
    fun pdr(ds: Dataset, tuning: TuningParams): PdrMetrics
    fun filter(ds: Dataset, tuning: TuningParams, mode: StartMode, seed: Int): FilterRun
    fun <T> parallel(jobs: List<() -> T>): List<T>     // fixed pool = availableProcessors()
}
```

**Définitions exactes des mesures :**
- **`routeDtwM`** : `TruthEval.score(pdrRun.steps positions, route.path).first` (rotation libre, échelle = rapport des longueurs).
- **`wallCrossingsPer100m`** : on pose le chemin du PDR avec la rotation et l'échelle du DTW, départ sur `path[0]`. Chaque entrée dans une cellule `WALL` (échantillons tous les 5 cm) compte 1. Le résultat est ramené à 100 m de trajet réel.
- **`closurePct`** : seulement si `path.first()` est à moins de 2 m de `path.last()`. Vaut |fin − départ| du PDR / distance du PDR × 100, sinon `null`.
- **`FilterRun`**, pour chaque pas `k` avec `q_k = positionAt(track, t_k)` :
  - `err_k` = |centre de la zone principale − q_k| si l'état est au moins APPROXIMATIF ;
  - `trackErr*` : médiane et 90e centile des `err_k`, pris dès le premier pas en KNOWN_START, ou après le premier « Trouvé » juste (erreur ≤ 5 m) à froid ;
  - `foundAfterM` : abscisse `arcM` de la vérité au premier « Trouvé » juste ;
  - `falseFoundShare` : pas en « Trouvé » avec `err > 5 m`, divisés par le nombre de pas en « Trouvé » ;
  - `supportLostAtS` : premier instant où le poids des hypothèses à moins de 3 m de `q_k` reste sous 0,01 pendant 5 pas de suite (`stepObserver` + `controller.sampleParticles(2000)`).
- **KNOWN_START** = `startHint = WarmStart(listOf(LocZone(path[0], 1.0, 1.0, 0.0)), 0.0)`, sans GPS.

**`build.gradle.kts`** : dans `tasks.withType<Test>().configureEach { … }`, transmettre `train`, `accept`, `sweep`, `loop`, `guess`, `replay` s'ils sont définis (`System.getProperty(k)?.let { systemProperty(k, it) }`), et fixer `maxHeapSize = "4g"`.

- [ ] **Step 1: Write the failing tests** (jeu de données synthétique : `SyntheticWalk.stanislasBackAndForth` + sa route exacte)

```kotlin
@Test fun perfectSyntheticPdrScoresNearZero() {
    val m = WalkEval.pdr(syntheticDataset(), TuningParams())
    assertTrue(m.routeDtwM < 0.5); assertEquals(0.0, m.wallCrossingsPer100m, 0.0); assertTrue(m.distanceRatio in 0.95..1.05)
}
@Test fun knownStartTracksTheSyntheticWalk() {
    val r = WalkEval.filter(syntheticDataset(), TuningParams(), StartMode.KNOWN_START, seed = 1)
    assertTrue(r.trackErrMedianM!! < 1.5); assertNull(r.supportLostAtS)
}
@Test fun coldStartNeverClaimsAWrongPlace() =
    assertEquals(0.0, WalkEval.filter(syntheticDataset(), TuningParams(), StartMode.COLD_GPS, 1).falseFoundShare, 0.0)
@Test fun parallelRunsGiveTheSameNumbersAsSequential() { /* 4 filter runs, both ways: identical FilterRun lists */ }
```

- [ ] **Step 2: Run them, check they fail** · Expected: compilation FAIL.
- [ ] **Step 3: Implement.** Add `startHint: WarmStart? = null` and `stepObserver: ((tNs: Long, controller: LocalizationController) -> Unit)? = null` to `WalkReplay`. `startHint` is passed to `controller.start(…, warm = startHint)`, and `stepObserver` is called after each PDR step.
- [ ] **Step 4: Run them, check they pass** · and `… --tests '*PdrLoopTuningTest*'` still prints its table.
- [ ] **Step 5: Commit + push** — `"eval: one evaluation of PDR and filter against traced routes, datasets, parallel runs"`.

### Task A4 : Outil de tracé v2 (tous les étages, instants, libellés)

**Files:**
- Modify: `app/src/test/java/com/hackdecouverte/rec/TraceToolTest.kt`
- Create (générés, commités) : `tools/trace-STAN-1.html`, `tools/trace-MB-1.html` … `tools/trace-MB-7.html`
- Test: `TraceToolTest` (génération + contrôle du texte produit)

**Comportement de la page** (simple et rapide, comme la v1) :
- **Tracer :** un clic ajoute un point. « Annuler le dernier » et « Tout effacer » restent.
- **Liste des points :** à droite (en dessous sur téléphone), une ligne par point avec deux champs facultatifs :
  - « instant (s) » : secondes depuis le début de l'enregistrement, lisibles dans `report.txt` ou au chronomètre de l'app ;
  - un menu de libellés : aucun, porte, demi-tour, salle, escalier, pivot.
- **Champ « nom de la marche »** (par exemple `STAN-1_20261118-150346`).
- **Bouton « Copier pour Claude » :** copie `TRUTH {json v2}`.
- **Bouton « Télécharger » :** enregistre `<nom>.truth.json`, à placer dans `recordings/`.

- [ ] **Step 1: Write the failing test** — `writesOneToolPerFloor()` : après exécution, les 8 fichiers existent, chacun contient `"floor":"<id>"`, et la `viewBox` couvre la grille de son étage. `exampleOutputParses()` : la chaîne d'exemple produite par une fonction Kotlin miroir (`TraceToolTest.exampleJson(points, checkpoints, labels)`), écrite avec la même forme que le JavaScript, passe dans `TruthTrack.parse`.
- [ ] **Step 2: Run, check it fails.**
- [ ] **Step 3: Implement.** Générer chaque page depuis `WalkAnalysis.mapProvider(site)(floorId)` et le `FloorPlan` (libellés des salles). Le JavaScript sérialise `{"version":2,"walk":…,"floor":…,"path":[[e,n],…],"checkpoints":[{"i":…,"tS":…}],"labels":[{"i":…,"label":…}]}`.
- [ ] **Step 4: Run, check it passes.** Ouvrir `tools/trace-STAN-1.html` dans le navigateur intégré, cliquer 3 points, saisir un instant et un libellé, copier, puis vérifier que le texte copié passe dans `TruthTrack.parse` (petit test JVM `pastedV2Parses`).
- [ ] **Step 5: Commit + push** — `"tools: trace tool v2 for every floor (times, labels, download)"`.

---

## Phase B — Le filtre doit suivre (corrections tirées de la vraie marche)

### Task B1 : Diagnostic du support sur la vraie marche (sans changement du produit)

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/train/SupportReportTest.kt`

**Interfaces:**
- Consumes: `WalkEval`, `Datasets` (A3).
- Produces: `app/build/replay/<walk>/support.txt`. Une ligne par pas : `tS, arcM, poids≤3m, erreurZone, état, distancePorteLaPlusProcheM, libelléVérité`, puis la liste des intervalles où le support est perdu, avec les 5 pas qui précèdent chaque perte.

- [ ] **Step 1: Write the test** — `syntheticWalkNeverLosesSupport()` : sur le jeu synthétique, le poids à moins de 3 m reste ≥ 0,5 à chaque pas. `writesReportsForRealWalks()` s'exécute pour chaque jeu réel (`Assume` s'il n'y en a pas) et écrit `support.txt`.
- [ ] **Step 2: Run, fails, implement, passes** (même cycle que A3).
- [ ] **Step 3: Read `support.txt` of `STAN-1_20261118-150346`** and write in the execution ledger which hypotheses it supports, by the rule below. B2 to B6 are implemented in every case: each is measured afterwards and kept only if it lowers the stage-B loss (C2).

| Où le support est perdu | Hypothèse | Tâche |
|---|---|---|
| ≤ 1,5 m d'une porte | marche le long d'un montant ou passage d'une porte décalée | B2 |
| ≤ 3 s après un libellé `pivot` ou une forte rotation | pivots comptés comme des pas | B3 |
| support présent, mais état RECHERCHE | seuils d'état trop stricts en poche | B4 |
| perdu sans retour | absence de reprise | B5 |
| un tronçon droit entier | cap qui dérive malgré le recalage (champ magnétique) | B6 |

- [ ] **Step 4: Commit + push** — `"diag: per-step support of the filter against the traced route"`.

### Task B2 : Murs souples près des portes

**Files:**
- Modify: `map/IndoorMap.kt` (`nearDoor: BooleanArray`, `fun isNearDoor(p: PointM): Boolean`, largeur des portes du graphe réglable via le paramètre de construction `graphDoorWidthM: Double = GRAPH_DOOR_WIDTH_M`), `loc/LocalizationFilter.kt` (`advance`), `loc/LocModels.kt`, `tuning/TuningParams.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/DoorSoftnessTest.kt`

**Interfaces:**
- Produces:
  - `LocParams.doorWallFactor: Double = 0.3` et `LocParams.doorSoftRadiusM: Double = 0.6` ;
  - `FilterTuning.doorWallFactor`, `doorSoftRadiusM` et `FilterTuning.graphDoorWidthM: Double = 1.0`.
  - Le chargeur de cartes accepte `graphDoorWidthM`. Le cache de cartes est indexé par `(étage, graphDoorWidthM arrondi à 0,1)`.
- **Règle dans `advance` :** si le segment du pas traverse un mur, et qu'au moins une de ses cellules bloquées est « près d'une porte » (≤ `doorSoftRadiusM` du bord de l'ouverture), le facteur vaut `doorWallFactor` au lieu de `wallFactor`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun aStepClippingADoorJambKeepsMoreWeightThanAPlainWall() {
    // Corridor with a 1 m door to a room; particle A steps through the jamb 0.4 m beside the opening,
    // particle B through the plain wall 5 m away: weight(A)/weight(B) == doorWallFactor/wallFactor (±1e-9).
}
@Test fun doorWidthIsTunable() { /* graphDoorWidthM = 1.4 → the opening carved in the grid is 1.4 m (±0.1) */ }
```

- [ ] **Step 2: Fails** → **Step 3: Implement** → **Step 4: Passes**, avec tous les tests existants.
- [ ] **Step 5: Commit + push** — `"loc: soft walls next to doors, tunable graph door width"`.

### Task B3 : Qualité des pas (pivots et piétinements)

**Files:**
- Modify: `pdr/PdrTracker.kt`, `pdr/PdrModels.kt`, `pdr/StepReconciler.kt`, `backend/LocalizationController.kt`, `loc/LocalizationFilter.kt`, `tuning/TuningParams.kt`
- Test: `app/src/test/java/com/hackdecouverte/pdr/StepQualityTest.kt`, `app/src/test/java/com/hackdecouverte/loc/PivotTrackingTest.kt`

**Interfaces:**
- Produces:
  - `PdrState.stepQuality: Double` (1 = pas normal ; < 1 = pas douteux) ;
  - `PdrTuning.pivotTurnRateRadS: Double = 1.2`, `pivotFullRateSpanRadS: Double = 1.0`, `pivotMinQuality: Double = 0.2` ;
  - `StepReconciler.recentCountRatio(nowNs: Long, windowNs: Long = 5_000_000_000L): Double?`.
- **Qualité d'un pas accepté :** `q = clamp(1 − (tauxDeVirage − pivotTurnRateRadS) / pivotFullRateSpanRadS, pivotMinQuality, 1)`. Le taux de virage est celui moyenné sur la foulée en mode « porté », instantané sinon. Ensuite `q × 0,5` si `recentCountRatio < 0,5` (le compteur ne confirme pas les pas des 5 dernières secondes).
- **Le PDR ne raccourcit plus les pas pendant un pivot :** les réglages `pivot*` actuels, qui n'ont pas d'effet par défaut, servent désormais à la qualité.
- **Le filtre**, pour un pas de qualité `q`, multiplie la longueur par `q`, le bruit de cap par `1/q` et le bruit de longueur par `1/q`. Le contrôleur passe `s.stepQuality` au filtre avec `f.stepQuality = q` avant `onStep`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun fastTurnOnTheSpotLowersStepQuality()      // 180° in 1.5 s with 3 detector steps → each q ≤ 0.5; straight walking → q == 1.0
@Test fun stepsTheCounterDoesNotConfirmAreDoubtful() // detector 6 steps, counter +1 in the same 5 s → q ≤ 0.5
@Test fun aPivotWithSpuriousStepsKeepsTheTrack() {
    // Straight corridor (SyntheticMaps.straightCorridor), known start, walk 20 steps north, pivot 180° with 3 spurious
    // steps, walk 15 steps south. With quality: final main-zone error < 2.0 m. With quality forced to 1: error > 3.0 m.
}
```

- [ ] **Step 2: Fails** → **Step 3: Implement** → **Step 4: Passes** (all tests).
- [ ] **Step 5: Commit + push** — `"pdr+loc: step quality for pivots and unconfirmed steps; the filter trusts doubtful steps less"`.

### Task B4 : Seuils d'état réglables, confiance en poche, départ dans une salle

**Files:**
- Modify: `loc/LocModels.kt`, `loc/LocalizationFilter.kt` (`estimate()`, compteurs bons et mauvais pas), `tuning/TuningParams.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/StateThresholdsTest.kt`, `app/src/test/java/com/hackdecouverte/rec/train/RoomStartTest.kt`

**Interfaces:**
- Produces, dans `LocParams` et `FilterTuning` : `foundWeight = 0.9`, `foundRadiusM = 3.0`, `approxWeight = 0.6`, `approxRadiusM = 8.0`, `goodStepsToTrust = 10`, `badStepLikelihood = 0.05`. Ils remplacent les constantes du même rôle.
- **Pas « porté » :** quand `headingNoiseScale > 1`, un pas compte comme mauvais seulement si sa vraisemblance est inférieure à `badStepLikelihood / headingNoiseScale`. Un cap plus bruité ne doit pas faire revenir le filtre en RECHERCHE à chaque foulée.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun defaultThresholdsKeepTodaysBehaviour()     // all existing filter tests pass unchanged (run them)
@Test fun aCorrectCloudWithPocketNoiseReachesFound() // L corridor, known start, heading noise σ 8°, headingNoiseScale 2 → FOUND within 30 steps
@Test fun aSymmetricCorridorNeverClaimsFound()       // straight corridor, unknown start, no compass: never FOUND in 40 steps
@Test fun coldStartInsideARoomIsFoundAfterLeavingIt() {
    // SyntheticWalk on STAN-1 starting inside the room at (-17.8, 8.5), out through its door, along the corridor to the SE end:
    // COLD_NO_GPS seed 1..3 → correct FOUND (≤ 5 m) before the end in ≥ 2 of 3 seeds; no false FOUND.
}
```

- [ ] **Step 2: Fails** → **Step 3: Implement.** `SyntheticWalk` gets `fun stanislasFromRoom(origin: GeoOrigin): Recording` with legs `(41°, 8)`, `(134°, 34)`.
- [ ] **Step 4: Passes.**
- [ ] **Step 5: Commit + push** — `"loc: tunable state thresholds, pocket-tolerant bad-step rule, room-start test"`.

### Task B5 : Reprise quand le filtre est perdu

**Files:**
- Modify: `loc/LocalizationFilter.kt`, `loc/LocModels.kt`, `tuning/TuningParams.kt`
- Test: `app/src/test/java/com/hackdecouverte/loc/LostRecoveryTest.kt`

**Interfaces:**
- Produces : `LocParams.lostRecoveryShare: Double = 0.3` et `FilterTuning.lostRecoveryShare`.
- **Mémoire de la dernière bonne zone :** le filtre garde `lastConfidentCenter: PointM?`, `lastConfidentRadiusM: Double` et `distanceSinceConfidentM: Double`. Ils sont mis à jour à chaque pas où l'état est au moins APPROXIMATIF, et la distance s'accumule sinon.
- **Quand le filtre réensemence** (`reseedAround`, appelé sur alarme), une part `lostRecoveryShare` des hypothèses va dans le disque centré sur `lastConfidentCenter`, de rayon `lastConfidentRadiusM + 3 + 0,3 × distanceSinceConfidentM` (au plus 30 m), avec un angle et une foulée tirés des hypothèses survivantes. Sans dernière bonne zone, rien ne change.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun aHeadingJumpMidCorridorIsRecovered() {
    // Z corridor, known start, walk; after 15 steps the PDR heading jumps by +60° for 10 steps (bad bridge), then is right again.
    // Within 25 steps after the jump the main zone is < 3 m from the truth (seed 1..3, at least 2 of 3).
}
```

- [ ] **Step 2: Fails** → **Step 3: Implement** → **Step 4: Passes.**
- [ ] **Step 5: Commit + push** — `"loc: lost-track recovery around the last confident zone"`.

### Task B6 : Boussole coupée quand le champ magnétique est anormal

**Files:**
- Modify:
  - `sensor/PdrSensorAdapter.kt` (enregistre `TYPE_MAGNETIC_FIELD` à 16 Hz dans le flux du PDR) ;
  - `sensor/SensorRouter.kt` (`TYPE_MAGNETIC_FIELD` → `tracker.onMagneticField(t, µT)`) ;
  - `pdr/PdrTracker.kt` ;
  - `rec/WalkReplay.kt` (pour les anciens enregistrements sans magnétomètre dans le flux du PDR, utiliser le flux de recherche `S,2,…,x`) ;
  - `tuning/TuningParams.kt`.
- Test: `app/src/test/java/com/hackdecouverte/pdr/MagneticGateTest.kt`

**Interfaces:**
- Produces :
  - `PdrTracker.onMagneticField(timestampNs: Long, magnitudeMicroTesla: Double)`, `var expectedFieldMicroTesla: Double? = null` (null = médiane glissante) et `PdrTuning.magGateTolerance: Double = 0.15` ;
  - pour les tests, `SyntheticWalk.record(…, gyroDriftDegPerMin: Double = 0.0, compassOffsetDeg: (tS: Double) -> Double = { 0.0 }, fieldMicroTesla: (tS: Double) -> Double = { 52.0 })`. Le vecteur de rotation de jeu y tourne de `gyroDriftDegPerMin`, la boussole est décalée de `compassOffsetDeg(t)`, et la marche écrit des échantillons `TYPE_MAGNETIC_FIELD` du PDR (intensité `fieldMicroTesla(t)`, direction cohérente avec la boussole) à 16 Hz.
- **Champ de référence :** médiane glissante sur 60 s de l'intensité mesurée, initialisée à 52 µT (Montréal). La spec permet de préférer `GeomagneticField` côté app ; le tracker reçoit alors la valeur via `expectedFieldMicroTesla`.
- **Échantillons de boussole ignorés** pour le recalage continu quand l'intensité, prise ±0,5 s autour de l'échantillon, s'écarte de plus de `magGateTolerance` de la référence.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun aLongMagneticDisturbanceDoesNotTurnTheHeading() {
    // Walk 40 steps straight north; from step 10 to 30 the compass says +50° and the field reads 80 µT (steel).
    // With the gate: final PDR bearing within 5° of north. Without (magGateTolerance = 10.0): bearing off by > 25°.
}
@Test fun aNormalFieldKeepsTheCompassPull() // same walk, game heading drifting +20°/min, field 52 µT: final error < 6°
```

- [ ] **Step 2: Fails** → **Step 3: Implement.** Ajouter `SyntheticWalk.record(gyroDriftDegPerMin, compassOffsetDeg(t), fieldMicroTesla(t))` pour les deux tests.
- [ ] **Step 4: Passes** + `… --tests '*PdrLoopTuningTest*'` : sur la vraie marche, l'écart au trajet ne doit pas dépasser 1,78 m + 0,2 m.
- [ ] **Step 5: Commit + push** — `"pdr: compass pull gated by the field magnitude (steel, magnets)"`.

---

## Phase C — Entraîner les réglages (« le petit modèle »)

### Task C1 : Optimiseur CMA-ES

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/train/Cmaes.kt`
- Test: `app/src/test/java/com/hackdecouverte/rec/train/CmaesTest.kt`

**Interfaces:**
- Produces : `class Cmaes(val dim: Int, x0: DoubleArray, sigma0: Double, val lambda: Int = 4 + (3 * ln(dim.toDouble())).toInt(), seed: Int = 1)`, avec `fun ask(): List<DoubleArray>`, `fun tell(xs: List<DoubleArray>, losses: List<Double>)`, `val bestX: DoubleArray`, `val bestLoss: Double` et `val generation: Int`.
- **Espace :** coordonnées normalisées dans [0, 1]. Une valeur hors de l'espace est **réfléchie** avant l'évaluation (`x → 2 − x` si x > 1, `x → −x` si x < 0), et c'est la valeur réfléchie qui est donnée à `tell`.
- **Algorithme :** CMA-ES standard (Hansen, « The CMA Evolution Strategy: A Tutorial », 2016, équations (3)–(47)) :
  - poids logarithmiques, `μ = λ/2` ;
  - mises à jour de rang 1 et de rang μ ;
  - contrôle du pas par longueur du chemin ;
  - décomposition propre de C toutes les `max(1, λ/(10·dim))` générations, par Jacobi (pas de bibliothèque).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun sphereConverges()      // dim 5, x* = 0.3 everywhere, 150 generations → bestLoss < 1e-6
@Test fun rosenbrockConverges()  // dim 4 mapped into [0,1] (x' = 4x − 2), 600 generations → bestLoss < 1e-3
@Test fun sameSeedSameRun()      // two optimizers, seed 1, 20 generations → identical bestX
@Test fun boundsAreRespected()   // optimum at x = 1.2 outside the box → all asked points in [0,1], bestX ≈ 1.0 (±0.02)
```

- [ ] **Step 2: Fails** → **Step 3: Implement** → **Step 4: Passes.**
- [ ] **Step 5: Commit + push** — `"train: CMA-ES optimizer (pure Kotlin, bounded)"`.

### Task C2 : Entraînement en deux étages, validation croisée, porte d'acceptation

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/train/Trainer.kt`, `.../train/TrainModel.kt` (ne s'exécute qu'avec `-Dtrain=true`)
- Test: `app/src/test/java/com/hackdecouverte/rec/train/TrainerTest.kt` (rapide, toujours exécuté)

**Interfaces:**
- Consumes: `Cmaes` (C1), `WalkEval`, `Datasets` (A3), `TuningParams` (A2, champs ajoutés en B2–B6).
- Produces:

```kotlin
data class Knob(val name: String, val min: Double, val max: Double, val log: Boolean,
                val get: (TuningParams) -> Double, val set: (TuningParams, Double) -> TuningParams)
object Knobs { val STAGE_A: List<Knob>; val STAGE_B: List<Knob> }
data class StageResult(val tuning: TuningParams, val loss: Double, val lossDefault: Double, val generations: Int)
object Trainer {
    fun lossA(datasets: List<Dataset>, t: TuningParams): Double
    fun lossB(datasets: List<Dataset>, t: TuningParams): Double
    fun train(datasets: List<Dataset>, start: TuningParams, knobs: List<Knob>, loss: (List<Dataset>, TuningParams) -> Double,
              generations: Int, regularization: Double = 0.05): StageResult
    fun crossValidate(datasets: List<Dataset>, start: TuningParams): CvReport      // leave-one-walk-out, stages A then B
    fun gate(cv: CvReport): GateDecision                                            // ACCEPT / PROVISIONAL / REJECT + reasons
    fun writeParams(t: TuningParams, file: File = File("src/main/assets/tuning/params.json"))
    fun writeReport(cv: CvReport, decision: GateDecision, final: TuningParams, file: File)
}
data class CvReport(val perWalk: List<HeldOut>, val meanDefault: Double, val meanTrained: Double)
data class HeldOut(val walk: String, val lossDefault: Double, val lossTrained: Double, val pdr: PdrMetrics, val runs: List<FilterRun>)
enum class Gate { ACCEPT, PROVISIONAL, REJECT }; data class GateDecision(val gate: Gate, val reasons: List<String>)
```

**Étage A — PDR** (8 réglages, CMA-ES avec λ = 10 et 30 générations) :

| Réglage | Bornes | Échelle |
|---|---|---|
| `pdr.compassPullTauS` | 2 – 40 | log |
| `pdr.compassPullMaxErrDeg` | 20 – 120 | lin |
| `pdr.walkAxisTau` | 1,5 – 15 | log |
| `pdr.walkAxisMinRatio` | 1,2 – 4 | lin |
| `pdr.strideWindowFactor` | 0,5 – 2 | log |
| `carry.swingOnDeg` (`swingOffDeg` = 0,67 ×) | 5 – 15 | lin |
| `pdr.pivotTurnRateRadS` | 0,4 – 3 | lin |
| `pdr.magGateTolerance` | 0,05 – 0,5 | log |

`lossA = moyenne(routeDtwM + 0,5 × wallCrossingsPer100m + 0,05 × (closurePct ?: 0)) + 0,05 × Σ (z − z_défaut)²`, avec z la coordonnée normalisée.

**Étage B — filtre** (13 réglages, λ = 11, 20 générations ; réglages de l'étage A figés) :

| Réglage | Bornes | Échelle |
|---|---|---|
| `filter.headingNoiseDeg` | 1 – 10 | log |
| `filter.bodyCarryNoiseScale` | 1 – 4 | lin |
| `filter.lengthNoise` | 0,02 – 0,15 | log |
| `filter.offsetDriftDeg` | 0,1 – 3 | log |
| `filter.scaleDrift` | 0,001 – 0,03 | log |
| `filter.wallFactor` | 0,002 – 0,2 | log |
| `filter.doorWallFactor` | 0,05 – 1 | log |
| `filter.doorSoftRadiusM` | 0,2 – 1,2 | lin |
| `filter.beta` | 0 – 0,6 | lin |
| `filter.recoveryFraction` | 0 – 0,1 | lin |
| `filter.lostRecoveryShare` | 0 – 0,6 | lin |
| `filter.foundRadiusM` | 2 – 4 | lin |
| `filter.graphDoorWidthM` | 0,8 – 1,6 | lin (arrondi à 0,1) |

Pour chaque marche :
- 3 exécutions KNOWN_START (tirages 1–3), 3 COLD_GPS (1–3) et 2 COLD_NO_GPS (1–2), en parallèle ;
- `suivi = moyenne_KNOWN(min(médiane ?: 15, 15) + 0,5 × min(p90 ?: 15, 15))` ;
- `froid = moyenne_COLD(min(foundAfterM ?: 150, 150)/50 + 20 × falseFoundShare + min(endErrM ?: 15, 15)/5)` ;
- `lossB = moyenne_marches(suivi + froid) + 0,05 × Σ (z − z_défaut)²`.

**Validation croisée et porte :**
- **Avec au moins 3 marches :** pour chaque marche tenue à l'écart, on entraîne sur les autres (étage A à 15 générations, étage B à 12), puis on évalue sur la marche tenue à l'écart avec la perte totale `lossA + lossB` sans régularisation.
  - **ACCEPT** si la moyenne entraînée est ≤ 0,9 × la moyenne par défaut, **et** qu'aucune marche tenue à l'écart n'est pire de plus de 10 % que par défaut.
  - **REJECT** sinon, et `params.json` reste inchangé.
- **Avec 1 ou 2 marches :** entraînement sur tout, **PROVISIONAL** si la perte d'entraînement baisse d'au moins 10 % ; le fichier est écrit avec `"provisional": true`.
- **Réglages finaux :** une fois accepté, réentraînement sur toutes les marches, avec les budgets complets.

**Temps attendu** : étage A environ 5 min, étage B environ 30 min pour 4 marches de 5 min, sur un Mac à 8 cœurs.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun stageATrainingFixesADeliberatelyWrongSetting() {
    // Synthetic dataset with gyro drift 15°/min; start from compassPullTauS = 40 (too slow): after 8 generations
    // lossA falls by ≥ 30 % and compassPullTauS < 20.
}
@Test fun regularizationKeepsUselessKnobsAtTheirDefaults() // a knob with no effect on the loss stays within 0.05 (normalized) of its default
@Test fun gateRefusesParamsWorseOnAHeldOutWalk() // CvReport with one held-out walk 20 % worse → REJECT with that walk named
@Test fun gateMarksFewWalksAsProvisional()       // 2 datasets, 15 % better → PROVISIONAL
@Test fun writtenParamsLoadBackEqual()           // writeParams + Tuning.parse → equal
```

- [ ] **Step 2: Fails** → **Step 3: Implement.** `TrainModel` (avec `Assume` sur `-Dtrain=true`) charge `Datasets.loadAll()`, lance `crossValidate`, puis `gate`. Il écrit ensuite les réglages finaux et le rapport `docs/training/<yyyy-MM-dd>-<n>-report.md`, avec :
  - un tableau par marche (avant/après : `routeDtwM`, murs, fermeture, médianes et p90 KNOWN, distance jusqu'à « Trouvé », faux « Trouvé ») ;
  - les réglages modifiés (ancien → nouveau) ;
  - la décision de la porte et ses raisons.
- [ ] **Step 4: Passes** (`TrainerTest`).
- [ ] **Step 5: Commit + push** — `"train: two-stage training with leave-one-walk-out validation and an acceptance gate"`.

### Task C3 : Premier entraînement sur la marche existante (référence)

- [ ] **Step 1: Run** `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests '*TrainModel*' -Dtrain=true` · Expected : une seule marche, donc **PROVISIONAL** ou **REJECT**. Le rapport est écrit dans `docs/training/2026-11-xx-1-report.md`.
- [ ] **Step 2:** If PROVISIONAL: commit `params.json` (with `"provisional": true`) + the report. If REJECT: commit the report only.
- [ ] **Step 3: Install on the phone** (`./gradlew :app:installDebug`) and check that the debug map shows the trained values (add a line « Réglages : v<version> (<n> marches)<, provisoire> » to `DebugStatus`, with a test of the string formatting).
- [ ] **Step 4: Commit + push** — `"train: baseline training report on the first walk"`.

---

## Phase D — Cartes

### Task D1 : Échelle et portes du collège vérifiées sur place

**Files:**
- Create: `floor-plans/STAN/measurements.json`, `app/src/test/java/com/hackdecouverte/map/StanMeasurementsTest.kt`
- Modify (si le test l'exige) : `floor-plans/STAN/gen_floor.py` (`PX_PER_M`, positions des portes), puis régénérer `app/src/main/assets/buildings/STAN/*`

**Mesures à faire sur place** (10 minutes, par toi) :
1. La longueur du couloir, de la porte de l'escalier sud (`E-M-S`) à celle de l'escalier nord (`E-M-N`), avec l'app Mesure du téléphone, ou en comptant les dalles et en mesurant une dalle.
2. La largeur du couloir à mi-longueur.
3. Pour 3 salles visitées, la distance entre le centre de la porte et le bout sud-est du couloir.

**Format :**

```json
{"distances":[{"from":"STAN-1-r06","to":"STAN-1-r12","measuredM":0.0,"how":"Mesure"}],
 "corridorWidthM":0.0, "doors":[{"node":"STAN-1-r02","fromSouthEastEndM":0.0}]}
```

- [ ] **Step 1: Write the test** — `mapScaleMatchesTheMeasurements()` : pour chaque distance, |carte − mesure| / mesure ≤ 3 %. En cas d'échec, le message donne le facteur à appliquer à `PX_PER_M`. `doorsWhereTheTraceSaysSo()` : chaque point de vérité libellé `porte` est à moins de 0,8 m d'une ouverture de la carte.
- [ ] **Step 2: Run with the real numbers.** If it fails, fix `gen_floor.py`, regenerate, and run `StanislasFloorTest` + `MapValidatorTest` → PASS.
- [ ] **Step 3: Commit + push** — `"map: college scale and doors checked against on-site measurements"`.

### Task D2 : Cartes précalculées (seulement si la construction prend plus de 2 s sur le téléphone)

- [ ] **Step 0: Measure on the Pixel.** Add `mapBuildMs` to `LocDebug` and show it in `DebugStatus` (« carte prête en N ms »). If ≤ 2000 ms for STAN-1 and for MB-2, **skip this task** (note it in the ledger).

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/map/BakedMap.kt`, `app/src/test/java/com/hackdecouverte/map/BakeMapsTest.kt` (écrit `app/src/main/assets/maps-baked/<étage>.bin`)
- Modify: `map/IndoorMap.kt` (`loadIndoorMap` essaie d'abord la carte précalculée)

**Interfaces:** `object BakedMap { fun write(map: IndoorMap, sourceHash: String, out: OutputStream); fun read(input: InputStream, expectedHash: String): IndoorMap? }`. `sourceHash` est le SHA-256 du JSON du plan + SVG + calque + `graphDoorWidthM`. Si le hash ne correspond pas, `null`, et la carte est reconstruite au lancement.

**Format v1** (petit-boutiste) :
- magie `HDMP`, version 1 ;
- `floorId` (UTF-8, préfixé par sa longueur) ;
- `minE`, `minN`, `res` en double ; `w`, `h` en int ;
- les cellules (octets) ;
- la clairance en `UShort` (cm) ;
- l'axe en `UByte` (π/256) et la cohérence en `UByte` (1/255) ;
- les portes : nombre, puis `(e, n, largeur, nodeId)` ;
- `dominantAxisRad` ;
- `sourceHash` (32 octets).

- [ ] **Step 1: Tests** — `roundTripKeepsCellsExactlyAndAxesWithin1Degree`, `staleHashFallsBackToBuilding`, `bakedLoadsFasterThanBuilding` (JVM : précalculée < 0,3 × construction).
- [ ] **Step 2–4: Fails, implement, passes.** The generation runs from `BakeMapsTest` (with `-Dbake=true`, added to the forwarded properties). The `.bin` files are committed.
- [ ] **Step 5: Commit + push** — `"map: pre-baked maps with a source hash"`.

### Task D3 : Les 7 étages du MB passent les contrôles

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/map/MbFloorsValidTest.kt`
- Modify (manuel) : `app/src/main/assets/maps/MB-<n>-overlay.json` via `floor-plans/MB/editor.html`

- [ ] **Step 1: Write the test** — `everyMbFloorPassesTheMapChecks()` : pour n = 1..7, `MapValidator.validate(map, plan).ok`, avec le résumé en message d'échec.
- [ ] **Step 2: Run it**, and list the failing floors and checks in the ledger.
- [ ] **Step 3: For each failing floor** (manual, about 30–60 min per floor), in `editor.html`, open the missing doors, mark the passable furniture and realign the graph nodes the validator names. Save `MB-<n>-overlay.json`. Start with floors 1 and 7 (spec §13).
- [ ] **Step 4: Run again** — Expected: 7/7 PASS.
- [ ] **Step 5: Commit + push** — `"map: all seven MB floors pass the map checks"`.

---

## Phase E — Le produit

### Task E1 : Navigation à partir de la position localisée

**Files:**
- Create: `app/src/main/java/com/hackdecouverte/backend/LocalizedNavigation.kt` (pur)
- Modify: `backend/IndoorNavigationBackend.kt` (`NavigationSnapshot.localizedPose`, `searchZones`, `arrived` ; `resolveRoute` en mode localisation ; `hintRoom`)
- Test: `app/src/test/java/com/hackdecouverte/backend/LocalizedNavigationTest.kt`

**Interfaces:**

```kotlin
data class LocalizedPose(val floorId: String, val positionM: PointM, val headingRad: Double?, val radiusM: Double, val state: LocState)
object LocalizedNavigation {
    fun pose(floorId: String, estimate: LocEstimate): LocalizedPose?          // main zone; heading only when FOUND; null if no zone
    fun shouldReroute(last: LocalizedPose?, cur: LocalizedPose, stepsSince: Int): Boolean
    fun arrived(pose: LocalizedPose, destinationDoorsM: List<PointM>): Boolean   // FOUND and ≤ 2.5 m from a door (spec §6.7)
    fun roomHintZones(plan: FloorPlan, roomLabel: String): List<LocZone>     // every door node of that room, r 2 m, equal weights
    fun entranceHintZones(plan: FloorPlan, lastOutdoorFixM: PointM): List<LocZone> // exit nodes within 25 m of the fix, r 3 m, weight ∝ 1/distance
    fun stickyDestinationDoor(prevDoor: PointM?, candidates: List<PointM>, from: PointM): PointM // hysteresis 3 m
}
```

- **Entrée depuis la rue (spec §6.4, cas 1) :** au début d'une recherche, si un point GPS dehors (précision ≤ 10 m, à plus de 15 m de l'origine du site) date de moins de 60 s, la recherche démarre avec `WarmStart(LocalizedNavigation.entranceHintZones(plan, fixM), 0.0)`. Sinon, le départ reste celui d'aujourd'hui.

- **Recalcul de l'itinéraire :** quand l'état passe au moins à APPROXIMATIF, ou quand ≥ 2 pas ont été faits depuis le dernier calcul et que la position a bougé de ≥ 1 m, ou qu'elle a changé d'étage.
- **Hystérésis :** on ne change de porte de destination que si la nouvelle est plus proche de plus de 3 m.
- **Côté backend**, en mode localisation :
  - l'itinéraire part de `pose.positionM`, via `buildingGraph.findRouteFromPosition(floorId, pose.positionM, dest, accessibleOnly, pose.headingRad)` ;
  - aucun itinéraire tant que l'état est RECHERCHE ;
  - `fun hintRoom(key: NodeKey)` relance la recherche avec `WarmStart(LocalizedNavigation.roomHintZones(...), 0.0)` (spec §6.4 cas 4 : un indice, pas une certitude).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun poseFollowsTheMainZoneOnceApproximate()
@Test fun noHeadingUntilFound()
@Test fun arrivalWithin2_5mOfADoorOnlyWhenFound()
@Test fun routeDoesNotFlapBetweenTwoDoors()      // two doors 6 m apart, pose oscillating ±1 m in between → same door kept
@Test fun roomHintGivesOneZonePerDoorOfThatRoom() // STAN-1 "M-105" has 2 door nodes → 2 zones of r 2 m, weight 0.5
@Test fun enteringFromTheStreetStartsAtTheNearestEntrance() // STAN-1, fix 8 m outside "Sortie" → one zone on the exit node, r 3 m
```

- [ ] **Step 2: Fails** → **Step 3: Implement** → **Step 4: Passes** (all tests).
- [ ] **Step 5: Commit + push** — `"nav: routes, arrival and room hint from the localized position"`.

### Task E2 : L'écran utilisateur final (accueil de l'app)

**Files:**
- Modify:
  - `lync/app/ui/indoor/IndoorScreen.kt` ;
  - `lync/app/ui/indoor/IndoorCanvas.kt` (affiche `searchZones` / `localizedPose`) ;
  - `lync/app/ui/MainScreen.kt` (`startDestination = Routes.INDOOR`) ;
  - `lync/app/ui/settings/SettingsScreen.kt` (ligne « Mode développeur » → `nav.indoorDev()`) ;
  - `res/values*/strings.xml`.

**Comportement :**
1. **Au lancement :** un écran d'explication, une seule fois (« L'app compte vos pas pour savoir où vous êtes. Elle a besoin de l'activité physique, de la position et des notifications pour continuer téléphone en poche. »). Ensuite, la demande des 3 autorisations, puis `setLocalizationEnabled(true)`.
2. **État RECHERCHE :**
   - cercles des 2 premières zones (contour violet, remplissage à 12 %), étiquette « Vous êtes probablement ici » ;
   - feuille du bas : titre « Recherche de votre position », texte « Marchez normalement, le téléphone peut rester dans la poche. 20 à 40 m suffisent en général. », bouton « Je suis à… » (sélecteur de salles → `hintRoom`), champ « Aller à… ».
3. **État APPROXIMATIF :** un cercle de rayon `radiusM` qui suit l'utilisateur, titre « Position approximative (± N m) ».
4. **État TROUVÉ :**
   - point bleu avec le cône de cap (rendu existant `drawUserLocation`) et la trace ;
   - si une destination est choisie : l'itinéraire et `instructionText(route)` ;
   - à l'arrivée : bandeau « Vous êtes arrivé » et vibration (le retour haptique `rememberHaptics` existe).
5. **Toujours :**
   - la pastille d'étage avec « Étage N ? » (déjà présente, commit `e3e81dc`) ;
   - « Accessible » seulement s'il y a plusieurs étages ;
   - le sélecteur de bâtiment.
6. **Retiré du parcours normal :** la calibration de 5 m et le choix « départ / regard ». Ils restent accessibles dans le mode développeur.

- [ ] **Step 1: Strings.** Add every text above to `values-fr/strings.xml` and `values/strings.xml` (English). Lint `MissingTranslation` must pass.
- [ ] **Step 2: Implement** the composables `LocalizationSheet(snap, onHintRoom, onPickDestination)` and `PermissionsIntro(onContinue)` in `IndoorScreen.kt`, and the zone and pose rendering in `IndoorCanvas`.
- [ ] **Step 3: Check on the emulator**, with a GPS position on the college. Each check gets a screenshot in `recordings/review/ui-*.png`:
  - launch, explanation screen, permissions ;
  - RECHERCHE with circles ;
  - « Je suis à M-105 » → circles on its 2 doors ;
  - destination M-101 → no route while in RECHERCHE ;
  - « Réglages → Mode développeur » opens the debug map.
- [ ] **Step 4: Check on the Pixel** — a 2-minute walk, phone in a pocket and screen off, then screen back on: the state and the route are there, and nothing was asked to « reprendre ».
- [ ] **Step 5: Full suite + lint + APK** → PASS. **Commit + push** — `"ui: final user screen driven by map localization (search, approximate, found, room hint, arrival)"`.

### Task E3 : Performance, batterie et chaleur sur le téléphone

**Files:**
- Modify: `backend/LocalizationController.kt` (temps par pas : médiane et p95 sur une fenêtre de 200 pas → `LocDebug.stepMsP50`, `stepMsP95`), `lync/.../IndoorDevScreen.kt` (affichage), `rec/WalkAnalysis.kt` (batterie en % par heure et chaleur maximale d'après les notes `health`)
- Test: `app/src/test/java/com/hackdecouverte/rec/HealthReportTest.kt` (`batteryDrainPerHourFromHealthNotes` : notes 80 % → 78 % en 30 min donnent 4,0 % par heure)

- [ ] **Step 1: Test** → fails → implement → passes.
- [ ] **Step 2: On the Pixel:** a 30-minute recording, screen off, phone in a pocket (sitting or walking). Read `analysis.txt`. Targets: ≤ 5 % per hour, thermal state ≤ 1 (light), `stepMsP95` ≤ 15 ms.
- [ ] **Step 3: If `stepMsP95` > 15 ms**, set `filter.maxParticles = 1500` in `params.json` and measure again. If the battery is above 5 % per hour, lower the PDR's game rotation vector to 50 Hz in the background (spec §6.7) and measure again.
- [ ] **Step 4: Commit + push** — `"perf: step time, battery and heat measured on the phone"`.

---

## Phase F — Données et version finale

### Task F1 : Protocole des marches d'entraînement

**Files:**
- Create: `docs/data/protocole-marches.md`

**Contenu** (texte exact à écrire ; c'est ce qu'il faut pour que 3 marches suffisent) :
- **Avant chaque marche :**
  - « Limitation de la recherche Wi-Fi » désactivée ;
  - localisation activée ;
  - app installée depuis `lync-algorythme` ;
  - nom de la marche relevé (affiché après « Arrêter »).
- **W1, en main puis en poche (5 min) :**
  - départ debout dans le couloir, repère touché ;
  - couloir jusqu'au bout sud-est en main, repère au bout ;
  - téléphone en poche (étiquette « Poche ») ;
  - retour jusqu'au bout nord-ouest, demi-tour (étiquette « Virage ») ;
  - retour au départ.
- **W2, salles et pivots (5 min), téléphone en poche :**
  - entrer dans 3 salles différentes (étiquette « Porte » en entrant si le téléphone est sorti, sinon libellé `porte` dans le tracé) ;
  - deux pivots volontaires sur place (libellé `pivot` dans le tracé) ;
  - fin au départ.
- **W3, départ à froid dans une salle + main qui balance + écran éteint (5 min) :**
  - ouvrir l'app assis dans une salle ;
  - marcher main qui balance (étiquette), puis en poche écran éteint ;
  - parcours différent de W1 et W2.
- **Après chaque marche :**
  - `tools/pull-walks.sh` ;
  - tracer le trajet dans `tools/trace-STAN-1.html` avec au moins 3 instants (lus dans `report.txt` ou au chronomètre) ;
  - « Télécharger » → `recordings/<nom>.truth.json`.
- **Contrôle :** `./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*'` liste la marche, et `analysis.txt` montre une vérité recalée (« pseudo truth vs markers » ou recalage du tracé), sans erreur.

- [ ] **Step 1: Write the file.**
- [ ] **Step 2: Commit + push** — `"docs: walk protocol for training data"`.

### Task F2 : Entraînement final, acceptation et version

**Files:**
- Create: `app/src/test/java/com/hackdecouverte/rec/train/AcceptanceTest.kt` (avec `-Daccept=true`), `docs/training/<date>-final-report.md`
- Modify: `app/src/main/assets/tuning/params.json`, `app/build.gradle.kts` (`versionCode = 2`, `versionName = "1.0.0"`), `AGENTS.md`, `docs/superpowers/specs/2026-11-17-indoor-tracking-design.md` (§10 : étapes faites)

- [ ] **Step 1:** With at least 3 walks traced (the existing one + W1–W3), run `… --tests '*TrainModel*' -Dtrain=true`. Expected: **ACCEPT**. If **REJECT**: read the report; the held-out walk that gets worse says which type of walk is missing. Add one walk of that type (protocol F1) and start again.
- [ ] **Step 2: Write `AcceptanceTest`.** It computes the table below on the **held-out** walks of the cross-validation, with the final settings for the device measurements, and fails on the first criterion that is not met, naming it.
- [ ] **Step 3: Run** `… --tests '*AcceptanceTest*' -Daccept=true` → PASS.
- [ ] **Step 4:** Commit `params.json` (`"provisional": false`) + the final report, bump the version, update `AGENTS.md` (trained settings, training and acceptance commands, what is still out of scope), then **Commit + push** — `"release 1.0.0: trained settings accepted on held-out walks"`.

## Critères d'acceptation (vérifiés par `AcceptanceTest` sur les marches tenues à l'écart)

| Mesure | Cible | Source |
|---|---|---|
| PDR, distance moyenne au trajet réel (en main / en poche) | ≤ 1,5 m / ≤ 2,0 m | `PdrMetrics.routeDtwM` |
| PDR, fermeture des boucles | ≤ 5 % | `closurePct` |
| PDR, murs traversés | ≤ 1 pour 100 m | `wallCrossingsPer100m` |
| Suivi avec départ connu, en main | médiane ≤ 1,5 m, p90 ≤ 3 m (spec §9) | `FilterRun` KNOWN_START |
| Suivi avec départ connu, en poche | médiane ≤ 2,5 m, p90 ≤ 5 m (spec §9) | idem |
| Support jamais perdu, départ connu | ≥ 2 marches sur 3 | `supportLostAtS == null` |
| Distance avant « Trouvé » juste, avec GPS, au collège | médiane ≤ 40 m (au lieu des 25 m de la spec : le disque GPS couvre toute l'aile et le couloir est symétrique) | `foundAfterM` COLD_GPS |
| « Trouvé » au mauvais endroit (> 5 m) | ≤ 5 % des pas en « Trouvé » | `falseFoundShare` |
| Fin de marche, une fois trouvé | erreur ≤ 5 m sur chaque marche | `endErrM` |
| Carte prête sur le téléphone | ≤ 3 s | `mapBuildMs` (D2/E3) |
| Temps par pas | p95 ≤ 15 ms | `stepMsP95` |
| Batterie écran éteint | ≤ 5 % par heure | E3 |
| Chaleur sur 30 min | état ≤ « léger » | E3 |

## Risques et ce que le plan fait si ça arrive

- **Peu de données :** 3 à 4 marches, un seul téléphone (Pixel 9).
  - La régularisation et la porte limitent le sur-apprentissage.
  - Le rapport nomme le modèle de téléphone.
  - Sur un autre téléphone, une marche tracée suffit à revérifier `AcceptanceTest`.
- **Le couloir du collège est un long couloir symétrique :** un départ à froid sans indice peut demander plus de 40 m. Le raccourci « Je suis à… » (E1) et le départ à partir des dernières zones (déjà fait) couvrent l'usage réel. Le critère est mesuré, pas promis.
- **Le plan du collège vient d'une photo :** si D1 révèle plus de 3 % d'erreur d'échelle ou des portes décalées, la carte est corrigée *avant* l'entraînement final, sinon les réglages appris compenseraient l'erreur de carte.
- **Un suivi toujours perdu après B2–B6 et l'entraînement :** le rapport de support (B1) dit où. Les deux pistes suivantes, hors de ce plan, sont :
  - l'odométrie apprise sur les pas (`docs/research`, piste C) ;
  - le positionnement par empreinte Wi-Fi, appris à partir des marches tracées (164 bornes vues en 2 min : l'information existe).
- **Arrêts en arrière-plan par d'autres marques de téléphone :** non mesurés. Sur un arrêt, l'enregistrement reste lisible et le départ suivant repart des zones enregistrées.

## Ordre et durée réaliste

1. **A1–A4 :** 1 jour.
2. **B1–B6 :** 1,5 à 2 jours.
3. **C1–C3 :** 1 jour.
4. **D1 :** 30 min sur place + 1 h.
5. **D2 :** 0 à 0,5 jour, selon la mesure.
6. **D3 :** 4 à 6 h de travail manuel dans l'éditeur.
7. **E1–E3 :** 1,5 jour.
8. **F1 :** 1 h.
9. **Les 3 marches :** 45 min de terrain + 30 min de tracé.
10. **F2 :** 1 h de calcul + corrections.

Les phases A puis B puis C sont dans cet ordre parce que chaque phase mesure avec les outils de la précédente. D1 doit passer avant F2. E peut se faire en parallèle de C et D.
