# Coordinate gestures: the `gesture` tool

This document describes the 14th phone tool this fork adds to upstream `v1.0.13`: `gesture`, which
drives the finger from the pixel coordinates of a numbered screenshot — a long press of any
duration, a swipe along any path, a tap, a double tap, a two-finger pinch, and up to ten of those
as one sequence in a single call.

## Why it exists

Upstream's only coordinate capability is `tap_xy` (tap a point). Gestures are hard-wired to the
element path:

```kotlin
// app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/Actuator.kt
fun tap(x: Int, y: Int, long: Boolean = false): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val duration = if (long) ViewConfiguration.getLongPressTimeout().toLong() + 300 else 60L
    ...
}
```

So a "long press" is always the system long-press timeout plus 300 ms — nothing can hold for
three seconds or 1.5 seconds — `swipe` is a straight two-point line reachable only from list
`scroll`, and there is no double tap, no multi-finger input and no press-then-drag.

Plenty of real actions need exactly those: opening a context menu, holding to drag and reorder,
swiping along a curve to unlock, pinching a photo, double-tapping to like. `gesture` fills that gap.

## The tool

```jsonc
{
  "observation": "s7",          // the screen id of the latest `screen` screenshot
  "steps": [ /* 1–10 steps */ ],
  "gap": 120                     // optional: pause between steps in ms (default 120)
}
```

Each step is an object whose `kind` picks the action. Every `x` / `y` is a pixel on **that
screenshot** (its W×H), not a physical screen coordinate.

| kind | What it does | Required | Optional |
| --- | --- | --- | --- |
| `tap` | Tap | `x` `y` | `duration` ms (default 60) |
| `long` | Press for any duration | `x` `y` | `duration` ms (default 800) |
| `double` | Double tap | `x` `y` | `gap` between taps (default 120, 40–300), `duration` (≤300) |
| `swipe` | Straight swipe | `x` `y` `x2` `y2` | `duration` ms (default 300) |
| `path` | Swipe along a path | `points`: `[[x,y],[x,y],…]` | `duration` ms (default 300) |
| `pinch` | Two-finger pinch | `x` `y` `spread` | `duration` ms (default 300) |

`spread` is the **sum** of both fingers' travel: each finger moves `spread÷2`, positive zooms in,
negative zooms out. The fingers start `max(220, |spread|÷2+40)` pixels above and below the centre,
so they never cross.

### Examples

Hold for 3 s → swipe up for 500 ms → double tap:

```json
{
  "observation": "s7",
  "steps": [
    {"kind": "long",   "x": 540, "y": 1200, "duration": 3000},
    {"kind": "swipe",  "x": 540, "y": 1500, "x2": 540, "y": 700, "duration": 500},
    {"kind": "double", "x": 540, "y": 1200}
  ]
}
```

Swipe along an arc that avoids a button in the middle:

```json
{"kind": "path", "points": [[200, 1800], [500, 1500], [800, 1200]], "duration": 600}
```

Zoom a photo in:

```json
{"kind": "pinch", "x": 540, "y": 900, "spread": 400}
```

## Limits and refusals

`GestureDescription` has its own limits and throws when they are exceeded, so the planner rejects
them before anything is dispatched:

| Limit | Value | Notes |
| --- | --- | --- |
| Steps | ≤ 10 | per call |
| Strokes | ≤ 10 | platform limit |
| Total duration | ≤ 60 000 ms | platform limit, from the first step's start to the last step's end |
| Path points | ≤ 32 | one `path`; consecutive duplicates are dropped |
| Double-tap gap | 40–300 ms | Android's real `GestureDetector` window |
| Double-tap press | ≤ 300 ms | longer and the first press becomes a long press, so it is no longer a double tap |
| `pinch` `spread` | ≥ 40 px | below that the fingers barely move |
| All coordinates | inside the screenshot | its long side is scaled to ≤1300 px |

Over-limit requests are refused with an explanation (for example "a double tap needs 40–300ms") so
the model splits the call itself instead of silently truncating.

## Coordinate space and freshness

- A numbered screenshot is produced by `ScreenShooter`, which scales its long side to at most
  1300 px; `ImageGeometry.toScreen(x, y)` maps a screenshot pixel back to the screen and returns
  `null` outside the image.
- A coordinate gesture requires that screenshot to still match the current UI. Before injecting,
  `MainScreenDriver.gesture()` captures again and compares with
  `observation(cap).reusableCoordinates(snap.observation)`; a mismatch refuses and asks for a new
  `screen`.
- Snapshot ids expire: an old id yields "屏幕编号已过期，坐标对不上了".

## Safety

The new `WriteKind.GESTURE` goes through the same gate as `tap_xy`, with no rule relaxed:

- Every coordinate gesture is confirmed (`SafetyGate.checkWrite` returns `Confirm`
  unconditionally for `GESTURE`), exactly like `tap_xy`; only your own "always allow" mode waives it.
- The confirmation card reads `Do a gesture in "App": hold (540,1200) 3000ms → swipe …` — the
  summary is language-neutral ASCII, so an English UI does not get Chinese mixed in.
- DSH's own UI, system permission pages and `hidden` / `unreadable` screens are refused, checked in
  exactly the same order as `tap_xy`.
- Nothing is resolved from the tree (`target` is always `null`), so the package comes from
  `foregroundPackage()` and the deny list, system-page and own-app rules still apply.
- The background screen (Shizuku / self-ADB) does **not** support coordinate gestures; it answers
  "后台屏按元素编号操作，不用坐标".

## Code map

| File | Role |
| --- | --- |
| `app/android/app/src/main/kotlin/.../phone/GesturePlan.kt` | Pure-Kotlin planner: validates `steps` and lays them out as `GestureStroke`s |
| `.../phone/PhoneContracts.kt` | `PhoneAction.Gesture(screenId, strokes)` and `GestureStroke(points, startMs, durationMs)` |
| `.../phone/PhoneToolCatalog.kt` | Tool schema and description (enters every request; a change invalidates the KV cache) |
| `.../phone/PhoneCore.kt` | Argument parsing, routing, gate, budget (120 s for `gesture`) |
| `.../phone/SafetyGate.kt` | `WriteKind.GESTURE` and its confirmation sentence |
| `.../phone/a11y/Actuator.kt` | Packs every stroke into one `GestureDescription` and waits on the existing dispatch primitive |
| `.../phone/a11y/MainScreenDriver.kt` | Coordinate guards (freshness, per-point bounds, own UI / system page / unreadable) and result observation |
| `.../phone/background/BackgroundDisplay.kt` | Refuses gestures on the background screen |
| `app/android/app/src/test/kotlin/.../phone/GesturePlanTest.kt` | 10 JVM unit tests for the planner |

## Known limitations (deliberate)

- **A `pinch` centre cannot sit close to an edge**: the planner does not know the screenshot size,
  so "will a finger leave the image" is only known before injection — after the confirm card has
  already been shown. The rule is documented in the tool description to avoid a wasted confirmation.
- **A double tap's `duration` is capped at 300 ms**: past the system long-press timeout the first
  press becomes a long press and the double is lost, so it is refused rather than silently adjusted.
- **No automatic splitting**: more than 10 steps or 60 s is refused with an explanation; the caller
  splits the call. Auto-splitting cannot handle "break inside a double tap" and would be worse.
- **No coordinate gestures on the background screen**: it injects through a different protocol
  (element, text, direction only); supporting it would need new protocol keys and gate work, which
  is out of scope here.

## Build and verify

```bash
# planner unit tests (JVM unit tests exist only for the Debug variants)
cd app/android && ./gradlew testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.phone.*"

# build the arm64 release APK
bash tooling/build-apk.sh --build-name 1.0.14 -f arm64
```

This fork's release APK is **debug-signed** (the repository has no `app/android/key.properties`).
Uninstall the official app first; that clears app data, including credentials and the runtime rootfs.

On a real phone, self-test in this order: `tap` → `long` 3 s → `swipe` → `path` → `double` → `pinch`.
