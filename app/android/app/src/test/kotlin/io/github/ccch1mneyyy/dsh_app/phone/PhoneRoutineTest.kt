package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real worker, routine dispatch, safety gates, and progress results. */
class PhoneRoutineTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        val log = ActionLog()
        var policy = PhonePolicies()
        var pkg = "example.app"
        var body = "Form ready\nName\nContinue\n发送"
        var reads = 0
        var serial = 0
        var latest = ScreenSnapshot("s0", body, "Example", pkg)
        val actions = mutableListOf<PhoneAction>()
        val found = mutableListOf<Pair<String, String>>()
        val confirmations = mutableListOf<ConfirmRequest>()
        var onPerform: (PhoneAction) -> ActionResult? = { null }
        var onObserve: () -> Unit = {}
        var decide: (ConfirmRequest) -> Decision = { Decision.ALLOW_ONCE }
        private fun snapshot(): ScreenSnapshot {
            latest = ScreenSnapshot("s${++serial}", "屏幕 s$serial\n$body", "Example", pkg)
            return latest
        }
        private val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun foregroundPackage() = pkg
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                assertFalse("routine must not request screenshots", image)
                reads++
                onObserve()
                return snapshot()
            }
            override fun find(screenId: String, text: String): Either<String, Int> {
                assertEquals("lookup must use the current observation", latest.id, screenId)
                found += screenId to text
                return when (text) {
                    "Name" -> Either.Right(1)
                    "Continue" -> Either.Right(2)
                    "发送" -> Either.Right(3)
                    else -> Either.Left("target missing")
                }
            }
            override fun target(screenId: String, elementId: Int): TargetInfo? {
                if (screenId != latest.id) return null
                val label = mapOf(1 to "Name", 2 to "Continue", 3 to "发送")[elementId] ?: return null
                return TargetInfo(screenId, elementId, if (elementId == 1) "输入框" else "按钮", label,
                    pkg, "Example", false, false, elementId == 1, "example.app:id/field_$elementId")
            }
            override fun perform(action: PhoneAction): ActionResult {
                assertEquals("write must use the current observation", latest.id, action.screenId)
                actions += action
                return onPerform(action) ?: ActionResult(true, "done", snapshot())
            }
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun open(packageName: String) = error("unused")
        }
        val core = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = policy
            override fun isLocked() = false
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision {
                    confirmations += request
                    return decide(request)
                }
                override fun handoff(reason: String, timeoutMs: Long) = error("unused")
                override fun status(text: String?) {}
            }
            override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun system(): SystemApi = error("unused")
        }, tasks, log)
        fun run(steps: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) =
            core.call(PhoneToolCatalog.RUN, mapOf("app" to "example.app", "steps" to steps) + extra)
        override fun close() { core.worker.shutdown() }
    }

    private fun step(op: String, vararg args: Pair<String, Any?>): Map<String, Any?> = mapOf("op" to op) + args
    private fun progress(out: ToolOutput) = Json.parse(out.text.substringBefore("\n\n")) as Map<*, *>

    @Test fun invalidLastStepPreventsEveryDeviceOperation() {
        Fixture().use { f ->
            val out = f.run(listOf(step("tap", "target" to "Continue"), step("shell")))
            assertTrue(out.isError)
            assertEquals(0, f.reads)
            assertTrue(f.actions.isEmpty())
            assertTrue(f.confirmations.isEmpty())
        }
    }

    @Test fun dryRunChecksOnlyThePlanAndMasksTheInput() {
        Fixture().use { f ->
            val out = f.run(listOf(step("type", "target" to "Name", "text" to "private draft")), mapOf("dryRun" to true))
            assertFalse(out.text, out.isError)
            assertEquals(0, f.reads)
            assertTrue(f.actions.isEmpty())
            assertTrue(f.confirmations.isEmpty())
            assertFalse(out.text.contains("private draft"))
        }
    }

    @Test fun everyWriteResolvesItsTargetAgainAgainstANewSnapshot() {
        Fixture().use { f ->
            val out = f.run(listOf(step("type", "target" to "Name", "text" to "Ada"), step("tap", "target" to "Continue")))
            assertFalse(out.text, out.isError)
            assertEquals(listOf("Name", "Continue"), f.found.map { it.second })
            assertEquals(2, f.actions.map { it.screenId }.distinct().size)
            assertEquals(1, (f.actions[0] as PhoneAction.Type).elementId)
            assertEquals("Ada", (f.actions[0] as PhoneAction.Type).text)
            assertEquals(2, (f.actions[1] as PhoneAction.Tap).elementId)
            assertEquals(1, f.confirmations.size)
            assertEquals(2L, progress(out)["completed"])
            assertNull(progress(out)["nextStep"])
        }
    }

    @Test fun changingAppsAfterAnActionStopsBeforeTheNextWriteAndDoesNotReturnItsContents() {
        Fixture().use { f ->
            f.onPerform = { f.pkg = "another.app"; f.body = "private other app contents"; null }
            val out = f.run(listOf(step("tap", "target" to "Continue"), step("type", "target" to "Name", "text" to "Ada")))
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals("app-changed", progress(out)["reason"])
            assertEquals(true, progress(out)["mayHaveActed"])
            assertFalse(out.text.contains("private other app contents"))
        }
    }

    @Test fun failedPreconditionNeverDispatchesTheStep() {
        Fixture().use { f ->
            val out = f.run(listOf(step("tap", "target" to "Continue", "requireText" to "Not this page")))
            assertTrue(out.isError)
            assertTrue(f.actions.isEmpty())
            assertEquals("precondition", progress(out)["reason"])
            assertEquals(0L, progress(out)["completed"])
            assertEquals(1L, progress(out)["nextStep"])
        }
    }

    @Test fun failedAssertionReturnsProgressWithoutDispatchingLaterWrites() {
        Fixture().use { f ->
            val out = f.run(listOf(step("snapshot"), step("assert", "text" to "Not here"), step("tap", "target" to "Continue")))
            val result = progress(out)
            assertTrue(out.isError)
            assertTrue(f.actions.isEmpty())
            assertEquals("assertion", result["reason"])
            assertEquals(1L, result["completed"])
            assertEquals(2L, result["nextStep"])
            val trace = result["trace"] as List<*>
            assertEquals(2, trace.size)
            assertEquals(false, (trace.last() as Map<*, *>)["ok"])
        }
    }

    @Test fun failedExpectDoesNotReplayTheActionOrContinueThePlan() {
        Fixture().use { f ->
            val out = f.run(listOf(step("tap", "target" to "Continue", "expect" to "Saved"), step("tap", "target" to "Continue")))
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals("untilMissing", progress(out)["reason"])
            assertEquals(0L, progress(out)["completed"])
            assertEquals(1L, progress(out)["nextStep"])
            assertEquals(true, progress(out)["mayHaveActed"])
        }
    }

    @Test fun uncertainWriteFailureReturnsTraceAndRequiresObservationBeforeResuming() {
        Fixture().use { f ->
            f.onPerform = { ActionResult(false, "injection result uncertain") }
            val out = f.run(listOf(step("snapshot"), step("tap", "target" to "Continue"), step("type", "target" to "Name", "text" to "Ada")))
            val result = progress(out)
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals(1L, result["completed"])
            assertEquals(2L, result["nextStep"])
            assertEquals(true, result["mayHaveActed"])
            assertEquals(2, (result["trace"] as List<*>).size)
            assertTrue(result["resume"].toString().contains("requireText"))
        }
    }

    @Test fun resumptionSkipsCompletedStepsOnlyAfterCheckingItsPageGuard() {
        Fixture().use { f ->
            val steps = listOf(step("tap", "target" to "Continue"), step("type", "target" to "Name", "text" to "Ada", "requireText" to "Form ready"))
            assertFalse(f.run(steps, mapOf("startAt" to 2)).isError)
            assertEquals(1, f.actions.size)
            assertTrue(f.actions.single() is PhoneAction.Type)
        }
        Fixture().use { f ->
            val out = f.run(listOf(step("snapshot"), step("tap", "target" to "Continue")), mapOf("startAt" to 2))
            assertTrue(out.isError)
            assertEquals(0, f.reads)
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun sensitiveSendStillAsksEvenWhenOrdinaryTaskConfirmationsAreWaived() {
        Fixture().use { f ->
            f.policy = f.policy.copy(confirmMode = ConfirmMode.ALWAYS)
            f.decide = { Decision.DENY }
            val out = f.run(listOf(step("tap", "target" to "发送")))
            assertTrue(out.isError)
            assertEquals(1, f.confirmations.size)
            assertTrue(f.confirmations.single().sentence.contains("发送"))
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun stopDuringAWritePreventsLaterSteps() {
        Fixture().use { f ->
            f.onPerform = { f.tasks.stop("user stop"); null }
            val out = f.run(listOf(step("tap", "target" to "Continue"), step("type", "target" to "Name", "text" to "Ada")))
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals("stopped", progress(out)["reason"])
        }
    }

    @Test fun pauseHoldsTheNextStepUntilThePersonResumes() {
        Fixture().use { f ->
            val paused = CountDownLatch(1)
            f.onPerform = {
                if (f.actions.size == 1) { f.tasks.setPaused(true); paused.countDown() }
                null
            }
            val caller = Executors.newSingleThreadExecutor()
            try {
                val result = caller.submit<ToolOutput> { f.run(listOf(step("tap", "target" to "Continue"), step("type", "target" to "Name", "text" to "Ada"))) }
                assertTrue(paused.await(2, TimeUnit.SECONDS))
                try { result.get(100, TimeUnit.MILLISECONDS); fail("paused routine completed") } catch (_: TimeoutException) {}
                assertEquals(1, f.actions.size)
                f.tasks.setPaused(false)
                val out = result.get(2, TimeUnit.SECONDS)
                assertFalse(out.text, out.isError)
                assertEquals(2, f.actions.size)
            } finally {
                f.tasks.setPaused(false)
                caller.shutdownNow()
            }
        }
    }
}
