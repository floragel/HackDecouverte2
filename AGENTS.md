# Hackdecouverte — PDR calibration / telemetry test app

Minimal Android app (French UI) that establishes a robust pedestrian-dead-reckoning
foundation: 5 m straight-line step-length calibration, step counting (hardware
`STEP_DETECTOR` or explicit experimental accelerometer fallback), relative heading
from `GAME_ROTATION_VECTOR`, and a meter-accurate telemetry trace view. No pins,
no navigation, no backend, no GNSS.

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
  absolute north, no GPS, no cm-level accuracy claims.
- Optional floor anchoring (package `com.hackdecouverte.map`, pure Kotlin):
  `floorplan.json` (asset, also on unit-test classpath) is a node/edge graph in
  normalized coords × estimated plan dimensions (axis y = south). `MapAnchor`
  projects local PDR points onto the plan: `toMap(p) = origin + r·p.x + f·p.y`
  with `f = (sin ψ, −cos ψ)`, `r = (cos ψ, sin ψ)` — ψ = facing heading,
  clockwise from plan-up (−y). Display heading = `wrap(ψ + pdrHeading)`.
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
