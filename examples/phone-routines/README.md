# 手机日常任务包

这是一份 **DSH 任务技能 + 本地验收页**，不是后台服务插件。它教模型怎样组合手机工具、验证结果和在出错时停下；不会替系统增加通知监听、定时唤醒或平台专用 API。

- [任务能力矩阵](TASKS.md)：30 个日常与挑战任务，列出前提、判断方法和缺口。
- [手机验收页](phone-lab.html)：不联网、不发送真实消息，不保存账号与真实作业。
- [任务技能](dsh-phone-routines/SKILL.md)：可装入 DSH 的技能目录。
- [任务用法与示例](dsh-phone-routines/routines.md)：视频、消息、签到、表单、练习题。

## 安装技能

项目实际使用的技能位置是 `$DSH_HOME/skills/<技能名>/SKILL.md`；没有配置 `DSH_HOME` 时，手机本机引擎通常是 `/root/.dsh`。这可由 App 的技能页、`app/lib/ui_config/ui_skill.dart` 和原生 `ManagedSkillInstaller.kt` 的 `skills/${request.name}` 写入路径核对。先确认当前连接的是 **本机引擎**，手机操控不在远程主机运行。

1. 把整个 `phone-routines` 文件夹放进手机 `Download`。
2. 在 DSH Go 设置中开启“访问手机文件”，引擎里对应 `/sdcard/Download/`。
3. 打开 DSH 对话的终端，先预览安装位置：

```sh
sh /sdcard/Download/phone-routines/install.sh --dsh-home /root/.dsh --dry-run
```

4. 确认路径确实是当前本机 DSH Home 后安装：

```sh
sh /sdcard/Download/phone-routines/install.sh --dsh-home /root/.dsh
```

5. 回到技能页刷新，或新建对话；找不到时重启本机引擎。输入 `/dsh-phone-routines`，或者说“使用手机日常任务技能，先在测试页填写表单并验证保存结果”。技能目录不是 npm 插件，**不需要** `dsh plugin add`。

安装脚本要求显式传入已存在的 DSH Home，同样内容重复安装不改文件；已存在的同名技能内容不同会拒绝覆盖，保留你的修改。升级时先把旧目录改名备份，再安装。停用时在技能目录中将 `dsh-phone-routines` 移出 `skills` 目录，刷新技能列表即可。

## 使用测试页

可以直接在浏览器打开 HTML；为了避免部分手机对下载 HTML 的限制，也可以在电脑上仅给本次 USB 调试提供本地 HTTP 页面：

```powershell
python -m http.server 8765 --bind 127.0.0.1 --directory "<phone-routines 的绝对路径>"
adb reverse tcp:8765 tcp:8765
```

手机浏览器打开 `http://127.0.0.1:8765/phone-lab.html`。结束后关闭 HTTP 服务，执行 `adb reverse --remove tcp:8765`。页面全部逻辑在本地 DOM，刷新清空结果，**不是真实聊天、签到、作业平台**。测试时不需要填写真实姓名或账号。

推荐顺序：原生输入框 → 保存表单 → 延迟按钮 → 长列表 → 模拟聊天 → 每日签到 → 练习题。逐项记录工具输入、页面回读、耗时、是否误触和退出原因。重复测试先点该区域的清空/重置按钮；聊天和签到只影响这个页面。

## 验证范围

```sh
node --test examples/phone-routines/test.mjs
```

桌面自检只验证安装事务和页面业务逻辑；Android 的无障碍树、输入法、滚动、真实平台流程和模型决策必须另记真机结果。文档中的任务分析不是通过记录，不能当成“自动回复/签到已经可以长期无人值守”。
