# Phone control

Let DSH read the screen, tap and type to get things done on your phone. Pause or stop it anytime. Phone control works only with this phone's engine and isn't loaded in Safe mode.

## Turn it on

<!-- nav: commonSettings,hubSettingsPhoneControl -->
`Settings › Phone control` · [Open](dshgo://open/phone)

1. Turn on the switch at the top. The first time, you'll see what's read and where it goes; tap **Agree**. Turning it on or off restarts a running engine, interrupting any reply in progress.
2. Under **Setup**, tap **Turn on** next to **Accessibility service**, and turn on "DSH Phone Control" in Android's accessibility settings. If the switch won't turn on for a sideloaded app, tap **Accessibility switch disabled?** for brand-specific steps to allow restricted settings.
3. Tap **Turn on** next to **Notifications** and allow notifications in App info. Approvals and the stop button need them.
4. Tap **Read current screen** to see what DSH can read. This reading isn't sent to the model.

During a task, text on the screen is sent to the model you chose; with a vision model, a screenshot may be included. Bank card and ID numbers are masked first.

## Restore accessibility automatically

<!-- nav: commonSettings,hubSettingsPhoneControl,a11yRestoreTitle -->
`Settings › Phone control › Restore accessibility automatically`

Open this row under **Setup**, read the explanation and tap **Authorize and enable**. A connected Shizuku, Sui, root or self-ADB channel can grant permission (the page says which one it will use). If neither is available, follow the setup page or copy the displayed command and run it once on your computer. Restoration requires both the in-app phone-control switch and the system permission.

When authorizing through Shizuku (including Sui) or self-ADB, the app checks whether the permission was actually granted. It distinguishes an authorization request that could not be completed through the channel, an explicit system denial, and an acknowledged request that still left the permission ungranted. An explicit denial is not reported as an ADB disconnection. A temporary Shizuku or self-ADB authorization connection may close normally afterward; that alone does not mean the attempt failed. If the system denies permission, using the computer command is not guaranteed to succeed.

After granting permission, turning off the accessibility service in Android settings will trigger an attempt to turn it back on. To stop phone control, turn off the switch inside DSH Go. Restoration changes only DSH Go's service entry and preserves other accessibility services.

After a system force-stop, reopen DSH Go before it can recover. Repeated failures back off to about ten minutes between attempts. This permission does not keep the app alive indefinitely or fix the underlying cause of a service crash.

## Ask DSH to do something

Go back to a chat and say what you want done. While a task runs:

- Keep the screen on and unlocked. Turning the screen off stops the task.
- Passwords, verification codes, payments and system settings are up to you (unless you turn them on under Lift limits below). When a step needs you, **Your turn** appears; tap **Continue** when you're done. When one of these rules is the reason, the card's title says so, like **Passwords and codes are yours to enter** or **System settings are yours to change**.
- A task ends on its own when DSH makes no new move for 3 minutes.

Alarms, events, maps and sharing don't need the accessibility service.

## Floating bar

When a task starts, a floating bar appears at the top of the screen. It takes three shapes, and a tap moves between them:

- Capsule (the default): one line saying what DSH is doing, like "Tap “Send”", with **Pause** and **Stop** on the right. After **Pause**, the button reads **Continue**; tap it to let DSH carry on.
- Card: tap the line on the capsule to open it. It shows the app DSH is working in, whether approvals are off, the last few steps, and a field to steer DSH another way. Left alone for a few seconds, it goes back to the capsule.
- Ball: tap the dot at the capsule's left end, or **Shrink to a ball** in the card, to shrink it to a ball at the screen edge. Tap the ball to open it. Drag it anywhere; it snaps to the nearest edge and stays there next time. **Hold the ball for a second to stop**: a ring closes round it first, and letting go cancels.

When DSH needs your approval, the capsule says "Waiting for you below" and the approval card appears at the bottom of the screen.

Adjust it under **Floating bar** on the phone control page (shown once phone control is on):

- **Idle opacity**: how far the capsule or the ball fades after 3 seconds untouched, 30% to 100%, 70% by default. A touch brings it back; the card and anything waiting for you stay solid. With **Collapse when idle** on, the capsule shrinks to a ball instead of fading, and the ball fades.
- **Collapse when idle**: off by default. When on, the capsule shrinks to a ball after a few idle seconds, and opens by itself when you need to approve something.
- **Back to DSH Go when done**: on by default. When the reply ends and the app is in the background, it tries to bring you back to that chat. If the system blocks background windows, use the notification; see Confirmations and return below.

Whatever the settings, the bar closes when the reply ends.

## Stop

- **Stop** on the floating bar, or hold the ball for a second.
- **Stop** on the "Operating your phone" card on the phone control page.
- **Stop phone control** on the engine notification. **Stop engine** on the same notification stops the whole engine.

Stopping phone control also stops every reply in progress on this phone's engine.

## Confirmation

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneConfirmTitle -->
`Settings › Phone control › Confirmation`

- **Always ask** (default): asks before entering a new app, sending, deleting or paying.
- **This task**: asks once per task. Choose **Allow for task** on the approval card, and the rest of the task goes ahead.
- **Always skip**: acts without asking. Asks you to confirm before turning on.

When you pick Phone control during first-time setup, you choose once after agreeing to the disclosure: **Ask me at key steps** (Always ask) or **Fully automatic** (Always skip). You can change it here anytime.

An approval card left for 30 seconds counts as **Decline**. In every mode, system settings and permission pages, passwords and verification codes, and DSH Go itself stay with you unless turned on under **Lift limits**; apps set to Hands off are changed in **App rules**. Confirmation lists these, with links to both.

## Confirmations and return

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneBackgroundGuideTitle -->
`Settings › Phone control › Confirmations and return`

Once phone control is on, this row is under **Advanced** on the phone control page; tap it to read the guidance again. **Open system settings** opens DSH Go's system App info page.

When a step needs your decision, DSH Go shows a card. If the card is unavailable, it tries a notification or returns to the app. Allow phone-control notifications and pop-up alerts in system settings. Some phones also offer a permission to display pop-up windows while running in the background; allowing it can help the app return. Menu names vary by system version.

If the app doesn't return after a task finishes, tap **Task complete · View result** in the notification. If the system blocks notifications or background windows, open DSH Go yourself to see the result. Turning on **Back to DSH Go when done** doesn't guarantee that the system will allow it.

## App rules

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneAppRulesTitle -->
`Settings › Phone control › App rules`

Set each app to **Hands off**, **Every step** or **As usual**. Payment, banking and brokerage apps are **Hands off** by default, and WeChat is **Every step**. Unlisted apps work as usual, and sensitive actions still need approval. Loosening a rule explains the risk first; tap **Change anyway** to apply it. Tap **Add app** to add one by package name.

## Lift limits

<!-- nav: commonSettings,hubSettingsPhoneControl,phoneUnlockTitle -->
`Settings › Phone control › Lift limits` · [Open](dshgo://open/phone-unlocks)

All three are off by default, and each turns on separately:

- **System settings**: permissions, installers, security center, notifications.
- **Passwords and codes**: fill in passwords, verification and payment codes. The action log shows •••• and never what was typed.
- **DSH Go itself**: tap and type inside DSH Go.

Turning one on opens a page first: what AI can then do, what could go wrong, and the disclaimer below. Press and hold **I understand the risks, turn on** for about 1.5 seconds to apply it. Turning one off takes a single tap. Nothing can be turned on while a task is running. While any is on, the Phone control page shows **Limits lifted**, the floating bar puts a small lock before its line, and **Limits lifted** in its card brings you back here. Banking and payment apps aren't here; change them in **App rules**.

Disclaimer:

- Once on, AI can do the above without anyone confirming.
- AI can be misled by content in web pages, messages or files.
- Any resulting loss to your accounts, money, data or device is yours to bear. DSH Go and its developers accept no liability.
- Turn it on only when you need it, and off when you're done.
- We advise against turning it on for a device that holds payment or banking details.

## Action log and advanced

**Recent actions** logs every step, without screen contents. Tap **All actions** to see them. The log lasts until the app closes.

Under **Advanced**:

- **Non-disruptive mode**: works on a hidden screen while you use your phone. Needs Android 14 or later. Pick one under **Channel**: Shizuku if the Shizuku app is installed; Sui if Magisk or KernelSU has the Sui module; Root on a rooted phone (your root manager asks when you tap "Turn on with Root", and su never runs before that); Direct ADB otherwise. Open a channel to see its checklist, finish the setup, then tap "Turn on with …"; the page marks the suggested one but never picks it for you. Shizuku and Direct ADB need Wi-Fi with wireless debugging on, and the screen must still stay on and unlocked. Once it is on, a "Keep … channel" switch sits under "Using …", off by default: with it on, DSH Go reconnects the channel by itself after a disconnect, reopening the app or restarting your phone, so you don't pick it again; with it off, the mode must be reactivated after a restart. "Keep root channel" means DSH Go asks your root manager again by itself, and the manager still decides; Shizuku and Sui only use the permission they already gave and wait for their service, and Direct ADB only uses its existing pairing. An app you force-stopped in system settings doesn't come back on its own after a restart; open DSH Go once.
- **Background running**: prevents Android from turning off accessibility; opens the phone settings guide.
- **Confirmations and return**: see confirmations and task results in time; see Confirmations and return above.

## Saved phone tasks

The saved-tasks page under Phone control runs a known flow locally. Waiting, branches and bounded loops do not need an AI request per step. Page interpretation, visual targeting and repairs still need a model. Existing single-action phone tools remain available.

1. In chat, specify the goal, allowed apps, parameters and stopping point. Ask AI to inspect the actual page before generating a schema-1 task. Importing JSON creates a proposal and never starts it.
2. Review the complete flow, apps and limits. You can confirm a single run first; this does not save the task or grant lasting authority, and the proposal remains available.
3. Save a flow once it is worth reusing. Specify allowed parameter values, or explicitly allow all values accepted by the parameter definition. Choose confirmation per run or long-term authorization. Changed flows need a newly reviewed revision.
4. Start saved tasks with parameters from the task page or ask AI to call their task ID in chat. Values outside approved scopes require your approval for that run; they do not expand the saved scope.

Global automatic mode is enabled by you and persists after restart until you turn it off. It skips run confirmation within approved scopes. Declared apps, parameter scopes, app rules, system permissions and forbidden actions still apply. Turning it off does not revoke individual long-term task authorizations.

Only one local automation task runs at a time. After a pause, an out-of-scope app switch, a lock or an unavailable observation, check the current page and choose resume or stop in the task page. Resume observes the page again. A run interrupted by process exit is not replayed. If a send or submission may have happened but cannot be verified, the reason is `result-uncertain`. This release cannot directly resume that run: inspect the actual result, stop it and create the remaining flow to avoid duplicate writes.

At most two repair rounds are allowed per whole run, within the original apps and capabilities. Repairs never silently replace a saved definition; review the new revision first. Sanitized run summaries retain up to 100 records for 7 days, without message text, screen text or AI prompts. Task definitions and parameter values you explicitly save are stored locally.

## Local screenshot evidence

Local screenshot evidence is off by default on the task page and requires your confirmation to enable. It can retain images from explicit `snapshot` nodes, on-demand AI vision and failure capture. Add `snapshot` before `finish` when you need a result image. Completion does not automatically produce a result frame every time, and this is not screen recording.

Images stay on this device and are not sent automatically. Retention is limited to 10 images per run, 50 MB in total and 7 days. Tap a thumbnail on the task page to enlarge it; missing files are reported. Images may contain private content, so review them before sharing.

Turning the switch off stops future retention but keeps existing images. Confirming Clear screenshot evidence deletes all retained images while keeping tasks, text results and the switch setting. Model tools do not receive the local image file list.

## Task JSON format

This is a read-only template. `example.phone.lab` is a placeholder package, not a browser or a universal app identifier. Observe the real app to confirm its package, visible text and starting state before replacing it. Parser validation does not prove that a page works on a device.

```json
{
  "schema": 1,
  "id": "local-check",
  "name": "Read-only local page check",
  "apps": ["example.phone.lab"],
  "steps": [
    {"op": "snapshot"},
    {"op": "wait", "text": "DSH 手机操控验收页", "timeoutMs": 10000},
    {"op": "assert", "text": "DSH 手机操控验收页"},
    {"op": "finish", "result": {"status": "verified"}}
  ],
  "limits": {"timeoutMs": 60000, "maxActions": 10, "maxAiCalls": 0}
}
```

Required fields are `schema: 1`, `id`, `name`, `apps` and nonempty `steps`. Optional fields include `parameters`, `limits`, `capabilities`, `description` and `forbiddenTargets`; saving assigns the next revision. Limits are 10 apps, 200 definition nodes and 4 nesting levels. Arbitrary JavaScript, shell and Auto.js scripts are not supported.

| Operation | Required fields and meaning |
| --- | --- |
| `open` | `app`: a declared package |
| `tap`, `type` | Visible `target` text or a `selector`; `type` also needs `text` |
| `observe`, `snapshot` | Read the current page; no other required fields |
| `read_text` | `target` or `selector`, and `name` for a run-local variable |
| `scroll` | `direction`: up/down/left/right; optional `until` text and `max`, at most 20 |
| `key` | Only `key: "back"` in this release |
| `tap_xy`, `input` | A recent `observation`, `x`, `y`; `input` also needs `text` |
| `assert` | Current visible `text` condition |
| `wait` | Either a fixed `ms`, or `text` with `timeoutMs` |
| `if` | `condition`, `then`, optional `else` |
| `repeat` | `count` from 1 to 100 and body `steps`; optional `until` checked before each iteration |
| `break` | Leave the current loop |
| `set` | Run-local `name` and `value`; cannot overwrite start parameters |
| `ai` | Decision `prompt`, optional `name` for its returned value |
| `plugin` | The actual installed `tool` and declared `arguments`; wait for its real receipt |
| `finish` | Optional `result`; end the task |

Conditions use `{"text":"Done","match":"contains"}` or `{"variable":"done","equals":true}`; `not: true` inverts the result. Text matching supports `contains`, `exact` (one complete line) and `normalized` (contains after collapsing whitespace), not regular expressions. Any step can use `requireText` before acting and `expect` afterwards. A failed postcondition after a write makes the result uncertain.

`parameters` declares named values with `type` string/integer/number/boolean and optional `default`, `required`, `enum`, `min`, `max`, `maxLength`. For example: `{"message":{"type":"string","required":true,"maxLength":100}}`. `${message}` refers to a parameter or an earlier run-local value; a whole-value reference preserves its type. Plugin arguments may reference declared start parameters only, not run-local variables that could change a recipient or target.

Omitted `capabilities` are inferred from the definition. Actions subsequently supplied by AI must also be declared; for example, use `"capabilities":["observe","tap","type"]` if a checkpoint may add taps and text input. Capabilities do not remove app or parameter restrictions. `forbiddenTargets` lists targets that cannot be tapped. Tasks with forbidden targets reject coordinates because the executor cannot prove that a coordinate avoids a forbidden button.

`limits.timeoutMs` counts active time. Ordinary tasks default to 10 minutes with a 30-minute maximum; `continuous: true` defaults to 60 minutes with a 120-minute maximum. Pauses are excluded, waits included. `maxActions` defaults to and cannot exceed 500, counting native writes and plugin nodes. `maxAiCalls` defaults to and cannot exceed 20 for ordinary tasks or 50 for continuous ones, and may be 0. It counts AI checkpoints, not underlying model requests or token cost: one checkpoint can need several model turns. Fixed waits are limited to 120 seconds and text waits to 30 seconds.

## Chat tool and visual targeting

`mcp__phone__task` accepts actions `list/get/validate/propose/start/status/pause/resume/stop/ai_result/repair`. Resuming requires the user in the task page; the model cannot authorize it. Saving, authority changes and global mode also require your confirmation in the app.

AI uses `validate` for definitions, `propose` for review candidates and `start` for saved tasks. An AI node changes the run to `awaiting_ai`, with `runId`, `checkpoint` and `kind` in status. A `get` with the current `runId` reads a detached definition and bound parameters. AI must inspect the current state and return `ai_result` or `repair` for that same checkpoint. Pausing or stopping invalidates old checkpoints. Repair `steps` describe only the remaining workflow, never an implicit replay.

This release has no local OCR guarantee. When an ordinary capturable page has no usable accessibility text, an image-capable model must inspect the screenshot and target using a valid observation ID. A clear screenshot does not authorize every coordinate: app rules, forbidden targets, system protection and stale-observation checks may still refuse. Models without image input cannot perform this visual fallback.

Shopping and course flows depend on real pages and account state. Declare the stopping point, candidate count, course chapters and question scope. Template validation, timed waits or a model's verbal report do not prove an order, actual playback, credited course progress or a correct answer. Scheduling, notification triggers, boot execution and volume-key triggers are outside this release.
