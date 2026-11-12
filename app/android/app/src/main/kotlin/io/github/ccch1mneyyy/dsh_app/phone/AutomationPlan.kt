package io.github.ccch1mneyyy.dsh_app.phone

/** Versioned, bounded task input. Authority lives in AutomationStore, never in this document. */
class AutomationPlan private constructor(
    val definition: Map<String, Any?>,
    val id: String,
    val name: String,
    val apps: Set<String>,
    val parameters: Map<String, Map<String, Any?>>,
    val steps: List<Map<String, Any?>>,
    val capabilities: Set<String>,
    val limits: Limits,
) {
    data class Limits(val continuous: Boolean, val timeoutMs: Long, val maxActions: Int, val maxAiCalls: Int)

    fun bind(values: Map<String, Any?>): Map<String, Any?> {
        require(values.keys.all { it in parameters }) { "unknown-parameter" }
        return parameters.mapNotNull { (key, spec) ->
            val value = if (values.containsKey(key)) values[key] else spec["default"]
            if (value == null) {
                require(spec["required"] != true) { "missing-parameter:$key" }
                null
            } else {
                validateValue(key, value, spec)
                key to value
            }
        }.toMap()
    }

    fun scopeAllows(values: Map<String, Any?>, scopes: Map<String, Any?>): Boolean =
        values.all { (key, value) ->
            val scope = scopes[key] as? Map<*, *> ?: return@all false
            if (scope["any"] == true) true
            else {
                val choices = scope["enum"] as? List<*>
                val min = scope["min"] as? Number
                val max = scope["max"] as? Number
                (choices != null || min != null || max != null) &&
                    (choices == null || choices.any { sameValue(it, value) }) &&
                    (min == null || value is Number && value.toDouble() >= min.toDouble()) &&
                    (max == null || value is Number && value.toDouble() <= max.toDouble())
            }
        }

    companion object {
        val PRIMITIVES = setOf("open", "tap", "type", "scroll", "key", "observe", "read_text", "plugin", "tap_xy", "input")
        val OPS = PRIMITIVES + setOf("snapshot", "assert", "wait", "if", "repeat", "set", "ai", "finish", "break")
        private val variable = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        // Android ICU requires literal closing braces to be escaped too.
        private val reference = Regex("\\$\\{([A-Za-z][A-Za-z0-9_]*)\\}")

        @Suppress("UNCHECKED_CAST")
        fun parse(input: Map<String, Any?>): AutomationPlan {
            require(Json.write(input).length <= 128_000) { "task-too-large" }
            // Round trip detaches caller-owned maps, including nested steps and defaults.
            val d = Json.parse(Json.write(input)) as Map<String, Any?>
            known(d, setOf("schema", "id", "name", "version", "apps", "parameters", "steps", "limits", "capabilities", "description", "forbiddenTargets"))
            require((d["schema"] as? Number)?.toInt() == 1) { "unsupported-schema" }
            val id = d["id"] as? String ?: error("missing-id")
            require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}").matches(id)) { "invalid-id" }
            val name = d["name"] as? String ?: error("missing-name")
            require(name.isNotBlank() && name.length <= 120) { "invalid-name" }
            val apps = (d["apps"] as? List<*>)?.map {
                require(it is String && Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it)) { "invalid-app" }
                it
            }?.toSet() ?: error("missing-apps")
            require(apps.size in 1..10) { "apps-limit" }
            if (d["forbiddenTargets"] != null) require(d["forbiddenTargets"] is List<*> && (d["forbiddenTargets"] as List<*>).all { it is String && it.isNotBlank() && it.length <= 200 }) { "invalid-forbidden-targets" }
            val parameters = obj(d["parameters"] ?: emptyMap<String, Any?>()).mapValues { (key, raw) ->
                require(variable.matches(key)) { "invalid-parameter" }
                val spec = obj(raw)
                known(spec, setOf("type", "default", "required", "enum", "min", "max", "maxLength", "label"))
                require(spec["type"] in setOf("string", "integer", "number", "boolean")) { "parameter-type:$key" }
                require(spec["required"] == null || spec["required"] is Boolean) { "parameter-required:$key" }
                if (spec["maxLength"] != null) bounded(spec, "maxLength", 0, 20_000, null)
                require(spec["enum"] == null || spec["enum"] is List<*> && (spec["enum"] as List<*>).size in 1..100) { "parameter-enum:$key" }
                for (bound in listOf("min", "max")) require(spec[bound] == null || finite(spec[bound])) { "parameter-bound:$key" }
                if (spec["min"] is Number && spec["max"] is Number) require((spec["min"] as Number).toDouble() <= (spec["max"] as Number).toDouble()) { "parameter-bound:$key" }
                spec["default"]?.let { validateValue(key, it, spec) }
                spec
            }
            require(parameters.size <= 30) { "parameters-limit" }
            val steps = list(d["steps"])
            val found = linkedSetOf<String>()
            var nodes = 0
            fun validateSteps(items: List<Map<String, Any?>>, depth: Int) {
                require(depth <= 4 && items.isNotEmpty()) { "steps-depth-or-empty" }
                for (s in items) {
                    require(++nodes <= 200) { "steps-limit" }
                    val op = s["op"] as? String ?: error("missing-op")
                    require(op in OPS) { "unsupported-op:$op" }
                    val fields = when (op) {
                        "open" -> setOf("app")
                        "tap" -> setOf("target", "selector", "long")
                        "type" -> setOf("target", "selector", "text", "submit")
                        "tap_xy" -> setOf("observation", "x", "y", "note")
                        "input" -> setOf("observation", "x", "y", "text", "submit", "note")
                        "scroll" -> setOf("direction", "until", "max")
                        "key" -> setOf("key")
                        "observe", "snapshot" -> emptySet()
                        "read_text" -> setOf("target", "selector", "name")
                        "plugin" -> setOf("tool", "arguments")
                        "assert" -> setOf("text", "match")
                        "wait" -> setOf("ms", "text", "timeoutMs", "match")
                        "if" -> setOf("condition", "then", "else")
                        "repeat" -> setOf("count", "steps", "until")
                        "set" -> setOf("name", "value")
                        "ai" -> setOf("prompt", "name", "repair")
                        "finish" -> setOf("result")
                        else -> emptySet()
                    }
                    known(s, fields + setOf("op", "requireText", "expect"))
                    for (flag in listOf("long", "submit", "repair")) require(s[flag] == null || s[flag] is Boolean) { "invalid-$flag" }
                    if (op in PRIMITIVES) found += op
                    when (op) {
                        "open" -> { string(s, "app"); val a = s["app"] as String; require(a in apps || reference.containsMatchIn(a)) { "app-out-of-scope" } }
                        "tap", "type", "read_text" -> {
                            require(s["target"] is String || s["selector"] is Map<*, *>) { "missing-target" }
                            if (op == "type") string(s, "text", allowEmpty = true)
                            if (op == "read_text") require(variable.matches(s["name"] as? String ?: "") && s["name"] !in parameters) { "invalid-variable" }
                        }
                        "scroll" -> { require(s["direction"] in setOf("up", "down", "left", "right")) { "invalid-direction" }; bounded(s, "max", 1, 20, 10) }
                        "tap_xy", "input" -> {
                            string(s, "observation")
                            bounded(s, "x", 0, 32_768, null); bounded(s, "y", 0, 32_768, null)
                            if (op == "input") string(s, "text", allowEmpty = true)
                        }
                        "key" -> require(s["key"] == "back") { "only-back-key" }
                        "plugin" -> {
                            val tool = string(s, "tool")
                            require(Regex("[A-Za-z0-9_./-]{1,160}").matches(tool)) { "invalid-plugin-tool" }
                            val args = obj(s["arguments"] ?: emptyMap<String, Any?>())
                            require(reference.findAll(Json.write(args)).all { it.groupValues[1] in parameters }) { "plugin-arguments-must-use-approved-parameters" }
                        }
                        "assert" -> string(s, "text")
                        "wait" -> {
                            require((s["ms"] != null) != (s["text"] != null)) { "wait-needs-ms-or-text" }
                            if (s["ms"] != null) bounded(s, "ms", 0, 120_000, 0) else string(s, "text")
                            bounded(s, "timeoutMs", 250, 30_000, 10_000)
                        }
                        "if" -> {
                            validateCondition(obj(s["condition"]))
                            validateSteps(list(s["then"]), depth + 1)
                            if (s["else"] != null && list(s["else"]).isNotEmpty()) validateSteps(list(s["else"]), depth + 1)
                        }
                        "repeat" -> {
                            bounded(s, "count", 1, 100, null)
                            s["until"]?.let { validateCondition(obj(it)) }
                            validateSteps(list(s["steps"]), depth + 1)
                        }
                        "set" -> require(variable.matches(s["name"] as? String ?: "") && s["name"] !in parameters) { "invalid-variable" }
                        "ai" -> { string(s, "prompt"); if (s["name"] != null) require(variable.matches(s["name"] as String) && s["name"] !in parameters) { "invalid-variable" } }
                    }
                    for (field in listOf("requireText", "expect")) if (s[field] != null) string(s, field)
                    if (s["match"] != null) require(s["match"] in setOf("contains", "exact", "normalized")) { "invalid-match" }
                }
            }
            validateSteps(steps, 1)
            val declared = (d["capabilities"] as? List<*>)?.map { require(it is String && it in PRIMITIVES) { "invalid-capability" }; it }?.toSet()
            val capabilities = declared ?: (found + "observe")
            require(found.all { it in capabilities }) { "undeclared-capability" }
            val l = obj(d["limits"] ?: emptyMap<String, Any?>())
            known(l, setOf("continuous", "timeoutMs", "maxActions", "maxAiCalls"))
            require(l["continuous"] == null || l["continuous"] is Boolean) { "continuous-boolean" }
            val continuous = l["continuous"] == true
            val timeout = bounded(l, "timeoutMs", 1_000, if (continuous) 7_200_000 else 1_800_000, if (continuous) 3_600_000 else 600_000).toLong()
            val actions = bounded(l, "maxActions", 1, 500, 500)
            val ai = bounded(l, "maxAiCalls", 0, if (continuous) 50 else 20, if (continuous) 50 else 20)
            return AutomationPlan(d, id, name, apps, parameters, steps, capabilities + "observe", Limits(continuous, timeout, actions, ai))
        }

        fun validateValue(key: String, value: Any, spec: Map<String, Any?>) {
            val valid = when (spec["type"]) {
                "string" -> value is String && value.length <= ((spec["maxLength"] as? Number)?.toInt() ?: 2000).coerceIn(0, 20_000)
                "integer" -> finite(value) && (value as Number).toDouble() == value.toLong().toDouble()
                "number" -> finite(value)
                "boolean" -> value is Boolean
                else -> false
            }
            require(valid) { "parameter-type:$key" }
            (spec["enum"] as? List<*>)?.let { require(it.any { choice -> sameValue(choice, value) }) { "parameter-enum:$key" } }
            if (value is Number) {
                (spec["min"] as? Number)?.let { require(value.toDouble() >= it.toDouble()) { "parameter-min:$key" } }
                (spec["max"] as? Number)?.let { require(value.toDouble() <= it.toDouble()) { "parameter-max:$key" } }
            }
        }

        fun sameValue(a: Any?, b: Any?): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
        fun finite(v: Any?): Boolean = v is Number && v.toDouble().isFinite()
        fun known(m: Map<String, Any?>, fields: Set<String>) { require(m.keys.all { it in fields }) { "unknown-fields:" + (m.keys - fields).joinToString(",") } }
        fun string(m: Map<String, Any?>, key: String, allowEmpty: Boolean = false): String =
            (m[key] as? String)?.takeIf { (allowEmpty || it.isNotBlank()) && it.length <= 4000 } ?: error("invalid-$key")

        fun bounded(m: Map<String, Any?>, key: String, min: Int, max: Int, default: Int?): Int {
            val v = m[key] ?: return default ?: error("missing-$key")
            require(v is Number && finite(v) && v.toDouble() == v.toInt().toDouble() && v.toInt() in min..max) { "invalid-$key" }
            return v.toInt()
        }

        fun obj(raw: Any?): Map<String, Any?> {
            require(raw is Map<*, *> && raw.keys.all { it is String }) { "expected-object" }
            return raw.entries.associate { it.key as String to it.value }
        }
        fun list(raw: Any?): List<Map<String, Any?>> {
            require(raw is List<*>) { "expected-steps" }
            return raw.map(::obj)
        }
        fun validateCondition(m: Map<String, Any?>) {
            known(m, setOf("text", "match", "not", "variable", "equals"))
            require((m["text"] is String) != (m["variable"] is String)) { "invalid-condition" }
            if (m["text"] != null) string(m, "text")
            if (m["variable"] != null) require(variable.matches(m["variable"] as String)) { "invalid-variable" }
            require(m["match"] == null || m["match"] in setOf("contains", "exact", "normalized")) { "invalid-match" }
            require(m["not"] == null || m["not"] is Boolean) { "invalid-not" }
        }

        fun substitute(raw: Any?, values: Map<String, Any?>): Any? = when (raw) {
            is String -> {
                val exact = reference.matchEntire(raw)
                if (exact != null) {
                    require(values.containsKey(exact.groupValues[1])) { "unknown-variable:" + exact.groupValues[1] }
                    values[exact.groupValues[1]]
                } else reference.replace(raw) {
                    require(values.containsKey(it.groupValues[1])) { "unknown-variable:" + it.groupValues[1] }
                    values[it.groupValues[1]].toString()
                }
            }
            is Map<*, *> -> raw.entries.associate { it.key.toString() to substitute(it.value, values) }
            is List<*> -> raw.map { substitute(it, values) }
            else -> raw
        }

        fun matches(actual: String, expected: String, mode: String = "contains"): Boolean = when (mode) {
            "exact" -> actual.lineSequence().any { it == expected }
            "normalized" -> actual.replace(Regex("\\s+"), " ").trim().contains(expected.replace(Regex("\\s+"), " ").trim())
            else -> actual.contains(expected)
        }
    }
}
