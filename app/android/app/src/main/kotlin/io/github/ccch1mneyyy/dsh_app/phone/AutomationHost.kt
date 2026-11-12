package io.github.ccch1mneyyy.dsh_app.phone

import java.io.File

/** Android wiring for the plain Kotlin runner. All input still passes through the phone core. */
internal class AutomationHost(
    directory: File,
    private val core: PhoneCore,
    private val tasks: PhoneTasks,
    private val env: PhoneEnv,
    private val changed: () -> Unit,
) {
    private var held = false
    private val evidence = AutomationEvidence(File(directory, "evidence"))
    private val service: AutomationService = AutomationService(directory, ::execute, changed)

    val active: Boolean get() = service.isRunning

    @Synchronized fun request(action: String, args: Map<String, Any?>, trusted: Boolean): Map<String, Any?> {
        if (action in setOf("set_evidence", "clear_evidence", "evidence")) {
            if (!trusted) return mapOf("ok" to false, "reason" to "user-approval-required")
            return try {
                when (action) {
                    "set_evidence" -> {
                        require(args["enabled"] is Boolean) { "invalid-enabled" }
                        evidence.setEnabled(args["enabled"] == true)
                    }
                    "clear_evidence" -> evidence.clear()
                }
                changed()
                mapOf("ok" to true, "evidenceEnabled" to evidence.enabled(), "evidence" to evidence.list())
            } catch (_: Exception) { mapOf("ok" to false, "reason" to "evidence-unavailable") }
        }
        if (action in setOf("start", "start_once") && !active) {
            if (!env.enabled()) return mapOf("ok" to false, "reason" to "phone-disabled")
            if (tasks.callsInFlight > 0 || tasks.snapshot().paused)
                return mapOf("ok" to false, "reason" to "phone-busy")
            if (tasks.isStopped() && !trusted) return mapOf("ok" to false, "reason" to "phone-stopped")
            tasks.clearStop()
            tasks.callStarted()
            held = true
            if (tasks.begin() !is PhoneTasks.Begin.Go) {
                releaseIfFinished()
                return mapOf("ok" to false, "reason" to "phone-stopped")
            }
        }
        val result = service.request(action, args, trusted)
        if (result["ok"] == true && action in setOf("pause", "resume")) tasks.setPaused(action == "pause")
        releaseIfFinished()
        return if (trusted && action in setOf("list", "status")) result + mapOf(
            "evidenceEnabled" to evidence.enabled(), "evidence" to evidence.list(),
        ) else result
    }

    @Synchronized fun tick() {
        if (held) {
            val status = tasks.snapshot()
            if (status.stopped) control("stop")
            else if (status.paused && active) control("pause")
            else if (this.status()["state"] == "paused") tasks.setPaused(true)
        }
        releaseIfFinished()
    }

    @Synchronized private fun releaseIfFinished() {
        if (held && !active) {
            held = false
            tasks.callEnded(null)
            tasks.finish()
            changed()
        }
    }

    private fun control(action: String): Boolean {
        val run = status()
        val id = run["runId"] as? String ?: return false
        return request(action, mapOf("runId" to id), trusted = true)["ok"] == true
    }

    fun pause(): Boolean = active && control("pause")
    fun resume(): Boolean = active && control("resume")
    fun stop() {
        if (active) control("stop")
        synchronized(this) { releaseIfFinished() }
    }

    private fun status(): Map<*, *> = service.request("status", emptyMap())["run"] as? Map<*, *> ?: emptyMap<String, Any?>()

    @Synchronized private fun pauseCheckpoint(expected: Map<*, *>) {
        val run = status()
        if (run["runId"] == expected["runId"] && run["checkpoint"] == expected["checkpoint"] &&
            run["dispatchEpoch"] == expected["dispatchEpoch"] && run["state"] == "awaiting_ai") control("pause")
    }

    /** AI reads are bound to the pending checkpoint and the same approved main-screen apps. */
    fun read(args: Map<String, Any?>): ToolOutput {
        val run = status()
        if (run["state"] != "awaiting_ai" || args["background"] == true)
            return ToolOutput("automation-busy: 仅当前 AI 检查点可读取任务允许的主屏应用。", isError = true)
        val allowed = (run["apps"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val driver = env.driver(PhoneScreen.MAIN)
            ?: return ToolOutput("observation-unavailable", isError = true)
        fun admitted(): Boolean {
            val now = status()
            return now["runId"] == run["runId"] && now["checkpoint"] == run["checkpoint"] &&
                now["state"] == "awaiting_ai" && now["dispatchEpoch"] == run["dispatchEpoch"] &&
                driver.foregroundPackage() in allowed && !env.isLocked()
        }
        if (!admitted()) {
            pauseCheckpoint(run)
            return ToolOutput("app-out-of-scope-or-interrupted", isError = true)
        }
        val observed = core.automationCall(PhoneToolCatalog.SCREEN, args, ::admitted)
        if (!admitted() || observed.screen?.packageName !in allowed) {
            pauseCheckpoint(run)
            return ToolOutput("app-out-of-scope-or-interrupted", isError = true)
        }
        if (!observed.output.isError) evidence.capture(run["runId"] as String, "ai", observed.screen)
        return observed.output
    }

    private fun execute(op: String, args: Map<String, Any?>): AutomationRunner.ActionResult {
        val result = executeStep(op, args)
        if (!result.ok && evidence.enabled()) runCatching {
            val allowed = (args["_apps"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
            val driver = env.driver(PhoneScreen.MAIN) ?: return@runCatching
            fun admitted(): Boolean {
                val run = status()
                return run["runId"] == args["_runId"] && run["generation"] == args["_generation"] &&
                    run["dispatchEpoch"] == args["_dispatchEpoch"] && run["state"] == "running" &&
                    !env.isLocked() && driver.foregroundPackage() in allowed
            }
            if (!admitted()) return@runCatching
            val observed = core.automationCall(PhoneToolCatalog.SCREEN, mapOf("image" to true), ::admitted)
            if (admitted() && !observed.output.isError && observed.screen?.packageName in allowed)
                evidence.capture(args["_runId"] as String, "failure", observed.screen)
        }
        return result
    }

    private fun executeStep(op: String, args: Map<String, Any?>): AutomationRunner.ActionResult {
        val expectedRun = args["_runId"]
        fun runnable(): Boolean {
            val run = status()
            return run["runId"] == expectedRun && run["generation"] == args["_generation"] &&
                run["dispatchEpoch"] == args["_dispatchEpoch"] && run["state"] == "running" &&
                !Thread.currentThread().isInterrupted
        }
        if (!runnable() || env.isLocked())
            return AutomationRunner.ActionResult(false, if (env.isLocked()) "device-locked" else "stopped")
        val allowed = (args["_apps"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val forbidden = (args["_forbiddenTargets"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val driver = env.driver(PhoneScreen.MAIN)
            ?: return AutomationRunner.ActionResult(false, "observation-unavailable")
        if (op == "open") {
            val pkg = args["app"] as? String ?: return AutomationRunner.ActionResult(false, "missing-app")
            if (pkg !in allowed) return AutomationRunner.ActionResult(false, "app-out-of-scope")
            return scoped(core.automationCall(PhoneToolCatalog.OPEN, mapOf("app" to pkg), ::runnable), allowed, writing = true)
        }
        val foreground = driver.foregroundPackage()
        if (foreground !in allowed) return AutomationRunner.ActionResult(false, "app-out-of-scope", packageName = foreground)
        // A new screen read would retire the exact screenshot observation supplied by the AI.
        if (op in setOf("tap_xy", "input")) {
            if (forbidden.isNotEmpty()) return AutomationRunner.ActionResult(false, "coordinate-boundary-unknown")
            if (PhoneScreen.ofScreenId(args["observation"] as? String ?: "") != PhoneScreen.MAIN)
                return AutomationRunner.ActionResult(false, "observation-stale")
            val wire = args.filterKeys { it in setOf("observation", "x", "y", "text", "submit") }
            val tool = if (op == "input") PhoneToolCatalog.INPUT else PhoneToolCatalog.TAP_XY
            return scoped(core.automationCall(tool, wire) { runnable() && driver.foregroundPackage() in allowed }, allowed, writing = true)
        }
        val observed = core.automationCall(PhoneToolCatalog.SCREEN,
            mapOf("image" to (args["image"] == true || op == "snapshot" && evidence.enabled())), ::runnable)
        val snap = observed.screen ?: return convert(observed)
        if (snap.packageName !in allowed) return AutomationRunner.ActionResult(false, "app-out-of-scope")
        if (observed.output.isError) return convert(observed)
        if (snap.observationReason != null) return AutomationRunner.ActionResult(false, snap.observationReason)
        if (op == "observe" || op == "snapshot") {
            if (op == "snapshot") evidence.capture(expectedRun as String, "snapshot", snap)
            return convert(observed)
        }
        val selector = args["selector"] as? Map<*, *>
        val target = args["target"] as? String
            ?: selector?.get("text") as? String ?: selector?.get("viewId") as? String
            ?: selector?.get("description") as? String
        if (target != null && forbidden.any { target.contains(it, ignoreCase = true) })
            return AutomationRunner.ActionResult(false, "task-boundary")
        val tool: String
        val wire = linkedMapOf<String, Any?>("screen" to snap.id)
        when (op) {
            "tap", "type", "read_text" -> {
                if (target == null && selector == null) return AutomationRunner.ActionResult(false, "missing-target")
                val found = if (selector != null) driver.select(snap.id, selector.entries.associate { it.key.toString() to it.value })
                    else driver.lookup(snap.id, target!!)
                val id = found.elementId
                    ?: return AutomationRunner.ActionResult(false, found.reasonCode ?: "target-missing")
                val info = driver.target(snap.id, id)
                    ?: return AutomationRunner.ActionResult(false, "observation-stale")
                if (forbidden.any { info.label.contains(it, true) || info.viewId?.contains(it, true) == true })
                    return AutomationRunner.ActionResult(false, "task-boundary")
                if (op == "read_text") return AutomationRunner.ActionResult(
                    true, "ok", snap.id, info.label, snap.packageName, data = mapOf("value" to info.label),
                )
                wire["id"] = id
                if (op == "tap") {
                    tool = PhoneToolCatalog.TAP
                    wire["long"] = args["long"] == true
                } else {
                    tool = PhoneToolCatalog.TYPE
                    wire["text"] = args["text"]
                    wire["submit"] = args["submit"] == true
                }
            }
            "scroll" -> {
                tool = PhoneToolCatalog.SCROLL
                wire["direction"] = args["direction"]
                args["until"]?.let { wire["until"] = it }
                args["max"]?.let { wire["max"] = it }
            }
            "key" -> { tool = PhoneToolCatalog.KEY; wire["key"] = args["key"] }
            else -> return AutomationRunner.ActionResult(false, "unsupported-op")
        }
        if (!runnable() || driver.foregroundPackage() !in allowed)
            return AutomationRunner.ActionResult(false, "interrupted")
        return scoped(core.automationCall(tool, wire) { runnable() && driver.foregroundPackage() in allowed }, allowed, writing = true)
    }

    private fun scoped(result: PhoneCore.AutomationObservation, allowed: Set<String>, writing: Boolean): AutomationRunner.ActionResult {
        if (result.screen != null && result.screen.packageName !in allowed)
            return AutomationRunner.ActionResult(false, "app-out-of-scope", mayHaveActed = writing)
        return convert(result, writing)
    }

    private fun convert(result: PhoneCore.AutomationObservation, writing: Boolean = false): AutomationRunner.ActionResult {
        val snap = result.screen
        return AutomationRunner.ActionResult(
            ok = !result.output.isError,
            reason = result.reason ?: if (result.output.isError) "device-error" else "ok",
            screenId = snap?.id,
            text = snap?.visibleTexts?.joinToString("\n") ?: snap?.text,
            packageName = snap?.packageName,
            mayHaveActed = writing && result.reason !in setOf("target-missing", "target-ambiguous", "target-disabled", "observation-stale", "stopped", "unavailable") &&
                result.reason?.startsWith("refused:") != true,
        )
    }
}
