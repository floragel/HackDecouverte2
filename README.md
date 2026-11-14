# Lync

**The companion app for university students: meet your campus, then get to the right room.**

Lync has two halves that live in one Android app:

- **Network**: a campus-only social app (Instagram × LinkedIn × Snap Map). Feed, stories, classmates, clubs, events, live friends map and a holographic student card.
- **Maps**: door-to-door navigation down to the room. Pick `MB-2.255` (or tap a class in your calendar): Lync walks you there through the streets like Google Maps, then switches by itself to the indoor floor plan when you enter the building, detects your floor from altitude and guides you floor by floor with step counting.

Built for the HackDécouverte hackathon, starting with Concordia University (Montréal). Bilingual English (default) / French. Android first (Kotlin + Jetpack Compose), iOS later (SwiftUI, same structure).

| | |
|---|---|
| Buildings mapped | John Molson (MB), floors 1 to 7 |
| Navigation graph | 761 points, about 300 rooms, stairs and elevators linked between floors |
| Unit tests | 120, all passing |
| Backend | Supabase (optional: the app runs offline on demo data) |

---

## Contents

1. [Run it](#run-it)
2. [Features](#features)
3. [How indoor navigation works](#how-indoor-navigation-works)
4. [Door to door: outside, then inside](#door-to-door-outside-then-inside)
5. [Where the MB floor plans come from](#where-the-mb-floor-plans-come-from)
6. [Architecture](#architecture)
7. [Project layout](#project-layout)
8. [Supabase](#supabase)
9. [Tests](#tests)
10. [Privacy](#privacy)
11. [Branches](#branches)
12. [Known limits](#known-limits)
13. [Credits](#credits)

---

## Run it

**Requirements:** Android Studio (or the Android SDK), JDK 17, a phone or emulator with Android 8.0 (API 26) or newer.

1. Clone the repo and open the folder in Android Studio. Let Gradle sync.
2. Press **Run**. Without Supabase keys the app starts on built-in Concordia demo data (a "Demo data" badge shows it).
3. Rotate the phone, from any screen, to see the Lync student card.

From the command line:

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If Gradle can't find the SDK, create `local.properties` at the root (it is git-ignored):

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

> Use forward slashes in `sdk.dir` on Windows: backslashes must be doubled in a `.properties` file.
> Don't keep the project in an iCloud- or OneDrive-synced folder: synced copies like `file 2.kt` break the build.

### Try door-to-door navigation

1. **Calendar tab** › tap a class › **Get walking directions**, or **Map tab** › search a room (`2.210`, `MB-1.437`, `H-920`…) › **Directions**.
2. Outside: the walking route, time and arrival appear. Press **Start** for turn-by-turn guidance ("In 80 m, turn right onto Rue Guy").
3. Walk into the John Molson building: the map turns into the floor plan by itself, says which floor you're on and keeps guiding you to the room.
4. Already inside? Press **I'm already inside**, pick where you stand (a room, stairs, elevator or exit): that is your starting point, then your steps move the dot. **Change** it any time if the dot drifts.

Far from campus (more than 8 km), the app offers to open the phone's Maps app for the metro or bus, and takes over at the door.

### Try indoor navigation only

**Map tab › Find a room** opens the MB plan.

1. Allow **physical activity** when asked, so the app can count your steps.
2. Choose the room next to you ("You're at"), or use your GPS position near the entrance.
3. Choose where you're going ("Going to"), e.g. `2.255`. Turn on **Step-free route** to use elevators only.
4. Press **Let's go** and walk with the phone held flat in front of you. Measure your step once with **Measure my step (5 m walk)** for better accuracy.

---

## Features

### Network

- **Feed**: photo and text posts, **polls**, **plans** ("who's coming to the gym at 5?" with spots and an "I'm in" button), likes, comments, share.
- **Stories** (24 h): text or photo, full-screen viewer.
- **Network**: requests, classmates (shared courses), clubs, search, profiles with Connect and Follow.
- **Events**: club events by category, featured events, RSVP, friends going, add to calendar.
- **Groups**: join or create interest groups, each with its own feed.
- **Calendar**: import your class schedule from an `.ics` file or link, see classmates per course.
- **Lync card**: turn the phone sideways anywhere. Holographic foil driven by the gyroscope; tap to flip it.
- **Lync code**: our own circular code (not a QR code). Scan it with the camera, or **tap phones back to back (NFC)** to connect.
- **Settings**: profile, program picker (all 165 Concordia programs), who sees your location, theme, language.

### Maps

- **Door-to-door directions** from any room, building or calendar class: walking route on the street map, then the indoor plan inside MB, with a trip bar *Outside → MB → 2.210*.
- **Floor from altitude**: the barometer pressure measured in the street is compared to the pressure inside to find your floor.
- **Set your own position** inside: choose the room you stand at, the pedometer takes it from there.
- **Campus map**: friends and classmates live, ghost mode, **"Qui est là ?"** (friends grouped by building), search over every real MB room and Concordia building.
- **Indoor plan of MB**: 7 floors, room search, turn-by-turn directions in English or French, step-free routes, elevator / stairs / exit icons.
- **Light and dark mode** button right on the map (same setting as Settings › Appearance).
- **Developer menu** (Settings › Developer › Indoor navigation test): the raw test screens of the navigation engine, with step count, heading and sensor status.

---

## How indoor navigation works

GPS barely works inside a concrete building and knows nothing about floors. Lync uses **pedestrian dead reckoning (PDR)**: it counts your steps and reads which way the phone points.

1. **North.** GPS and the compass give true north, so "straight ahead" on the phone matches the plan. The MB grid is rotated 40.5° from north.
2. **Step length.** Measured once by walking 5 m, then adjusted to your pace (shorter steps when you walk slowly).
3. **Each step** moves your dot in the direction of the phone. Corrections stay small (at most 2.5° of heading and 12 cm sideways per step), so the dot never jumps. In corridors, the dot is gently pulled onto the corridor axis.
4. **Route.** Shortest path (Dijkstra) on the building graph. Walking costs its distance, stairs 15 m per floor, elevators 20 m + 3 m per floor. Step-free mode drops stairs and steps.
5. **Directions** are recomputed at every step: "Go straight for 12 m, then turn left", "Take the elevator to floor 4", "You're at 2.255".

The full explanation (sensors, clocks, formulas) is in [`ALGORITHM.md`](ALGORITHM.md) (French). The rules for changing the engine are in [`AGENTS.md`](AGENTS.md).

---

## Door to door: outside, then inside

One screen (`ui/go/GoScreen.kt`) handles the whole trip.

1. **Where to?** `CampusPlaces` reads free text from the calendar or the search: `JMSB, MB-2.210` → MB, floor 2, room 2.210; `H-920` → Hall building, floor 9.
2. **Outside.** The walking route comes from the free OpenStreetMap routing service (OSRM, foot profile, no key), towards the MB door closest to you. The screen shows time, distance and arrival time, then follows you with a tilted camera and turn-by-turn cards. It recalculates when you are more than 35 m off route. Offline, it falls back to a straight line.
3. **Entering MB.** The screen switches to the floor plan when:
   - GPS places you inside the building twice in a row, or
   - you were at a door and GPS suddenly gets much less accurate, or stops for 12 s (walls block the sky).

   Step tracking then starts at the door you came through, and the route continues on the MB graph up to the room.
4. **Which floor?** A phone barometer reads absolute pressure, and the weather shifts that by about ±20 hPa (±170 m), so pressure alone can't give a floor. Lync samples the pressure **in the street** next to MB. Inside, the height above the street is `h = 44330 · (1 − (p / p₀)^(1/5.255))`, matched to the nearest floor (ground floor at street level, +5 m for floor 2, then +4 m per floor). Without a street sample, it assumes the ground floor if you came from outside, or asks you. Stair and elevator climbs are then followed by the barometer as before.
5. **Your own reference.** Inside, you can set your position yourself: pick the room, stairs or door next to you. That point becomes the origin and each step moves you from it.
6. **Leaving.** Three clean GPS fixes more than 30 m outside the building bring back the street map, unless you chose "I'm already inside" by hand.

Code: `data/places/` (place parser, OSRM client, route progress), `pdr/AltitudeFloorEstimator.kt`, `IndoorNavigationBackend.enterBuilding()`.

---

## Where the MB floor plans come from

Concordia does not publish detailed floor plans, so we made our own. The sources and the graph editor are in [`floor-plans/MB/`](floor-plans/MB/README.md) (run `python floor-plans/MB/editor_server.py`, then open the page it prints).

1. **Photos**: we photographed the evacuation plan on each floor.
2. **Vectorization**: a Python script straightens the photo, removes the red evacuation layer and traces the walls as SVG.
3. **Alignment**: the origin is the middle of the 6 central elevators. The scale comes from OpenStreetMap (the elevator lobby is 3.46 m wide), so all 7 floors share one metric frame, x = east and y = north.
4. **Graph**: in a small web editor, each room is one point: **its door**. Corridors, stairs and elevators are linked, between floors too; edges with steps are marked.
5. **Export**: one JSON file per floor, copied into `app/src/main/assets/floors/`.

The same graph drives the route, the room labels on the plan and the room search.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│ SCREENS (Jetpack Compose)                                               │
│  Home · Network · Calendar · Map · Profile · Lync card · Scanner        │
│  Indoor MB plan (ui/indoor)                                             │
├─────────────────────────────────────────────────────────────────────────┤
│ LOGIC                                                                   │
│  One ViewModel per screen (state in a StateFlow)                        │
│  CalendarRepository (ICS)        IndoorNavigationBackend (one facade)   │
│                                   ├─ PdrTracker: steps, heading, length │
│                                   └─ BuildingGraph: Dijkstra, 7 floors  │
├─────────────────────────────────────────────────────────────────────────┤
│ DATA ON THE PHONE                                                       │
│  CampusRepository ─┬─ DemoRepository (offline, fake Concordia data)     │
│                    └─ SupabaseRepository                                │
│  Location (GPS + visible sharing service) · Sensors (step detector,     │
│  rotation vector, compass) · Floor plans (7 SVG + 7 graph JSON) · OSM   │
├─────────────────────────────────────────────────────────────────────────┤
│ SERVICES                                                                │
│  Supabase (Postgres, Auth, Storage, Realtime) · OpenFreeMap tiles       │
│  (MapLibre) · Google Play location                                      │
└─────────────────────────────────────────────────────────────────────────┘
```

Rules that keep it simple, and portable to iOS one-to-one:

- **The UI only talks to one interface**, `CampusRepository`. `AppContainer` picks the demo or the Supabase implementation at startup (no dependency injection framework).
- **The navigation engine is pure Kotlin** (`com.hackdecouverte.pdr` and `.map` have no Android code), so its maths is unit-tested without a phone. The app only uses `IndoorNavigationBackend`, which publishes a `NavigationSnapshot`.
- **Each screen** is an `XxxViewModel` (state in a `StateFlow`) plus an `XxxScreen` composable: on iOS, an `ObservableObject` plus a SwiftUI `View`.
- **No hard-coded text**: everything the user reads is in `res/values/strings.xml` (EN) and `res/values-fr/strings.xml` (FR). The engine's developer screens are the exception (French only).

### Design

- Neutral black, white and blue-grey interface. **The only bright color is the university's** (Concordia burgundy), applied at runtime.
- Bricolage Grotesque for titles, Figtree for text, bundled in the app.
- Every tappable element reacts (`Modifier.pressable()`: a small spring scale).
- Style guide (French): [`design/CHARTE_GRAPHIQUE.md`](design/CHARTE_GRAPHIQUE.md). Tokens and the Android ⇄ iOS component map: [`design/README.md`](design/README.md).

---

## Project layout

```
app/src/main/
├── java/com/lync/app/            the Lync app
│   ├── AppContainer.kt           picks DemoRepository or SupabaseRepository
│   ├── code/                     Lync code (encoder, renderer) + NFC
│   ├── data/                     models, CampusRepository, demo/, supabase/,
│   │                             location/, calendar/, places/ (place parser,
│   │                             walking router, room directory), Buildings
│   └── ui/                       one folder per screen: feed/, network/, map/,
│                                 calendar/, profile/, card/, scan/, settings/,
│                                 go/ (door-to-door trip), indoor/ (MB plan),
│                                 theme/, components/, motion/
├── java/com/hackdecouverte/      the indoor navigation engine
│   ├── backend/                  IndoorNavigationBackend (the facade), OSM loader
│   ├── pdr/                      step detection, heading, PdrTracker, barometer
│   │                             floors, AltitudeFloorEstimator (pure Kotlin)
│   ├── map/                      FloorPlan, BuildingGraph, GeoReference, SVG parser
│   ├── sensor/                   Android sensors and GPS adapters
│   └── ui/                       the engine's own test screens (developer menu)
├── assets/
│   ├── floors/                   floorplan-MB-1..7.json (navigation graph)
│   ├── floor-plans/MB/           MB-floor-01..07.svg (aligned walls)
│   └── osm/                      streets and buildings around MB
└── res/                          strings (EN/FR), fonts, icons
floor-plans/MB/                   graph source (MB-graph.json), aligned SVGs, graph editor
supabase/                         schema.sql, migrations/, seed/
design/                           style guide, logo, app icon, fonts
```

---

## Supabase

The app works without a backend. To use a real one:

1. Create a project on supabase.com, open **SQL Editor** and run, in order (each file is safe to re-run):
   1. [`supabase/schema.sql`](supabase/schema.sql)
   2. [`supabase/migrations/002_lync_v2.sql`](supabase/migrations/002_lync_v2.sql): polls, plans, comments, stories, Lync code
   3. [`supabase/migrations/003_campus_life.sql`](supabase/migrations/003_campus_life.sql): events, groups
   4. [`supabase/seed/seed_demo.sql`](supabase/seed/seed_demo.sql) (optional): 13 fake Concordia students with posts, events, groups and map positions
2. **Authentication › Providers › Email**: for the hackathon, turn off "Confirm email".
3. Add your keys to `local.properties` (never committed):

   ```properties
   supabase.url=https://YOUR-PROJECT.supabase.co
   supabase.anonKey=YOUR-ANON-KEY
   ```

4. Rebuild. The app switches to the real backend automatically.

With the demo seed, choose **I already have an account** and sign in with the demo account written at the top of `seed_demo.sql`. To plug your own account into the fake network: `select public.lync_demo_adopt('you@live.concordia.ca');`

To force the offline demo even with keys: `lync.demo=true` in `local.properties`, or `./gradlew assembleDebug -Plync.demo=true`.

> Never commit a key: **this repository is public**. `local.properties` is git-ignored for that reason.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

120 unit tests, run on the computer without a phone:

- **Navigation engine**: step detection and its accelerometer fallback, heading, true-north alignment, path smoothing, routes across floors, a corridor-turn regression, MB plan and OpenStreetMap parsing.
- **Door to door**: floor from altitude, place parsing (`JMSB, MB-2.210`, `H-920`…), OSRM route parsing and route progress.
- **Lync**: Lync code encoding and decoding, calendar `.ics` parsing.

---

## Privacy

Users are 16 to 24, and some are minors.

- Friends' positions are readable **only** by accepted connections (and classmates if the user opts in). This is enforced in the database by Postgres row-level security (`schema.sql`), not only by the app.
- **Ghost mode** stops sharing in one tap. While sharing is on, Android shows a notification: it never happens silently.
- Positions older than 2 hours are ignored.
- **Indoor navigation stays on the phone**: your position inside the building is never sent anywhere.
- Any email can sign up for now; university email verification is planned.

---

## Branches

| Branch | What it holds |
|---|---|
| `main` | Everything: Lync + door-to-door navigation + indoor engine + MB plans and graph editor |
| [`lync-network`](https://github.com/floragel/HackDecouverte2/tree/lync-network) | The Lync app (network, design, Supabase, map, calendar) |
| [`algorythme`](https://github.com/floragel/HackDecouverte2/tree/algorythme) | The indoor navigation engine and its test app |
| [`mb-floor-plans`](https://github.com/floragel/HackDecouverte2/tree/mb-floor-plans) | MB floor plans: photos to SVG, alignment, graph editor, exports |
| [`lync-algorythme`](https://github.com/floragel/HackDecouverte2/tree/lync-algorythme) | Working branch of the merged app (same as `main`) |

---

## Known limits

- **One building**: only MB is mapped so far. Adding one takes about 3 hours with the same tools, plus the photos.
- **Drift**: step counting gets less accurate with distance. Setting your position again from a known room resets it.
- **Floor from altitude** needs a barometer and a street sample taken near MB; floor heights (5 m, then 4 m) are estimates still to be checked on site.
- **Outdoor routes** depend on the public OSRM server (straight line when offline).
- **Plans come from photos**: furniture stays visible as a light background; room numbers are redrawn cleanly from the graph.
- **Android only** for now. iPhones can read NFC but not act as an NFC card, so on iOS the card will show the Lync code to scan.
- Indoor navigation needs more real walking tests in the building.

---

## Credits

Jetpack Compose, supabase-kt, Ktor, Coil, kotlinx.serialization and coroutines, MapLibre with OpenFreeMap tiles, map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, Google Play Services (location), CameraX, Bricolage Grotesque and Figtree (SIL Open Font License).
Demo images: Unsplash, pravatar.cc. Logo and onboarding photo generated with Higgsfield (GPT Image).
Floor plans: Concordia evacuation plans, photographed on site and redrawn.
