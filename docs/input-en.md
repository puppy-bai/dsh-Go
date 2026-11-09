# Coordinate input: the `input` tool, plus `tap.text` / `screen.until` / `scroll.until`

This document covers four things: why `input` exists, its contract and failure chain, the safety and freshness rules for
coordinate input, and where the code lives.

## Why it exists

Upstream (v1.0.13) has exactly one way to write text: `type`, and its argument is an element id. Filling one field looked
like this:

1. `screen` (`detail:true`) — hundreds of lines of elements; find the field's id by eye;
2. `tap` (`id`) — tap it to put focus in the field;
3. `type` (`id` + `text`) — write.

Three calls, three round trips, and the field the tap opened may already have a new id before step 3 (a keyboard sliding
up changes the window and the scroll position, and `screen` renumbers elements every time). Worse, on some pages a plain
element click only reaches the page: the field needs a real 600 ms press before it takes focus, which upstream's `tap`
cannot do.

`input` packs tap, focus, write and read-back into one call and tries them in sequence on the device, never returning
mid-way.

## The tool

| Parameter | Required | Meaning |
| --- | --- | --- |
| `observation` | yes | Screen id of the latest `screen`, which must have carried `image`; coordinates are that numbered screenshot's pixels |
| `x` `y` | yes | Pixel coordinates of the field on the numbered screenshot (non-negative, 0-based) |
| `text` | yes | The text to write, at most 2000 characters; blank is refused |
| `submit` | no | Send one IME enter after writing (`ACTION_IME_ENTER`, only on API 30+ nodes that accept it) |

The result is the **new screen** after the action (as with `tap` / `tap_xy`), not the word "ok" — the model sees what
actually happened.

### The on-device failure chain

One `input` tries these four steps in order and stops at the first success:

1. **Tap**: an ordinary 60 ms click on the point, then wait for the UI to settle (800 ms first screen, 250 ms quiet,
   2.5 s cap).
2. **Find focus and write**: walk every window root, `findFocus(FOCUS_INPUT)`; if it `isEditable`, write with
   `ACTION_SET_TEXT` (`ActionActuator.setText` focuses first and retries with `ACTION_CLICK` and a fresh node on
   failure). Then read back every 100 ms for 1.5 s and compare with `sameText` (ignoring whitespace, `-` and zero-width
   characters). A mismatch counts as failure and falls through.
3. **Press, then look again**: if step 2 failed, hold the same point for 600 ms (`Actuator.hold`, gesture injection),
   pause 300 ms and try the focus-and-write again. Many WebView pages only focus on a real press.
4. **Clipboard**: put the text on the clipboard (`Actuator.copy`) and send `ACTION_PASTE` to the focused node; if the
   node's `actionList` has no paste, press again for 600 ms, pause 400 ms, and click 「粘贴」/「Paste」 by text in the
   menu that appears (`Actuator.clickText`, any window, clickable nodes only).

Finishing: send one enter if `submit` is set; capture the screen once more and send a back key **only when the keyboard
is really up** (it would cover the next screenshot's coordinates). Then the usual settle, new screen and diff.

### Refusals (each one is a sentence the model can act on)

| Situation | First sentence |
| --- | --- |
| Stale screen id | `屏幕编号 s7 已过期，坐标对不上了。请重新 screen 加 image 再输入。` |
| No screenshot | `屏幕 s7 没有截图。按坐标输入要先用 screen 加 image 拿一张标号截图。` |
| Point outside the image | `坐标 (x, y) 不在这张截图里（W×H）。` |
| Focus is a password field | `焦点落在密码框上，DSH 不替用户填密码。请用 handoff 让用户自己来。` |
| All four steps failed | `点了 (x, y)，但没有出现能写入的输入框，也没有找到可以粘贴的地方。看看新屏幕，可能要先点一下输入框。` |

### Examples

```json
{"observation": "s7", "x": 540, "y": 760, "text": "13800138000"}
```

With submit:

```json
{"observation": "s7", "x": 540, "y": 760, "text": "how is the weather today", "submit": true}
```

## `tap` with `text`: tap by the words on screen

`tap` now takes `screen` plus either `id` or `text`. Matching runs five tiers, strongest first:

1. the element's text equals the query;
2. the row text equals the query (a list row's text is often several child nodes joined);
3. the element's text contains it (at least 2 characters);
4. the row text contains it (at least 2 characters);
5. the `viewId` contains it (at least 3 characters; for developers and tests).

When a tier has several candidates, only actionable ones stay (`clickable || longClickable || editable`) — the passive
`Text` layer does not compete with the button it sits in. If more than one remains, the call is **refused with the
candidates**:

```
「删除」匹配到 2 个元素，无法确定点哪个。用 id 指定：#7「删除」、#8「删除」。
```

With no hit, the current actionable elements are listed so the model can pick different words or an id:

```
屏幕上没有「淘宝」。现在能点的有：#3「微信」、#4「支付宝」、#5「设置」。
```

Once found, the **original `tap`** runs: same fingerprint re-check, same safety gate, same confirmation card, same stale
detection.

## `screen.until` and `detail` bounds

- `screen`'s `until`: after reading the screen, if the text is not there, read again every 250 ms for up to 5 s. Use it
  right after a transition instead of making the model poll.
- `screen`'s `detail`: elements used to carry a centre point (`@540,1200`); now they carry bounds,
  `@120,1160-960,1240` (`left,top-right,bottom`, screen pixels), so deciding which element a coordinate lands on no
  longer needs arithmetic.
- Focus state is already rendered (`已聚焦` in an element's states), which is why there is no `focus` parameter.

## `scroll.until` and `max`

```json
{"screen": "s7", "direction": "down", "until": "第 3 页", "max": 8}
```

Every step is an ordinary `scroll` (same confirmation, same action log); after each it checks the screen text and stops
on `until`, returning that screen. If `max` (1–20, default 10) scrolls pass without a hit, it fails and says how many it
did.

## Safety

- `input` is `WriteKind.INPUT`, exactly like `tap_xy`: **every call asks for confirmation**, with no remember-me and no
  batch approval.
- The confirmation card shows the app, the coordinates and the character count but **never the text** — the focused
  field may be a password box, and that cannot be known before writing. The action log stores the length only
  (`按坐标输入 11 字 (540,760)`).
- DSH's own UI, system permission pages, `hidden`/`unreadable` screens and "cannot confirm the screen still matches the
  screenshot" are all refused, in the same order as `tap_xy`'s guards.
- The background screen (Shizuku / self-ADB) addresses elements only: `input` is refused there, and `tap` with `text`
  hits `PhoneDriver.find`'s default implementation, which answers "this screen cannot find elements by text".

## Code map

| Location | Role |
| --- | --- |
| `phone/PhoneContracts.kt` | `PhoneAction.Input`; `PhoneDriver.find(screenId, text)` with a default |
| `phone/TextSelector.kt` | five-tier matching, actionable-first, refusal with candidates (pure Kotlin, JVM-testable) |
| `phone/a11y/MainScreenDriver.kt` | `input()`'s four-step chain; `find()` maps snapshot elements to candidates |
| `phone/a11y/Actuator.kt` | `focusedEditor()`, `paste()`, `copy()`, `hold()`, `clickText()` |
| `phone/PhoneCore.kt` | `input` argument checks; `tapByText()`; `scrollUntil()`; `screen()`'s `until` |
| `phone/PhoneToolCatalog.kt` | the 15th tool, the three new parameters, the `screen.detail` description |
| `phone/SafetyGate.kt` | `WriteKind.INPUT` and its confirmation wording |
| `a11y/ScreenFormat.kt` | `detail` prints element bounds |
| `res/values{,-zh}/i18n_strings.xml` | `phone_status_input` / `phone_confirm_input` / `phone_log_input_xy` |

## Known limitations (deliberate)

- **The point must be inside the screenshot**, as with `tap_xy`/`gesture`; nothing is guessed. `input` refuses when
  `screen` carried no `image`.
- **A password field is only known after focus**: hence the card hides the text. Focus landing on one fails the whole
  call; there is no fallback to `type`'s password guard because there is no element id here.
- **`ACTION_SET_TEXT` does not work in some WebViews**: the chain falls back to press + clipboard, which is the usual
  behaviour there; if both fail, the returned sentence tells the model to have the user tap the field.
- **The menu's 「粘贴」 is matched by text**: Chinese and English are tried; other input-method menus fail that path
  (still usable via `ACTION_PASTE` when the node supports it).
- **The keyboard is folded with the back key**, only when it is really up; a page that intercepts back while the
  keyboard is up (rare) would go back a screen — the same behaviour upstream's `type` has.
- **`tap.text` does not fuzzy-match**: no pinyin, no synonyms, no OCR. If the words do not match it refuses and lists
  the candidates.

## Build and verify

```bash
# JVM unit tests for the matcher and the tool catalog (the JVM test task only exists for Debug variants)
cd app/android && ./gradlew testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.phone.*"

# Dart side (a new phoneToolInput key)
cd app && flutter analyze

# arm64 release APK
tooling/build-apk.sh --build-name 1.0.15 -f arm64
```

Self-test order on a phone: `screen` with `image` → `input` into a normal field → `input` with `submit` → `tap` with
`text` on 「登录」 → `scroll` with `until` into a long list. Watch three cases in particular: a WebView field (likely to
take the press or clipboard path), a password field (must be refused), and whether coordinates still line up on the
screenshot taken after the keyboard came up.
