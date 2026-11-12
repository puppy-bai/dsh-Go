package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutomationTest {
    private fun definition(steps: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) =
        mapOf<String, Any?>("schema" to 1, "id" to "example", "name" to "Example", "apps" to listOf("example.app"), "steps" to steps) + extra
    private fun task(steps: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) =
        mapOf("definition" to definition(steps, extra), "version" to 1)
    private fun observe() = AutomationRunner.ActionResult(true, screenId = "s1", text = "Ready\nContinue", packageName = "example.app")
    private fun await(runner: AutomationRunner, state: String): Map<String, Any?> {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < end) {
            val s = runner.status()
            if (s["state"] == state) return s
            Thread.sleep(5)
        }
        fail("Expected $state, got " + runner.status())
        return emptyMap()
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun rejectsUnknownActionsUnboundedLoopsAndInvalidMetadataBeforeExecution() {
        for (s in listOf(
            mapOf("op" to "shell"), mapOf("op" to "repeat", "steps" to listOf(mapOf("op" to "snapshot"))),
            mapOf("op" to "repeat", "count" to 101, "steps" to listOf(mapOf("op" to "snapshot"))),
            mapOf("op" to "open", "app" to "other.app"), mapOf("op" to "tap", "target" to "x", "long" to "yes"),
            mapOf("op" to "key", "key" to "home"),
        )) invalid { AutomationPlan.parse(definition(listOf(s))) }
        invalid { AutomationPlan.parse(definition(List(201) { mapOf("op" to "snapshot") })) }
        invalid { AutomationPlan.parse(definition(listOf(mapOf("op" to "snapshot")), mapOf("limits" to mapOf("timeoutMs" to 1_800_001)))) }
    }

    @Test fun shippedExamplesUseTheActualParserAndConservativePlaceholderPackages() {
        val candidates = listOf("../../../examples/phone-automation", "../../examples/phone-automation", "../examples/phone-automation", "examples/phone-automation").map { java.io.File(it) }
        val directory = candidates.firstOrNull { it.isDirectory } ?: error("example-directory-not-found")
        val fixtures = directory.listFiles()!!.filter { it.extension == "json" }
        assertEquals(3, fixtures.size)
        fixtures.forEach {
            val plan = AutomationPlan.parse(AutomationPlan.obj(Json.parse(it.readText())))
            assertTrue(plan.apps.all { app -> app.startsWith("example.") })
            assertEquals(0, plan.limits.maxAiCalls)
        }
    }

    @Test fun immutableParametersCannotBeOverwrittenWithLocalVariables() {
        val parameters = mapOf("recipient" to mapOf("type" to "string", "default" to "approved"))
        for (s in listOf(
            mapOf("op" to "set", "name" to "recipient", "value" to "attacker"),
            mapOf("op" to "read_text", "target" to "x", "name" to "recipient"),
            mapOf("op" to "ai", "prompt" to "x", "name" to "recipient"),
        )) invalid { AutomationPlan.parse(definition(listOf(s), mapOf("parameters" to parameters))) }
    }

    @Test fun bindingAndUserScopesAreSeparateFromParameterSchema() {
        val p = AutomationPlan.parse(definition(listOf(mapOf("op" to "snapshot")), mapOf(
            "parameters" to mapOf("budget" to mapOf("type" to "integer", "min" to 1, "max" to 100, "required" to true)),
        )))
        val values = p.bind(mapOf("budget" to 50))
        assertFalse(p.scopeAllows(values, emptyMap()))
        assertFalse(p.scopeAllows(values, mapOf("budget" to mapOf("max" to 40))))
        assertTrue(p.scopeAllows(values, mapOf("budget" to mapOf("min" to 20, "max" to 50))))
        invalid { p.bind(mapOf("budget" to 101)) }
        invalid { p.bind(mapOf("budget" to 2.5)) }
        invalid { p.bind(mapOf("budget" to 20, "unknown" to true)) }
    }

    @Test fun storeApprovalsPersistAndNeverMutateRunningDefinition() {
        val dir = Files.createTempDirectory("automation-store").toFile()
        val store = AutomationStore(dir)
        val first = definition(listOf(mapOf("op" to "snapshot")))
        val proposal = store.propose(first)
        assertEquals(0, store.tasks().size)
        store.approve(proposal["proposalId"] as String, "always", emptyMap())
        val saved = store.get("example")
        store.setGlobal(true)
        val reloaded = AutomationStore(dir)
        assertTrue(reloaded.globalAuto())
        assertEquals("always", reloaded.get("example")["authorization"])
        val second = reloaded.propose(first + ("name" to "Changed"))
        reloaded.approve(second["proposalId"] as String, "each", emptyMap())
        assertEquals(2, (reloaded.get("example")["version"] as Number).toInt())
        assertEquals("Example", saved["name"])
        assertEquals(1, (saved["version"] as Number).toInt())
    }

    @Test fun serviceModelCannotApproveOrEnableGlobalModeAndOutOfScopeNeedsUser() {
        val service = AutomationService(Files.createTempDirectory("automation-service").toFile(), { _, _ -> observe() })
        val d = definition(listOf(mapOf("op" to "snapshot")), mapOf("parameters" to mapOf("name" to mapOf("type" to "string", "required" to true))))
        val proposal = service.request("propose", mapOf("definition" to d))
        val approval = mapOf("proposalId" to proposal["proposalId"], "authorization" to "always", "parameterScopes" to mapOf("name" to mapOf("enum" to listOf("allowed"))))
        assertEquals(false, service.request("approve", approval)["ok"])
        assertEquals(false, service.request("set_auto", mapOf("enabled" to true))["ok"])
        assertEquals(true, service.request("approve", approval, trusted = true)["ok"])
        val denied = service.request("start", mapOf("id" to "example", "parameters" to mapOf("name" to "other")))
        assertEquals(false, denied["ok"])
        assertEquals("needs_confirmation:parameters", denied["reason"])
        val check = service.request("check_start", mapOf("id" to "example", "parameters" to mapOf("name" to "other")), trusted = true)
        assertEquals("needs_confirmation:parameters", check["reason"])
        assertFalse(service.isRunning)
        assertEquals(true, service.request("check_start", mapOf("id" to "example", "parameters" to mapOf("name" to "allowed")), trusted = true)["ok"])
        assertFalse(service.isRunning)
    }

    @Test fun corruptedStoreIsPreservedAndFailsClosed() {
        val dir = Files.createTempDirectory("automation-corrupt").toFile()
        val file = java.io.File(dir, "automation-tasks-v1.json").apply { writeText("broken original") }
        val service = AutomationService(dir, { _, _ -> observe() })
        val listed = service.request("list")
        assertEquals("task-store-unavailable", listed["storeError"])
        assertEquals(false, listed["globalAuto"])
        assertEquals(false, service.request("set_auto", mapOf("enabled" to true), trusted = true)["ok"])
        assertEquals("broken original", file.readText())
    }

    @Test fun temporaryProposalNeedsUserApprovalAndRunsWithoutSavingOrGrantingAuthority() {
        val service = AutomationService(Files.createTempDirectory("automation-once").toFile(), { _, _ -> observe() })
        val proposal = service.request("propose", mapOf("definition" to definition(listOf(mapOf("op" to "snapshot")))))
        val args = mapOf("proposalId" to proposal["proposalId"])
        assertEquals("user-approval-required", service.request("start_once", args)["reason"])
        assertEquals("user-approval-required", service.request("check_once", args)["reason"])
        assertEquals(true, service.request("check_once", args, trusted = true)["ok"])
        assertFalse(service.isRunning)
        val started = service.request("start_once", args, trusted = true)
        assertEquals(true, started["ok"])
        repeat(200) {
            if ((service.request("status")["run"] as Map<*, *>)["state"] != "completed") Thread.sleep(5)
        }
        val listed = service.request("list")
        assertEquals("completed", (listed["run"] as Map<*, *>)["state"])
        assertTrue((listed["tasks"] as List<*>).isEmpty())
        assertEquals(1, (listed["proposals"] as List<*>).size)
        assertEquals(false, listed["globalAuto"])
    }

    @Test fun externalInterruptionsPauseWithoutRepairAndRecheckTheSameNodeOnUserResume() {
        for (failure in listOf("app-out-of-scope", "device-locked", "observation-unavailable", "root-unavailable", "tree-unavailable", "interrupted", "stopped")) {
            var interrupted = true
            val taps = AtomicInteger()
            val runner = AutomationRunner({ op, _ ->
                if (interrupted) AutomationRunner.ActionResult(false, failure)
                else { if (op == "tap") taps.incrementAndGet(); observe() }
            })
            runner.start(task(listOf(mapOf("op" to "tap", "target" to "Continue"))), emptyMap())
            val paused = await(runner, "paused")
            assertEquals(failure, paused["reason"])
            assertEquals(0, paused["repairs"])
            assertEquals(0, paused["aiCalls"])
            assertEquals(0, taps.get())
            interrupted = false
            runner.resume(paused["runId"] as String)
            await(runner, "completed")
            assertEquals(1, taps.get())
        }
    }

    @Test fun currentTemporaryRunDefinitionIsDetachedAndRejectsOtherRunIds() {
        val service = AutomationService(Files.createTempDirectory("automation-current").toFile(), { _, _ -> observe() })
        val definition = definition(listOf(mapOf("op" to "ai", "prompt" to "Inspect")), mapOf(
            "parameters" to mapOf("message" to mapOf("type" to "string", "required" to true)),
            "forbiddenTargets" to listOf("Pay"),
        ))
        val proposal = service.request("propose", mapOf("definition" to definition))
        val started = service.request("start_once", mapOf("proposalId" to proposal["proposalId"], "parameters" to mapOf("message" to "approved")), trusted = true)
        val run = started["run"] as Map<*, *>
        val id = run["runId"] as String
        assertEquals(listOf("Pay"), run["forbiddenTargets"])
        assertNotNull(run["limits"])
        val task = service.request("get", mapOf("runId" to id))["task"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        (task["definition"] as MutableMap<String, Any?>)["name"] = "tampered"
        @Suppress("UNCHECKED_CAST")
        (task["boundParameters"] as MutableMap<String, Any?>)["message"] = "tampered"
        val fresh = service.request("get", mapOf("runId" to id))["task"] as Map<*, *>
        assertEquals("Example", (fresh["definition"] as Map<*, *>)["name"])
        assertEquals("approved", (fresh["boundParameters"] as Map<*, *>)["message"])
        assertEquals(false, service.request("get", mapOf("runId" to "another-run"))["ok"])
        service.request("stop", mapOf("runId" to id))
    }

    @Test fun visualActionKeepsItsObservationUntilTheDriverValidatesIt() {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner({ op, _ -> calls += op; observe() })
        runner.start(task(listOf(mapOf("op" to "tap_xy", "observation" to "observed-screen", "x" to 10, "y" to 20))), emptyMap())
        await(runner, "completed")
        assertEquals(listOf("tap_xy"), calls)
    }

    @Test fun ordinaryActionsDoNotDuplicateTheHostObservationAndNeverCallAi() {
        val calls = mutableListOf<String>()
        val runner = AutomationRunner({ op, _ -> calls += op; observe() })
        runner.start(task(listOf(mapOf("op" to "repeat", "count" to 16, "steps" to listOf(mapOf("op" to "tap", "target" to "Continue"))))), emptyMap())
        val completed = await(runner, "completed")
        assertEquals(List(16) { "tap" }, calls)
        assertEquals(16, completed["actions"])
        assertEquals(0, completed["aiCalls"])
    }

    @Test fun successfulHostResultOutsideScopePausesWithoutReplayingThePossibleWrite() {
        val runner = AutomationRunner({ _, _ -> observe().copy(packageName = "other.app") })
        runner.start(task(listOf(mapOf("op" to "tap", "target" to "Continue"))), emptyMap())
        val paused = await(runner, "paused")
        assertEquals("result-uncertain", paused["reason"])
        assertEquals(1, paused["actions"])
        assertEquals(0, paused["repairs"])
        invalid { runner.resume(paused["runId"] as String) }
        runner.stop(paused["runId"] as String)
    }

    @Test fun interruptedRunIsShownAsInterruptedAfterRecreationWithoutReplay() {
        val dir = Files.createTempDirectory("automation-interrupted").toFile()
        val service = AutomationService(dir, { _, _ -> observe() })
        val proposed = service.request("propose", mapOf("definition" to definition(listOf(mapOf("op" to "ai", "prompt" to "Inspect")))))
        service.request("approve", mapOf("proposalId" to proposed["proposalId"], "authorization" to "always"), trusted = true)
        val start = service.request("start", mapOf("id" to "example"))
        assertEquals(true, start["ok"])
        Thread.sleep(50)
        val calls = AtomicInteger()
        val fresh = AutomationService(dir, { _, _ -> calls.incrementAndGet(); observe() })
        val run = fresh.request("status")["run"] as Map<*, *>
        assertEquals("interrupted", run["state"])
        assertEquals("process-interrupted", run["reason"])
        assertEquals(0, calls.get())
        service.request("stop", mapOf("runId" to (start["run"] as Map<*, *>)["runId"]))
    }

    @Test fun pluginPatchCannotChangeDeclaredRecipientOrTool() {
        val runner = AutomationRunner({ _, _ -> observe() })
        val original = mapOf<String, Any?>("op" to "plugin", "tool" to "mcp__robot__send", "arguments" to mapOf("to" to "approved"))
        runner.start(task(listOf(mapOf("op" to "ai", "prompt" to "Inspect"), original)), emptyMap())
        val s = await(runner, "awaiting_ai")
        invalid { runner.answer(s["runId"] as String, s["checkpoint"] as String, listOf(original + ("arguments" to mapOf("to" to "other"))), null, false) }
        invalid { runner.answer(s["runId"] as String, s["checkpoint"] as String, listOf(original + ("tool" to "mcp__robot__delete")), null, false) }
        runner.stop(s["runId"] as String)
    }

    @Test fun revokedGlobalAuthorityStopsBeforeFurtherWrite() {
        var allowed = true
        val taps = AtomicInteger()
        val runner = AutomationRunner({ op, _ -> if (op == "tap") { taps.incrementAndGet(); allowed = false }; observe() })
        runner.start(task(List(2) { mapOf("op" to "tap", "target" to "Continue") }), emptyMap()) { allowed }
        val paused = await(runner, "paused")
        assertEquals("authorization-revoked", paused["reason"])
        assertEquals(1, taps.get())
        runner.stop(paused["runId"] as String)
    }

    @Test fun revokedAuthorityCannotCreateAPluginCheckpoint() {
        var allowed = true
        val runner = AutomationRunner({ _, _ -> allowed = false; observe() })
        runner.start(task(listOf(
            mapOf("op" to "tap", "target" to "Continue"),
            mapOf("op" to "plugin", "tool" to "mock_robot_send", "arguments" to emptyMap<String, Any?>()),
        )), emptyMap()) { allowed }
        val paused = await(runner, "paused")
        assertEquals("authorization-revoked", paused["reason"])
        assertEquals(0, paused["aiCalls"])
        assertNull(paused["checkpoint"])
        assertEquals(1, paused["actions"])
        runner.stop(paused["runId"] as String)
    }

    @Test fun disablingGlobalModeRetiresPendingPluginOnlyWhenItDependsOnGlobalAuthority() {
        for ((authorization, once) in listOf("each" to false, "always" to false, "each" to true)) {
            val service = AutomationService(Files.createTempDirectory("automation-revoke-plugin").toFile(), { _, _ -> observe() })
            val proposal = service.request("propose", mapOf("definition" to definition(listOf(
                mapOf("op" to "plugin", "tool" to "mock_robot_send", "arguments" to emptyMap<String, Any?>()),
            ))))
            service.request("approve", mapOf("proposalId" to proposal["proposalId"], "authorization" to authorization), trusted = true)
            service.request("set_auto", mapOf("enabled" to true), trusted = true)
            service.request("start", mapOf("id" to "example"), trusted = once)
            var waiting = service.request("status")["run"] as Map<*, *>
            repeat(200) {
                if (waiting["state"] != "awaiting_ai") {
                    Thread.sleep(5)
                    waiting = service.request("status")["run"] as Map<*, *>
                }
            }
            assertEquals("awaiting_ai", waiting["state"])
            assertEquals(true, service.request("set_auto", mapOf("enabled" to false), trusted = true)["ok"])
            val after = service.request("status")["run"] as Map<*, *>
            if (authorization == "each" && !once) {
                assertEquals("paused", after["state"])
                assertEquals("result-uncertain", after["reason"])
                assertNull(after["checkpoint"])
                assertEquals("stale-checkpoint", service.request("ai_result", mapOf("runId" to waiting["runId"], "checkpoint" to waiting["checkpoint"]))["reason"])
            } else {
                assertEquals("awaiting_ai", after["state"])
                assertEquals(waiting["checkpoint"], after["checkpoint"])
            }
            service.request("stop", mapOf("runId" to waiting["runId"]))
        }
    }

    @Test fun branchesLoopsAndVariablesExecuteLocallyWithoutAi() {
        val taps = AtomicInteger()
        val runner = AutomationRunner({ op, _ -> if (op == "tap") taps.incrementAndGet(); observe() })
        runner.start(task(listOf(
            mapOf("op" to "set", "name" to "flag", "value" to true),
            mapOf("op" to "if", "condition" to mapOf("variable" to "flag", "equals" to true), "then" to listOf(
                mapOf("op" to "repeat", "count" to 3, "steps" to listOf(mapOf("op" to "tap", "target" to "Continue"))),
            )),
            mapOf("op" to "finish", "result" to mapOf("done" to true)),
        )), emptyMap())
        val status = await(runner, "completed")
        assertEquals(3, taps.get())
        assertEquals(0, status["aiCalls"])
        assertEquals(mapOf("done" to true), status["result"])
    }

    @Test fun childBlockVariablesAreEvaluatedAfterEarlierChildSteps() {
        val typed = mutableListOf<String>()
        val runner = AutomationRunner({ op, args -> if (op == "type") typed += args["text"] as String; observe() })
        runner.start(task(listOf(
            mapOf("op" to "if", "condition" to mapOf("text" to "Ready"), "then" to listOf(
                mapOf("op" to "set", "name" to "draft", "value" to "generated locally"),
                mapOf("op" to "type", "target" to "Continue", "text" to "\${draft}"),
            )),
        )), emptyMap())
        await(runner, "completed")
        assertEquals(listOf("generated locally"), typed)
    }

    @Test fun actionBudgetPausesWithoutDispatchingExtraWrite() {
        val taps = AtomicInteger()
        val runner = AutomationRunner({ op, _ -> if (op == "tap") taps.incrementAndGet(); observe() })
        runner.start(task(listOf(mapOf("op" to "repeat", "count" to 3, "steps" to listOf(mapOf("op" to "tap", "target" to "Continue")))), mapOf("limits" to mapOf("maxActions" to 2))), emptyMap())
        val s = await(runner, "paused")
        assertEquals("action-budget", s["reason"])
        assertEquals(2, taps.get())
        runner.stop(s["runId"] as String)
    }

    @Test fun aiCheckpointRejectsStaleRepliesAndCannotBroadenCapabilities() {
        val runner = AutomationRunner({ _, _ -> observe() })
        runner.start(task(listOf(mapOf("op" to "ai", "prompt" to "Inspect", "name" to "answer"), mapOf("op" to "finish", "result" to "\${answer}"))), emptyMap())
        val waiting = await(runner, "awaiting_ai")
        val id = waiting["runId"] as String
        invalid { runner.answer(id, "stale", null, "x", false) }
        invalid { runner.answer(id, waiting["checkpoint"] as String, listOf(mapOf("op" to "tap", "target" to "x")), null, false) }
        runner.answer(id, waiting["checkpoint"] as String, null, "result", false)
        assertEquals("result", await(runner, "completed")["result"])
    }

    @Test fun pausedDecisionMustBeObservedAgainAndOldReplyCannotAct() {
        val runner = AutomationRunner({ _, _ -> observe() })
        runner.start(task(listOf(mapOf("op" to "ai", "prompt" to "Inspect"))), emptyMap())
        val first = await(runner, "awaiting_ai")
        val id = first["runId"] as String
        runner.pause(id)
        invalid { runner.answer(id, first["checkpoint"] as String, null, null, false) }
        runner.resume(id)
        val second = await(runner, "awaiting_ai")
        assertNotEquals(first["checkpoint"], second["checkpoint"])
        assertEquals(2, second["aiCalls"])
        runner.stop(id)
    }

    @Test fun uncertainWriteNeverReplaysAndCannotSimplyResume() {
        val taps = AtomicInteger()
        val runner = AutomationRunner({ op, _ ->
            if (op == "tap") { taps.incrementAndGet(); AutomationRunner.ActionResult(false, "timeout", mayHaveActed = true) } else observe()
        })
        runner.start(task(listOf(mapOf("op" to "tap", "target" to "Send"))), emptyMap())
        val s = await(runner, "paused")
        assertEquals("result-uncertain", s["reason"])
        assertEquals(0, s["repairs"])
        invalid { runner.resume(s["runId"] as String) }
        assertEquals(1, taps.get())
        runner.stop(s["runId"] as String)
    }

    @Test fun atMostTwoRepairsAcrossTheEntireRun() {
        val runner = AutomationRunner({ op, _ -> if (op == "tap") AutomationRunner.ActionResult(false, "target-missing") else observe() })
        val bad = listOf(mapOf<String, Any?>("op" to "tap", "target" to "Missing"))
        runner.start(task(bad), emptyMap())
        repeat(2) {
            val s = await(runner, "awaiting_ai")
            assertEquals(it + 1, s["repairs"])
            runner.answer(s["runId"] as String, s["checkpoint"] as String, bad, null, true)
        }
        val paused = await(runner, "paused")
        assertEquals(2, paused["repairs"])
        runner.stop(paused["runId"] as String)
    }

    @Test fun stopRetiresLateResultsBeforeNextAction() {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val taps = AtomicInteger()
        val runner = AutomationRunner({ op, _ ->
            if (op == "tap") { taps.incrementAndGet(); admitted.countDown(); release.await(2, TimeUnit.SECONDS) }
            observe()
        })
        val start = runner.start(task(List(2) { mapOf("op" to "tap", "target" to "Continue") }), emptyMap())
        assertTrue(admitted.await(2, TimeUnit.SECONDS))
        runner.stop(start["runId"] as String)
        release.countDown()
        Thread.sleep(50)
        assertEquals(1, taps.get())
        assertEquals("stopped", runner.status()["state"])
    }
}
