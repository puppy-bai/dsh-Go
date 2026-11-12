# Phone routines in 1.0.16

`run` executes 1–60 steps inside one explicitly named foreground package, with a 240-second execution budget. It validates the entire plan before observing or acting. `dryRun:true` performs parameter validation only; it does not check whether targets exist on the device.

```json
{"app":"com.android.chrome","steps":[
  {"op":"assert","text":"手机操控测试场"},
  {"op":"type","target":"姓名输入框","text":"Test user"},
  {"op":"tap","target":"保存测试表单","expect":"表单已保存"}
]}
```

Supported operations are `tap{target,long?,expect?}`, `type{target,text,submit?,expect?}`, `scroll{direction,until?,max?,expect?}`, `wait{ms}` or `wait{text,timeoutMs?}`, `assert{text}`, `snapshot`, and `key{key:"back",expect?}`. Waits are bounded to 10 seconds, postconditions to 5 seconds. Each step may specify `requireText` as a visible precondition. Targets are visible text, hints, or viewId fragments; ambiguous targets stop execution. Coordinates and cached element ids are deliberately excluded from routines.

Normal actions share plan approval. Sensitive targets, submission, and apps configured to confirm every write retain separate confirmation, including when a routine is run in the usual confirmation-waiver mode. App deny lists, secret fields, lock state, driver fingerprints, pause, and stop still apply. This does not grant a task-wide waiver or persist an approval.

The result includes `trace`, `completed`, `nextStep`, and `mayHaveActed`. Steps are numbered from 1. A failed write is never automatically replayed. Before using `startAt`, inspect the current screen; the resumed step must include `requireText`. Progress is returned for replanning, not persisted across restarts. Driver hangs may still hit the outer worker timeout, which cannot guarantee a full trace.

`scroll.until` uses the new observation after each scroll, checks an already visible target first, and stops after two unchanged observations. `screen.until` polls text and captures an optional image only at the end; a missing target is an error, not success.

The [task pack](../examples/phone-routines/README.md) contains a DSH skill and an isolated form/chat/check-in/quiz test page. Install it into the current DSH Home's `skills` directory using its explicit-path installer. It is not a background plugin: notification-driven auto-replies and scheduled check-ins still require trigger, deduplication, ownership, and background-lifecycle support. Real-app feasibility remains a source-based assessment until verified on a phone; model reasoning accuracy is separate from input success.
