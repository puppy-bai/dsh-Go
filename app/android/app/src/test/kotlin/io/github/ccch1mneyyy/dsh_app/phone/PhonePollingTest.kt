package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Real Core/worker/safety flow; only screen reads and scroll injection are faked. */
class PhonePollingTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        val log = ActionLog()
        var policy = PhonePolicies()
        var locked = false
        var available = true
        var nextId = 1
        var latest = ScreenSnapshot("s1", "屏幕 s1\nfirst page", "Example", "example.app")
        val reads = mutableListOf<Pair<Boolean, Boolean>>()
        val actions = mutableListOf<PhoneAction.Scroll>()
        val readBodies = ArrayDeque<String>()
        val scrollBodies = ArrayDeque<String>()
        var onObserve: () -> Unit = {}
        var onSleep: () -> Unit = {}
        var onPerform: () -> Unit = {}
        var listViewId: () -> String? = { "example.app:id/results" }
        private fun next(body: String) = latest.copy(id = "s${++nextId}", text = "屏幕 s$nextId\n$body", image = null, imageMime = null)
        private val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(available, "accessibility unavailable")
            override fun foregroundPackage() = latest.packageName
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                reads += detail to image
                latest = next(readBodies.removeFirstOrNull() ?: latest.text.substringAfter('\n'))
                if (image) latest = latest.copy(image = byteArrayOf(1, 2), imageMime = "image/png")
                onObserve()
                return latest
            }
            override fun target(screenId: String, elementId: Int): TargetInfo? =
                if (screenId != latest.id) null else TargetInfo(screenId, elementId, "列表", "Results", latest.packageName, "Example", false, false, false, listViewId())
            override fun perform(action: PhoneAction): ActionResult {
                val scroll = action as PhoneAction.Scroll
                assertEquals("scroll must use the latest snapshot", latest.id, scroll.screenId)
                actions += scroll
                latest = next(scrollBodies.removeFirstOrNull() ?: latest.text.substringAfter('\n'))
                onPerform()
                return ActionResult(true, "scrolled", latest)
            }
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun open(packageName: String) = error("unused")
        }
        val core = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = policy
            override fun isLocked() = locked
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest) = Decision.ALLOW_ONCE
                override fun handoff(reason: String, timeoutMs: Long) = error("unused")
                override fun status(text: String?) {}
            }
            override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun sleep(ms: Long) { onSleep() }
            override fun system(): SystemApi = error("unused")
        }, tasks, log)
        fun scroll(id: Int? = null): ToolOutput = core.call(PhoneToolCatalog.SCROLL,
            mapOf("screen" to "s1", "direction" to "down", "until" to "wanted", "max" to 10) + if (id == null) emptyMap() else mapOf("id" to id))
        fun waitFor(image: Boolean = false): ToolOutput = core.call(PhoneToolCatalog.SCREEN,
            mapOf("until" to "wanted", "image" to image))
        override fun close() { core.worker.shutdown() }
    }

    @Test fun scrollUsesEachReturnedSnapshotUntilTheTargetAppears() {
        Fixture().use { f ->
            f.scrollBodies.addAll(listOf("second page", "third page", "wanted"))
            val out = f.scroll()
            assertFalse(out.text, out.isError)
            assertEquals(listOf("s2", "s3", "s4"), f.actions.map { it.screenId })
            assertTrue(out.text.contains("wanted"))
            assertEquals("until", f.log.last()!!.decision)
        }
    }

    @Test fun visibleTargetDoesNotScroll() {
        Fixture().use { f ->
            f.readBodies += "wanted"
            assertFalse(f.scroll().isError)
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun specifiedListContinuesOnlyWhileItsIdentityStillMatches() {
        Fixture().use { f ->
            f.scrollBodies.addAll(listOf("second page", "wanted"))
            assertFalse(f.scroll(id = 4).isError)
            assertEquals(listOf(4, 4), f.actions.map { it.elementId })
        }
        Fixture().use { f ->
            f.scrollBodies += "second page"
            f.listViewId = { if (f.actions.isEmpty()) "example.app:id/results" else "example.app:id/another_list" }
            val out = f.scroll(id = 4)
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertEquals("targetChanged", f.log.last()!!.decision)
            assertTrue(out.text.contains("重新选择列表"))
        }
    }

    @Test fun anonymousListIsNotReusedAcrossSnapshots() {
        Fixture().use { f ->
            f.listViewId = { null }
            assertTrue(f.scroll(id = 4).isError)
            assertTrue(f.actions.isEmpty())
            assertEquals("targetChanged", f.log.last()!!.decision)
        }
    }

    @Test fun changingScreenIdsAndElementNumbersCannotHideNoProgress() {
        Fixture().use { f ->
            f.readBodies += "[1] 列表 unchanged"
            f.scrollBodies.addAll(listOf("[2] 列表 unchanged", "[3] 列表 unchanged"))
            val out = f.scroll()
            assertTrue(out.isError)
            assertEquals(2, f.actions.size)
            assertEquals("no-progress", f.log.last()!!.decision)
        }
    }

    @Test fun waitPollsTextAndTakesOnlyTheFinalScreenshot() {
        Fixture().use { f ->
            f.readBodies.addAll(listOf("loading", "wanted", "wanted"))
            val out = f.waitFor(image = true)
            assertFalse(out.text, out.isError)
            assertEquals(listOf(false, false, true), f.reads.map { it.second })
            assertArrayEquals(byteArrayOf(1, 2), out.image)
        }
    }

    @Test fun waitTimeoutReturnsUntilMissingAndTheCurrentScreen() {
        Fixture().use { f ->
            val out = f.waitFor()
            assertTrue(out.isError)
            assertTrue(out.text.contains("first page"))
            assertEquals("untilMissing", f.log.last()!!.decision)
            assertEquals(21, f.reads.size)
        }
    }

    @Test fun finalScreenshotMustStillContainTheWaitedText() {
        Fixture().use { f ->
            f.readBodies.addAll(listOf("wanted", "different page"))
            assertTrue(f.waitFor(image = true).isError)
            assertEquals("untilMissing", f.log.last()!!.decision)
            assertEquals(listOf(false, true), f.reads.map { it.second })
        }
    }

    @Test fun stopOrLockDuringWaitDoesNotReadAgain() {
        for (stop in listOf(false, true)) Fixture().use { f ->
            f.onSleep = { if (stop) f.tasks.stop("stop") else f.locked = true }
            val out = f.waitFor(image = true)
            assertTrue(out.isError)
            assertEquals(1, f.reads.size)
            assertFalse(out.text.contains("first page"))
        }
    }

    @Test fun aRestrictedPageDuringPollingIsFilteredBeforeMatchingOrScreenshot() {
        Fixture().use { f ->
            f.policy = PhonePolicies(denyPackages = listOf("blocked.app"))
            f.onSleep = { f.latest = f.latest.copy(packageName = "blocked.app", text = "屏幕 hidden\nwanted PRIVATE") }
            val out = f.waitFor(image = true)
            assertTrue(out.isError)
            assertEquals(1, f.reads.size)
            assertFalse(out.text.contains("PRIVATE"))
            assertNull(out.image)
        }
    }

    @Test fun accessibilityLossDuringWaitStopsBeforeTheNextRead() {
        Fixture().use { f ->
            f.onSleep = { f.available = false }
            val out = f.waitFor(image = true)
            assertTrue(out.isError)
            assertEquals(1, f.reads.size)
            assertEquals("unavailable", f.log.last()!!.decision)
        }
    }

    @Test fun lockOrRestrictionInAReadSuppressesItsContent() {
        for (lock in listOf(false, true)) Fixture().use { f ->
            f.readBodies += "wanted PRIVATE"
            f.onObserve = {
                if (lock) f.locked = true else f.latest = f.latest.copy(secure = true)
            }
            val out = f.waitFor(image = true)
            assertTrue(out.isError)
            assertFalse(out.text.contains("PRIVATE"))
            assertNull(out.image)
            assertEquals(1, f.reads.size)
        }
    }

    @Test fun restrictedScrollResultStopsBeforeReadingOrScrollingAgain() {
        Fixture().use { f ->
            f.policy = PhonePolicies(denyPackages = listOf("blocked.app"))
            f.scrollBodies += "wanted PRIVATE"
            f.onPerform = { f.latest = f.latest.copy(packageName = "blocked.app") }
            val out = f.scroll()
            assertTrue(out.isError)
            assertEquals(1, f.actions.size)
            assertFalse(out.text.contains("PRIVATE"))
            assertEquals("refused:read", f.log.last()!!.decision)
        }
    }
}
