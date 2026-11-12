package io.github.ccch1mneyyy.dsh_app.phone

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Local task definitions and user authority. Model requests can propose, but never approve. */
class AutomationStore(private val directory: File) {
    private val file = File(directory, "automation-tasks-v1.json")
    private var state: MutableMap<String, Any?>
    val loadError: String?

    init {
        directory.mkdirs()
        val empty = mutableMapOf<String, Any?>("schema" to 1, "globalAuto" to false, "tasks" to emptyMap<String, Any?>(), "proposals" to emptyMap<String, Any?>())
        var failure: String? = null
        state = try {
            if (file.exists()) {
                val raw = AutomationPlan.obj(Json.parse(file.readText()))
                require((raw["schema"] as? Number)?.toInt() == 1) { "store-schema" }
                AutomationPlan.obj(raw["tasks"]); AutomationPlan.obj(raw["proposals"])
                raw.toMutableMap()
            } else empty
        } catch (_: Exception) {
            // Preserve the original file and fail closed instead of crashing phone control.
            failure = "task-store-unavailable"
            empty
        }
        loadError = failure
    }

    @Synchronized fun globalAuto(): Boolean = state["globalAuto"] == true

    @Synchronized fun setGlobal(enabled: Boolean) {
        commit(state + ("globalAuto" to enabled))
    }

    @Synchronized fun tasks(): List<Map<String, Any?>> = entries("tasks").values.map { summary(AutomationPlan.obj(it)) }
    @Synchronized fun proposals(): List<Map<String, Any?>> = entries("proposals").values.map { AutomationPlan.obj(it) }
    @Synchronized fun get(id: String): Map<String, Any?> = entries("tasks")[id]?.let(AutomationPlan::obj) ?: error("task-not-found")

    @Synchronized fun propose(raw: Map<String, Any?>): Map<String, Any?> {
        val plan = AutomationPlan.parse(raw)
        val version = ((entries("tasks")[plan.id] as? Map<*, *>)?.get("version") as? Number)?.toInt()?.plus(1) ?: 1
        val proposalId = UUID.randomUUID().toString()
        val definition = plan.definition + ("version" to version)
        val proposal = linkedMapOf<String, Any?>(
            "proposalId" to proposalId, "id" to plan.id, "name" to plan.name, "version" to version,
            "apps" to plan.apps.toList(), "parameters" to plan.parameters, "definition" to definition,
        )
        val proposals = entries("proposals").toMutableMap()
        // Only a recent candidate per task is needed; approving never executes it.
        proposals.entries.removeAll { (it.value as? Map<*, *>)?.get("id") == plan.id }
        require(proposals.size < 100) { "proposals-limit" }
        proposals[proposalId] = proposal
        commit(state + ("proposals" to proposals))
        return proposal
    }

    @Synchronized fun approve(proposalId: String, authorization: String, scopes: Map<String, Any?>): Map<String, Any?> {
        require(authorization in setOf("each", "always")) { "invalid-authorization" }
        val proposals = entries("proposals").toMutableMap()
        val proposal = proposals[proposalId]?.let(AutomationPlan::obj) ?: error("proposal-not-found")
        val plan = AutomationPlan.parse(AutomationPlan.obj(proposal["definition"]))
        validateScopes(plan, scopes)
        val tasks = entries("tasks").toMutableMap()
        val old = tasks[plan.id]?.let(AutomationPlan::obj)
        require(((old?.get("version") as? Number)?.toInt() ?: 0) + 1 == (proposal["version"] as Number).toInt()) { "proposal-outdated" }
        val versions = (old?.get("versions") as? List<*>)?.toMutableList() ?: mutableListOf()
        if (old != null) versions.add(old - "versions")
        while (versions.size > 10) versions.removeAt(0)
        val task = linkedMapOf<String, Any?>(
            "id" to plan.id, "name" to plan.name, "version" to proposal["version"], "enabled" to true,
            "authorization" to authorization, "parameterScopes" to scopes,
            "apps" to plan.apps.toList(), "parameters" to plan.parameters, "definition" to plan.definition,
            "versions" to versions,
        )
        require(tasks.size < 100 || plan.id in tasks) { "tasks-limit" }
        tasks[plan.id] = task
        proposals.remove(proposalId)
        commit(state + mapOf("tasks" to tasks, "proposals" to proposals))
        return summary(task)
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val tasks = entries("tasks").toMutableMap()
        tasks[id] = get(id) + ("enabled" to enabled)
        commit(state + ("tasks" to tasks))
    }

    @Synchronized fun delete(id: String) {
        val tasks = entries("tasks").toMutableMap()
        require(tasks.remove(id) != null) { "task-not-found" }
        commit(state + ("tasks" to tasks))
    }

    private fun entries(key: String): Map<String, Any?> = AutomationPlan.obj(state[key])
    private fun summary(task: Map<String, Any?>): Map<String, Any?> = task - setOf("definition", "versions")

    private fun validateScopes(plan: AutomationPlan, scopes: Map<String, Any?>) {
        require(scopes.keys.all { it in plan.parameters }) { "unknown-scope" }
        for ((name, raw) in scopes) {
            val scope = AutomationPlan.obj(raw)
            AutomationPlan.known(scope, setOf("any", "enum", "min", "max"))
            require(scope["any"] == null || scope["any"] is Boolean) { "scope-any" }
            require(scope["any"] == true || scope["enum"] is List<*> || scope["min"] is Number || scope["max"] is Number) { "empty-scope" }
            (scope["enum"] as? List<*>)?.forEach { require(it != null) { "null-scope" }; AutomationPlan.validateValue(name, it, plan.parameters.getValue(name)) }
            for (bound in listOf("min", "max")) if (scope[bound] != null) require(AutomationPlan.finite(scope[bound])) { "scope-bound" }
            if (scope["min"] is Number && scope["max"] is Number) require((scope["min"] as Number).toDouble() <= (scope["max"] as Number).toDouble()) { "scope-bound" }
        }
    }

    private fun commit(next: Map<String, Any?>) {
        require(loadError == null) { loadError ?: "task-store-unavailable" }
        val tmp = File(directory, "automation-tasks-v1.tmp")
        val encoded = Json.write(next)
        tmp.outputStream().use { stream ->
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = AutomationPlan.obj(Json.parse(encoded)).toMutableMap()
    }
}
