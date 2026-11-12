package io.github.ccch1mneyyy.dsh_app.phone

/** A bounded, single-app plan. Validation finishes before the first device operation. */
data class RoutinePlan(val app: String, val steps: List<Step>, val startAt: Int, val dryRun: Boolean) {
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
    }
}
