# 坐标手势：`gesture` 工具

这份文档说明本 Fork 相对上游 `v1.0.13` 新增的第 14 个手机操控工具 `gesture`：它能只凭标号截图的像素坐标，
模拟人的手指动作——任意时长长按、任意路径滑动、点击、双击、双指缩放，并能把多步连成一个序列一次做完。

## 为什么需要它

上游已有的坐标能力只有 `tap_xy`（按坐标点一下）。手势能力被写死在元素路径上：

```kotlin
// app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/a11y/Actuator.kt
fun tap(x: Int, y: Int, long: Boolean = false): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val duration = if (long) ViewConfiguration.getLongPressTimeout().toLong() + 300 else 60L
    ...
}
```

也就是说：

- 「长按」的时长是**系统长按时长 + 300 毫秒**，调用方无法指定按住 3 秒或 1.5 秒；
- `swipe` 只有两点直线，且只被列表滚动 `scroll` 调用，不能自己指定起止点与速度；
- 没有双击，没有多指，没有「按住后拖」这类复合动作。

很多真实操作恰好需要这些：长按弹出菜单、按住拖动排序、沿曲线滑动解锁、双指缩放图片、双击点赞。
`gesture` 就是补这一块。

## 工具契约

```jsonc
{
  "observation": "s7",          // 最近一次 screen 返回的标号截图编号
  "steps": [ /* 1–10 个步骤 */ ],
  "gap": 120                     // 可选：两步之间停多久（毫秒，默认 120）
}
```

每一步是一个对象，用 `kind` 选动作。所有 `x` / `y` 都是**那张截图的像素坐标**（图上的 W×H，不是屏幕物理像素）。

| kind | 作用 | 必要参数 | 可选参数 |
| --- | --- | --- | --- |
| `tap` | 点击 | `x` `y` | `duration` 毫秒（默认 60） |
| `long` | 长按任意时长 | `x` `y` | `duration` 毫秒（默认 800） |
| `double` | 双击 | `x` `y` | `gap` 两次点击间隔（默认 120，范围 40–300）、`duration`（≤300） |
| `swipe` | 直线滑动 | `x` `y` `x2` `y2` | `duration` 毫秒（默认 300） |
| `path` | 沿任意路径滑 | `points`：`[[x,y],[x,y],…]` | `duration` 毫秒（默认 300） |
| `pinch` | 双指缩放 | `x` `y` `spread` | `duration` 毫秒（默认 300） |

`pinch` 的 `spread` 是**两根手指移动量之和**：每根手指移动 `spread÷2`，正数放大、负数缩小；
两根手指的起点在中心上下各 `max(220, |spread|÷2+40)` 像素处，所以永远不会交叉。

### 例子

按住 3 秒 → 上滑 500ms → 双击：

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

沿弧线滑动（绕开中间的按钮）：

```json
{"kind": "path", "points": [[200, 1800], [500, 1500], [800, 1200]], "duration": 600}
```

双指放大图片：

```json
{"kind": "pinch", "x": 540, "y": 900, "spread": 400}
```

## 平台上限与拒绝条件

`GestureDescription` 本身有限制，越界时 `build()` 会抛异常，所以规划阶段就先挡住：

| 限制 | 值 | 说明 |
| --- | --- | --- |
| 步骤数 | ≤ 10 | 一次调用 |
| 触点（stroke）数 | ≤ 10 | 平台上限 |
| 总时长 | ≤ 60 000 ms | 平台上限，从第一步开始算到最后一步结束 |
| 路径点数 | ≤ 32 | 单条 `path`，相邻重复点会自动去掉 |
| 双击间隔 | 40–300 ms | Android `GestureDetector` 的真实判定窗口 |
| 双击单次按压 | ≤ 300 ms | 超过就会变成一次长按，不再是双击 |
| `pinch` 的 `spread` | ≥ 40 像素 | 更小手指基本不动 |
| 所有坐标 | 必须落在截图内 | 截图长边会被缩到 ≤1300 像素 |

超过上限不会偷偷截断，而是返回一句说明（例如「双击要求 40–300ms」），让模型自己拆成多次调用。

## 坐标空间与新鲜度

- `screen` 返回的标号截图由 `ScreenShooter` 生成，长边被缩放到不超过 1300 像素；
  `ImageGeometry.toScreen(x, y)` 负责把截图坐标映射回屏幕物理坐标，落在图外会返回 `null`。
- 坐标手势要求「这张截图仍然对应当前界面」。`MainScreenDriver.gesture()` 在注入前会重新截一张图，
  用 `observation(cap).reusableCoordinates(snap.observation)` 比较；不一致就拒绝并要求重新 `screen`。
- 屏幕编号 `s7` 之类的快照会过期。用过期编号做手势会得到「屏幕编号已过期，坐标对不上了」。

## 安全边界

新增的 `WriteKind.GESTURE` 走的是和 `tap_xy` 同一条闸门，**没有放松任何规则**：

- 坐标手势**每次都要你确认**（`SafetyGate.checkWrite` 对 `GESTURE` 无条件返回 `Confirm`）；
  只有你自己把确认模式设成「总是允许」时才会免确认，和 `tap_xy` 一致。
- 确认卡片写的是「在「应用」做手势：long (540,1200) 3000ms → swipe (540,1500)→(540,700) 500ms」，
  摘要用语言中立的 ASCII，English 界面不会混进中文。
- 自己 App 的界面、系统权限页、`hidden` / `unreadable` 页面一律拒绝，判断顺序与 `tap_xy` 逐条一致。
- 手势从元素树里解析不出目标（`target` 恒为 `null`），包名取 `foregroundPackage()`，
  所以「应用名单」的禁碰列表、系统设置页、自己界面这些规则照旧生效。
- 后台屏（Shizuku / 自 ADB 那条路）**不支持坐标手势**，返回「后台屏按元素编号操作，不用坐标」。

## 代码地图

| 文件 | 职责 |
| --- | --- |
| `app/android/app/src/main/kotlin/.../phone/GesturePlan.kt` | 纯 Kotlin 规划器：把 `steps` 校验并排成 `GestureStroke` 列表，返回摘要或拒绝理由 |
| `.../phone/PhoneContracts.kt` | `PhoneAction.Gesture(screenId, strokes)` 与 `GestureStroke(points, startMs, durationMs)` |
| `.../phone/PhoneToolCatalog.kt` | 工具 schema 与描述（进入每次请求，改动会使 KV cache 失效） |
| `.../phone/PhoneCore.kt` | 参数解析、路由、闸门、预算（`budgetMs` 给 120 秒） |
| `.../phone/SafetyGate.kt` | `WriteKind.GESTURE` 与确认文案 |
| `.../phone/a11y/Actuator.kt` | 把多条 stroke 装进一个 `GestureDescription` 并用现成的阻塞式派发等待完成 |
| `.../phone/a11y/MainScreenDriver.kt` | 坐标守卫（截图新鲜度、逐点入界、自己界面/系统页/读不到）与结果观察 |
| `.../phone/background/BackgroundDisplay.kt` | 后台屏明确拒绝 |
| `app/android/app/src/test/kotlin/.../phone/GesturePlanTest.kt` | 规划器的 JVM 单元测试（10 个用例） |

## 已知限制（有意保留）

- **`pinch` 的中心不能太靠边**：规划器不知道截图尺寸，所以「手指会不会出画」只能在注入前判断，
  那时确认卡片已经弹过了。文档与描述里写了规则，避免白确认一次。
- **双击的 `duration` 上限 300ms**：超过系统长按时长后，第一次按压会变成长按，双击就失效，所以直接拒绝而不是静默改值。
- **不做自动拆分**：超过 10 步 / 60 秒时拒绝并说明，由调用方拆成多次调用。
  自动拆分无法处理「双击中间断开」这类情况，强行拆会更糟。
- **后台屏没有坐标手势**：后台注入走的是另一套协议（只有元素、文本、方向），要支持得新增协议键与闸门，超出本次范围。

## 构建与验证

```bash
# 规划器单测（Debug 变体才有 JVM 单测任务）
cd app/android && ./gradlew testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.phone.*"

# 打 arm64 release 包
bash tooling/build-apk.sh --build-name 1.0.14 -f arm64
```

本 Fork 的 Release APK 是 **debug 签名**（仓库没有 `app/android/key.properties`）。
安装前必须先卸载官方版，卸载会清掉应用数据（包括登录凭据与运行时 rootfs）。

真机自测顺序建议：`tap` → `long` 3 秒 → `swipe` → `path` → `double` → `pinch`。
