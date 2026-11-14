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
