# 坐标输入：`input` 工具，以及 `tap.text` / `screen.until` / `scroll.until`

这份文档讲清楚四件事：为什么加 `input`、它的契约与失败链、坐标输入的安全与新鲜度规则、以及代码在哪。

## 为什么需要它

上游（v1.0.13）写文字只有一条路：`type`，参数是元素编号。于是填一个输入框是这样的：

1. `screen`（`detail:true`）—— 几百行元素，自己找那个输入框的编号；
2. `tap`（`id`）—— 点它，把焦点点进去；
3. `type`（`id` + `text`）—— 写入。

三次调用、三个往返，而且第 2 步点开的输入框在第 3 步之前可能已经换了一批编号（键盘弹出会改变窗口与滚动位置，
`screen` 的编号是重新分配的）。更麻烦的是有些页面 `tap` 只把焦点落到页面上，必须真按住 600 毫秒才聚焦，
上游的 `tap` 给的是元素点击，做不了这件事。

`input` 把这四件事（点、找焦点、写、回读）放进一次调用，在设备端连续试，中途不回给模型。

## 工具契约

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `observation` | 是 | 最近一次 `screen` 的屏幕号；必须带 `image`，坐标是那张标号截图的像素 |
| `x` `y` | 是 | 输入框在标号截图上的像素坐标（非负整数，从 0 开始） |
| `text` | 是 | 要写入的文字，最多 2000 字；空白会被拒 |
| `submit` | 否 | 写完后发一次输入法回车（`ACTION_IME_ENTER`，API 30+ 且节点支持才发） |

返回的是动作之后的**新屏幕**（与 `tap`/`tap_xy` 一样），不是「成功」两个字 —— 模型看得到结果。

### 设备端的失败链

一次 `input` 在设备端按顺序试下面四步，任一步成功就停：

1. **点**：在坐标上点一下（60ms 的普通点击），然后等界面稳定（首屏 800ms、安静 250ms、上限 2.5s）。
2. **找焦点并写入**：扫所有窗口的根节点，`findFocus(FOCUS_INPUT)`，`isEditable` 时用 `ACTION_SET_TEXT`
   写入（`ActionActuator.setText` 内部会先 `ACTION_FOCUS`，失败再 `ACTION_CLICK` + 重新取节点重试）。
   写完每 100ms 回读一次、共 1.5 秒，用 `sameText` 比较（忽略空白、`-` 和零宽字符）。不一致视为失败，继续下一步。
3. **长按再找**：第 2 步没成功，就在同一个坐标按住 600ms（`Actuator.hold`，走手势注入），停 300ms 再找焦点重写。
   很多 WebView 页面只有真按住才会聚焦。
4. **剪贴板**：把文字放进剪贴板（`Actuator.copy`），对焦点发 `ACTION_PASTE`；如果节点 `actionList` 里没有粘贴，
   就再长按 600ms、停 400ms，然后在弹出的菜单里按文字点「粘贴」/「Paste」（`Actuator.clickText`，跨窗口找可点的节点）。

收尾：如果 `submit` 为真就发一次回车；然后重新捕获一次屏幕，**只有键盘确实升起时**才发一次返回键把它收起来
（键盘会盖住下一张截图的坐标）。最后按常规流程 settle + 读新屏幕 + 标出差异。

### 失败码（都写成给模型看的一句话）

| 情况 | 返回的第一句 |
| --- | --- |
| 屏幕号过期 | `屏幕编号 s7 已过期，坐标对不上了。请重新 screen 加 image 再输入。` |
| 没有截图 | `屏幕 s7 没有截图。按坐标输入要先用 screen 加 image 拿一张标号截图。` |
| 坐标不在截图里 | `坐标 (x, y) 不在这张截图里（W×H）。` |
| 焦点是密码框 | `焦点落在密码框上，DSH 不替用户填密码。请用 handoff 让用户自己来。` |
| 四步都没写成 | `点了 (x, y)，但没有出现能写入的输入框，也没有找到可以粘贴的地方。看看新屏幕，可能要先点一下输入框。` |

### 例子

```json
{"observation": "s7", "x": 540, "y": 760, "text": "13800138000"}
```

带提交：

```json
{"observation": "s7", "x": 540, "y": 760, "text": "今天天气怎么样", "submit": true}
```

## `tap` 的 `text`：按文字点

`tap` 的参数从 `screen` + `id` 变成 `screen` + (`id` 或 `text`)。按文字找元素时匹配五档，从强到弱：

1. 元素文字全等；
2. 行文本全等（一条列表项的文字可能是多个子节点拼起来的）；
3. 元素文字包含这段字（至少 2 个字）；
4. 行文本包含（至少 2 个字）；
5. `viewId` 包含（至少 3 个字，给开发者与测试用）。

同一档里有多个候选时，只保留可点的（`clickable || longClickable || editable`）——点了没反应的那层 `Text`
不会跟它自己的按钮抢。仍然多于一个就**拒绝并列**：

```
「删除」匹配到 2 个元素，无法确定点哪个。用 id 指定：#7「删除」、#8「删除」。
```

零命中时列出当前能点的元素，让模型换一段文字或直接用 `id`：

```
屏幕上没有「淘宝」。现在能点的有：#3「微信」、#4「支付宝」、#5「设置」。
```

找到之后走的是**原来的 `tap`**：同样的指纹复查、同样的安全闸、同样的确认卡片、同样的 stale 检测。

## `screen.until` 与 `detail` 的边界

- `screen` 的 `until`：读到屏幕后，如果 `until` 不在屏幕文本里，就每 250ms 重新读一次，最多等 5 秒。
  页面刚划过来还没画完时用它，省掉模型自己反复读屏。
- `screen` 的 `detail`：以前每个元素后面跟一个中心点 `@540,1200`，现在跟的是边界 `@120,1160-960,1240`
  （`左上-右下`，屏幕像素）。判断「这个坐标落在哪个元素上」不用再自己算中心点。
- 焦点状态本来就会渲染（元素状态里有 `已聚焦`），所以没有加 `focus` 参数。

## `scroll.until` 与 `max`

```json
{"screen": "s7", "direction": "down", "until": "第 3 页", "max": 8}
```

每一步都是普通的 `scroll`（同样的确认、同样的动作日志），滚一次看一眼屏幕文本，出现 `until` 就停并返回当时的屏幕；
`max` 次（1–20，默认 10）都没出现则返回错误并说明滚了几次。

## 安全边界

- `input` 与 `tap_xy` 一样是 `WriteKind.INPUT`：**每一次都要确认**，没有「记住」或批量确认。
- 确认卡片显示 App、坐标、字数，**不显示要写的文字** —— 焦点框可能是密码框，写入之前无法确定。
  动作日志同样只记长度（`按坐标输入 11 字 (540,760)`）。
- 自己 App 的界面、系统权限页、`hidden`/`unreadable` 的屏幕、以及「无法确认界面仍与截图一致」全部拒绝，
  守卫顺序与 `tap_xy` 一字不差。
- 后台屏（Shizuku / 自 ADB）只支持按元素编号：`input` 拒绝，`tap` 的 `text` 走 `PhoneDriver.find` 的默认实现，
  返回「这个屏幕不支持按文字找元素」。

## 代码地图

| 位置 | 作用 |
| --- | --- |
| `phone/PhoneContracts.kt` | `PhoneAction.Input`；`PhoneDriver.find(screenId, text)`（带默认实现） |
| `phone/TextSelector.kt` | 五档匹配、可点优先、拒绝并列（纯 Kotlin，可 JVM 测） |
| `phone/a11y/MainScreenDriver.kt` | `input()` 的四段失败链；`find()` 把快照元素映射成候选 |
| `phone/a11y/Actuator.kt` | `focusedEditor()`、`paste()`、`copy()`、`hold()`、`clickText()` |
| `phone/PhoneCore.kt` | `input` 的参数校验；`tapByText()`；`scrollUntil()`；`screen()` 的 `until` |
| `phone/PhoneToolCatalog.kt` | 第 15 个工具与三个新参数、`screen.detail` 的说明 |
| `phone/SafetyGate.kt` | `WriteKind.INPUT` 与确认文案 |
| `a11y/ScreenFormat.kt` | `detail` 输出元素边界 |
| `res/values{,-zh}/i18n_strings.xml` | `phone_status_input` / `phone_confirm_input` / `phone_log_input_xy` |

## 已知限制（有意保留）

- **坐标必须落在截图内**：与 `tap_xy`/`gesture` 一致，不猜。`screen` 不带 `image` 时 `input` 直接拒绝。
- **密码框只有聚焦之后才知道**：所以确认卡片不显示文字。焦点落在密码框时整次调用失败（不会退回 `type` 的密码拦截逻辑，因为这里根本没有元素编号）。
- **`ACTION_SET_TEXT` 对某些 WebView 无效**：失败链会退到长按 + 剪贴板，这是 WebView 的普遍现象；两条都失败就只能让用户自己点一下（返回的那句话会这么建议）。
- **菜单里的「粘贴」是文字匹配**：中文与英文各试一次，其它语言的输入法菜单会失败（仍可退回 `ACTION_PASTE` 那条路，如果节点支持的话）。
- **收键盘用返回键**：只在键盘确实升起时发一次；如果页面在键盘升起时拦截返回键（少见），会退到上一页 —— 这是与上游 `type` 相同的行为。
- **`tap.text` 不做模糊匹配**：不做拼音、不做同义、不做 OCR。文字对不上就拒绝并列出候选。

## 构建与验证

```bash
# 纯 Kotlin 匹配器与工具目录的 JVM 单测（Debug 变体才有这个任务）
cd app/android && ./gradlew testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.phone.*"

# Dart 侧（新增了 phoneToolInput 这个 l10n key）
cd app && flutter analyze

# 打 arm64 release 包
tooling/build-apk.sh --build-name 1.0.15 -f arm64
```

真机自测顺序：`screen` 带 `image` → `input` 写普通输入框 → `input` 带 `submit` → `tap` 带 `text` 点「登录」
→ `scroll` 带 `until` 找列表深处条目。重点看三种情况：WebView 里的输入框（可能走长按或剪贴板那条路）、
密码框（应当直接拒绝）、以及键盘升起后下一张截图的坐标是否还对得上。
