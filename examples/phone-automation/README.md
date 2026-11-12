# 自动任务格式示例 / Automation task examples

这些文件使用任务格式 schema 1。它们是可解析的模板，未在手机或真实 App 上验证，不是可直接适配所有页面的脚本。

- local-check.json：只读检查本地测试页标题。
- parameter-branch-loop.json：参数输入、已完成分支和三次有界等待；不发送消息或提交订单。
- video-browse-bounded.json：短视频浏览结构；先观察真实 App，替换 example.video.app 和 readyText。direction 必须根据实际驱动与页面确认，不假定所有视频 App 一样。

example.phone.lab 和 example.video.app 都是占位包名。真实导入前请先让 AI 用 apps/screen 核实当前包名、可见目标和页面前提，再修改定义。任务页导入只提出候选；用户检查后可确认仅运行一次，或审核参数范围并确认保存后重复运行。validate 成功只证明格式正确，不证明页面可用。

These schema-1 files are parser-valid templates, not device-tested universal scripts. Replace placeholder packages only after observing the actual app and page. Review a proposal before confirming one temporary run or saving it with approved parameter scopes. Successful validation proves syntax and bounds, not the existence of on-screen targets. The bounded video template schedules ten 30-second waits and scrolls; its reported scheduled time is not proof that ten videos played or learning progress was credited.
