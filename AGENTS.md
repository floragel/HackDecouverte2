# Hackdecouverte — Indoor PDR + MB 1-7 Multi-Floor + OpenStreetMap Backend

Modular Android library/testbench (French UI) exposing a single integration facade
`com.hackdecouverte.backend.IndoorNavigationBackend` (`StateFlow<NavigationSnapshot>`)
for the complete hackathon app:
- `pdr/` (Android-free): 5 m step-length calibration, dynamic cadence step length,
  anti-shake gait filter, Swing-Twist heading fused with True-North (GPS + geomagnetic
  declination + `ROTATION_VECTOR` / `GAME_ROTATION_VECTOR`), bounded corridor assist.
- `map/` (Android-free):
  - `GeoReference`: WGS84 <-> True-North metric `(eastM, northM)` centered at the MB
    6-elevator core `(45.4952549, -73.5790496)`.
  - `FloorVectorPlan`: parses aligned `mb-floor-plans` SVGs (`MB-floor-01..07.svg`).
  - `FloorPlan` & `BuildingGraph`: loads `floors/floorplan-MB-1..7.json` (761 nodes,
    ~300 rooms across 7 floors, multi-door rooms, `15 m/floor` stairs,
    `20 m + 3 m * Δfloor` elevators, PMR `accessibleOnly` mode).
  - `OsmSurroundings`: OpenStreetMap vector surroundings (`assets/osm/mb_surroundings.json`
    + live `/api/0.6/map` XML parser & disk cache via `OsmNetworkLoader`).
- `sensor/`: `PdrSensorAdapter` + `GpsLocationSource` (automatic True-North alignment,
  zero manual angle calibration required).
- `rec/` (Android-free): walk recording format (`RecordingWriter`/`RecordingReader`) and
  `WalkReplay`, which runs a recorded walk through `SensorRouter` → `PdrTracker` →
  `LocalizationController` exactly as the phone does. The phone side is `sensor/WalkRecorder`
  (record button on the debug map, tap = ground-truth marker). `tools/pull-walks.sh` pulls the walks
  into the git-ignored `recordings/`, then `RecordingReplayTest` writes `app/build/replay/<walk>/`
  (report.txt, steps.csv, walk.svg). Research directions: `docs/research/`.
- `ui/`: Thin test harness UI + GPU-cached `FloorVectorRenderer` over `IndoorNavigationBackend`.

## Build / test

- JDK 17 required (`/opt/homebrew/opt/openjdk@17`). Gradle wrapper included.
- `local.properties` must contain `sdk.dir=/Users/Apple/Library/Android/sdk` (gitignored).
- Commands:
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest`
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug`
  - `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:lintDebug`
- Pure-domain PDR math (package `com.hackdecouverte.pdr`) is Android-free and JVM
  unit-tested: `PdrModels`, `OrientationProcessor`, `StepDetectorEngine`,
  `PathGeometry`, `PdrTracker`.

## Frame and conventions

- Position: x right, y forward; yaw positive clockwise from the reference heading.
- Each accepted step: `theta = meanHeading(event.timestamp) - reference`,
  `x += L·sin(theta)`, `y += L·cos(theta)`. Position only changes on accepted steps.
- Cap is always relative ("Axe fixe"); `GAME_ROTATION_VECTOR` drifts, so no
  absolute north, no cm-level accuracy claims.
- Corrections in `PdrTracker` must stay bounded per step (heading ≤ 2.5°/step,
  lateral ≤ 12 cm/step, GPS ≤ 8 cm/step) and must never rewrite past trace
  points; corridor matching is lateral-only on corridor↔corridor edges aligned
  with the walking direction. Step length is dynamic:
  `L₀·clamp(1 + 0.9·(f/f₀ − 1), 0.75, 1.30)` from step cadence.
- Optional floor anchoring (package `com.hackdecouverte.map`, pure Kotlin):
  `floorplan.json` (asset, also on unit-test classpath) is a node/edge graph in
  normalized coords × estimated plan dimensions; image y is flipped at parse
  (`posM.y = (1 − y)·H`) so the plan frame is north-up (+y = plan top/screen
  up). `MapAnchor` projects local PDR points onto the plan:
  `toMap(p) = origin + r·p.x + f·p.y` with `f = (sin ψ, cos ψ)`,
  `r = (cos ψ, −sin ψ)` — ψ = facing heading, clockwise from plan-up (+y).
  Display heading = `wrap(ψ + pdrHeading)`.
  Opt-in: start node + facing picked on the calibration screen; without an
  anchor the local-frame behavior is unchanged. `pdr/` is untouched.
- Sensor timestamps (`SensorEvent.timestamp` = `SystemClock.elapsedRealtimeNanos`,
  CLOCK_BOOTTIME) are the truth; delayed events are matched against a 2.5 s
  orientation buffer, not their arrival time. The whole app injects that same
  clock into `PdrTracker`/`FallbackStepDetector` — never `System.nanoTime`.

## Limitations

- Foreground only: `onStop` pauses tracking / cancels calibration and clears the
  orientation buffer; resume requires the explicit "Reprendre (nouveau trajet)"
  action plus a fresh valid heading (sensor re-registration resets the reference
  frame, so traces are never connected across registrations).
- Step length is a scalar in [0.2, 1.5] m; calibration needs an actual 5 m walk.
- Accelerometer fallback thresholds (1.0 m/s² crossing, 0.3 rearm, 330 ms refractory,
  500 ms warm-up) are experimental, no latency guarantee.
- Display smoothing (quadratic midpoint) is visual only; distances/milestones are
  computed on raw step points.
