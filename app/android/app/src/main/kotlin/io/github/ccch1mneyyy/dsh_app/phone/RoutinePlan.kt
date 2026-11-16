package io.github.ccch1mneyyy.dsh_app.phone

/** A bounded, single-app plan. Validation finishes before the first device operation. */
data class RoutinePlan(
    val app: String, val steps: List<Step>, val startAt: Int, val dryRun: Boolean,
    val schema: Int = 1, val apps: Set<String> = setOf(app), val forbiddenTargets: List<String> = emptyList(),
    val send: SendScope? = null,
) {
    data class Step(val op: String, val args: Map<String, Any?>) {
        fun a() = Args(args)
        // Input contents never enter the confirmation, trace, or action log.
        fun summary(): String = when (op) {
            "type" -> "type ${a().str("target")} (${a().text("text")!!.length} chars)"
            "tap" -> "tap ${a().str("target")}"
            else -> op
        }
    }

    companion object {
        const val MAX_STEPS = 60
        const val BUDGET_MS = 240_000L
        val OPS = listOf("tap", "type", "scroll", "wait", "assert", "snapshot", "key")

        fun parse(a: Args): Either<String, RoutinePlan> {
            if ("schema" in a.keys()) {
                if (a.number("schema")?.toDouble() != 2.0) return Either.Left("unsupported-run-schema")
                return parseChat(a)
            }
            if ("send" in a.keys()) return Either.Left("send-requires-run-schema-2")
            val app = a.str("app") ?: return Either.Left("run 需要 app（当前应用的完整包名）。")
            if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(app)) return Either.Left("app 必须是完整包名。")
            val raw = a.list("steps") ?: return Either.Left("run 需要 steps 数组。")
            if (raw.size !in 1..MAX_STEPS) return Either.Left("steps 需要 1–$MAX_STEPS 步。")
            if (a.has("dryRun") && a.bool("dryRun") == null) return Either.Left("dryRun 必须是布尔值。")
            val start = if (a.has("startAt")) a.int("startAt") else 1
            if (start == null || start !in 1..raw.size) return Either.Left("startAt 从 1 开始，不能超过步骤数。")
            val steps = ArrayList<Step>()
            for ((index, value) in raw.withIndex()) {
                val map = (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                    ?: return Either.Left("第 ${index + 1} 步必须是对象。")
                val s = Args(map)
                val op = s.str("op") ?: return Either.Left("第 ${index + 1} 步缺少 op。")
                val fields = when (op) {
                    "tap" -> setOf("target", "long", "expect")
                    "type" -> setOf("target", "text", "submit", "expect")
                    "scroll" -> setOf("direction", "until", "max", "expect")
                    "wait" -> setOf("ms", "text", "timeoutMs")
                    "assert" -> setOf("text")
                    "snapshot" -> emptySet()
                    "key" -> setOf("key", "expect")
                    else -> return Either.Left("第 ${index + 1} 步不支持 $op。可用：${OPS.joinToString()}。")
                } + setOf("op", "requireText")
                if (map.keys.any { it !in fields }) return Either.Left("第 ${index + 1} 步有不支持的参数：${(map.keys - fields).joinToString()}。")
                fun bad(message: String): Either.Left<String> = Either.Left("第 ${index + 1} 步：$message")
                for (key in listOf("target", "requireText", "expect", "until")) {
                    if (s.has(key) && (map[key] !is String || s.str(key) == null || (map[key] as String).length > 200)) return bad("$key 需要 1–200 字。")
                }
                when (op) {
                    "tap", "type" -> {
                        if (s.str("target") == null) return bad("需要 target（可见文字、提示或 viewId 片段）。")
                        if (op == "type" && (map["text"] !is String || s.text("text")!!.length > 2000)) return bad("text 必须是最多 2000 字的字符串。")
                        for (key in listOf("long", "submit")) if (s.has(key) && map[key] !is Boolean) return bad("$key 必须是布尔值。")
                    }
                    "scroll" -> {
                        if (s.str("direction") !in PhoneToolCatalog.DIRECTIONS) return bad("direction 需要 up/down/left/right。")
                        if (s.has("max") && (s.int("max") ?: 0) !in 1..20) return bad("max 需要 1–20。")
                    }
                    "wait" -> {
                        if (s.has("ms") == s.has("text")) return bad("ms 与 text 必须二选一。")
                        if (s.has("ms") && ((s.int("ms") ?: -1) !in 0..10_000 || s.has("timeoutMs"))) return bad("ms 需要 0–10000，不能同时指定 timeoutMs。")
                        if (s.has("text") && (map["text"] !is String || s.str("text") == null || s.str("text")!!.length > 200)) return bad("text 需要 1–200 字。")
                        if (s.has("timeoutMs") && (s.int("timeoutMs") ?: 0) !in 250..10_000) return bad("timeoutMs 需要 250–10000。")
                    }
                    "assert" -> if (map["text"] !is String || s.str("text") == null || s.str("text")!!.length > 200) return bad("text 需要 1–200 字。")
                    "key" -> if (s.str("key") != "back") return bad("宏内只允许 back；其他系统键请单独调用 key。")
                }
                steps += Step(op, map)
            }
            if (start > 1 && steps[start - 1].a().str("requireText") == null) return Either.Left("恢复步骤必须带 requireText，先观察页面再续跑，不能盲目重放。")
            return Either.Right(RoutinePlan(app, steps, start, a.bool("dryRun") == true))
        }

        private fun parseChat(a: Args): Either<String, RoutinePlan> = try {
            require(a.keys().all { it in CHAT_FIELDS }) { "unknown-fields:" + (a.keys() - CHAT_FIELDS).joinToString(",") }
            require(!a.has("startAt") || a.int("startAt") == 1) { "run-v2-needs-fresh-steps" }
            require(!a.has("dryRun") || a.bool("dryRun") != null) { "invalid-dry-run" }
            require(!a.has("apps") || a.list("apps") != null) { "invalid-apps" }
            require(!a.has("forbiddenTargets") || a.list("forbiddenTargets") != null) { "invalid-forbidden-targets" }
            val apps = a.list("apps") ?: listOfNotNull(a.str("app"))
            val send = SendScope.parse(a)
            require(send == null || send.app in apps) { "send-scope-app-out-of-scope" }
            val raw = a.list("steps") ?: error("missing-steps")
            require(Json.write(raw).length <= 64_000) { "run-too-large" }
            val steps = AutomationPlan.list(Json.parse(Json.write(raw)))
            var nodes = 0
            fun convert(items: List<Map<String, Any?>>, depth: Int, inLoop: Boolean = false): List<Map<String, Any?>> {
                require(depth <= 4 && items.isNotEmpty()) { "steps-depth-or-empty" }
                return items.map { source ->
                    require(++nodes <= MAX_STEPS) { "steps-limit" }
                    val op = source["op"] as? String ?: error("missing-op")
                    require(op in CHAT_OPS) { "unsupported-op:$op" }
                    require(op != "break" || inLoop) { "break-outside-loop" }
                    if (op == "scroll") require(source["until"] == null || source["until"] is String) { "scroll-until-needs-text" }
                    if (op == "repeat") require(source["until"] == null || source["until"] is Map<*, *>) { "repeat-until-needs-condition" }
                    source["selector"]?.let { validateSelector(AutomationPlan.obj(it)) }
                    when (op) {
                        "yield" -> {
                            AutomationPlan.known(source, setOf("op"))
                            mapOf("op" to "snapshot", "_chatOp" to "yield")
                        }
                        "gesture" -> {
                            AutomationPlan.known(source, setOf("op", "observation", "steps", "gap", "note"))
                            AutomationPlan.string(source, "observation")
                            val strokes = source["steps"] as? List<*> ?: error("missing-gesture-steps")
                            val gap = source["gap"]?.let { AutomationPlan.bounded(source, "gap", 0, 5000, null).toLong() }
                            require(GesturePlan.plan(strokes, gap) is GesturePlan.Outcome.Ok) { "invalid-gesture" }
                            source + mapOf("op" to "snapshot", "_chatOp" to "gesture")
                        }
                        "find" -> source + mapOf("op" to "read_text", "name" to (source["name"] ?: "found"))
                        "if" -> source + mapOf("then" to convert(AutomationPlan.list(source["then"]), depth + 1, inLoop)) +
                            (source["else"]?.let {
                                val branch = AutomationPlan.list(it)
                                mapOf("else" to if (branch.isEmpty()) emptyList<Map<String, Any?>>() else convert(branch, depth + 1, inLoop))
                            } ?: emptyMap())
                        "repeat" -> source + mapOf("steps" to convert(AutomationPlan.list(source["steps"]), depth + 1, true))
                        else -> source
                    }
                }
            }
            val forbidden = a.list("forbiddenTargets") ?: emptyList<Any?>()
            fun stripInternal(items: List<Map<String, Any?>>): List<Map<String, Any?>> = items.map { source ->
                when (source["_chatOp"]) {
                    "yield", "gesture" -> mapOf("op" to "snapshot")
                    else -> {
                        val clean = source.filterKeys { it != "_chatOp" }.toMutableMap()
                        if (source["op"] == "if") {
                            clean["then"] = stripInternal(AutomationPlan.list(source["then"]))
                            source["else"]?.let { clean["else"] = stripInternal(AutomationPlan.list(it)) }
                        } else if (source["op"] == "repeat") {
                            clean["steps"] = stripInternal(AutomationPlan.list(source["steps"]))
                        }
                        clean
                    }
                }
            }
            val normalized = convert(steps, 1)
            val checked = AutomationPlan.parse(mapOf(
                "schema" to 1, "id" to "chat-run", "name" to "Chat execution", "apps" to apps,
                "steps" to stripInternal(normalized), "forbiddenTargets" to forbidden,
                "limits" to mapOf("timeoutMs" to BUDGET_MS, "maxActions" to 500, "maxAiCalls" to 0),
            ))
            require(steps.isNotEmpty()) { "missing-steps" }
            Either.Right(RoutinePlan(checked.apps.first(), normalized.map { Step(it["op"] as String, it) }, 1,
                a.bool("dryRun") == true, 2, checked.apps, forbidden.filterIsInstance<String>(), send))
        } catch (e: IllegalArgumentException) { Either.Left(e.message ?: "invalid-run") }
          catch (e: IllegalStateException) { Either.Left(e.message ?: "invalid-run") }

        val CHAT_OPS = setOf("open", "tap", "type", "scroll", "wait", "assert", "snapshot", "observe", "key",
            "if", "repeat", "break", "set", "find", "read_text", "yield", "tap_xy", "input", "gesture")
        private val CHAT_FIELDS = setOf("schema", "app", "apps", "steps", "startAt", "dryRun", "forbiddenTargets", "send")

        private fun validateSelector(selector: Map<String, Any?>) {
            val textFields = setOf("text", "viewId", "description", "role", "parentText")
            AutomationPlan.known(selector, textFields + setOf("match", "enabled", "visible", "unique"))
            require(textFields.any { selector[it] is String && (selector[it] as String).isNotBlank() }) { "invalid-selector" }
            for (key in textFields) if (selector.containsKey(key)) AutomationPlan.string(selector, key)
            for (key in listOf("enabled", "visible", "unique")) require(!selector.containsKey(key) || selector[key] is Boolean) { "invalid-selector" }
            require(selector["visible"] != false && selector["unique"] != false) { "invalid-selector" }
            require(selector["match"] == null || selector["match"] in setOf("contains", "exact", "normalized")) { "invalid-match" }
        }
    }
}
