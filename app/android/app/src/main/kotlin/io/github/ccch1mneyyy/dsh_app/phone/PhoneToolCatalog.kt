package io.github.ccch1mneyyy.dsh_app.phone

/**
 * The FIXED tool set the bridge serves. The model sees `mcp__phone__<name>`.
 *
 * Never make this depend on permission state: names, descriptions and schemas enter every
 * request, and any change invalidates DeepSeek's KV cache from the first changed token.
 * A call that can't run right now returns an actionable explanation instead. The JVM test pins
 * a hash of [listJson]; change it only on purpose.
 */
object PhoneToolCatalog {
    const val APPS = "apps"
    const val OPEN = "open"
    const val INTENT = "intent"
    const val NOTIFY = "notify"
    const val CLIPBOARD = "clipboard"
    const val SCREEN = "screen"
    const val COLLECT = "collect"
    const val TAP = "tap"
    const val TYPE = "type"
    const val SCROLL = "scroll"
    const val KEY = "key"
    const val TAP_XY = "tap_xy"
    const val GESTURE = "gesture"
    const val INPUT = "input"
    const val RUN = "run"
    const val TASK = "task"
    const val HANDOFF = "handoff"

    class Tool(val name: String, val description: String, val properties: Map<String, Any?>, val required: List<String>) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
                if (required.isNotEmpty()) put("required", required)
                put("additionalProperties", false)
            },
        )
    }

    val INTENT_KINDS = listOf("alarm", "timer", "calendar", "dial", "sms_draft", "share", "navigate", "settings")
    val SETTINGS_PAGES = listOf(
        "home", "wifi", "bluetooth", "mobile_network", "airplane", "display", "sound", "battery",
        "location", "date", "language", "storage", "nfc", "app_notifications",
    )
    val KEYS = listOf("back", "home", "recents", "notifications")
    val DIRECTIONS = listOf("up", "down", "left", "right")
    val NAV_MODES = listOf("drive", "transit", "walk", "ride")

    private fun str(description: String) = linkedMapOf<String, Any?>("type" to "string", "description" to description)
    private fun bool(description: String) = linkedMapOf<String, Any?>("type" to "boolean", "description" to description)
    private fun int(description: String) = linkedMapOf<String, Any?>("type" to "integer", "description" to description)
    private fun num(description: String) = linkedMapOf<String, Any?>("type" to "number", "description" to description)
    private fun enum(values: List<String>, description: String) =
        linkedMapOf<String, Any?>("type" to "string", "enum" to values, "description" to description)
    private fun array(description: String) = linkedMapOf<String, Any?>(
        "type" to "array",
        "items" to linkedMapOf<String, Any?>("type" to "object"),
        "description" to description,
    )

    // l10n:keep agent tool catalog
    private val SCREEN_ID = str("屏幕号，如 s7（主屏）或 b3（后台屏），取自最近一次返回的屏幕")

    private val SEND_SCOPE = mapOf(
        "type" to "object", "additionalProperties" to false, "required" to listOf("app", "recipient", "text"),
        "description" to "本次发送范围：首次确认联系人和完整正文，同一手机任务内后续调用原样携带以复用；变更任一字段重新确认，任务结束失效。仅用于搜索联系人、输入、粘贴、核对和发送；每步仍须核对真实聊天对象与草稿，不能用于其他敏感动作。",
        "properties" to linkedMapOf(
            "app" to str("发送应用完整包名，必须等于当前应用"),
            "recipient" to str("明确联系人/群名，1–200字；同名时先核对身份"),
            "text" to str("用户要发送的完整原文，1–2000字；不得截断或改写"),
        ),
    )

    private val RUN_CONDITION = mapOf(
        "type" to "object", "additionalProperties" to false, "properties" to mapOf(
            "text" to str("当前可见文字；树不可读则交回 AI，不能当作不存在"),
            "match" to enum(listOf("contains", "exact", "normalized"), "文字匹配方式"),
            "variable" to str("本次变量名，与 text 二选一"), "equals" to emptyMap<String, Any>(),
            "not" to bool("取反"),
        ),
    )
    private val RUN_SELECTOR = mapOf(
        "type" to "object", "additionalProperties" to false, "properties" to mapOf(
            "text" to str("可见文字"), "viewId" to str("资源 ID"), "description" to str("内容说明"),
            "role" to str("目标角色"), "parentText" to str("可见祖先文字"),
            "match" to enum(listOf("contains", "exact", "normalized"), "匹配方式"),
            "enabled" to bool("是否启用"), "visible" to mapOf("type" to "boolean", "enum" to listOf(true)), "unique" to mapOf("type" to "boolean", "enum" to listOf(true)),
        ),
    )
    private val RUN_GESTURE_STEPS = mapOf(
        "type" to "array", "items" to mapOf("type" to "object", "required" to listOf("kind"),
            "additionalProperties" to false, "properties" to mapOf(
                "kind" to enum(GesturePlan.KINDS, "手势类型"), "x" to int("起点X"), "y" to int("起点Y"),
                "x2" to int("终点X"), "y2" to int("终点Y"), "duration" to int("1–60000毫秒"),
                "gap" to int("双击间隔40–300毫秒"), "spread" to int("双指缩放像素"),
                "points" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("type" to "integer"))),
            )),
    )
    private fun runSteps(depth: Int): Map<String, Any?> = mapOf(
        "type" to "array",
        "items" to mapOf(
            "type" to "object", "additionalProperties" to false, "required" to listOf("op"),
            "properties" to linkedMapOf(
                "op" to enum(RoutinePlan.CHAT_OPS.toList(), "动作；不支持 ai/plugin 或任意脚本"),
                "app" to str("open 的目标，必须属于 apps"), "target" to str("可见文字/提示/viewId"),
                "selector" to RUN_SELECTOR, "long" to bool("长按"), "text" to str("输入或检查文字"),
                "submit" to bool("输入后提交"), "direction" to enum(DIRECTIONS, "滚动方向"),
                "until" to mapOf("anyOf" to listOf(mapOf("type" to "string"), RUN_CONDITION), "description" to "scroll 只用文字；repeat 只用条件对象"),
                "max" to int("滚动上限1–20"),
                "ms" to int("固定等待0–120000毫秒，旧格式最多10000"),
                "timeoutMs" to int("文字等待250–30000毫秒，旧格式最多10000"),
                "match" to enum(listOf("contains", "exact", "normalized"), "匹配方式"),
                "key" to enum(listOf("back"), "返回键"), "requireText" to str("前置文字"), "expect" to str("后置文字"),
                "condition" to RUN_CONDITION, "count" to int("repeat次数1–100"),
                "then" to if (depth > 0) runSteps(depth - 1) else array("同格式子步骤；总深度上限4，执行前完整校验"),
                "else" to if (depth > 0) runSteps(depth - 1) else array("同格式子步骤"),
                "steps" to mapOf("anyOf" to listOf(if (depth > 0) runSteps(depth - 1) else array("repeat子步骤"), RUN_GESTURE_STEPS)),
                "gap" to int("gesture各手势间隔0–5000毫秒"),
                "name" to str("find/read_text/set 的本次变量名"), "value" to emptyMap<String, Any>(),
                "observation" to str("AI 已看到的截图编号；视觉动作必须在本次调用首次读屏/写操作前执行，执行后交回 AI"),
                "x" to int("截图像素 X"), "y" to int("截图像素 Y"), "note" to str("坐标用途"),
            ),
        ),
    )

    private val RUN_STEPS = runSteps(1)

    val tools: List<Tool> = listOf(
        Tool(
            APPS,
            // l10n:keep agent tool catalog
            "列出这台手机上能打开的应用（名称 · 包名）。",
            // l10n:keep agent tool catalog
            linkedMapOf("query" to str("按名称或包名筛选，可省略")),
            emptyList(),
        ),
        Tool(
            OPEN,
            // l10n:keep agent tool catalog
            "打开应用或链接（app 或 url 二选一）。background:true 在后台屏打开（需用户开通不打扰模式）；无障碍开着时返回新屏幕。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "app" to str("包名或应用名"),
                // l10n:keep agent tool catalog
                "url" to str("网址或深链"),
                // l10n:keep agent tool catalog
                "background" to bool("在后台屏打开"),
            ),
            emptyList(),
        ),
        Tool(
            INTENT,
            // l10n:keep agent tool catalog
            "用系统意图办事（最后一步用户在对方界面确认），能办就别点界面。字段按 kind：" +
                // l10n:keep agent tool catalog
                "alarm(hour,minute,days?,message?)、timer(seconds,message?)、calendar(title,begin,end?,all_day?,location?,description?)、" +
                // l10n:keep agent tool catalog
                "dial(number)、sms_draft(number?,text)、share(text,app?)、navigate(destination|lat+lng,mode?,map?)、settings(page,app?)。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "kind" to enum(INTENT_KINDS, "事务类型"),
                // l10n:keep agent tool catalog
                "hour" to int("时 0–23"),
                // l10n:keep agent tool catalog
                "minute" to int("分 0–59"),
                "days" to linkedMapOf(
                    "type" to "array",
                    "items" to linkedMapOf("type" to "integer"),
                    // l10n:keep agent tool catalog
                    "description" to "重复的星期，1=周一 … 7=周日；省略只响一次",
                ),
                // l10n:keep agent tool catalog
                "seconds" to int("秒 1–86400"),
                // l10n:keep agent tool catalog
                "message" to str("闹钟 / 计时器标签"),
                // l10n:keep agent tool catalog
                "title" to str("日程标题"),
                // l10n:keep agent tool catalog
                "begin" to str("开始，如 2026-10-24T19:00（手机时区）；全天写 2026-10-24"),
                // l10n:keep agent tool catalog
                "end" to str("结束，省略 = 开始后 1 小时"),
                // l10n:keep agent tool catalog
                "all_day" to bool("全天"),
                // l10n:keep agent tool catalog
                "location" to str("地点"),
                // l10n:keep agent tool catalog
                "description" to str("备注"),
                // l10n:keep agent tool catalog
                "number" to str("电话号码"),
                // l10n:keep agent tool catalog
                "text" to str("文字内容"),
                // l10n:keep agent tool catalog
                "app" to str("分享目标应用（省略弹分享面板）；settings:app_notifications 用哪个应用"),
                // l10n:keep agent tool catalog
                "destination" to str("目的地名称或地址"),
                // l10n:keep agent tool catalog
                "lat" to num("纬度 GCJ-02"),
                // l10n:keep agent tool catalog
                "lng" to num("经度 GCJ-02"),
                // l10n:keep agent tool catalog
                "mode" to enum(NAV_MODES, "出行方式，默认 drive"),
                // l10n:keep agent tool catalog
                "map" to enum(listOf("amap", "baidu"), "地图，默认先高德再百度"),
                // l10n:keep agent tool catalog
                "page" to enum(SETTINGS_PAGES, "设置页；app_notifications 还需 app"),
            ),
            listOf("kind"),
        ),
        Tool(
            NOTIFY,
            // l10n:keep agent tool catalog
            "给用户发一条手机通知。",
            // l10n:keep agent tool catalog
            linkedMapOf("title" to str("标题"), "text" to str("正文")),
            listOf("title", "text"),
        ),
        Tool(
            CLIPBOARD,
            // l10n:keep agent tool catalog
            "读写剪贴板：set 写入文字；get:true 读取（系统只允许前台应用读，DSH 不在前台时读不到）。",
            // l10n:keep agent tool catalog
            linkedMapOf("set" to str("要写入的文字"), "get" to bool("读取剪贴板")),
            emptyList(),
        ),
        Tool(
            SCREEN,
            // l10n:keep agent tool catalog
            "读当前屏幕：每行一个元素，[编号] 只在本屏有效。detail:true 附元素边界（左上-右下）；image:true 附标号截图；until 是等这段文字出现再返回；background:true 读后台屏。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "detail" to bool("附上元素边界（左上-右下）"),
                // l10n:keep agent tool catalog
                "image" to bool("附带标号截图"),
                // l10n:keep agent tool catalog
                "until" to str("等这段文字出现再返回（可选，最多 5 秒）"),
                // l10n:keep agent tool catalog
                "background" to bool("读后台屏（不打扰模式）"),
            ),
            emptyList(),
        ),
        Tool(
            COLLECT,
            // l10n:keep agent tool catalog
            "只读：自动滚动列表收集全部条目（按文字去重），读长列表只用这一次。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "list" to int("列表的编号"),
                // l10n:keep agent tool catalog
                "max_pages" to int("最多翻几页（1–30），默认 10"),
                // l10n:keep agent tool catalog
                "until" to str("出现这段文字就停"),
            ),
            listOf("screen", "list"),
        ),
        Tool(
            TAP,
            // l10n:keep agent tool catalog
            "点一个元素，返回新屏幕。id 和 text 二选一：text 是界面上看得见的文字，按它找元素（匹配到多个会拒绝，列出候选让你用 id 指定）。敏感按钮或首次操作该 App 会先请用户确认。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "id" to int("元素编号（与 text 二选一）"),
                // l10n:keep agent tool catalog
                "text" to str("按钮/条目上的文字，按名字点（与 id 二选一）"),
                // l10n:keep agent tool catalog
                "long" to bool("长按"),
                "send" to SEND_SCOPE,
            ),
            listOf("screen"),
        ),
        Tool(
            TYPE,
            // l10n:keep agent tool catalog
            "在输入框输入文字（替换原内容），返回回读和新屏幕。submit:true 后按回车 / 发送，要确认。密码、验证码、支付框默认拒绝，被拒绝就 handoff。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "id" to int("输入框编号"),
                // l10n:keep agent tool catalog
                "text" to str("要输入的文字"),
                // l10n:keep agent tool catalog
                "submit" to bool("输入后提交 / 发送"),
                "send" to SEND_SCOPE,
            ),
            listOf("screen", "id", "text"),
        ),
        Tool(
            INPUT,
            // l10n:keep agent tool catalog
            "按最新截图坐标聚焦并输入，优先写入可编辑节点；无节点时准备剪贴板并打开可见粘贴菜单，返回 need-visual-paste 和截图给当前 AI，尚未完成输入；随后按新图点粘贴并核对草稿。不能把准备剪贴板当作已发送。每 App 首次确认视觉权限，此后仍遵守普通手机操控授权。密码框拒绝。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "observation" to str("那张标号截图的屏幕号"),
                // l10n:keep agent tool catalog
                "x" to int("输入框的横坐标（截图像素，从 0 开始）"),
                // l10n:keep agent tool catalog
                "y" to int("输入框的纵坐标（截图像素，从 0 开始）"),
                // l10n:keep agent tool catalog
                "text" to str("要填入的文字"),
                // l10n:keep agent tool catalog
                "submit" to bool("写入后按回车 / 发送"),
                "send" to SEND_SCOPE,
            ),
            listOf("observation", "x", "y", "text"),
        ),
        Tool(
            SCROLL,
            // l10n:keep agent tool catalog
            "滚动，返回新屏幕。id 是要滚动的列表，省略时滚主列表。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "id" to int("列表编号，可省略"),
                // l10n:keep agent tool catalog
                "direction" to enum(DIRECTIONS, "方向"),
                // l10n:keep agent tool catalog
                "until" to str("一直往这个方向滚，直到出现这段文字（最多 max 次）"),
                // l10n:keep agent tool catalog
                "max" to int("until 最多滚几次（1–20，默认 10）"),
            ),
            listOf("screen", "direction"),
        ),
        Tool(
            KEY,
            // l10n:keep agent tool catalog
            "按系统键（back / home / recents / notifications），返回新屏幕。",
            // l10n:keep agent tool catalog
            linkedMapOf("key" to enum(KEYS, "按键"), "screen" to str("屏幕号；省略时作用于主屏")),
            listOf("key"),
        ),
        Tool(
            TAP_XY,
            // l10n:keep agent tool catalog
            "按最新截图的像素坐标点击，文字树不可用但截图正常时可用。每 App 首次确认视觉权限，此后遵守普通手机操控授权。截图、前台或几何状态失效时拒绝并要求重新看图。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "observation" to str("那张截图的屏幕号"),
                // l10n:keep agent tool catalog
                "x" to int("横坐标（像素，从 0 开始）"),
                // l10n:keep agent tool catalog
                "y" to int("纵坐标（像素，从 0 开始）"),
                "send" to SEND_SCOPE,
            ),
            listOf("observation", "x", "y"),
        ),
        Tool(
            GESTURE,
            // l10n:keep agent tool catalog
            "按最新截图像素做手势：tap点击、long长按、double双击、swipe滑动、path路径、pinch双指缩放。每 App 首次确认视觉权限，此后遵守普通手机操控授权。steps只描述这一屏的手势，不得跨页面串旧坐标。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "observation" to str("那张标号截图的屏幕号"),
                // l10n:keep agent tool catalog
                "steps" to array(
                    // l10n:keep agent tool catalog
                    "按顺序执行的步骤，最多 10 步，一步一个对象，用 kind 选动作。tap 点一下 {kind:\"tap\",x,y}，可带 duration（默认 60）；" +
                        // l10n:keep agent tool catalog
                        "long 按住任意毫秒 {kind:\"long\",x,y,duration}（默认 800）；" +
                        // l10n:keep agent tool catalog
                        "double 双击 {kind:\"double\",x,y,gap}（两次点击间隔，默认 120）；" +
                        // l10n:keep agent tool catalog
                        "swipe 直线滑动 {kind:\"swipe\",x,y,x2,y2,duration}（默认 300）；" +
                        // l10n:keep agent tool catalog
                        "path 沿路径滑 {kind:\"path\",points:[[x,y],[x,y],…],duration}（每根手指一条路径）；" +
                        // l10n:keep agent tool catalog
                        "pinch 双指缩放 {kind:\"pinch\",x,y,spread}，spread 是两根手指移动量之和，每根手指移动 spread÷2}。" +
                        // l10n:keep agent tool catalog
                        "所有坐标都要落在这张截图里。double 的 gap 只能 40–300ms（默认 120），每次点击不超过 300ms，再长就变成一次长按。" +
                        // l10n:keep agent tool catalog
                        "pinch 的两根手指从中心上下各 max(220, |spread|÷2+40) 像素起，所以中心离边缘不够远、或 spread 太大（手指会出画）都会被拒。",
                ),
                // l10n:keep agent tool catalog
                "gap" to int("两步之间停多久（毫秒，默认 120）；这不是 double 那两次点击的间隔"),
            ),
            listOf("observation", "steps"),
        ),
        Tool(
            RUN,
            // l10n:keep agent tool catalog
            "普通聊天直接执行手机步骤，无需创建或保存任务。新调用使用 schema:2、apps、steps：支持选择器、查找、等待、滚动、if、有限 repeat、变量、open 和 yield；复杂判断或无障碍不可读时返回 need-ai 与截图给当前会话，不新建会话、不留任务锁。范围内沿用手机操控授权。坐标动作绑定最近截图，执行后立即返回，不能跨页面串旧坐标。最多60个定义节点/500个执行节点/240秒；失败不重放发送。旧 app/steps（不传schema）继续使用旧单应用宏；dryRun不操作手机。",
            linkedMapOf(
                "app" to str("预期当前应用的完整包名，切到别的应用立即停止"),
                "schema" to mapOf("type" to "integer", "enum" to listOf(2)),
                "send" to SEND_SCOPE,
                "apps" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                "forbiddenTargets" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "不可点目标；非空时坐标动作拒绝"),
                "steps" to RUN_STEPS,
                "dryRun" to bool("仅检查参数，不执行；默认 false"),
                "startAt" to int("从第几步开始，默认1；恢复必须先读屏，并给该步 requireText，不能盲重放"),
            ),
            listOf("steps"),
        ),
        Tool(
            TASK,
            // l10n:keep agent tool catalog
            "本地可复用自动任务。action=list/get/validate/propose/start/status/pause/resume/stop/ai_result/repair。propose只提出保存候选，用户在任务页确认后才保存授权。start只运行已批准任务，授权或参数超范围返回needs_confirmation。步骤由本地连续执行；ai节点返回awaiting_ai与checkpoint，使用screen读取新页面后以ai_result提交受限steps或value，不能直接绕开任务写手机。repair整次最多2轮；结果不确定先查实况，禁止盲目重放发送/提交。schema=1；apps固定包名；parameters字段定义；steps支持open/tap/type/scroll/key(back)/wait/assert/if/repeat/set/read_text/ai/finish以及当前支持的视觉动作。先validate确认实际格式。task工具不能批准任务、改全局模式或扩大授权。",
            linkedMapOf(
                "action" to enum(listOf("list", "get", "validate", "propose", "start", "status", "pause", "resume", "stop", "ai_result", "repair"), "操作"),
                "id" to str("任务ID"),
                "definition" to linkedMapOf("type" to "object", "description" to "任务定义：schema:1,id,name,apps,parameters,steps,limits；先validate"),
                "parameters" to linkedMapOf("type" to "object", "description" to "本次参数，必须落在用户批准范围"),
                "runId" to str("运行标识"),
                "checkpoint" to str("status返回的当前AI或修复检查点令牌"),
                "steps" to array("AI节点后继续的有界步骤，不能扩大原应用和动作权限"),
                "value" to linkedMapOf("description" to "AI节点结果值，按节点变量保存"),
            ),
            listOf("action"),
        ),
        Tool(
            HANDOFF,
            // l10n:keep agent tool catalog
            "把手机交给用户完成登录、验证码、支付或没把握的步骤；等用户点「继续」或「停止」，最长 10 分钟。",
            // l10n:keep agent tool catalog
            linkedMapOf("reason" to str("要用户做什么，一句话")),
            listOf("reason"),
        ),
    )

    val names: List<String> = tools.map { it.name }

    fun listJson(): List<Map<String, Any?>> = tools.map { it.toJson() }

    /** Tools that act on a screen through a [PhoneDriver] (need accessibility or the background display). */
    val SCREEN_TOOLS = setOf(SCREEN, COLLECT, TAP, TYPE, SCROLL, KEY, TAP_XY, GESTURE, INPUT, RUN, HANDOFF)
}
