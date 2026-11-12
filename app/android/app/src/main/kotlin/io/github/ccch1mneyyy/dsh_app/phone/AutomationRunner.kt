package io.github.ccch1mneyyy.dsh_app.phone

import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One UI run, with local control flow and explicit, generation-bound AI checkpoints. */
class AutomationRunner(
    private val execute: (String, Map<String, Any?>) -> ActionResult,
    private val changed: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class ActionResult(
        val ok: Boolean,
        val reason: String = if (ok) "ok" else "failed",
        val screenId: String? = null,
        val text: String? = null,
        val packageName: String? = null,
        val mayHaveActed: Boolean = false,
        val data: Map<String, Any?> = emptyMap(),
    )

    private data class Work(val step: Map<String, Any?>, val loopId: String? = null)
    private val monitor = Object()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-automation").apply { isDaemon = true } }
    private var generation = 0L
    private var dispatchEpoch = 0L
    private var runId: String? = null
    private var state = "idle"
    private var reason = ""
    private var plan: AutomationPlan? = null
    private var version = 0
    private var started = 0L
    private var ended = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var completed = 0
    private var actions = 0
    private var aiCalls = 0
    private var repairs = 0
    private var checkpoint: String? = null
    private var prompt: String? = null
    private var kind: String? = null
    private var pending: Map<String, Any?>? = null
    private var result: Any? = null
    private var current: String? = null
    private var needsCheck = false
    private var aiName: String? = null
    private var aiWork: Work? = null
    private var repairPending = false
    private var ticks = 0
    private var authority: () -> Boolean = { true }
    private var boundParameters: Map<String, Any?> = emptyMap()
    private val values = linkedMapOf<String, Any?>()
    private val queue = ArrayDeque<Work>()

    fun active(): Boolean = synchronized(monitor) { state in ACTIVE }

    fun recheckAuthority() {
        synchronized(monitor) {
            if (state in ACTIVE && !authority()) pause(runId!!, "authorization-revoked")
        }
    }

    fun status(): Map<String, Any?> = synchronized(monitor) {
        linkedMapOf(
            "runId" to runId, "generation" to generation, "dispatchEpoch" to dispatchEpoch,
            "taskId" to plan?.id, "name" to plan?.name, "version" to version,
            "state" to state, "reason" to reason, "current" to current, "completed" to completed,
            "actions" to actions, "aiCalls" to aiCalls, "repairs" to repairs, "repairRounds" to repairs,
            "elapsedMs" to elapsed(), "isActive" to (state in ACTIVE), "apps" to plan?.apps?.toList(),
            "capabilities" to plan?.capabilities?.toList(),
            "forbiddenTargets" to (plan?.definition?.get("forbiddenTargets") as? List<*>)?.toList().orEmpty(),
            "limits" to plan?.limits?.let { mapOf("continuous" to it.continuous, "timeoutMs" to it.timeoutMs, "maxActions" to it.maxActions, "maxAiCalls" to it.maxAiCalls) },
            "checkpoint" to checkpoint, "prompt" to prompt, "kind" to kind, "pending" to pending, "result" to result,
        )
    }

    fun taskSnapshot(id: String): Map<String, Any?> = synchronized(monitor) {
        requireRun(id)
        val currentPlan = plan ?: error("run-not-found")
        AutomationPlan.obj(Json.parse(Json.write(mapOf(
            "id" to currentPlan.id, "name" to currentPlan.name, "version" to version,
            "definition" to currentPlan.definition, "boundParameters" to boundParameters,
        ))))
    }

    fun start(task: Map<String, Any?>, bound: Map<String, Any?>, allowed: () -> Boolean = { true }): Map<String, Any?> {
        val parsed = AutomationPlan.parse(AutomationPlan.obj(task["definition"]))
        val token: Long
        synchronized(monitor) {
            require(state !in ACTIVE) { "ui-busy" }
            generation++
            dispatchEpoch++
            token = generation
            runId = UUID.randomUUID().toString()
            plan = parsed
            version = (task["version"] as? Number)?.toInt() ?: 1
            state = "running"; reason = ""; current = null; result = null
            started = clock(); ended = 0; pausedAt = 0; pausedTotal = 0
            completed = 0; actions = 0; aiCalls = 0; repairs = 0
            ticks = 0; aiWork = null; repairPending = false
            authority = allowed
            boundParameters = bound.toMap()
            checkpoint = null; prompt = null; kind = null; pending = null; needsCheck = false
            values.clear(); values.putAll(bound)
            queue.clear(); append(parsed.steps)
        }
        worker.execute { run(token) }
        changed()
        return status()
    }

    fun pause(id: String, pauseReason: String = "user"): Map<String, Any?> {
        synchronized(monitor) {
            requireRun(id)
            if (state in ACTIVE && state != "paused") {
                val previous = state
                val previousKind = kind
                dispatchEpoch++
                pausedAt = clock()
                state = "paused"; reason = pauseReason
                // A pending AI response loses authority once the person intervenes.
                if (previous == "awaiting_ai" && previousKind == "decision") aiWork?.let { queue.addFirst(it) }
                if (previous == "awaiting_ai" && previousKind == "plugin") { reason = "result-uncertain"; repairPending = true }
                checkpoint = null; prompt = null; kind = null; aiWork = null
                needsCheck = true
                monitor.notifyAll()
            }
        }
        changed()
        return status()
    }

    fun resume(id: String): Map<String, Any?> {
        synchronized(monitor) {
            requireRun(id)
            require(state == "paused") { "not-paused" }
            require(reason !in setOf("result-uncertain", "ai-budget", "action-budget", "time-budget", "repair-budget", "step-budget") && !repairPending) { "requires-new-task-or-inspection" }
            dispatchEpoch++
            pausedTotal += (clock() - pausedAt).coerceAtLeast(0)
            pausedAt = 0; state = "running"; reason = ""; needsCheck = true
            monitor.notifyAll()
        }
        changed()
        return status()
    }

    fun stop(id: String): Map<String, Any?> {
        synchronized(monitor) {
            requireRun(id)
            generation++
            dispatchEpoch++
            ended = clock()
            state = "stopped"; reason = "user"
            checkpoint = null; prompt = null; kind = null; pending = null; queue.clear()
            monitor.notifyAll()
        }
        changed()
        return status()
    }

    fun answer(id: String, token: String, steps: List<Map<String, Any?>>?, value: Any?, repair: Boolean): Map<String, Any?> {
        synchronized(monitor) {
            requireRun(id)
            require(state == "awaiting_ai" && token == checkpoint) { "stale-checkpoint" }
            require((kind == "repair") == repair) { "checkpoint-kind" }
            if (steps != null && steps.isNotEmpty()) {
                val p = plan!!
                val patch = AutomationPlan.parse(p.definition + ("steps" to steps))
                require(patch.apps == p.apps && patch.capabilities.all { it in p.capabilities }) { "repair-out-of-scope" }
                require(pluginCalls(patch.steps).all { it in pluginCalls(p.steps) }) { "plugin-out-of-scope" }
            }
            if (repair) {
                require(steps != null && steps.isNotEmpty()) { "repair-needs-steps" }
                // Replacement describes the remaining workflow, never an implicit replay.
                queue.clear()
            }
            if (steps != null) prepend(steps)
            aiName?.let { values[it] = value }
            aiName = null; aiWork = null; repairPending = false
            state = "running"; reason = ""; checkpoint = null; prompt = null; kind = null; pending = null
            monitor.notifyAll()
        }
        changed()
        return status()
    }

    private fun run(token: Long) {
        try {
            while (true) {
                val work = synchronized(monitor) {
                    if (!ready(token)) return
                    if (queue.isEmpty()) {
                        ended = clock(); state = "completed"; reason = "done"; current = null
                        null
                    } else queue.removeFirst().also { current = it.step["op"] as? String }
                }
                if (work == null) { changed(); return }
                if (needsCheck) {
                    val observed = observe(token)
                    if (!observed.ok) { suspendFailure(token, work, observed); continue }
                    needsCheck = false
                }
                try {
                    if (++ticks > 5000) throw StepFailure(ActionResult(false, "step-budget"))
                    perform(token, work)
                    synchronized(monitor) { if (generation == token && state == "running") completed++ }
                } catch (e: StepFailure) {
                    suspendFailure(token, work, e.output)
                } catch (e: IllegalArgumentException) {
                    suspendFailure(token, work, ActionResult(false, e.message ?: "invalid-runtime"))
                } catch (e: IllegalStateException) {
                    suspendFailure(token, work, ActionResult(false, e.message ?: "invalid-runtime"))
                }
                changed()
            }
        } catch (_: InterruptedException) {
            synchronized(monitor) { if (generation == token) { ended = clock(); state = "interrupted"; reason = "interrupted"; checkpoint = null } }
            Thread.currentThread().interrupt()
            changed()
        } catch (e: Exception) {
            synchronized(monitor) { if (generation == token) { state = "paused"; pausedAt = clock(); reason = "invalid-runtime:" + (e.message ?: e.javaClass.simpleName); checkpoint = null; needsCheck = true } }
            changed()
        }
    }

    private fun perform(token: Long, work: Work) {
        val context = synchronized(monitor) { values.toMap() }
        // Child blocks are evaluated when reached, after their preceding set/read steps ran.
        val s = work.step.mapValues { (key, value) ->
            if (key in setOf("steps", "then", "else")) value else AutomationPlan.substitute(value, context)
        }
        val op = s["op"] as String
        var writeDispatched = false
        s["requireText"]?.let { checkText(token, it as String, "contains", 0) }
        when (op) {
            "if" -> {
                val branch = if (condition(token, AutomationPlan.obj(s["condition"]))) s["then"] else s["else"]
                if (branch != null) synchronized(monitor) { prepend(AutomationPlan.list(branch), work.loopId) }
            }
            "repeat" -> {
                val loop = UUID.randomUUID().toString()
                val count = (s["count"] as Number).toInt()
                val body = AutomationPlan.list(s["steps"])
                val expanded = ArrayList<Work>()
                repeat(count) {
                    s["until"]?.let { condition -> expanded += Work(mapOf("op" to "__until", "condition" to condition), loop) }
                    expanded += body.map { Work(it, loop) }
                }
                synchronized(monitor) { expanded.asReversed().forEach(queue::addFirst) }
            }
            "__until" -> if (condition(token, AutomationPlan.obj(s["condition"]))) synchronized(monitor) { queue.removeIf { it.loopId == work.loopId } }
            "break" -> { require(work.loopId != null) { "break-outside-loop" }; synchronized(monitor) { queue.removeIf { it.loopId == work.loopId } } }
            "set" -> synchronized(monitor) { values[s["name"] as String] = s["value"] }
            "finish" -> synchronized(monitor) { result = s["result"]; queue.clear() }
            "ai", "plugin" -> {
                synchronized(monitor) {
                    if (op == "plugin" && !authority()) throw StepFailure(ActionResult(false, "authorization-revoked"))
                    if (aiCalls >= plan!!.limits.maxAiCalls) throw StepFailure(ActionResult(false, "ai-budget"))
                    aiCalls++
                    if (op == "plugin") {
                        if (actions >= plan!!.limits.maxActions) throw StepFailure(ActionResult(false, "action-budget"))
                        actions++
                    }
                    state = "awaiting_ai"; kind = if (op == "plugin") "plugin" else "decision"
                    reason = "ai-required"; checkpoint = UUID.randomUUID().toString()
                    aiWork = work; repairPending = false
                    aiName = s["name"] as? String
                    pending = if (op == "plugin") s else null
                    prompt = if (op == "plugin") "Call only the declared plugin tool and arguments, inspect its actual receipt, do not replay an uncertain write: " + Json.write(s - "op")
                        else s["prompt"] as String
                }
                return
            }
            "assert" -> checkText(token, s["text"] as String, s["match"] as? String ?: "contains", 0)
            "wait" -> {
                if (s["ms"] != null) waitUntil(token, clock() + (s["ms"] as Number).toLong())
                else checkText(token, s["text"] as String, s["match"] as? String ?: "contains", (s["timeoutMs"] as? Number)?.toLong() ?: 10_000)
            }
            else -> {
                require(op in plan!!.capabilities || op == "snapshot") { "capability-out-of-scope" }
                if (op == "open") require(s["app"] in plan!!.apps) { "app-out-of-scope" }
                if (op !in setOf("observe", "snapshot", "read_text")) synchronized(monitor) {
                    if (actions >= plan!!.limits.maxActions) throw StepFailure(ActionResult(false, "action-budget"))
                    actions++
                }
                val output = invoke(token, op, s - "op")
                if (!output.ok) throw StepFailure(output)
                if (output.packageName != null && output.packageName !in plan!!.apps) {
                    throw StepFailure(ActionResult(false, "app-out-of-scope", mayHaveActed = op !in setOf("observe", "snapshot", "read_text")))
                }
                writeDispatched = op !in setOf("observe", "snapshot", "read_text")
                if (op == "read_text") synchronized(monitor) { values[s["name"] as String] = output.data["value"] ?: output.text }
            }
        }
        s["expect"]?.let {
            try { checkText(token, it as String, "contains", 10_000) }
            catch (e: StepFailure) { throw StepFailure(e.output.copy(mayHaveActed = writeDispatched || e.output.mayHaveActed)) }
        }
    }

    private fun observe(token: Long): ActionResult = invoke(token, "observe", emptyMap()).let {
        if (it.ok && it.packageName !in plan!!.apps) ActionResult(false, "app-out-of-scope") else it
    }

    private fun condition(token: Long, c: Map<String, Any?>): Boolean {
        val yes = if (c["variable"] != null) AutomationPlan.sameValue(values[c["variable"]], c["equals"])
        else {
            val read = observe(token)
            if (!read.ok) throw StepFailure(read)
            AutomationPlan.matches(read.text.orEmpty(), c["text"] as String, c["match"] as? String ?: "contains")
        }
        return if (c["not"] == true) !yes else yes
    }

    private fun checkText(token: Long, text: String, match: String, timeout: Long) {
        val deadline = clock() + timeout
        while (true) {
            val read = observe(token)
            if (!read.ok) throw StepFailure(read)
            if (AutomationPlan.matches(read.text.orEmpty(), text, match)) return
            if (clock() >= deadline) throw StepFailure(ActionResult(false, if (timeout == 0L) "assertion" else "target-timeout"))
            waitUntil(token, minOf(deadline, clock() + 250))
        }
    }

    private fun invoke(token: Long, op: String, args: Map<String, Any?>): ActionResult {
        val dispatch = synchronized(monitor) {
            if (!ready(token)) throw InterruptedException()
            mapOf("_runId" to runId, "_generation" to token, "_dispatchEpoch" to dispatchEpoch,
                "_apps" to plan!!.apps.toList(), "_allowedApps" to plan!!.apps.toList(),
                "_forbiddenTargets" to (plan!!.definition["forbiddenTargets"] ?: emptyList<String>()))
        }
        if (op !in setOf("observe", "snapshot", "read_text") && !authority()) throw StepFailure(ActionResult(false, "authorization-revoked"))
        val scopeArgs = args + dispatch
        val output = execute(op, scopeArgs)
        synchronized(monitor) {
            if (generation != token || state == "stopped") throw InterruptedException()
            if (state == "paused") needsCheck = true
        }
        return output
    }

    private fun suspendFailure(token: Long, work: Work, out: ActionResult) {
        synchronized(monitor) {
            if (generation != token) return
            reason = if (out.mayHaveActed) "result-uncertain" else out.reason
            pending = mapOf("op" to work.step["op"], "mayHaveActed" to out.mayHaveActed, "reason" to out.reason)
            if (!out.mayHaveActed && reason in setOf("app-out-of-scope", "device-locked", "observation-unavailable", "root-unavailable", "tree-unavailable", "user-interrupted", "interrupted", "stopped")) {
                // An outside interruption is not a broken workflow. Recheck before this node on user resume.
                queue.addFirst(work)
                state = "paused"; pausedAt = clock(); dispatchEpoch++
                checkpoint = null; prompt = null; kind = null; aiWork = null
                repairPending = false; needsCheck = true
                monitor.notifyAll()
                return@synchronized
            }
            repairPending = true; aiWork = null
            // Any replacement is explicitly supplied; never retry this failed step implicitly.
            if (reason in setOf("ai-budget", "action-budget", "time-budget", "step-budget", "authorization-revoked") || repairs >= 2 || out.mayHaveActed) {
                pausedAt = clock(); state = "paused"; kind = null; checkpoint = null
            } else {
                repairs++; aiCalls++
                if (aiCalls > plan!!.limits.maxAiCalls) {
                    state = "paused"; pausedAt = clock(); reason = "ai-budget"; checkpoint = null
                } else {
                    state = "awaiting_ai"; kind = "repair"; checkpoint = UUID.randomUUID().toString()
                    prompt = "Inspect the current screen first. Repair only the remaining workflow within the original apps/capabilities. Never replay uncertain sends/submits. Failure: " + Json.write(pending)
                }
            }
        }
        changed()
    }

    private fun waitUntil(token: Long, deadline: Long) {
        while (true) synchronized(monitor) {
            if (!ready(token)) throw InterruptedException()
            val left = deadline - clock()
            if (left <= 0) return
            monitor.wait(minOf(left, 100))
        }
    }

    private fun ready(token: Long): Boolean {
        while (generation == token && state in ACTIVE) {
            if (state != "paused" && elapsed() >= plan!!.limits.timeoutMs) {
                state = "paused"; reason = "time-budget"; pausedAt = clock(); checkpoint = null
            }
            if (state == "running") return true
            monitor.wait(100)
        }
        return false
    }

    private fun elapsed(): Long = if (runId == null) 0 else ((if (state == "paused") pausedAt else if (ended > 0) ended else clock()) - started - pausedTotal).coerceAtLeast(0)
    private fun requireRun(id: String) { require(id == runId && runId != null) { "run-not-found" } }
    private fun append(steps: List<Map<String, Any?>>) { steps.forEach { queue.addLast(Work(it)) } }
    private fun prepend(steps: List<Map<String, Any?>>, loop: String? = null) { steps.asReversed().forEach { queue.addFirst(Work(it, loop)) } }
    private fun pluginCalls(steps: List<Map<String, Any?>>): Set<String> = buildSet {
        for (s in steps) {
            if (s["op"] == "plugin") {
                val bound = AutomationPlan.substitute(mapOf("tool" to s["tool"], "arguments" to (s["arguments"] ?: emptyMap<String, Any?>())), values)
                add(Json.write(bound))
            }
            for (key in listOf("then", "else", "steps")) (s[key] as? List<*>)?.let { addAll(pluginCalls(AutomationPlan.list(it))) }
        }
    }
    private class StepFailure(val output: ActionResult) : Exception()
    companion object { private val ACTIVE = setOf("running", "paused", "awaiting_ai") }
}

/** Map contract shared by the native settings channel and the phone MCP task tool. */
class AutomationService(
    directory: File,
    execute: (String, Map<String, Any?>) -> AutomationRunner.ActionResult,
    private val onChange: () -> Unit = {},
) {
    private val store = AutomationStore(directory)
    private val runFile = File(directory, "automation-runs-v1.json")
    @Volatile private var persistenceError: String? = null
    @Volatile private var records = try { if (runFile.exists()) {
        val raw = Json.parse(runFile.readText()) as? List<*> ?: error("run-store-corrupt")
        raw.map(AutomationPlan::obj).map { record ->
            if (record["state"] in setOf("running", "paused", "awaiting_ai")) record + mapOf("state" to "interrupted", "reason" to "process-interrupted", "isActive" to false) else record
        }
    } else emptyList() } catch (_: Exception) { persistenceError = "run-store-unavailable"; emptyList() }
    private val persistLock = Object()
    private val runner = AutomationRunner(execute, ::changed)
    val isRunning: Boolean get() = runner.active()

    private fun visibleStatus(): Map<String, Any?> = runner.status().let { if (it["runId"] == null) records.lastOrNull() ?: it else it }

    private fun changed() {
        val status = runner.status()
        if (status["runId"] != null) {
            try { synchronized(persistLock) {
                val previous = records.lastOrNull { it["runId"] == status["runId"] }
                // Save transitions and periodic progress, without persisting page/AI/message contents.
                if (previous == null || previous["state"] != status["state"] ||
                    ((status["completed"] as? Number)?.toInt() ?: 0) - ((previous["completed"] as? Number)?.toInt() ?: 0) >= 5) {
                    val now = System.currentTimeMillis()
                    val safe = status.filterKeys { it in setOf("runId", "taskId", "name", "version", "state", "reason", "completed", "actions", "aiCalls", "repairs", "repairRounds", "elapsedMs", "isActive", "current") } + ("updatedAt" to now)
                    records = (records.filter { it["runId"] != safe["runId"] && (it["updatedAt"] as? Number)?.toLong()?.let { stamp -> stamp >= now - 7 * 86_400_000L } == true } + safe).takeLast(100)
                    val tmp = File(runFile.parentFile, "automation-runs-v1.tmp")
                    tmp.outputStream().use { stream -> stream.write(Json.write(records).toByteArray(Charsets.UTF_8)); stream.fd.sync() }
                    Files.move(tmp.toPath(), runFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            } } catch (_: Exception) { persistenceError = "run-store-unavailable" }
        }
        onChange()
    }

    fun request(action: String, args: Map<String, Any?> = emptyMap(), trusted: Boolean = false): Map<String, Any?> = try {
        val response: Map<String, Any?> = when (action) {
            "list" -> mapOf("tasks" to store.tasks(), "proposals" to store.proposals(), "globalAuto" to store.globalAuto(), "run" to visibleStatus(), "history" to records, "storeError" to (store.loadError ?: persistenceError))
            "get" -> mapOf("task" to if (args["runId"] != null) runner.taskSnapshot(AutomationPlan.string(args, "runId")) else store.get(AutomationPlan.string(args, "id")))
            "validate" -> AutomationPlan.parse(AutomationPlan.obj(args["definition"])).let { mapOf("valid" to true, "id" to it.id, "nodes" to it.steps.size) }
            "propose" -> store.propose(AutomationPlan.obj(args["definition"])).let { mapOf("proposalId" to it["proposalId"], "proposal" to it) }
            "approve" -> { require(trusted) { "user-approval-required" }; mapOf("task" to store.approve(AutomationPlan.string(args, "proposalId"), args["authorization"] as? String ?: "each", AutomationPlan.obj(args["parameterScopes"] ?: emptyMap<String, Any?>()))) }
            "set_auto" -> {
                require(trusted && args["enabled"] is Boolean) { "user-approval-required" }
                store.setGlobal(args["enabled"] == true)
                if (!store.globalAuto()) runner.recheckAuthority()
                mapOf("globalAuto" to store.globalAuto())
            }
            "set_enabled" -> { require(trusted && args["enabled"] is Boolean) { "user-approval-required" }; store.setEnabled(AutomationPlan.string(args, "id"), args["enabled"] == true); emptyMap() }
            "delete" -> {
                require(trusted) { "user-approval-required" }
                val id = AutomationPlan.string(args, "id")
                require(!(runner.active() && runner.status()["taskId"] == id)) { "task-running" }
                store.delete(id); emptyMap()
            }
            "start", "check_start" -> {
                val task = store.get(AutomationPlan.string(args, "id"))
                require(task["enabled"] == true) { "task-disabled" }
                val plan = AutomationPlan.parse(AutomationPlan.obj(task["definition"]))
                val values = plan.bind(AutomationPlan.obj(args["parameters"] ?: emptyMap<String, Any?>()))
                val inScope = plan.scopeAllows(values, AutomationPlan.obj(task["parameterScopes"]))
                require(inScope || trusted && args["approveScopes"] == true) { "needs_confirmation:parameters" }
                require(trusted || task["authorization"] == "always" || store.globalAuto()) { "needs_confirmation:run" }
                val once = trusted
                val independent = task["authorization"] == "always"
                if (action == "check_start") mapOf("ready" to true)
                else mapOf("run" to runner.start(task, values) { once || independent || store.globalAuto() })
            }
            "start_once", "check_once" -> {
                require(trusted) { "user-approval-required" }
                val proposalId = AutomationPlan.string(args, "proposalId")
                val proposal = store.proposals().firstOrNull { it["proposalId"] == proposalId } ?: error("proposal-not-found")
                val plan = AutomationPlan.parse(AutomationPlan.obj(proposal["definition"]))
                val values = plan.bind(AutomationPlan.obj(args["parameters"] ?: emptyMap<String, Any?>()))
                if (action == "check_once") mapOf("ready" to true)
                else mapOf("run" to runner.start(proposal, values))
            }
            "status" -> mapOf("run" to visibleStatus(), "globalAuto" to store.globalAuto(), "storeError" to (store.loadError ?: persistenceError))
            "pause" -> mapOf("run" to runner.pause(AutomationPlan.string(args, "runId")))
            "resume" -> { require(trusted) { "user-resume-required" }; mapOf("run" to runner.resume(AutomationPlan.string(args, "runId"))) }
            "stop" -> mapOf("run" to runner.stop(AutomationPlan.string(args, "runId")))
            "ai_result", "repair" -> mapOf("run" to runner.answer(AutomationPlan.string(args, "runId"), AutomationPlan.string(args, "checkpoint"), args["steps"]?.let(AutomationPlan::list), args["value"], action == "repair"))
            else -> error("unknown-action")
        }
        onChange()
        mapOf("ok" to true) + response
    } catch (e: Exception) {
        mapOf("ok" to false, "reason" to (e.message ?: e.javaClass.simpleName))
    }
}
