# Lync

University companion app — **Network** (Instagram × LinkedIn × Snap Map) + **Maps** (exact rooms).
Starting with Concordia. Bilingual EN (default) / FR. Android first (Kotlin + Jetpack Compose), then iOS (Swift + SwiftUI).

## Run it (Android Studio)

1. Open this folder in Android Studio and let Gradle sync.
2. Press Run. **Without Supabase keys the app runs on built-in Concordia demo data** (badge "Demo data").
3. Rotate the phone, from any screen, to see the student card.

### Connect Supabase

1. Create a project on supabase.com → SQL Editor → paste and run [`supabase/schema.sql`](supabase/schema.sql).
2. Authentication → Providers → Email: for the hackathon, turn off "Confirm email".
3. Add to `local.properties` (never committed):
   ```
   supabase.url=https://YOUR-PROJECT.supabase.co
   supabase.anonKey=YOUR-ANON-KEY
   ```
4. Rebuild. The app switches to the real backend automatically.

## Features (network side)

- **Feed**: photos, text posts, **polls**, **plans** ("who's coming to the gym at 5?" with spots and an "I'm in" button), likes, comments, share.
- **Stories** (24h): text or photo, full-screen viewer with progress bars.
- **Network**: requests, classmates (shared courses), clubs, search, profiles with Connect + Follow.
- **Lync card**: turn the phone sideways anywhere. Holographic foil driven by the gyroscope. Back = **Lync code**.
- **Lync code**: our own circular code (not a QR). Scan it with the camera, or **tap phones back to back (NFC)**.
- **Map**: friends and classmates live, ghost mode.
- **Settings**: edit profile (program picker with all 165 Concordia programs), location privacy, theme, language, notifications.

### Lync code & NFC
- `code/LyncCode.kt` — encoder/decoder (pure Kotlin, unit-tested: `./gradlew :app:testDebugUnitTest`).
- `code/LyncCodeView.kt` — renderer. `code/NfcCard.kt` — NFC: the phone showing its card answers taps (HCE); the scanner reads phones and Lync NFC stickers (`https://lync.app/c/<code>`).
- iPhone can read but not emulate NFC: on iOS the card shows the Lync code and the scanner reads Android cards / stickers.

### Demo mode
With Supabase keys the app uses the real backend. Force the offline demo with `lync.demo=true` in `local.properties` (or `./gradlew assembleDebug -Plync.demo=true`).
If you already ran the first `schema.sql`, run `supabase/migrations/002_lync_v2.sql` for polls, plans, comments, stories and the Lync code.

> ⚠️ Don't keep the project in an iCloud-synced folder (Desktop / Documents): iCloud creates `file 2.kt` copies that break the build. If it happens: delete `app/build` and rebuild.

## Architecture (kept simple so the Swift port is 1:1)

```
app/src/main/java/com/lync/app/
├── AppContainer.kt          manual DI → picks DemoRepository or SupabaseRepository
├── data/
│   ├── model/Models.kt      University, Profile, FeedPost, Person, MapPin…   (→ Swift Codable structs)
│   ├── CampusRepository.kt  the one interface the UI talks to                (→ Swift protocol)
│   ├── demo/                in-memory Concordia data
│   ├── supabase/            real backend (supabase-kt)                       (→ supabase-swift)
│   └── location/            foreground GPS                                   (→ CoreLocation)
└── ui/
    ├── theme/               neutral palette + university accent color        (→ SwiftUI Environment)
    ├── components/          Avatar, Pill, PrimaryButton, pressable()
    ├── onboarding/          welcome → pick university (app re-tints) → about → location
    ├── feed/                Instagram side: status row, posts, double-tap like, new post
    ├── network/             LinkedIn side: requests, classmates, clubs, search, QR scan
    ├── map/                 Snap side: friends & classmates on a MapLibre map, ghost mode
    ├── profile/             stats, status, courses, language switch
    └── card/                landscape student card: flip, holographic tilt, QR
```

Each screen = `XxxViewModel` (state in a `StateFlow`) + `XxxScreen` composable → maps to `ObservableObject` + SwiftUI `View`.

## Design

Full token table and Android ⇄ iOS component map: [design/README.md](design/README.md).

## Design rules

- Neutral black/white/grey UI. **The only color is the university's** (`Campus.colors.accent`).
- Bricolage Grotesque (display) + Figtree (text), bundled on both platforms.
- Every tappable thing uses `Modifier.pressable()` (spring scale) → consistent, fluid feel.
- Optimistic updates (likes, connect, follow): UI reacts instantly, server catches up.
- All user-facing text in `res/values/strings.xml` (EN) and `res/values-fr/strings.xml` (FR). No hard-coded strings.

## Privacy (users are 16–24, some are minors)

- Location is **only** readable by accepted connections (and classmates if the user opts in). Enforced by Postgres RLS in `schema.sql`, not just by the app.
- Ghost mode in one tap on the map. Location is only sent while the app is open.
- Positions older than 2h are ignored.
- Sign-up restricted to the university email domain.

## Business model

Free for students. Clubs / businesses pay to promote (`organizations.plan`, `posts.is_sponsored`).
`is_sponsored` can never be set by a client (RLS) — only server-side after payment.

## Third-party

Jetpack Compose, supabase-kt, Ktor, Coil, MapLibre + OpenFreeMap tiles (© OpenStreetMap contributors),
Google Play Services (location, code scanner), ZXing, Bricolage Grotesque & Figtree (OFL).
Demo images: Unsplash, pravatar.cc. Logo and onboarding photo generated with Higgsfield (GPT Image).
