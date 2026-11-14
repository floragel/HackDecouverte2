# Lync design system — Android ⇄ iOS parity

Everything in this folder is shared by both apps. Android source of truth: `app/src/main/java/com/lync/app/ui/theme/Tokens.kt` and `Type.kt`.
The SwiftUI app must declare the **same values** so both look identical.

## Assets

| File | Android | iOS |
|---|---|---|
| `lync-mark.svg` | `res/drawable/ic_lync_mark.xml` (tintable) | Assets.xcassets → Image Set, "Preserve Vector Data", Render As: Template |
| `app-icon.svg` | adaptive icon (`mipmap-anydpi-v26`) | export 1024×1024 PNG → AppIcon |
| `onboarding_hero.jpg` | `res/drawable-nodpi` | Image Set |
| `fonts/bricolage.ttf`, `fonts/figtree.ttf` | `res/font` | add to target + `UIAppFonts` in Info.plist |

## Colors

| Token | Light | Dark | Use |
|---|---|---|---|
| bg | `#FFFFFF` Paper | `#10121A` | screens |
| surface | `#F3F4F7` Mist | `#1A1D29` | inputs, chips, tiles |
| surfaceAlt | `#E8EAF0` Fog | `#252938` | segmented track |
| border | `#E3E5EB` Line | `#2A2E3D` | hairlines (0.5pt) |
| text | `#1A1D29` Night | `#FFFFFF` | primary text |
| textMuted | `#6B7081` Slate | `#9398A8` | secondary text |
| accent / accent2 | university `primary_color` / `secondary_color` from DB | same | the ONLY saturated color |

## Type

| Style | Font | Size / line | Weight | Tracking |
|---|---|---|---|---|
| displayLarge | Bricolage Grotesque | 44 / 44 | 800 | -1.4 |
| displaySmall (screen titles) | Bricolage | 34 / 36 | 700 | -0.9 |
| headlineSmall (numbers, wordmark) | Bricolage | 28 / 32 | 700 | -0.6 |
| titleLarge (section titles) | Bricolage | 21 / 26 | 600 | -0.3 |
| titleMedium | Figtree | 16 / 21 | 600 | 0 |
| titleSmall | Figtree | 14 / 19 | 600 | 0 |
| bodyLarge / bodyMedium / bodySmall | Figtree | 16/23 · 14/20 · 12/16 | 400 | 0 |
| labelLarge | Figtree | 14 / 18 | 600 | 0 |

## Shape & spacing

- Radius: card 28, media 22, control 16, chips/avatars fully round.
- Screen gutter 20. 4-pt grid (4, 8, 12, 16, 24, 32).
- Press feedback: spring scale to 0.96 (Android `Modifier.pressable`, iOS `.buttonStyle(PressableStyle())` with `.spring(response: 0.3, dampingFraction: 0.6)`).

## Component map

| Android (Compose) | iOS (SwiftUI) |
|---|---|
| `ScreenHeader` | `.navigationTitle` large, or custom `Text(...).font(.display34)` |
| `Pill` (Filled/Tonal/Outline) | `PillButton(style:)` |
| `CircleIconButton` | 40pt circle `Button` with SF Symbol |
| `Avatar` | `AsyncImage` in `Circle()` with initials fallback |
| `LyncCardFront` / `LyncCardBack` | `LyncCardFront` / `LyncCardBack` — everything scales from width `k = width / 340` |
| `StudentCardScreen` (landscape) | observe `UIDevice.orientationDidChangeNotification`, overlay full-screen |
| `rememberTilt()` | `CMMotionManager.deviceMotion.gravity` |
| `MapLibre` + Compose pins | MapLibre iOS SDK (same OpenFreeMap style URLs) + SwiftUI overlay |
| ViewModel + `StateFlow` | `@Observable` class |
| `CampusRepository` | `protocol CampusRepository` |

## Rules

1. Neutral UI; the university color is the only color.
2. Sentence case everywhere. No all-caps labels.
3. Left-aligned large titles; no top app bars.
4. Rows over boxes: cards only for things that are cards (Lync card, classmate).
5. Motion only answers a tap (press, like burst, flip) — no decorative entrance animations.

## Motion

Source of truth: `app/src/main/java/com/lync/app/ui/motion/`. One signature moment, everything else answers a gesture.
If the system "reduce motion" setting is on, everything snaps (Android: animator scale 0, iOS: `UIAccessibility.isReduceMotionEnabled`).

| Token | Android (Compose) | iOS (SwiftUI) | Used for |
|---|---|---|---|
| press | spring(damping 0.6, stiffness 600) | `.spring(response: 0.25, dampingFraction: 0.6)` | every tappable thing (scale 0.96) |
| standard | spring(0.8, 400) | `.spring(response: 0.35, dampingFraction: 0.8)` | UI movement |
| bouncy | spring(MediumBouncy, 380) | `.spring(response: 0.4, dampingFraction: 0.5)` | likes, avatars joining, scanner lock |
| short / medium | 180 / 280 ms | 0.18 / 0.28 s | fades / screen transitions |
| stagger | 70 ms | 0.07 s | poll bars, lists |

| Moment | What happens | Duration |
|---|---|---|
| **Launch** | system splash shows the mark on Night, then the two links pull apart and snap back ("click"), "Lync" fades in | ~550 ms |
| **Connect (signature)** | both avatars fly in, the two links interlock between them, ring bursts in the university color, double haptic, "You and X are connected" + shared courses | ~1.5 s |
| Scanner lock | circle tightens (bouncy) and turns green `#2FD07A` when a code is read | 300 ms |
| Shared elements | event cover → event page header, group emoji tile → group page | 280 ms (iOS `matchedGeometryEffect` / `.navigationTransition(.zoom)`) |
| Likes | digits roll up/down (iOS `.contentTransition(.numericText())`), heart springs, double-tap heart burst | 220 ms |
| Poll | bars fill one after another | 520 ms + 70 ms stagger |
| Plan "I'm in" | your avatar pops into the stack (bouncy) | 300 ms |
| Card | flip on tap, gyroscope tilt + holographic foil | continuous |

Haptics: `tick` (like, tab switch, follow, card flip) = `UISelectionFeedbackGenerator`; `confirm` (vote, I'm in, RSVP, join, connect) = `UIImpactFeedbackGenerator(.light)`; `success` (two people connected) = `UINotificationFeedbackGenerator(.success)`.

Demo tip: in demo mode the scanner shows **"Demo: simulate a scan"** to trigger the signature moment without a second phone.
