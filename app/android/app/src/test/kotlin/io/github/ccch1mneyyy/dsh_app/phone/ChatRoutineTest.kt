package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ChatRoutineTest {
    private class Fixture : AutoCloseable {
        val tasks = PhoneTasks()
        var policy = PhonePolicies(visualPackages = listOf("example.app"))
        var pkg = "example.app"
        var texts = listOf("Ready", "Continue", "Name")
        var reason: String? = null
        var reads = 0
        var confirms = 0
        var choice = Decision.ALLOW_APP_FOR_TASK
        var duringConfirm: () -> Unit = {}
        val confirmations = arrayListOf<ConfirmRequest>()
        var next = 0
        var afterAction: () -> Unit = {}
        var actionResult: ActionResult? = null
        val actions = arrayListOf<PhoneAction>()
        var latest = ScreenSnapshot("s0", "Ready", "Example", pkg)
        fun snapshot(image: Boolean = false): ScreenSnapshot {
            latest = ScreenSnapshot("s" + ++next, texts.joinToString("\n"), "Example", pkg,
                visibleTexts = texts, observationReason = reason,
                image = if (image) byteArrayOf(1, 2, 3) else null, imageMime = if (image) "image/png" else null)
            return latest
        }
        val driver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun foregroundPackage() = pkg
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot { reads++; return snapshot(image) }
            override fun find(screenId: String, text: String): Either<String, Int> =
                if (text in texts) Either.Right(texts.indexOf(text) + 1) else Either.Left("target missing")
            override fun target(screenId: String, elementId: Int): TargetInfo? =
                texts.getOrNull(elementId - 1)?.let { TargetInfo(screenId, elementId, "按钮", it, pkg, "Example", false, false, it == "Name") }
            override fun perform(action: PhoneAction): ActionResult {
                actions += action
                afterAction()
                return actionResult ?: ActionResult(true, "done", snapshot())
            }
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun open(packageName: String) = error("unused")
        }
        val core = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = policy
            override fun grantVisual(packageName: String): Boolean { policy = policy.copy(visualPackages = policy.visualPackages + packageName); return true }
            override fun isLocked() = false
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest): Decision { confirms++; confirmations += request; duringConfirm(); return choice }
                override fun handoff(reason: String, timeoutMs: Long) = error("unused")
                override fun status(text: String?) {}
            }
            override fun driver(screen: PhoneScreen) = driver.takeIf { screen == PhoneScreen.MAIN }
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("UTC")
            override fun notifyFix(kind: String) {}
            override fun system(): SystemApi = error("unused")
        }, tasks, ActionLog())
        fun run(vararg steps: Map<String, Any?>) = core.call(PhoneToolCatalog.RUN,
            mapOf("schema" to 2, "apps" to listOf("example.app"), "steps" to steps.toList()))
        fun runSend(send: Map<String, Any?>, vararg steps: Map<String, Any?>) = core.call(PhoneToolCatalog.RUN,
            mapOf("schema" to 2, "apps" to listOf(pkg), "send" to send, "steps" to steps.toList()))
        override fun close() = core.worker.shutdown()
    }
    private fun step(op: String, vararg args: Pair<String, Any?>) = mapOf("op" to op) + args
    private fun data(out: ToolOutput) = Json.parse(out.text.substringBefore("\n\n")) as Map<*, *>

    @Test fun boundedBranchesAndLoopsExecuteWithoutCreatingAutomationState() {
        Fixture().use { f ->
            val out = f.run(step("if", "condition" to mapOf("text" to "Ready"),
                "then" to listOf(step("repeat", "count" to 3, "steps" to listOf(step("tap", "target" to "Continue"))))))
            assertFalse(out.text, out.isError)
            assertEquals("completed", data(out)["status"])
            assertEquals(3, f.actions.size)
            assertEquals(1, f.confirms)
            assertEquals(0, f.tasks.callsInFlight)
            assertFalse(f.tasks.snapshot().paused)
        }
    }
    @Test fun subsequentSegmentsReuseOrdinaryPhoneAuthorization() {
        Fixture().use { f ->
            repeat(2) { assertFalse(f.run(step("tap", "target" to "Continue")).isError) }
            assertEquals(1, f.confirms)
            assertEquals(2, f.actions.size)
        }
    }
    @Test fun unreadableConditionIsUnknownAndNeverTakesTheElseBranch() {
        Fixture().use { f ->
            f.reason = "tree-unavailable"
            val out = f.run(step("if", "condition" to mapOf("text" to "not here"),
                "then" to listOf(step("snapshot")), "else" to listOf(step("tap", "target" to "Continue"))))
            assertFalse(out.isError)
            assertEquals("need-ai", data(out)["status"])
            assertEquals("tree-unavailable", data(out)["reason"])
            assertTrue(f.actions.isEmpty())
            assertNotNull(out.image)
            assertEquals(0, f.tasks.callsInFlight)
        }
    }
    @Test fun explicitYieldReturnsToCallerAndLeavesNormalToolsAvailable() {
        Fixture().use { f ->
            val out = f.run(step("tap", "target" to "Continue"), step("yield"), step("tap", "target" to "Continue"))
            assertEquals("need-ai", data(out)["status"])
            assertEquals(true, data(out)["mayHaveActed"])
            assertEquals(1, f.actions.size)
            assertFalse(f.core.call(PhoneToolCatalog.SCREEN, emptyMap()).isError)
        }
    }
    @Test fun findReadTextAndVariablesUseVisibleTargets() {
        Fixture().use { f ->
            val out = f.run(step("find", "target" to "Ready", "name" to "label"),
                step("if", "condition" to mapOf("variable" to "label", "equals" to "Ready"),
                    "then" to listOf(step("tap", "target" to "Continue"))))
            assertFalse(out.text, out.isError)
            assertEquals(1, f.actions.size)
            assertEquals("Ready", (data(out)["values"] as Map<*, *>)["label"])
        }
    }
    @Test fun coordinateActionUsesCallerObservationThenYieldsWithoutReplayingNextPoint() {
        Fixture().use { f ->
            val first = f.snapshot(image = true).id
            val out = f.run(step("tap_xy", "observation" to first, "x" to 20, "y" to 30),
                step("tap_xy", "observation" to first, "x" to 40, "y" to 50))
            assertEquals("need-ai", data(out)["status"])
            assertEquals(1, f.actions.size)
            assertEquals(first, f.actions.single().screenId)
            assertEquals("1.after", data(out)["nextStep"])
            assertNotNull(out.image)
        }
    }
    @Test fun coordinateActionInAnotherAppNeverDispatches() {
        Fixture().use { f ->
            val first = f.snapshot(true).id
            f.pkg = "other.app"
            val out = f.run(step("tap_xy", "observation" to first, "x" to 20, "y" to 30))
            assertEquals("interrupted", data(out)["status"])
            assertTrue(f.actions.isEmpty())
        }
    }
    @Test fun gestureIsDispatchedAsGestureAndYieldsToCaller() {
        Fixture().use { f ->
            val first = f.snapshot(image = true).id
            val out = f.run(step("gesture", "observation" to first, "steps" to listOf(
                mapOf("kind" to "tap", "x" to 20, "y" to 30))))
            assertEquals("need-ai", data(out)["status"])
            assertEquals(1, f.actions.size)
            assertTrue(f.actions.single() is PhoneAction.Gesture)
            assertEquals(first, f.actions.single().screenId)
        }
    }
    @Test fun pastePreparationIsNotReportedAsCompletedInput() {
        Fixture().use { f ->
            val first = f.snapshot(true).id
            f.actionResult = ActionResult(true, "paste menu opened", f.latest, reasonCode = "need-visual-paste")
            val out = f.run(step("input", "observation" to first, "x" to 20, "y" to 30, "text" to "你好"))
            assertEquals("need-ai", data(out)["status"])
            assertEquals("need-visual-paste", data(out)["reason"])
            assertEquals(0L, data(out)["completed"])
            assertEquals(1, f.actions.size)
        }
    }

    @Test fun coordinateInputPassesTheFullDraftToItsConfirmationWithoutLoggingIt() {
        Fixture().use { f ->
            val draft = "请核对完整的待发送消息。".repeat(12)
            val first = f.snapshot(true).id
            f.run(step("input", "observation" to first, "x" to 20, "y" to 30, "text" to draft))
            assertTrue(f.confirmations.any { it.sentence.contains(draft) })
            assertFalse(f.core.log.toMaps(100).toString().contains(draft))
            assertEquals(draft, (f.actions.single() as PhoneAction.Input).text)
        }
    }
    @Test fun oneWechatSendApprovalSpansSearchPasteAndSendAcrossCalls() {
        Fixture().use { f ->
            f.pkg = "com.tencent.mm"
            f.policy = f.policy.copy(visualPackages = listOf(f.pkg))
            f.texts = listOf("搜索", "发送", "Name")
            f.choice = Decision.ALLOW_ONCE
            val draft = "这是需要完整确认的消息。".repeat(15)
            val send = mapOf("app" to f.pkg, "recipient" to "云星", "text" to draft)
            val initial = f.snapshot().id
            assertFalse(f.core.call(PhoneToolCatalog.TAP, mapOf("screen" to initial, "text" to "搜索", "send" to send)).isError)

            f.reason = "tree-unavailable"
            val image = f.snapshot(true)
            f.actionResult = ActionResult(true, "paste menu opened", image, reasonCode = "need-visual-paste")
            val prepared = f.runSend(send, step("input", "observation" to image.id, "x" to 20, "y" to 30, "text" to draft))
            assertEquals("need-visual-paste", data(prepared)["reason"])
            assertEquals("need-ai", data(prepared)["status"])
            f.actionResult = null
            assertFalse(f.core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to f.latest.id, "x" to 40, "y" to 50, "send" to send)).isError)

            f.reason = null
            val sent = f.runSend(send, step("tap", "target" to "发送"))
            assertEquals("completed", data(sent)["status"])
            assertEquals(4, f.actions.size)
            assertEquals(1, f.confirms)
            assertTrue(f.confirmations.single().sendScope)
            assertTrue(f.confirmations.single().sentence.contains("云星"))
            assertTrue(f.confirmations.single().sentence.contains(draft))
            assertFalse(f.core.log.toMaps(100).toString().contains(draft))
            assertFalse(f.tasks.isApproved(f.pkg))
            assertFalse(f.tasks.isWaived)
        }
    }

    @Test fun aDeniedSendDoesNotDispatchOrClaimThatItMayHaveActed() {
        Fixture().use { f ->
            f.choice = Decision.DENY
            val send = mapOf("app" to f.pkg, "recipient" to "云星", "text" to "你好")
            val out = f.runSend(send, step("input", "observation" to f.snapshot(true).id, "x" to 20, "y" to 30, "text" to "你好"))
            assertTrue(out.isError)
            assertEquals("blocked", data(out)["status"])
            assertEquals("denied", data(out)["reason"])
            assertEquals(false, data(out)["mayHaveActed"])
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun malformedOrMismatchedSendNeverTouchesTheDevice() {
        Fixture().use { f ->
            val bad = mapOf("app" to f.pkg, "recipient" to "云星", "text" to "你好", "approved" to true)
            val invalidRun = f.runSend(bad, step("tap", "target" to "Continue"))
            assertTrue(invalidRun.isError)
            assertEquals(0, f.reads)
            assertEquals(0, f.confirms)
            assertTrue(f.actions.isEmpty())
            val invalidSingle = f.core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to 1, "y" to 1, "send" to bad))
            assertTrue(invalidSingle.isError)
            assertTrue(f.actions.isEmpty())
            val send = bad - "approved"
            val mismatch = f.runSend(send, step("input", "observation" to f.snapshot(true).id, "x" to 20, "y" to 30, "text" to "未经确认的其他消息"))
            assertEquals("blocked", data(mismatch)["status"])
            assertEquals("refused:send-scope-text-mismatch", data(mismatch)["reason"])
            assertEquals(false, data(mismatch)["mayHaveActed"])
            assertEquals(0, f.confirms)
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun staleFrameRejectionDoesNotClaimThatTheSendMayHaveActed() {
        Fixture().use { f ->
            val send = mapOf("app" to f.pkg, "recipient" to "云星", "text" to "你好")
            val image = f.snapshot(true)
            f.actionResult = ActionResult(false, "The frame changed before dispatch", staleSnapshot = true)
            val out = f.runSend(send, step("input", "observation" to image.id, "x" to 20, "y" to 30, "text" to "你好"))
            assertEquals("stale", data(out)["reason"])
            assertEquals(false, data(out)["mayHaveActed"])
            assertEquals(0L, data(out)["completed"])
            assertEquals(1, f.actions.size)
        }
    }

    @Test fun changingForegroundDuringSendConsentNeverDispatchesTheAction() {
        Fixture().use { f ->
            val send = mapOf("app" to f.pkg, "recipient" to "云星", "text" to "你好")
            f.duringConfirm = { f.pkg = "other.app" }
            val out = f.runSend(send, step("input", "observation" to f.snapshot(true).id, "x" to 20, "y" to 30, "text" to "你好"))
            assertEquals("app-changed", data(out)["reason"])
            assertEquals(false, data(out)["mayHaveActed"])
            assertEquals(0L, data(out)["completed"])
            assertTrue(f.actions.isEmpty())
            assertEquals(1, f.confirms)
        }
    }
    @Test fun keyBackDoesNotRequireAReadableTree() {
        Fixture().use { f ->
            f.reason = "tree-unavailable"
            val out = f.run(step("key", "key" to "back"))
            assertFalse(out.text, out.isError)
            assertEquals(1, f.actions.size)
        }
    }
    @Test fun expandedLoopsStopAtSharedExecutionBudget() {
        Fixture().use { f ->
            val out = f.run(step("repeat", "count" to 100, "steps" to listOf(
                step("repeat", "count" to 100, "steps" to listOf(step("set", "name" to "n", "value" to 1))))))
            assertEquals("step-budget", data(out)["reason"])
            assertTrue(f.actions.isEmpty())
        }
    }
}
