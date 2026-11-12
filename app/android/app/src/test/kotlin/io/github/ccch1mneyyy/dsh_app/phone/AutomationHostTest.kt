package io.github.ccch1mneyyy.dsh_app.phone

import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Actual runner -> host -> core -> fake driver coverage, without Android or a phone. */
class AutomationHostTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        val reads = AtomicInteger()
        val writes = CopyOnWriteArrayList<PhoneAction>()
        @Volatile var pkg = "example.app"
        @Volatile var latest = ScreenSnapshot("s0", "Ready\nContinue", "Example", pkg)
        var onObserve: (Int) -> Unit = {}
        var onPerform: () -> Unit = {}
        val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun foregroundPackage() = pkg
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                val serial = reads.incrementAndGet()
                onObserve(serial)
                latest = ScreenSnapshot("s$serial", "Ready\nContinue", "Example", pkg,
                    image = if (image) byteArrayOf(1, 2, 3) else null,
                    imageMime = if (image) "image/png" else null, visibleTexts = listOf("Ready", "Continue"))
                return latest
            }
            override fun target(screenId: String, elementId: Int): TargetInfo? =
                if (screenId == latest.id) TargetInfo(screenId, elementId, "按钮", "Continue", pkg,
                    "Example", false, false, false) else null
            override fun find(screenId: String, text: String): Either<String, Int> =
                if (screenId == latest.id && text == "Continue") Either.Right(1) else Either.Left("missing")
            override fun perform(action: PhoneAction): ActionResult {
                if (action.screenId != latest.id) return ActionResult(false, "stale", reasonCode = "observation-stale")
                writes += action
                onPerform()
                return ActionResult(true, "done", latest.copy(packageName = pkg))
            }
            override fun open(packageName: String) = error("unused")
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult = error("unused")
        }
        val env = object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = PhonePolicies()
            override fun isLocked() = false
            override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision = error("approved task must not prompt again")
                override fun handoff(reason: String, timeoutMs: Long): HandoffResult = error("unused")
                override fun status(text: String?) {}
            }
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun system(): SystemApi = error("unused")
        }
        val core = PhoneCore(env, tasks, ActionLog())
        val host = AutomationHost(Files.createTempDirectory("automation-host").toFile(), core, tasks, env, {})
        fun start(steps: List<Map<String, Any?>>, capabilities: List<String> = listOf("observe", "tap", "tap_xy")) {
            val definition = mapOf("schema" to 1, "id" to "example", "name" to "Example", "apps" to listOf("example.app"),
                "steps" to steps, "capabilities" to capabilities)
            val proposal = host.request("propose", mapOf("definition" to definition), false)
            assertEquals(true, proposal["ok"])
            assertEquals(true, host.request("approve", mapOf("proposalId" to proposal["proposalId"], "authorization" to "always"), true)["ok"])
            assertEquals(true, host.request("start", mapOf("id" to "example"), false)["ok"])
        }
        fun status(): Map<*, *> = host.request("status", emptyMap(), true)["run"] as Map<*, *>
        fun await(state: String): Map<*, *> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline) {
                val run = status()
                if (run["state"] == state) return run
                Thread.sleep(5)
            }
            error("Expected $state: ${status()}")
        }
        override fun close() { host.stop(); core.worker.shutdown() }
    }

    @Test fun aiScreenshotCoordinatesDoNotAcquireAnInterveningObservation() {
        Fixture().use { f ->
            f.start(listOf(mapOf("op" to "ai", "prompt" to "Locate the visible button")))
            val checkpoint = f.await("awaiting_ai")
            val image = f.host.read(mapOf("image" to true))
            assertFalse(image.isError)
            assertNotNull(image.image)
            val observedId = f.latest.id
            val reads = f.reads.get()
            assertEquals(true, f.host.request("ai_result", mapOf("runId" to checkpoint["runId"], "checkpoint" to checkpoint["checkpoint"],
                "steps" to listOf(mapOf("op" to "tap_xy", "observation" to observedId, "x" to 10, "y" to 20))), false)["ok"])
            f.await("completed")
            assertEquals(reads, f.reads.get())
            assertEquals(listOf(PhoneAction.TapXY(observedId, 10, 20)), f.writes.toList())
        }
    }

    @Test fun changingAppsDuringCheckpointReadDiscardsTextAndImageAndPauses() {
        Fixture().use { f ->
            f.start(listOf(mapOf("op" to "ai", "prompt" to "Inspect")))
            f.await("awaiting_ai")
            f.onObserve = { f.pkg = "outside.app" }
            val result = f.host.read(mapOf("image" to true))
            assertTrue(result.isError)
            assertNull(result.image)
            assertFalse(result.text.contains("Continue"))
            f.await("paused")
            assertTrue(f.tasks.isPaused)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun checkpointReadsCannotInspectBackgroundOrPausedTasks() {
        Fixture().use { f ->
            f.start(listOf(mapOf("op" to "ai", "prompt" to "Inspect")))
            f.await("awaiting_ai")
            assertTrue(f.host.read(mapOf("background" to true)).isError)
            assertEquals(0, f.reads.get())
            assertTrue(f.host.pause())
            assertTrue(f.host.read(emptyMap()).isError)
            assertEquals(0, f.reads.get())
            assertTrue(f.tasks.isPaused)
        }
    }

    @Test fun evidenceRequiresUserOptInAndStaysOutOfModelResponses() {
        Fixture().use { f ->
            assertEquals(false, f.host.request("set_evidence", mapOf("enabled" to true), false)["ok"])
            assertEquals(false, f.host.request("evidence", emptyMap(), false)["ok"])
            assertEquals(true, f.host.request("set_evidence", mapOf("enabled" to true), true)["ok"])
            f.start(listOf(mapOf("op" to "snapshot")))
            f.await("completed")
            val trusted = f.host.request("list", emptyMap(), true)
            assertEquals(true, trusted["evidenceEnabled"])
            assertEquals(1, (trusted["evidence"] as List<*>).size)
            val model = f.host.request("list", emptyMap(), false)
            assertFalse(model.containsKey("evidence"))
            assertFalse(Json.write(model).contains(".png"))
        }
    }

    @Test fun waitAndPluginFirstStepsOwnThePhoneLifetimeUntilStopped() {
        for (step in listOf(mapOf("op" to "wait", "ms" to 120_000),
            mapOf("op" to "plugin", "tool" to "mcp__robot__test", "arguments" to emptyMap<String, Any?>()))) Fixture().use { f ->
            f.start(listOf(step), listOf("observe", "plugin"))
            if (step["op"] == "plugin") f.await("awaiting_ai")
            assertTrue(f.tasks.isActive)
            assertEquals(1, f.tasks.callsInFlight)
            assertFalse("chat turn finish must not end a locally running task", f.tasks.finish())
            f.host.stop()
            assertFalse(f.tasks.isActive)
            assertEquals(0, f.tasks.callsInFlight)
        }
    }

    @Test fun aRejectedStartReleasesTheTemporaryPhoneLifetime() {
        Fixture().use { f ->
            assertEquals(false, f.host.request("start", mapOf("id" to "missing"), true)["ok"])
            assertFalse(f.tasks.isActive)
            assertEquals(0, f.tasks.callsInFlight)
        }
    }

    @Test fun anAppSwitchDuringLocalReadPausesWithoutDispatchingOrLeakingItsText() {
        Fixture().use { f ->
            f.onObserve = { f.pkg = "outside.app" }
            f.start(listOf(mapOf("op" to "tap", "target" to "Continue")))
            val paused = f.await("paused")
            assertEquals("app-out-of-scope", paused["reason"])
            assertTrue(f.writes.isEmpty())
            assertFalse(Json.write(paused).contains("Continue"))
        }
    }

    @Test fun aWriteAdmittedBeforePauseCannotGainAuthorityAfterResume() {
        Fixture().use { f ->
            val captured = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onObserve = { count -> if (count == 1) { captured.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) } }
            f.start(listOf(mapOf("op" to "tap", "target" to "Continue")))
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            assertTrue(f.host.pause())
            assertTrue(f.host.resume())
            release.countDown()
            f.await("paused")
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun anOldCheckpointReadIsDiscardedAfterPauseAndResume() {
        Fixture().use { f ->
            f.start(listOf(mapOf("op" to "ai", "prompt" to "Inspect")))
            f.await("awaiting_ai")
            val captured = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.onObserve = { count -> if (count == 1) { captured.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) } }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val reading = executor.submit<ToolOutput> { f.host.read(mapOf("image" to true)) }
                assertTrue(captured.await(3, TimeUnit.SECONDS))
                assertTrue(f.host.pause())
                assertTrue(f.host.resume())
                release.countDown()
                val output = reading.get(3, TimeUnit.SECONDS)
                assertTrue(output.isError)
                assertNull(output.image)
                assertFalse(output.text.contains("Continue"))
                f.await("awaiting_ai")
                assertTrue(f.writes.isEmpty())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
}
