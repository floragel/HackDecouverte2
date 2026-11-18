# Zero-effort indoor localization: research directions

*2026-11-18 · working notes toward a paper · branch `lync-algorythme`*

## 1. The claim we want to be able to make

> A phone that has never been in a building, with no site survey, no installed infrastructure
> and no user calibration, finds where it is on the floor within a few metres after a short
> walk, using only a photo of the floor plan that is already posted on the wall.

"Zero effort" here means zero effort for **three** parties: the building owner (no beacons, no
fingerprint survey), the map maker (the plan comes from the evacuation plan already on the wall),
and the user (no 5 m calibration walk, no "tap where you are"). Zee (MobiCom 2012) removed the
survey but still assumed a ready-made floor map. Our angle is to remove the map-making step too,
and to use sensors and radios phones have gained since 2012.

## 2. What exists today in this repo (the baseline)

- **PDR:** step detection, dynamic step length, phone heading from the game rotation vector,
  aligned to True North by the compass and GPS course.
- **Map:** occupancy grid at 10 cm, door openings, a corridor direction field. It is built from a
  floor plan drawn by hand from the evacuation-plan photos (`floor-plans/STAN/gen_floor.py`).
- **Zee-style particle filter:** walls eliminate hypotheses, and the stride is learned from a wide
  prior. Backward belief propagation retraces the walk. A GPS disk gives the cold start and a
  tolerant GPS likelihood follows.
- **Walk recorder and replay (new):**
  - the phone logs the exact sensor stream the PDR used, plus research streams;
  - the computer replays it through the same code, so every idea below can be measured offline on
    the same walks;
  - ground truth comes from markers the walker taps on the map.

**First result from the replay tooling.** In a straight corridor, the filter with an unknown
phone angle cannot tell "walking north from the south half" from "walking south from the north
half". Using the compass only as a ±40° prior on that angle (spec §6.3, now implemented) removes
this mirror ambiguity. On the synthetic college corridor walk, the error at the end fell from
16 m to 1.7 m. That is a clean ablation for the paper: *compass as a weak prior, never as truth*.

## 3. Landscape (short)

| Line of work | What it gives | Limit for us |
|---|---|---|
| Zee / UnLoc (2012) | Walls sort out position and stride; organic landmarks | Needs a ready map; 2012 sensors |
| Learned inertial odometry (RoNIN, TLIO, ReNiL 2025, TinyIO 2025) | Better displacement than step counting, any phone hold | Needs ground-truth data to train; heavy models |
| Magnetic SLAM (calibration during SLAM, 2024; CrowdMagMap 2.0) | Map-free landmarks, works in steel buildings | Needs revisits (loop closures) |
| Wi-Fi RTT 802.11mc / 802.11az | 1–2 m ranges, sub-metre with 11az (Android 15) | Few APs answer; AP positions unknown |
| BLE Channel Sounding (Android 16 RangingManager) | About ±1 m to 20 m (vendor demo, Pixel 9/10) | Needs a reflector device; very new |
| Evacuation-plan photos (Stuttgart 2010–2013; ML digitization 2023) | Map **and** "you are here" start from one photo | Classic image processing, brittle |
| VLM floor-plan parsing (2024–2025) | Plans → graphs of rooms and doors | Not metric, not aimed at localization |
| GNSS shadow / sidewalk matching (UCL) | Which half of the sky is visible → side of street | Outdoors, needs 3D city models |
| Barometer door events (UbiComp 2015) | Door open/close detected anywhere in the building | Event, not position |

## 4. New directions

Each item says why it is new, what the recorder already logs to test it, and how big it is.

### A. The evacuation plan as the whole map and the start (★ strongest story)

**Idea.**
- One photo of the posted plan gives a metric map. A VLM plus a small vectorizer, as we did by
  hand for STAN, produces walls, doors, rooms and corridors.
- The plan also gives a **start prior**: the "you are here" marker.
- The phone's orientation at the moment of the photo gives the facing direction. The phone is
  roughly perpendicular to the wall the plan hangs on, and the plan's own up direction shows which
  way that wall faces.
- The start enters the filter as a *distribution*, not a pose. This makes the system robust to a
  wrong marker or a skewed photo.

**Why new.** The Stuttgart work used template matching and a single pose to initialize a
foot-mounted IMU. We combine:
- modern VLM parsing;
- a probabilistic start prior;
- a Zee filter that tolerates map errors.

The plan-to-filter pipeline is end to end, with no human in the loop.

**Test now.**
- Generate the map from the photos automatically and compare it with `gen_floor.py`'s
  hand-made map.
- Replay the same recorded walks on both maps; the error difference measures the cost of
  automation.

**Size.** Medium (one VLM prompt and a vectorizer, plus a start-prior API in the filter).

### B. Indoor "sky-side" matching with GNSS (★ cheapest new signal)

**Idea.**
- Indoors, the satellites a phone still hears mostly come through windows. The azimuths with
  usable C/N0 therefore say which facade you are near.
- The floor plan already holds the facades: the outer walls, with the building's true bearing from
  the site origin. So each particle can be scored by how well "facade direction" matches "sky
  directions heard".
- This needs no 3D city model; a 2D plan and the satellite almanac the phone already reports are
  enough.

**Why new.** Shadow matching and sidewalk matching are outdoor techniques. Applying the same idea
inside, against a 2D plan, to break symmetric ambiguities (for example, two parallel corridors) is
unexplored as far as we found.

**Test now.**
- The recorder logs every satellite's azimuth, elevation and C/N0 (`N` lines). The replay report
  already prints the mean C/N0 per 45° sector around each marker.
- Walk the corridor along each facade and check that the strongest sector flips.

**Size.** Small for the measurement study; medium for the likelihood in the filter.

**Risk.** A concrete building may block almost everything; the first walks will tell.

### C. The map is the teacher: self-supervised learned odometry

**Idea.**
- Once the filter has found the walker, the backward-propagated trace is a good estimate of where
  they really walked, typically within 1–2 m.
- Use those traces as pseudo ground truth to fine-tune a tiny inertial odometry network (TinyIO
  scale) on the user's own raw 100 Hz IMU data.
- Better odometry leads to faster convergence, which gives more traces: a loop that improves with
  use.

**Why new.** Learned inertial odometry needs motion-capture or visual-inertial ground truth.
Using map-constrained smoothing as the supervisor removes that need, which fits "zero effort".

**Test now.** The recorder logs raw accelerometer and gyroscope at 100 Hz (`S,1,…,x` and
`S,4,…,x`), and the replay produces the traced walk. Offline training needs no app change.

**Size.** Large (training pipeline). The highest research value if A and B land.

### D. Opportunistic ranging to anchors nobody surveyed

**Idea.**
- Wi-Fi RTT (802.11mc, 802.11az on Android 15+) and BLE Channel Sounding (Android 16) give
  metre-level ranges, but anchor positions are unknown.
- Crowdsourced traces locate the anchors: each found walk plus its ranges is a trilateration
  constraint on the AP.
- Ranges to localized anchors then weigh particles. This is Zee's Wi-Fi step with ranges instead
  of fingerprints.

**Test now.** The recorder flags 802.11mc responders in every scan and ranges up to the maximum
number of peers once a second (`R` lines). The report says how many APs at the college answer.
If none do, the idea is parked for this building.

**Size.** Medium. Hardware-dependent.

### E. Organic landmarks found by the filter itself

**Idea.** UnLoc's landmarks (magnetic anomalies, turns, elevators), but discovered and placed
using the smoothed traces rather than by assumption. Two signals no one uses for landmarks are
added:
- **Barometric door transients:** an exterior door or a stairwell door opening sends a pressure
  pulse that the phone barometer sees (UbiComp 2015 showed detection). Stairwells are often
  pressurized for smoke control, so entering one can show as a step change.
- **Light:** passing a window versus an interior corridor (the light sensor is logged).

**Test now.** Pressure at 10 Hz is in the pipeline stream (`S,6`), light and magnetometer
(calibrated and raw) are in the research stream. Align them on the traced walks and look for
repeatable signatures at the same places across walks.

**Size.** Medium; needs several walks over the same places.

### F. A map that repairs itself

**Idea.** A plan generated from a photo has errors: a door that is really closed, a wall a metre
off. Accumulated traced walks show where people actually pass, so:
- cells crossed by many confident traces are opened;
- door openings nobody uses are closed.

**Test now.** Needs about 10 or more walks; the replay already outputs traces per walk.

**Size.** Small once enough walks exist; a nice figure for the paper.

## 5. Evaluation protocol

- **Ground truth:** markers tapped on the map at the start, at every turn, at doors and at the
  end. About ±1 m, which is enough at this scale. For the paper, add a few surveyed points (taped
  marks on the floor) to bound the marker error.
- **Metrics per walk:**
  - error of the live estimate at each marker;
  - error of the traced-back walk;
  - time and distance to the first FOUND, and whether that FOUND was correct (error < 3 m);
  - GPS error indoors, for context.
- **Ablations:** no GPS, no compass prior, no map (raw PDR from the first marker), stride prior
  fixed versus learned. Then each new signal A–F added on top.
- **Dataset:** different routes, phone holds (hand, pocket, swinging), people and phones. The
  recordings stay local (`recordings/` is git-ignored) because they contain Wi-Fi BSSIDs and
  routes. A public release would hash the BSSIDs.

## 6. Suggested order

1. **Collect.** Record 10 short walks at the college with markers, different routes and phone
   holds. Run `tools/pull-walks.sh`.
2. **Baseline.** Measure the current system on them; fix what the replays show.
3. **B (GNSS sky-side).** The data is already logged and the measurement study is cheap. A
   positive result is a novel figure on its own.
4. **A (evacuation-plan pipeline).** This is the paper's headline: map plus start from one photo.
5. **C or D,** depending on what the college's radios and the first results allow.

## 7. Honest unknowns

- **GNSS** may be unusable deep inside the college (B fails gracefully: no signal, no
  likelihood).
- **Wi-Fi RTT:** the college APs are probably not 802.11mc responders (D may be impossible there).
- **VLM floor-plan parsing** is reported on clean plans; photos of posted plans are skewed,
  glared and annotated.
- **Marker taps** add about 1 m of noise to ground truth; fine for 3–5 m claims, not for
  sub-metre claims.

## Sources

- Zee: <https://www.microsoft.com/en-us/research/publication/zee-zero-effort-crowdsourcing-for-indoor-localization>
- TLIO: <https://arxiv.org/pdf/2007.01867> · IDOL: <https://arxiv.org/pdf/2102.04024> · ReNiL: <https://arxiv.org/html/2508.06053v2> · TinyIO: <https://arxiv.org/pdf/2507.15293v4> · UMLoc: <https://arxiv.org/pdf/2601.06602v1.pdf>
- Magnetometer calibration during SLAM (arXiv 2409.01242): <https://www.emergentmind.com/papers/2409.01242> · 1D magnetic SLAM: <https://arxiv.org/abs/2409.01091> · CrowdMagMap 2.0: <https://quarxiv.authorea.com/doi/full/10.36227/techrxiv.171591465.55835918/v1>
- Wi-Fi RTT: <https://people.csail.mit.edu/bkph/FTMRTT> · <https://www.crowdconnected.com/blog/testing-wifi-rtt-on-android-p-for-indoor-positioning/> · <https://pmc.ncbi.nlm.nih.gov/articles/PMC11598830> · 802.11az on Android: <https://nextpit.com/wi-fi-ranging-what-it-is-android-devices-feature>
- BLE Channel Sounding on Android 16: <https://devzone.nordicsemi.com/nordic/nordic-blog/b/blog/posts/evaluating-bluetooth-channel-sounding-with-our-open_2d00_source-android-app-on-google-pixel-10> · <https://www.bluetooth.com/blog/true-distance-awareness-with-bluetooth-channel-sounding/>
- Evacuation plans: <https://ifpwww.ifp.uni-stuttgart.de/publications/2011/20110530_Paper_IPIN_CamReady.pdf> · <https://isprs-archives.copernicus.org/articles/XLI-B4/325/2016/> · <https://doaj.org/article/11f2c0831ec249ebaa37a2fda795bb1f>
- VLM floor plans: <https://arxiv.org/abs/2409.12842v2> · <https://arxiv.org/html/2512.12177v1>
- GNSS shadow matching: <https://www.doi.org/10.1017/S0373463314000836> · environmental context from smartphone GNSS: <https://discovery-pp.ucl.ac.uk/id/eprint/10044245/1/NAVIGATION_Environmental%20Context%20Detection%20for%20Adaptive%20Navigation%20using%20GNSS%20Measurements%20from%20a%20Smartphone_Gao%20and%20Groves_submission.pdf>
- Barometer door events: <https://www.aminer.org/pub/573696db6e3b12023e5daca5/monitoring-building-door-events-using-barometer-sensor-in-smartphones>
- Leto (crowdsourced radio maps, TMC 2024): <https://home.cse.ust.hk/~gchan/papers/TMC24_leto.pdf>
