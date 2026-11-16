# 普通聊天执行段 / Ordinary chat sequences

这些是 schema:2 的普通手机 run 示例，不是保存任务 schema:1。example.phone.lab 和页面文字是占位内容，必须先看实际页面再替换。格式验证通过不代表真实目标存在，不可直接把例子用于真实账号。

These schema:2 run examples use placeholder packages and page labels. They are not saved-task schema:1 definitions. Observe the actual page before adapting them. Validation does not establish that a real target exists.

- branch-and-return.json：读取可见状态，简单分支后交回原会话。未知/不可读的文字树不能当作条件不成立。
- bounded-find.json：最多滚动五轮，查找目标，再交回原会话；未找到时不能声称完成。
- scoped-send-dry-run.json：仅检查明确发送范围及输入、发送步骤的格式。`send` 位于 `run` 顶层；`dryRun:true` 不操作手机。若用于人工测试，先替换自有测试页的包名和目标，并经用户同意后移除 `dryRun`；不要直接运行占位示例。

发送范围由 `send:{app,recipient,text}` 绑定完整包名、联系人和正文；也可用于单步 `tap/type/input/tap_xy`。同一手机任务里的后续发送操作必须重复相同范围，首次完整确认后复用，不因拆段反复询问。换范围、停止或任务结束后需重问，拒绝不留下授权；它不是长期免确认。视觉权限另行保存，选择只允许一步视觉操作仍可能再次询问。无树时要由 AI 从新图核对真实联系人和草稿，不能把范围声明当作界面证明。

The scoped-send example is validation-only. `send:{app,recipient,text}` binds the app, recipient and full message and is also supported by individual `tap/type/input/tap_xy` calls. Repeat the same scope across calls within a phone task to share one explicit send approval. A changed scope or an ended task asks again, and rejection retains no grant. Visual permission remains separate; allowing one visual step may prompt again later. With no text tree, AI must verify the actual recipient and draft in fresh screenshots.

Use dryRun:true to validate without phone actions. A need-ai result returns to the calling conversation; do not create an auxiliary model session. Never reuse image coordinates after page changes. Do not repeat an uncertain send.
