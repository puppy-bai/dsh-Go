package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Failure paths exercise the actual routine, worker, and safety gate with a fake device. */
class PhoneRoutineFailureTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        val log = ActionLog()
        var pkg = "example.app"
        var locked = false
        var reads = 0
        var serial = 0
        var visibleTexts: List<String>? = null
        var observationReason: String? = null
        var allowReuse = false
        var performFailure: ActionResult? = null
        var latest = ScreenSnapshot("s0", PRIVATE_SCREEN, "Example", pkg)
        val actions = mutableListOf<PhoneAction>()
        var onObserve: () -> Unit = {}
        var onPerform: () -> Unit = {}
        var onConfirm: () -> Unit = {}
        var onStatus: (String?) -> Unit = {}
        private fun snapshot(): ScreenSnapshot {
            latest = ScreenSnapshot("s${++serial}", "屏幕 s$serial\n$PRIVATE_SCREEN", "Example", pkg,
                visibleTexts = visibleTexts, observationReason = observationReason)
            return latest
        }
        private val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun foregroundPackage() = pkg
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                reads++
                onObserve()
                return snapshot()
            }
            override fun reusableSnapshot(screenId: String): ScreenSnapshot? =
                latest.takeIf { allowReuse && it.id == screenId }
            override fun find(screenId: String, text: String): Either<String, Int> {
                assertEquals(latest.id, screenId)
                return Either.Right(if (text == "Name") 1 else 2)
            }
            override fun target(screenId: String, elementId: Int): TargetInfo? =
                if (screenId != latest.id) null else TargetInfo(screenId, elementId,
                    if (elementId == 1) "输入框" else "按钮", if (elementId == 1) "Name" else "Continue",
                    pkg, "Example", false, false, elementId == 1)
            override fun perform(action: PhoneAction): ActionResult {
                assertEquals(latest.id, action.screenId)
                actions += action
                onPerform()
                performFailure?.let { return it }
                return ActionResult(true, "done", snapshot())
            }
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun open(packageName: String) = error("unused")
        }
        val core = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = PhonePolicies()
            override fun isLocked() = locked
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision { onConfirm(); return Decision.ALLOW_ONCE }
                override fun handoff(reason: String, timeoutMs: Long) = error("unused")
                override fun status(text: String?) { onStatus(text) }
            }
            override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun system(): SystemApi = error("unused")
        }, tasks, log)
        fun run(steps: List<Map<String, Any?>>) = core.call(PhoneToolCatalog.RUN,
            mapOf("app" to "example.app", "steps" to steps))
        override fun close() { core.worker.shutdown() }
    }

    private fun step(op: String, vararg args: Pair<String, Any?>) = mapOf("op" to op) + args
    private fun progress(out: ToolOutput) = Json.parse(out.text.substringBefore("\n\n")) as Map<*, *>
    private fun writes() = listOf(step("tap", "target" to "Continue"),
        step("type", "target" to "Name", "text" to PRIVATE_INPUT), step("tap", "target" to "Continue"))

    @Test fun assertionUsesVisibleTargetsInsteadOfTheFormattedTextTree() {
        Fixture().use { f ->
            f.visibleTexts = listOf("visible record 28")
            val out = f.run(listOf(step("assert", "text" to PRIVATE_SCREEN)))
            assertTrue(out.isError)
            assertEquals("assertion", progress(out)["reason"])
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun unavailableObservationDoesNotPretendTheApplicationChanged() {
        Fixture().use { f ->
            f.observationReason = "root-unavailable"
            val out = f.run(writes())
            assertTrue(out.isError)
            assertEquals("root-unavailable", progress(out)["reason"])
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun driverFailureCodeIsKeptInTheRoutineTrace() {
        Fixture().use { f ->
            f.performFailure = ActionResult(false, "target disabled", reasonCode = "target-disabled")
            val out = f.run(writes())
            assertEquals("target-disabled", progress(out)["reason"])
            assertEquals("target-disabled", ((progress(out)["trace"] as List<*>).single() as Map<*, *>)["decision"])
            assertEquals(1, f.actions.size)
        }
    }

    @Test fun onlyDriverVerifiedFreshObservationsAvoidRedundantReads() {
        val reads = mutableListOf<Int>()
        for (reuse in listOf(false, true)) Fixture().use { f ->
            f.allowReuse = reuse
            val out = f.run(writes())
            assertFalse(out.isError)
            assertEquals(3, f.actions.size)
            val result = progress(out)
            assertEquals(f.reads.toLong(), result["observations"])
            assertTrue((result["elapsedMs"] as Number).toLong() >= 0)
            reads += f.reads
        }
        assertTrue("verified reuse must reduce total observations", reads[1] < reads[0])
    }

    @Test fun injectionExceptionsPreserveProgressWithoutReplayingOrDisclosingExceptionDetails() {
        for (interrupt in listOf(false, true)) Fixture().use { f ->
            f.onPerform = {
                if (f.actions.size == 2) {
                    if (interrupt) throw InterruptedException(PRIVATE_EXCEPTION)
                    throw IllegalStateException(PRIVATE_EXCEPTION)
                }
            }
            val out = f.run(writes())
            val result = progress(out)
            assertTrue(out.isError)
            assertEquals(1L, result["completed"])
            assertEquals(2L, result["nextStep"])
            assertEquals(true, result["mayHaveActed"])
            assertEquals(if (interrupt) "stopped" else "device-error", result["reason"])
            val trace = result["trace"] as List<*>
            assertEquals(2, trace.size)
            assertEquals(true, (trace.first() as Map<*, *>)["ok"])
            assertEquals(false, (trace.last() as Map<*, *>)["ok"])
            assertEquals(2, f.actions.size)
            for (secret in listOf(PRIVATE_SCREEN, PRIVATE_INPUT, PRIVATE_EXCEPTION)) {
                assertFalse(secret, out.text.contains(secret))
                assertFalse(secret, Json.write(f.log.toMaps(30)).contains(secret))
            }
        }
    }

    @Test fun readFailureAfterAnInjectedActionRemainsAnUncertainFailedStep() {
        Fixture().use { f ->
            f.onObserve = { if (f.actions.isNotEmpty()) throw IllegalStateException(PRIVATE_EXCEPTION) }
            val out = f.run(writes())
            val result = progress(out)
            assertTrue(out.isError)
            assertEquals(0L, result["completed"])
            assertEquals(1L, result["nextStep"])
            assertEquals(true, result["mayHaveActed"])
            assertEquals("device-error", result["reason"])
            assertEquals(1, f.actions.size)
            assertFalse(out.text.contains(PRIVATE_SCREEN))
            assertFalse(out.text.contains(PRIVATE_EXCEPTION))
        }
    }

    @Test fun readFailureBeforeTheNextStepDoesNotClaimAnotherInjection() {
        Fixture().use { f ->
            f.onObserve = { if (f.reads == 4) throw IllegalStateException(PRIVATE_EXCEPTION) }
            val out = f.run(writes())
            val result = progress(out)
            assertTrue(out.isError)
            assertEquals(1L, result["completed"])
            assertEquals(2L, result["nextStep"])
            assertEquals(false, result["mayHaveActed"])
            assertEquals(1, f.actions.size)
            assertFalse(out.text.contains(PRIVATE_EXCEPTION))
        }
    }

    @Test fun appChangeImmediatelyBeforeBackPreventsTheGlobalKey() {
        Fixture().use { f ->
            var changed = false
            f.onStatus = { text ->
                if (text?.contains("按返回") == true) { f.pkg = "another.app"; changed = true }
            }
            val out = f.run(listOf(step("key", "key" to "back")))
            assertTrue("fixture must switch apps after the routine's observation", changed)
            assertTrue(out.isError)
            assertEquals("app-changed", progress(out)["reason"])
            assertTrue(f.actions.isEmpty())
            assertFalse(out.text.contains(PRIVATE_SCREEN))
        }
    }

    @Test fun stoppingOrLockingDuringBatchConfirmationDoesNotReturnTheUnpublishedScreen() {
        for (stop in listOf(false, true)) Fixture().use { f ->
            f.onConfirm = { if (stop) f.tasks.stop("user stop") else f.locked = true }
            val out = f.run(writes())
            assertTrue(out.isError)
            assertEquals(0L, progress(out)["completed"])
            assertTrue(f.actions.isEmpty())
            assertFalse(out.text.contains(PRIVATE_SCREEN))
            assertNull(out.image)
        }
    }

    @Test fun stoppingOrLockingAfterInjectionDoesNotReturnThePreviousScreenOrContinue() {
        for (stop in listOf(false, true)) Fixture().use { f ->
            f.onPerform = { if (stop) f.tasks.stop("user stop") else f.locked = true }
            val out = f.run(writes())
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals(true, progress(out)["mayHaveActed"])
            assertFalse(out.text.contains(PRIVATE_SCREEN))
            assertNull(out.image)
        }
    }

    private companion object {
        const val PRIVATE_SCREEN = "PRIVATE UNPUBLISHED SCREEN CONTENT"
        const val PRIVATE_INPUT = "PRIVATE INPUT VALUE"
        const val PRIVATE_EXCEPTION = "PRIVATE EXCEPTION DETAILS"
    }
}
